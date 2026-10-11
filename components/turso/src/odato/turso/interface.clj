(ns odato.turso.interface
  "Turso as a place to keep the durable half of the store.

  The configuration and the worklog outlive the process; everything gitdesk
  collects dies with it. This component is one answer to the first half — a
  DataScript storage whose nodes live in a Turso table instead of a file — and
  it knows nothing about either application.

  `components/db` takes a storage without knowing what is behind it, so nothing
  in the domain changes when this is switched on. What changes is where the
  worklog is when the container is replaced."
  (:require [odato.turso.core :as core]
            [odato.turso.storage :as storage]))

(def default-table core/default-table)

(defn options
  "Normalises and checks `{:url :token :table-name}`. A `libsql://` URL — which
  is what Turso hands out — is the same host over HTTPS, and that is what the
  API is reached on.

  Throws when there is not enough to reach a database, rather than half-working."
  [opts]
  (core/options opts))

(defn configured?
  "Whether there is a URL and a token at all. Asked before `options`, which
  throws."
  [opts]
  (core/configured? opts))

(defn from-env
  "`TURSO_URL`, `TURSO_TOKEN` and `TURSO_TABLE`, unchecked. Reading the
  environment is the caller's business; this is only where the names are
  written down."
  []
  {:url (System/getenv "TURSO_URL")
   :token (System/getenv "TURSO_TOKEN")
   :table-name (System/getenv "TURSO_TABLE")})

(defn storage
  "A `datascript.storage/IStorage` backed by Turso. Creating it creates the
  table, so an empty database is a usable one.

  Hand it to `odato.db.interface/open` as `:storage`."
  [opts]
  (storage/create opts))

(defn ping
  "Whether the database answers. Never throws — it is asked at startup, and a
  database that is down is something to say rather than a crash."
  [opts]
  (core/ping (core/options opts)))

(defn execute!
  "Runs statements — `{:sql \"…\" :args {:name value}}` — and answers one
  result per statement. The escape hatch: a migration, a count, a look at what
  is actually in the table."
  [opts statements]
  (core/execute! (core/options opts) statements))

(defn rows
  "One result's rows, as Clojure values."
  [result]
  (core/rows result))
