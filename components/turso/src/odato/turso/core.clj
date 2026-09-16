(ns odato.turso.core
  "Turso's HTTP API, as much of it as a DataScript storage needs.

  Turso speaks one endpoint, `/v2/pipeline`: a request carries a list of
  statements and a `close`, and the answer carries one result per statement in
  the same order. Everything below builds those statements and reads those
  answers."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [odato.http.interface :as http]))

(def default-table "odatoruthall")

;; A pipeline request is not unbounded, and a tree flush can hand us hundreds
;; of addresses at once, so the statements go up in batches.
(def ^:private batch-size 64)

(def ^:private table-name-re #"^[A-Za-z_][A-Za-z0-9_]{0,62}$")

;; ------------------------------------------------------------------ options

(defn- site-url
  "Turso hands out `libsql://…` URLs; the HTTP API is the same host over
  HTTPS."
  [url]
  (-> (str url)
      str/trim
      (str/replace-first #"^libsql://" "https://")
      (str/replace #"/+$" "")))

(defn configured?
  "Whether there is enough here to reach a Turso database at all."
  [{:keys [url token]}]
  (boolean (and url token
                (not (str/blank? (str url)))
                (not (str/blank? (str token))))))

(defn options
  "Normalises and checks what the operator supplied. Throws rather than
  half-working: a database that cannot be reached is not a database."
  [{:keys [url token table-name] :as opts}]
  (when-not (configured? opts)
    (throw (ex-info "Turso needs both a URL and a token" {:url url})))
  (let [table (or (not-empty (str/trim (str table-name))) default-table)]
    ;; The table name is interpolated into SQL — it cannot be a parameter —
    ;; so it is held to a plain identifier.
    (when-not (re-matches table-name-re table)
      (throw (ex-info "a Turso table name must be a plain SQL identifier"
                      {:table-name table})))
    {:url (site-url url) :token (str token) :table-name table}))

;; --------------------------------------------------------------- statements

(defn- value-type [value]
  (cond
    (nil? value) "null"
    (integer? value) "integer"
    (float? value) "float"
    :else "text"))

(defn argument
  "One named argument, in the shape Turso reads it: a type and the value as a
  string."
  [k value]
  {:name (name k)
   :value (if (nil? value)
            {:type "null"}
            {:type (value-type value) :value (str value)})})

(defn statement [{:keys [sql args]}]
  {:sql sql
   :named_args (mapv #(apply argument %) args)})

(defn create-table [{:keys [table-name]}]
  {:sql (format "create table if not exists %s (addr integer primary key, content text)"
                table-name)})

(defn put-address [{:keys [table-name]} [addr data]]
  {:sql (format (str "insert into %s (addr, content) values ($addr, $content) "
                     "on conflict (addr) do update set content = $content")
                table-name)
   :args {:addr addr :content (pr-str data)}})

(defn get-address [{:keys [table-name]} addr]
  {:sql (format "select content from %s where addr = $addr" table-name)
   :args {:addr addr}})

(defn list-addresses [{:keys [table-name]}]
  {:sql (format "select addr from %s" table-name)})

(defn delete-address [{:keys [table-name]} addr]
  {:sql (format "delete from %s where addr = $addr" table-name)
   :args {:addr addr}})

;; ------------------------------------------------------------------ reading

(defn cell->clj
  "One cell of one row, as Turso describes it."
  [{:keys [type value] :as cell}]
  (case (str type)
    "null" nil
    "integer" (Long/parseLong (str value))
    "float" (Double/parseDouble (str value))
    "text" (str value)
    (throw (ex-info "Turso answered with a cell this storage cannot read" cell))))

(defn rows
  "The rows of one result, as Clojure values."
  [result]
  (mapv #(mapv cell->clj %) (:rows result)))

(defn first-value
  "The single cell of a single-column, single-row answer, or nil."
  [result]
  (ffirst (rows result)))

(defn read-content
  "What was stored under an address: EDN, exactly as `put-address` wrote it."
  [result]
  (some-> (first-value result) edn/read-string))

;; ---------------------------------------------------------------- the wire

(defn- client [{:keys [url token]}]
  (http/client {:base-url url
                :timeout-ms 30000
                :headers {"authorization" (str "Bearer " token)
                          "accept" "application/json"
                          "user-agent" "odatoruthall"}}))

(defn- fail! [message data]
  (throw (ex-info (str "Turso: " message) data)))

(defn- results-of
  "One answer's results, with a failed statement raised rather than dropped —
  a write that silently did not happen is worse than one that threw."
  [response]
  (when-not (http/ok? response)
    (fail! (if (:error response)
             "the database could not be reached"
             (str "the database answered " (:status response)))
           {:status (:status response)
            :body (:body response)}))
  (let [entries (get-in response [:body :results])]
    (doseq [entry entries]
      (when (= "error" (str (:type entry)))
        (fail! (or (get-in entry [:error :message]) "a statement was refused")
               {:error (:error entry)})))
    (->> entries
         (map :response)
         (filter #(= "execute" (str (:type %))))
         (map :result)
         vec)))

(defn execute!
  "Runs `statements` in order and answers one result per statement.

  Sent in batches, because a tree flush can carry hundreds of addresses and a
  pipeline request is not unbounded."
  [opts statements]
  (let [c (client opts)]
    (into []
          (mapcat
           (fn [batch]
             (let [requests (conj (mapv #(hash-map :type "execute" :stmt (statement %))
                                        batch)
                                  {:type "close"})]
               (results-of (http/request c {:method :post
                                            :path "/v2/pipeline"
                                            :body {:requests requests}})))))
          (partition-all batch-size statements))))

(defn create-table!
  "Creates the table the storage lives in, if it is not there."
  [opts]
  (execute! opts [(create-table opts)])
  opts)

(defn ping
  "Whether the database answers at all. Never throws: it is asked at startup,
  and a database that is down is a thing to say rather than a crash."
  [opts]
  (try
    (execute! opts [{:sql "select 1"}])
    {:ok true}
    (catch Exception e
      {:ok false :error (ex-message e)})))
