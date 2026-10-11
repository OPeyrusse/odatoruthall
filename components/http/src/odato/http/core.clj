(ns odato.http.core
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(defn client [{:keys [base-url headers timeout-ms]}]
  {:http (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofMillis (long (or timeout-ms 20000))))
             (.followRedirects java.net.http.HttpClient$Redirect/NORMAL)
             (.build))
   :base-url (some-> base-url (str/replace #"/+$" ""))
   :headers (or headers {})
   :timeout-ms (long (or timeout-ms 20000))})

(defn- encode [s] (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- query-string [query]
  (when (seq query)
    (str "?" (str/join "&" (for [[k v] query :when (some? v)]
                             (str (encode (name k)) "=" (encode v)))))))

(defn- uri [{:keys [base-url]} path query]
  (URI/create (str (when-not (str/starts-with? (str path) "http") base-url)
                   path (query-string query))))

(defn- parse-json [s]
  (when (and s (not (str/blank? s)))
    (try (json/read-str s :key-fn keyword)
         (catch Exception _ s))))

(defn request [{:keys [http headers timeout-ms] :as c} {:keys [method path query body]}]
  (let [payload (when body (json/write-str body))
        builder (-> (HttpRequest/newBuilder (uri c path query))
                    (.timeout (Duration/ofMillis timeout-ms)))
        _ (doseq [[k v] headers] (.header builder (name k) (str v)))
        _ (when payload (.header builder "content-type" "application/json"))
        publisher (if payload
                    (HttpRequest$BodyPublishers/ofString payload)
                    (HttpRequest$BodyPublishers/noBody))
        req (-> builder
                (.method (str/upper-case (name (or method :get))) publisher)
                (.build))]
    (try
      (let [response (.send http req (HttpResponse$BodyHandlers/ofString))]
        {:status (.statusCode response)
         :headers (into {} (for [[k v] (.map (.headers response))]
                             [(str/lower-case k) (first v)]))
         :body (parse-json (.body response))})
      (catch Exception e
        {:error :unreachable :exception e}))))

(defn header [response name]
  (get (:headers response) (str/lower-case (clojure.core/name name))))

(defn ok? [response]
  (let [s (:status response)]
    (boolean (and s (<= 200 s 299)))))

(defn link-cursor
  "GitHub paginates with a Link header; the cursor we keep is the page number
  it points at."
  [response]
  (when-let [link (header response "link")]
    (second (re-find #"[?&]page=(\d+)[^>]*>;\s*rel=\"next\"" link))))
