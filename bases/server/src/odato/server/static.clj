(ns odato.server.static
  "The stylesheet, the Datastar bundle and the favicon, served from the jar."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private content-types
  {"css" "text/css; charset=utf-8"
   "js" "text/javascript; charset=utf-8"
   "svg" "image/svg+xml"
   "png" "image/png"
   "ico" "image/x-icon"
   "map" "application/json"})

(defn resource
  "The file `uri` names under `public/`, or nil. A uri that tries to climb out
  of it gets nil too."
  [uri]
  (let [path (str/replace (str uri) #"^/+" "")]
    (when-not (or (str/includes? path "..") (str/blank? path))
      (when-let [found (io/resource (str "public/" path))]
        {:status 200
         :headers {"content-type" (get content-types
                                       (str/lower-case (or (last (str/split path #"\.")) ""))
                                       "application/octet-stream")
                   "cache-control" "public, max-age=300"}
         :body (io/input-stream found)}))))
