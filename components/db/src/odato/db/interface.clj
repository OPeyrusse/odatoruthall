(ns odato.db.interface
  "The store: two DataScript databases, and the line between them.

  The specification draws that line itself. The configuration and the worklog
  outlive the process; everything gitdesk collects dies with it and is rebuilt
  by the collection that starts with the process. So there are two connections:

    :durable   configuration, work items, work days, worklog entries and the
               activities a day scan found
    :volatile  connections, the GitHub budget, the snapshot, its sources, its
               pull requests, the cycles and the scans in flight

  Both are in memory. `:durable` has a file seam — give the store a `:file`
  and what it holds is written there after every transaction and read back at
  startup — which is where a persistent engine goes when one arrives."
  (:require [odato.db.core :as core]))

(def durable-schema core/durable-schema)
(def volatile-schema core/volatile-schema)

(defn open
  "Opens a store.

  The durable half is kept wherever it is told to be kept:

    `:storage`  a `datascript.storage/IStorage` — `odato.turso.interface/storage`
                is one — which DataScript writes through on every transaction
    `:file`     a path; the durable half is written there as EDN after every
                transaction and read back at startup
    neither     nowhere: the whole store is in memory, and the configuration
                and the worklog go with the process

  The volatile half is always in memory, because the specification says the
  snapshot dies with the process."
  ([] (open {}))
  ([opts] (core/open opts)))

(defn backend
  "Where the durable half is kept: `:storage`, `:file` or `:memory`."
  [store]
  (:backend store))

(defn close! [store] (core/close! store))

(defn durable [store] (:durable store))
(defn volatile* [store] (:volatile store))

(defn transact!
  "Transacts and returns the report."
  [conn tx-data]
  (core/transact! conn tx-data))

(defn db [conn] (core/db conn))
(defn q [query db & args] (apply core/q query db args))
(defn pull [db pattern eid] (core/pull db pattern eid))
(defn pull-many [db pattern eids] (core/pull-many db pattern eids))
(defn entity [db eid] (core/entity db eid))

(defn lookup
  "The entity id behind a unique attribute's value, or nil."
  [db attr value]
  (core/lookup db attr value))

(defn entity-map
  "The entity behind a unique attribute's value, pulled whole, or nil."
  [db attr value]
  (core/entity-map db attr value))

(defn all
  "Every entity carrying `attr`, pulled whole."
  [db attr]
  (core/all db attr))

(defn put!
  "Upserts one entity keyed by a unique attribute. A nil value retracts the
  attribute — DataScript stores no nil, and the specification's
  `last_refusal = null` has to mean something. A cardinality-many attribute is
  replaced whole."
  [conn ident-attr ident-value m]
  (core/put! conn ident-attr ident-value m))

(defn retract-entity [eid] [:db/retractEntity eid])

(defn flush!
  "Writes the durable half out now. `transact!` already does; this is for a
  shutdown that wants to be sure."
  [store]
  (core/flush! store))

(defn collect-garbage!
  "Drops the stored nodes no live database references. A storage accumulates
  them as the tree is rewritten; a file does not, being rewritten whole."
  [store]
  (core/collect-garbage! store))
