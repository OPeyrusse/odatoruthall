(ns odato.fake-turso
  "A Turso that answers for real, over HTTP, from a map.

  It speaks the `/v2/pipeline` protocol — the bearer token, the batched
  statements, the one-result-per-statement answer, the cell types — so the
  storage under test goes through its own HTTP client and its own JSON rather
  than through a stub of itself. What it does not do is understand SQL: it
  matches the five statements the storage issues, which is exactly as much as a
  test double should know."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.server :as hk]))

(def ^:private token "test-token")

(defn- argument [stmt name]
  (->> (:named_args stmt)
       (filter #(= name (:name %)))
       first
       :value
       :value))

(defn- cell [type value] {:type type :value (str value)})

(defn- run-statement [state stmt]
  (let [sql (str/lower-case (str/trim (str (:sql stmt))))]
    (swap! state update :statements (fnil conj []) sql)
    (cond
      (str/starts-with? sql "create table")
      (do (swap! state assoc :table-created true)
          {:cols [] :rows [] :affected_row_count 0})

      (str/starts-with? sql "insert into")
      (do (swap! state assoc-in [:rows (parse-long (argument stmt "addr"))]
                 (argument stmt "content"))
          {:cols [] :rows [] :affected_row_count 1})

      (str/starts-with? sql "select content")
      (let [content (get-in @state [:rows (parse-long (argument stmt "addr"))])]
        {:cols [{:name "content"}]
         :rows (if content [[(cell "text" content)]] [])})

      (str/starts-with? sql "select addr")
      {:cols [{:name "addr"}]
       :rows (mapv (fn [addr] [(cell "integer" addr)]) (sort (keys (:rows @state))))}

      (str/starts-with? sql "delete from")
      (do (swap! state update :rows dissoc (parse-long (argument stmt "addr")))
          {:cols [] :rows [] :affected_row_count 1})

      (str/starts-with? sql "select 1")
      {:cols [{:name "1"}] :rows [[(cell "integer" 1)]]}

      :else (throw (ex-info "the fake Turso was asked something it cannot do"
                            {:sql sql})))))

(defn- answer [state body]
  {:baton nil
   :base_url nil
   :results
   (mapv (fn [{:keys [type stmt]}]
           (if (= "execute" type)
             {:type "ok" :response {:type "execute" :result (run-statement state stmt)}}
             {:type "ok" :response {:type "close"}}))
         (:requests body))})

(defn- handler [state]
  (fn [request]
    (let [body (json/read-str (slurp (:body request)) :key-fn keyword)
          authorization (get-in request [:headers "authorization"])]
      (swap! state update :calls (fnil inc 0))
      (cond
        (not= (str "Bearer " token) authorization)
        {:status 401 :body (json/write-str {:error {:message "bad token"}})}

        ;; Scripted: the next call fails the way Turso fails a statement.
        (:refuse-statement @state)
        {:status 200
         :body (json/write-str
                {:results [{:type "error" :error {:message "no such table: nope"}}]})}

        (:unreachable @state)
        {:status 500 :body (json/write-str {:error {:message "boom"}})}

        :else
        {:status 200
         :headers {"content-type" "application/json"}
         :body (json/write-str (answer state body))}))))

(defn start!
  "Starts one. Answers `{:options … :state … :stop!}` — `:options` is what
  `odato.turso.interface/storage` takes."
  []
  (let [state (atom {:rows {} :calls 0})
        stop (hk/run-server (handler state) {:port 0 :legacy-return-value? false})
        port (hk/server-port stop)]
    {:options {:url (str "http://127.0.0.1:" port)
               :token token
               :table-name "odatoruthall_test"}
     :state state
     :stop! (fn [] (hk/server-stop! stop {:timeout 500}))}))

(defmacro with-turso
  "Runs the body with a fake Turso, and stops it afterwards."
  [binding & body]
  `(let [turso# (start!)
         ~(first binding) turso#]
     (try ~@body (finally ((:stop! turso#))))))

(defn rows [turso] (:rows @(:state turso)))
(defn statements [turso] (:statements @(:state turso)))
(defn calls [turso] (:calls @(:state turso)))
