(ns odato.server.params
  "Reading a request's parameters.

  Three encodings arrive here and all three mean the same thing: a plain form
  post when the browser has no JavaScript, the multipart body Datastar sends
  for `contentType: 'form'`, and the query string. A parameter given more than
  once — the active days of the collection window — reads back as a vector."
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net URLDecoder)
           (java.nio.charset StandardCharsets)))

(defn- add [m k v]
  (if-let [existing (get m k)]
    (assoc m k (if (vector? existing) (conj existing v) [existing v]))
    (assoc m k v)))

(defn parse-query [s]
  (reduce (fn [m pair]
            (let [[k v] (str/split pair #"=" 2)]
              (if (str/blank? k)
                m
                (add m (URLDecoder/decode k StandardCharsets/UTF_8)
                     (URLDecoder/decode (or v "") StandardCharsets/UTF_8)))))
          {}
          (remove str/blank? (str/split (or s "") #"&"))))

(defn- slurp-bytes [body]
  (cond
    (nil? body) (byte-array 0)
    (string? body) (.getBytes ^String body StandardCharsets/UTF_8)
    (instance? InputStream body)
    (let [out (ByteArrayOutputStream.)]
      (io!  (let [buffer (byte-array 8192)]
              (loop []
                (let [n (.read ^InputStream body buffer)]
                  (when (pos? n)
                    (.write out buffer 0 n)
                    (recur))))))
      (.toByteArray out))
    :else (byte-array 0)))

(defn- parse-multipart
  "Datastar posts a form as multipart. Only text fields are expected here —
  nothing in either dashboard uploads a file."
  [body boundary]
  (let [delimiter (str "--" boundary)]
    (reduce
     (fn [m part]
       (let [part (str/replace part #"\r\n$" "")
             [headers value] (str/split part #"\r\n\r\n" 2)]
         (if-let [field (second (re-find #"(?i)name=\"([^\"]*)\"" (or headers "")))]
           (add m field (or value ""))
           m)))
     {}
     (->> (str/split body (re-pattern (java.util.regex.Pattern/quote delimiter)))
          (remove #(or (str/blank? %) (str/starts-with? % "--")))
          (map #(str/replace % #"^\r\n" ""))))))

(defn of
  "Every parameter of a request: the query string, then the body."
  [{:keys [query-string body headers]}]
  (let [content-type (str (get headers "content-type"))
        raw (String. ^bytes (slurp-bytes body) StandardCharsets/UTF_8)]
    (merge
     (parse-query query-string)
     (cond
       (str/starts-with? content-type "application/x-www-form-urlencoded")
       (parse-query raw)

       (str/starts-with? content-type "multipart/form-data")
       (if-let [boundary (second (re-find #"boundary=\"?([^\";]+)\"?" content-type))]
         (parse-multipart raw boundary)
         {})

       :else {}))))

(defn one
  "A parameter given once, whether or not it was given more than once."
  [params k]
  (let [v (get params k)]
    (if (vector? v) (first v) v)))
