(ns odato.db.interface-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [odato.db.interface :as db]))

(deftest the-two-halves-are-separate
  (let [store (db/open)]
    (db/transact! (db/durable store) [{:work-item/key "ABC-1" :work-item/summary "a"}])
    (db/transact! (db/volatile* store) [{:pr/ident "o/r#1" :pr/number 1}])
    (is (= "a" (:work-item/summary (db/entity-map (db/db (db/durable store))
                                                  :work-item/key "ABC-1"))))
    (is (nil? (db/entity-map (db/db (db/volatile* store)) :work-item/key "ABC-1")))))

(deftest the-durable-half-can-outlive-the-process
  (let [file (io/file (System/getProperty "java.io.tmpdir")
                      (str "odato-test-" (random-uuid) ".edn"))]
    (try
      (testing "what was saved"
        (let [store (db/open {:file (str file)})]
          (db/transact! (db/durable store) [{:work-item/key "ABC-1"
                                             :work-item/summary "Ship it"}])
          (db/close! store)))
      (testing "is what is in force after a restart"
        (let [store (db/open {:file (str file)})]
          (is (= "Ship it" (:work-item/summary
                            (db/entity-map (db/db (db/durable store))
                                           :work-item/key "ABC-1"))))
          (is (empty? (db/all (db/db (db/volatile* store)) :pr/ident))
              "and nothing collected came back with it")))
      (finally (.delete file) (.delete (io/file (str file ".tmp")))))))

(deftest a-pull-requests-facts-are-one-value
  (let [store (db/open)
        facts {:title "Ship it" :author "ada" :commits [{:sha "abc"}]}]
    (db/transact! (db/volatile* store) [{:pr/ident "o/r#1" :pr/facts facts}])
    (is (= facts (:pr/facts (db/entity-map (db/db (db/volatile* store))
                                           :pr/ident "o/r#1"))))))
