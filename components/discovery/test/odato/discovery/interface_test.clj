(ns odato.discovery.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [odato.discovery.interface :as discovery]
            [odato.fixtures :as fix]
            [odato.github.interface :as github]
            [odato.testing :as testing*]
            [odato.time.interface :as t]
            [odato.worklog.interface :as worklog]))

(def today (t/parse-day "2026-09-15"))
(def yesterday (t/parse-day "2026-09-14"))
(def long-ago (t/parse-day "2026-09-01"))
(def morning (+ today (t/hours 9)))

(def jira-script
  {:issues {"ABC-1" {:summary "Ship it" :status "In Progress"
                     :url "https://jira/ABC-1" :is-closed false}
            "ABC-2" {:summary "Fix it" :status "To Do"
                     :url "https://jira/ABC-2" :is-closed false}}
   :unknown #{"ABC-9"}})

(defn- github-script [& {:as overrides}]
  (merge
   {:login "ada"
    :activities
    {:github-commits
     [(fix/activity {:source :github-commits :happened-at morning
                     :reference "sha-1" :title "ABC-1 make it go"
                     :issue-keys #{"ABC-1"}})
      (fix/activity {:source :github-commits :happened-at (+ morning (t/hours 1))
                     :reference "sha-2" :title "ABC-1 make it go faster"
                     :issue-keys #{"ABC-1"}})]
     :github-pull-requests
     [(fix/activity {:source :github-pull-requests :happened-at (+ morning (t/hours 2))
                     :reference "https://github.com/acme/widget/pull/7"
                     :title "ABC-1 Ship it" :issue-keys #{"ABC-1"}})]
     :github-reviews
     [(fix/activity {:source :github-reviews :happened-at (+ morning (t/hours 3))
                     :reference "https://github.com/acme/widget/pull/9"
                     :title "Tidy the README" :issue-keys #{}})]
     :github-comments []}}
   overrides))

(defn- installation [& {:keys [github jira]}]
  (let [sys (testing*/connected! {:github (github-script) :jira (merge jira-script jira)})]
    (worklog/window sys)
    (cond-> sys
      github (assoc :github (github/mock-client (github-script github))))))

(deftest a-scan-turns-a-days-activity-into-tasks
  (let [sys (installation)]
    (discovery/scan! sys today)
    (let [d (worklog/day sys today)]
      (is (= ["ABC-1"] (map :work-item-key (:entries d)))
          "several activities on one ticket are one row")
      (is (= :discovered (:origin (first (:entries d)))))
      (is (= 0 (:total-logged d))
          "the scan says what was worked on, never how long"))
    (testing "and the lines stay separate under it"
      (is (= 3 (count (discovery/lines-for sys today "ABC-1"))))
      (is (= ["sha-1" "sha-2" "https://github.com/acme/widget/pull/7"]
             (map :reference (discovery/lines-for sys today "ABC-1")))
          "each with its own moment, oldest first"))
    (testing "a day records when it was last scanned"
      (is (:has-been-scanned (worklog/day sys today))))))

(deftest a-line-naming-no-issue-is-kept-as-an-orphan
  (let [sys (installation)]
    (discovery/scan! sys today)
    (let [orphans (discovery/orphans-on sys today)]
      (is (= 1 (count orphans)))
      (is (= "Tidy the README" (:title (first orphans))))
      (is (= :github-reviews (:source (first orphans)))))))

(deftest a-key-jira-does-not-know-leaves-the-line-an-orphan
  (let [sys (installation
             :github {:activities
                      {:github-commits
                       [(fix/activity {:source :github-commits :happened-at morning
                                       :reference "sha-x" :title "ABC-9 mystery"
                                       :issue-keys #{"ABC-9"}})]}})]
    (discovery/scan! sys today)
    (is (empty? (:entries (worklog/day sys today))) "nothing landed on the day")
    (is (= 1 (count (discovery/orphans-on sys today))))
    (is (= #{"ABC-9"} (:named-keys (first (discovery/orphans-on sys today))))
        "and the line still says what it named")))

(deftest an-orphan-is-attached-by-naming-the-task-it-belongs-to
  (let [sys (installation)]
    (discovery/scan! sys today)
    (let [orphan (first (discovery/orphans-on sys today))]
      (is (:ok (discovery/attach! sys (:id orphan) "ABC-2")))
      (is (empty? (discovery/orphans-on sys today)))
      (is (= #{"ABC-1" "ABC-2"}
             (set (map :work-item-key (:entries (worklog/day sys today)))))
          "the task lands on the day if it was not there")
      (is (= 1 (count (discovery/lines-for sys today "ABC-2"))))
      (is (empty? (:named-keys (discovery/activity sys (:id orphan))))
          "and what the activity names stays what the scan read"))))

(deftest attaching-a-key-jira-does-not-know-changes-nothing
  (let [sys (installation)]
    (discovery/scan! sys today)
    (let [orphan (first (discovery/orphans-on sys today))
          outcome (discovery/attach! sys (:id orphan) "ABC-9")]
      (is (false? (:ok outcome)))
      (is (= :unknown-task (:fault outcome)))
      (is (= 1 (count (discovery/orphans-on sys today)))))))

(deftest an-activity-outside-the-day-is-not-that-days-work
  (let [sys (installation
             :github {:activities
                      {:github-commits
                       [(fix/activity {:source :github-commits
                                       :happened-at (- today (t/hours 1))
                                       :reference "sha-yesterday"
                                       :title "ABC-1 yesterday" :issue-keys #{"ABC-1"}})]}})]
    (discovery/scan! sys today)
    (is (empty? (discovery/activities-on sys today)))
    (is (empty? (:entries (worklog/day sys today))))))

(deftest a-scan-only-ever-adds
  (let [sys (installation)]
    (discovery/scan! sys today)
    ;; The operator fills the discovered task, attaches the orphan and adds one
    ;; of their own.
    (let [entry (first (:entries (worklog/day sys today)))]
      (worklog/enter-time! sys (:id entry) "2h"))
    (discovery/attach! sys (:id (first (discovery/orphans-on sys today))) "ABC-2")
    (testing*/tick! sys (t/minutes 5))
    (discovery/scan! sys today)
    (let [d (worklog/day sys today)
          abc-1 (first (filter #(= "ABC-1" (:work-item-key %)) (:entries d)))]
      (is (= (t/hours 2) (:duration-ms abc-1)) "the entered time is untouched")
      (is (= #{"ABC-1" "ABC-2"} (set (map :work-item-key (:entries d))))
          "and the task the operator attached is still there")
      (is (= 4 (count (discovery/activities-on sys today)))
          "one line per thing done, not two")
      (is (empty? (discovery/orphans-on sys today))
          "and the orphan they attached stays attached"))))

(deftest a-source-that-failed-takes-nothing-with-it
  (let [sys (installation :github {:failures {:github-pull-requests :service-error}})]
    (discovery/scan! sys today)
    (is (= 3 (count (discovery/activities-on sys today)))
        "the other sources' lines are on the day")
    (let [scan (discovery/last-scan sys today)]
      (is (= 1 (:failed scan)))
      (is (= :service-error (:failure (first (filter #(= :github-pull-requests
                                                         (:source %))
                                                     (:tasks scan))))))
      (is (:has-been-scanned (worklog/day sys today))
          "and the day still says when it was last scanned"))))

(deftest scanning-is-asked-for-one-day-at-a-time
  (let [sys (installation)]
    (testing "both credentials are needed"
      (let [half (testing*/system {:github (github-script) :jira jira-script})]
        (odato.access.interface/check-connection!
         half :github #(github/whoami (:github half)))
        (is (= :credential-unavailable (:reason (discovery/request-scan! half today))))))
    (testing "a scan asked for while one is running is refused"
      (let [first-scan (discovery/request-scan! sys today)]
        (is (:started first-scan))
        (is (= :already-scanning (:reason (discovery/request-scan! sys yesterday))))
        (discovery/run-scan! sys (:scan first-scan))))
    (testing "and a day outside the window is not scanned"
      (is (= :day-outside-window (:reason (discovery/request-scan! sys long-ago)))))))

(deftest sources-take-their-turn-one-at-a-time
  (let [sys (installation)]
    (discovery/scan! sys today)
    (is (= [:github-commits :github-comments :github-pull-requests :github-reviews]
           (->> (github/calls (:github sys))
                (filter #(= :activity (:op %)))
                (map :source)))
        "GitHub first, in the order the scan queues them")))

(deftest quota-exhaustion-is-a-wait-during-a-scan
  (let [sys (installation
             :github {:page-size 1
                      :budget-sequence [{:remaining 5 :resets-at (- morning (t/minutes 1))}
                                        {:remaining 0 :resets-at (- morning (t/minutes 1))}
                                        {:remaining 5000 :resets-at (+ morning (t/hours 1))}]})]
    (discovery/scan! sys today)
    (is (testing*/saw? sys :scan/waiting-on-quota))
    (is (not (testing*/saw? sys :scan/source-failed)))
    (is (= ["sha-1" "sha-2"]
           (map :reference (filter #(= :github-commits (:source %))
                                   (discovery/activities-on sys today))))
        "the source picked up at the cursor it stopped on")))

(deftest a-jira-activity-names-its-issue
  (let [sys (testing*/connected!
             {:github (github-script :activities {})
              :jira (assoc jira-script
                           :activities
                           {:jira-issues-created
                            [(fix/activity {:source :jira-issues-created
                                            :happened-at morning
                                            :reference "https://jira/ABC-2"
                                            :title "ABC-2 Fix it"
                                            :issue-keys #{"ABC-2"}})]})})]
    (worklog/window sys)
    (discovery/scan! sys today)
    (is (= ["ABC-2"] (map :work-item-key (:entries (worklog/day sys today)))))
    (is (empty? (discovery/orphans-on sys today))
        "Jira activities are never unattributed")))
