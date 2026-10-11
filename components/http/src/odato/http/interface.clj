(ns odato.http.interface
  "A JSON client over `java.net.http`, which the JDK already has.

  Requests block the calling thread, and every caller here is a virtual thread
  paginating one source, so blocking is exactly what is wanted: the code reads
  as one page after another, and the platform carries thousands of such waits
  without a thread pool to size."
  (:require [odato.http.core :as core]))

(defn client
  "An HTTP client. `:base-url` is prefixed to every relative path, `:headers`
  are sent on every request, `:timeout-ms` bounds one call."
  [opts]
  (core/client opts))

(defn request
  "`{:method :get|:post|:put|:delete :path \"/user\" :query {...} :body <edn>}`.

  Answers `{:status ... :headers {...} :body <parsed json>}`, or
  `{:error :unreachable :exception e}` when the service did not answer at all."
  [client req]
  (core/request client req))

(defn json-body [response] (:body response))

(defn header
  "One response header, lower-cased, or nil."
  [response name]
  (core/header response name))

(defn ok? [response] (core/ok? response))

(defn link-cursor
  "The `page=N` of the `rel=\"next\"` link GitHub sends, or nil on the last
  page."
  [response]
  (core/link-cursor response))
