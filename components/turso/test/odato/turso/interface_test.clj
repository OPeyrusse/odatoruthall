(ns odato.turso.interface-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as d]
            [odato.fake-turso :as fake]
            [odato.turso.interface :as turso]))

;; ------------------------------------------------------------------ options

(deftest a-libsql-url-is-the-same-host-over-https
  (let [opts (turso/options {:url "libsql://acme-kineolyan.turso.io"
                             :token "t" :table-name "worklog"})]
    (is (= "https://acme-kineolyan.turso.io" (:url opts)))
    (is (= "worklog" (:table-name opts))))
  (testing "a trailing slash goes too"
    (is (= "https://acme.turso.io"
           (:url (turso/options {:url "https://acme.turso.io/" :token "t"}))))))

(deftest a-database-that-cannot-be-reached-is-not-half-configured
  (is (not (turso/configured? {})))
  (is (not (turso/configured? {:url "libsql://acme.turso.io"})))
  (is (not (turso/configured? {:token "t"})))
  (is (turso/configured? {:url "libsql://acme.turso.io" :token "t"}))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"URL and a token"
                        (turso/options {:url "libsql://acme.turso.io"}))))

(deftest the-table-name-is-held-to-an-identifier
  (is (= turso/default-table (:table-name (turso/options {:url "libsql://a" :token "t"})))
      "and defaults when it is not given")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plain SQL identifier"
                        (turso/options {:url "libsql://a" :token "t"
                                        :table-name "worklog; drop table users"}))
      "because it is interpolated into SQL, where a parameter cannot go"))

;; ------------------------------------------------------------------ the wire

(deftest it-talks-to-a-turso-that-answers
  (fake/with-turso [t]
    (let [opts (:options t)]
      (is (:ok (turso/ping opts)))
      (turso/storage opts)
      (is (:table-created @(:state t))
          "creating the storage creates the table, so an empty database is usable")
      (is (some #(str/includes? % "create table if not exists odatoruthall_test")
                (fake/statements t))))))

(deftest a-token-turso-refuses-is-an-error-not-a-shrug
  (fake/with-turso [t]
    (let [opts (assoc (:options t) :token "wrong")]
      (is (false? (:ok (turso/ping opts))))
      (is (thrown? clojure.lang.ExceptionInfo (turso/storage opts))))))

(deftest a-statement-turso-refuses-is-raised
  (fake/with-turso [t]
    (let [opts (:options t)]
      (turso/storage opts)
      (swap! (:state t) assoc :refuse-statement true)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such table"
                            (turso/execute! opts [{:sql "select addr from nope"}]))
          "a write that silently did not happen is worse than one that threw"))))

(deftest a-database-that-does-not-answer-is-raised-too
  (fake/with-turso [t]
    (let [opts (:options t)]
      (turso/storage opts)
      (swap! (:state t) assoc :unreachable true)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"answered 500"
                            (turso/execute! opts [{:sql "select 1"}]))))))

;; --------------------------------------------------------- as a DataScript storage

(def ^:private schema {:work-item/key {:db/unique :db.unique/identity}})

(deftest a-database-stored-in-turso-comes-back
  (fake/with-turso [t]
    (let [opts (:options t)]
      (testing "what was transacted"
        (let [conn (d/create-conn schema {:storage (turso/storage opts)})]
          (d/transact! conn [{:work-item/key "ABC-1" :work-item/summary "Ship it"}])
          (d/transact! conn [{:work-item/key "ABC-2" :work-item/summary "Fix it"}])
          (is (pos? (count (fake/rows t))) "reached the table")))
      (testing "is what a fresh storage restores"
        (let [conn (d/restore-conn (turso/storage opts))]
          (is (some? conn))
          (is (= #{"Ship it" "Fix it"}
                 (set (d/q '[:find [?s ...] :where [_ :work-item/summary ?s]]
                           @conn)))))))))

(deftest nothing-stored-restores-as-nothing
  (fake/with-turso [t]
    (is (nil? (d/restore-conn (turso/storage (:options t))))
        "an empty database is empty, not broken")))

(deftest garbage-collection-drops-what-nothing-references
  (fake/with-turso [t]
    (let [storage (turso/storage (:options t))
          conn (d/create-conn schema {:storage storage})]
      (dotimes [i 40]
        (d/transact! conn [{:work-item/key (str "ABC-" i)}]))
      (let [before (count (fake/rows t))]
        (d/collect-garbage storage)
        (is (<= (count (fake/rows t)) before))
        (is (pos? (count (fake/rows t))) "the live tree is still there")
        (is (= 40 (count (set (d/q '[:find [?k ...] :where [_ :work-item/key ?k]] @conn))))
            "and the data with it")))))

(deftest statements-go-up-in-batches
  (fake/with-turso [t]
    (let [opts (:options t)]
      (turso/storage opts)
      (let [calls-before (fake/calls t)]
        (turso/execute! opts (for [i (range 200)]
                               {:sql "select 1"}))
        (is (< 1 (- (fake/calls t) calls-before))
            "a tree flush can carry hundreds of addresses; a pipeline request is not unbounded")))))

(deftest what-is-in-the-table-can-be-read-back
  (fake/with-turso [t]
    (let [opts (:options t)
          storage (turso/storage opts)
          conn (d/create-conn schema {:storage storage})]
      (d/transact! conn [{:work-item/key "ABC-1"}])
      (let [[result] (turso/execute! opts [{:sql "select addr from odatoruthall_test"}])]
        (is (pos? (count (turso/rows result))))
        (is (every? integer? (map first (turso/rows result))))))))
