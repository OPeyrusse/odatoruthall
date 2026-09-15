(ns odato.work-item.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [odato.jira.interface :as jira]
            [odato.testing :as testing*]
            [odato.work-item.interface :as items]))

(def jira-script
  {:issues {"ABC-1" {:summary "Ship it" :status "In Progress"
                     :url "https://jira/ABC-1" :is-closed false}}
   :unknown #{"ABC-9"}})

(deftest a-key-becomes-a-work-item
  (let [sys (testing*/connected! {:jira jira-script})
        answer (items/resolve! sys "ABC-1")]
    (is (= "Ship it" (get-in answer [:known :summary])))
    (is (= :resolved (items/lookup-state sys "ABC-1")))
    (is (testing*/saw? sys :work-item/known))))

(deftest a-key-already-known-is-not-asked-about-again
  (let [sys (testing*/connected! {:jira jira-script})]
    (items/resolve! sys "ABC-1")
    (let [asked (count (filter #(= :issue (:op %)) (jira/calls (:jira sys))))]
      (items/resolve! sys "ABC-1")
      (is (= asked (count (filter #(= :issue (:op %)) (jira/calls (:jira sys)))))))
    (testing "but refreshing asks again and takes the answer as the facts"
      (items/refresh! sys "ABC-1")
      (is (< 1 (count (filter #(= :issue (:op %)) (jira/calls (:jira sys)))))))))

(deftest a-key-jira-does-not-know-is-not-a-work-item
  (let [sys (testing*/connected! {:jira jira-script})
        answer (items/resolve! sys "ABC-9")]
    (is (= :unknown-issue (:failure answer)))
    (is (nil? (items/known sys "ABC-9")))
    (is (= :failed (items/lookup-state sys "ABC-9")))
    (is (testing*/saw? sys :work-item/unavailable))
    (testing "and it can be asked about again"
      (items/resolve! sys "ABC-9")
      (is (= 2 (count (filter #(= :issue (:op %)) (jira/calls (:jira sys)))))))))

(deftest what-could-not-be-a-key-is-not-worth-a-round-trip
  (let [sys (testing*/connected! {:jira jira-script})]
    (is (items/valid-key? "ABC-1"))
    (is (not (items/valid-key? "not a key")))
    (is (= :unknown-issue (:failure (items/resolve! sys "not a key"))))
    (is (empty? (filter #(= :issue (:op %)) (jira/calls (:jira sys)))))))
