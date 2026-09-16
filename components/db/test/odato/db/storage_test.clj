(ns odato.db.storage-test
  "The durable half kept in a DataScript storage rather than a file.

  The storage here is a map: what is being tested is what `db/open` does with
  one, not what any particular engine does with the nodes. Turso's own
  behaviour is `odato.turso.interface-test`'s business."
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [datascript.storage :as ds]
            [odato.db.interface :as db]))

(defn- memory-storage [state]
  (reify ds/IStorage
    (-store [_ addr+data-seq] (swap! state into addr+data-seq) nil)
    (-restore [_ addr] (get @state addr))
    (-list-addresses [_] (vec (keys @state)))
    (-delete [_ addrs] (swap! state #(apply dissoc % addrs)) nil)))

(deftest a-store-says-where-its-durable-half-lives
  (is (= :memory (db/backend (db/open))))
  (is (= :storage (db/backend (db/open {:storage (memory-storage (atom {}))})))))

(deftest what-a-storage-holds-outlives-the-store
  (let [state (atom {})]
    (testing "what was transacted"
      (let [store (db/open {:storage (memory-storage state)})]
        (db/transact! (db/durable store) [{:work-item/key "ABC-1"
                                           :work-item/summary "Ship it"}])
        (db/transact! (db/volatile* store) [{:pr/ident "o/r#1" :pr/number 1}])
        (db/flush! store)))
    (testing "is what a store opened on the same storage holds"
      (let [store (db/open {:storage (memory-storage state)})]
        (is (= "Ship it" (:work-item/summary
                          (db/entity-map (db/db (db/durable store))
                                         :work-item/key "ABC-1"))))
        (is (empty? (db/all (db/db (db/volatile* store)) :pr/ident))
            "and nothing the snapshot held came back with it")))))

(deftest a-schema-that-has-moved-on-is-applied-to-what-was-stored
  (let [state (atom {})
        ;; Stored by an older version that knew nothing of work days.
        stale-schema {:work-item/key {:db/unique :db.unique/identity}}]
    (let [conn (d/create-conn stale-schema {:storage (memory-storage state)})]
      (d/transact! conn [{:work-item/key "ABC-1" :work-item/summary "Ship it"}]))
    (let [store (db/open {:storage (memory-storage state)})]
      (is (= (:schema (d/empty-db db/durable-schema))
             (:schema (db/db (db/durable store))))
          "the schema in force is the one that applies")
      (is (= "Ship it" (:work-item/summary
                        (db/entity-map (db/db (db/durable store))
                                       :work-item/key "ABC-1")))
          "and the datoms survived being re-indexed under it")
      (testing "and the attributes the old schema did not know now work"
        (db/transact! (db/durable store) [{:work-day/starts-at 1234}])
        (is (some? (db/lookup (db/db (db/durable store))
                              :work-day/starts-at 1234)))))))

(deftest a-storage-writes-through-without-being-asked
  (let [state (atom {})
        store (db/open {:storage (memory-storage state)})]
    (db/transact! (db/durable store) [{:work-item/key "ABC-1"}])
    (is (pos? (count @state))
        "DataScript persists on transact; nothing above has to remember to save")))

(deftest garbage-collection-is-for-a-storage-and-nothing-else
  (let [state (atom {})
        store (db/open {:storage (memory-storage state)})]
    (dotimes [i 40] (db/transact! (db/durable store) [{:work-item/key (str "ABC-" i)}]))
    (db/collect-garbage! store)
    (is (= 40 (count (db/all (db/db (db/durable store)) :work-item/key))))
    (testing "a file store is rewritten whole, so it has none to collect"
      (is (some? (db/collect-garbage! (db/open)))))))
