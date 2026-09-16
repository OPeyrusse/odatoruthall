(ns odato.turso.storage
  "A DataScript storage backed by Turso.

  DataScript stores a database as a set of addressed nodes: it hands the
  storage `[addr data]` pairs to write, asks for one address back at a time,
  and during a garbage collection asks which addresses exist and which to drop.
  That is the whole contract, and one `addr integer primary key, content text`
  table answers it.

  A transaction writes through to Turso on its own — DataScript appends to a
  tail after every `transact!` and flushes the tree when the tail overflows —
  so nothing above this namespace has to remember to save."
  (:require [datascript.storage :as ds]
            [odato.turso.core :as core]))

(defn- store! [opts addr+data-seq]
  (when (seq addr+data-seq)
    (core/execute! opts (mapv #(core/put-address opts %) addr+data-seq)))
  nil)

(defn- restore [opts addr]
  (-> (core/execute! opts [(core/get-address opts addr)])
      first
      core/read-content))

(defn- list-addresses [opts]
  (->> (core/execute! opts [(core/list-addresses opts)])
       first
       core/rows
       (mapv first)))

(defn- delete! [opts addrs]
  (when (seq addrs)
    (core/execute! opts (mapv #(core/delete-address opts %) addrs)))
  nil)

(defn create
  "The storage. Creating it creates the table, so an empty Turso database is a
  usable one."
  [opts]
  (let [opts (core/create-table! (core/options opts))]
    (with-meta
      (reify ds/IStorage
        (-store [_ addr+data-seq] (store! opts addr+data-seq))
        (-restore [_ addr] (restore opts addr))
        (-list-addresses [_] (list-addresses opts))
        (-delete [_ addrs-seq] (delete! opts addrs-seq)))
      ;; The token is not in here: what is worth reading back is where the
      ;; database is and which table it uses.
      {:turso (dissoc opts :token)})))
