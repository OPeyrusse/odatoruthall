(ns odato.access.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [odato.access.interface :as access]
            [odato.testing :as testing*]
            [odato.time.interface :as t]))

(deftest a-fresh-installation-has-offered-nothing
  (let [sys (testing*/system)]
    (is (= :absent (:connection/status (access/connection sys :github))))
    (is (= :absent (:connection/status (access/connection sys :jira))))
    (is (not (access/usable? sys :github)))
    (is (nil? (access/operator-login sys)))))

(deftest a-credential-is-offered-then-exercised
  (let [sys (testing*/system)]
    (access/present-credential! sys :github)
    (is (= :unverified (:connection/status (access/connection sys :github))))
    (is (testing*/saw? sys :connection/check-requested))
    (access/verified! sys :github "ada")
    (is (= :valid (:connection/status (access/connection sys :github))))
    (is (= "ada" (access/operator-login sys)))
    (is (testing*/saw? sys :connection/established))))

(deftest a-refused-credential-names-no-owner
  (let [sys (testing*/system)]
    (access/present-credential! sys :jira)
    (access/refused! sys :jira :credential-rejected)
    (let [c (access/connection sys :jira)]
      (is (= :rejected (:connection/status c)))
      (is (nil? (:connection/account-login c)))
      (is (= :credential-rejected (:connection/last-refusal c))))
    (is (testing*/saw? sys :access/lost))
    (testing "and a credential that is accepted later is valid again"
      (access/verified! sys :jira "ada")
      (is (access/usable? sys :jira)))))

(deftest a-check-that-cannot-reach-the-service-is-a-refusal
  (let [sys (testing*/system {:github {:whoami {:failure :service-unreachable}}})]
    (access/check-connection! sys :github (fn [] {:failure :service-unreachable}))
    (is (= :rejected (:connection/status (access/connection sys :github))))
    (is (= :service-unreachable (:connection/last-refusal
                                 (access/connection sys :github))))))

(deftest quota-exhaustion-is-a-wait-not-a-failure
  (let [sys (testing*/system)
        resets-at (+ (testing*/now sys) (t/minutes 30))]
    (access/budget-observed! sys 4321 resets-at)
    (is (access/budget-available? sys))
    (access/budget-observed! sys 0 resets-at)
    (is (not (access/budget-available? sys)))
    (is (testing*/saw? sys :budget/exhausted))
    (testing "the budget replenishes when its reset time passes"
      (is (nil? (access/replenish-due! sys)) "not before")
      (testing*/tick! sys (t/minutes 31))
      (is (true? (access/replenish-due! sys)))
      (is (access/budget-available? sys))
      (is (= 0 (:budget/remaining (access/budget sys)))
          "and the count stays as GitHub last reported it until the next answer"))))
