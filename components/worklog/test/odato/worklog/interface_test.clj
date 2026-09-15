(ns odato.worklog.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [odato.jira.interface :as jira]
            [odato.testing :as testing*]
            [odato.time.interface :as t]
            [odato.work-item.interface :as items]
            [odato.worklog.interface :as worklog]))

(def jira-script
  {:issues {"ABC-1" {:summary "Ship it" :status "In Progress"
                     :url "https://jira/ABC-1" :is-closed false}
            "ABC-2" {:summary "Fix it" :status "To Do"
                     :url "https://jira/ABC-2" :is-closed false}}
   :unknown #{"ABC-9"}})

(def today (t/parse-day "2026-09-15"))
(def yesterday (t/parse-day "2026-09-14"))
(def long-ago (t/parse-day "2026-09-01"))

(defn- installation [& {:as overrides}]
  (testing*/connected! (merge {:jira jira-script} overrides)))

(deftest five-working-days-always-show
  (let [sys (installation)
        days (worklog/window sys)]
    (is (= 5 (count days)))
    (is (= ["2026-09-09" "2026-09-10" "2026-09-11" "2026-09-14" "2026-09-15"]
           (map (comp t/format-day :starts-at) days)))
    (is (every? #(= 0 (:total-logged %)) days)
        "a day with nothing on it shows zero rather than being absent")
    (is (every? #(false? (:has-been-scanned %)) days))))

(deftest a-task-is-named-by-its-key-and-comes-from-jira
  (let [sys (installation)]
    (is (:ok (worklog/name-task! sys today "ABC-1")))
    (let [d (worklog/day sys today)]
      (is (= ["ABC-1"] (map :work-item-key (:entries d))))
      (is (= "Ship it" (get-in (first (:entries d)) [:work-item :summary])))
      (is (= :added-by-operator (:origin (first (:entries d)))))
      (is (= :drafted (:status (first (:entries d)))))
      (is (= 0 (:total-logged d)) "naming a task puts no time on it"))))

(deftest a-key-jira-does-not-know-puts-nothing-on-the-day
  (let [sys (installation)
        outcome (worklog/name-task! sys today "ABC-9")]
    (is (false? (:ok outcome)))
    (is (= :unknown-task (:fault outcome)))
    (is (empty? (:entries (worklog/day sys today))))))

(deftest one-entry-per-task-per-day
  (let [sys (installation)]
    (worklog/name-task! sys today "ABC-1")
    (worklog/enter-time! sys (:id (first (:entries (worklog/day sys today)))) "1h")
    (let [again (worklog/name-task! sys today "ABC-1")]
      (is (= :task-already-on-day (:fault again)))
      (is (= 1 (count (:entries (worklog/day sys today)))))
      (is (= (t/hours 1) (:total-logged (worklog/day sys today)))
          "and the time already on that entry is untouched"))))

(deftest every-day-in-the-window-can-be-filled
  (let [sys (installation)]
    (is (:ok (worklog/name-task! sys yesterday "ABC-1")))
    (is (= :day-outside-window (:fault (worklog/name-task! sys long-ago "ABC-1")))
        "and a day that has left it is settled")))

(deftest time-is-typed-as-text-and-read-back-as-typed
  (let [sys (installation)
        _ (worklog/name-task! sys today "ABC-1")
        entry-id (:id (first (:entries (worklog/day sys today))))]
    (is (:ok (worklog/enter-time! sys entry-id "1h 30m")))
    (let [e (worklog/entry sys entry-id)]
      (is (= "1h 30m" (:entered-text e)))
      (is (= (t/minutes 90) (:duration-ms e)))
      (is (= (t/minutes 90) (:total-logged (worklog/day sys today)))))
    (testing "text that says no length of time is refused and changes nothing"
      (is (= :unreadable-duration (:fault (worklog/enter-time! sys entry-id "a while"))))
      (is (= "1h 30m" (:entered-text (worklog/entry sys entry-id)))))
    (testing "and so is a duration no day can hold"
      (is (= :duration-out-of-bounds (:fault (worklog/enter-time! sys entry-id "30h"))))
      (is (= "1h 30m" (:entered-text (worklog/entry sys entry-id)))))))

(defn- filled-day [sys day key text]
  (worklog/name-task! sys day key)
  (let [e (first (filter #(= key (:work-item-key %)) (:entries (worklog/day sys day))))]
    (worklog/enter-time! sys (:id e) text)
    (:id e)))

(deftest a-day-reaches-jira-only-on-the-operators-word
  (let [sys (installation)
        entry-id (filled-day sys today "ABC-1" "1h 30m")]
    (is (empty? (jira/worklogs (:jira sys))) "nothing while the operator types")
    (let [outcome (worklog/submit-day! sys today)]
      (is (:ok outcome))
      (is (= 1 (:acknowledged outcome))))
    (let [e (worklog/entry sys entry-id)]
      (is (= :acknowledged (:status e)))
      (is (some? (:jira-worklog-id e)))
      (is (:is-settled (worklog/day sys today))))
    (is (= 1 (count (jira/worklogs (:jira sys)))))))

(deftest a-day-submitted-twice-logs-the-time-once
  (let [sys (installation)
        entry-id (filled-day sys today "ABC-1" "1h")]
    (worklog/submit-day! sys today)
    (let [worklog-id (:jira-worklog-id (worklog/entry sys entry-id))]
      (is (= :nothing-to-submit (:fault (worklog/submit-day! sys today)))
          "an entry Jira holds and untouched since is left alone")
      (testing "editing it drafts it again and keeps the worklog"
        (worklog/enter-time! sys entry-id "2h")
        (let [e (worklog/entry sys entry-id)]
          (is (= :drafted (:status e)))
          (is (= worklog-id (:jira-worklog-id e))))
        (worklog/submit-day! sys today)
        (is (= 1 (count (jira/worklogs (:jira sys))))
            "and the re-submission updated that worklog rather than adding a second")
        (is (= (t/hours 2) (:duration-ms (get (jira/worklogs (:jira sys)) worklog-id))))))))

(deftest a-refusal-costs-nothing-but-the-trip
  (let [sys (installation :jira (assoc jira-script :failures {:worklog :worklog-refused}))
        entry-id (filled-day sys today "ABC-1" "1h")]
    (worklog/submit-day! sys today)
    (let [e (worklog/entry sys entry-id)]
      (is (= :rejected (:status e)))
      (is (= :worklog-refused (:last-failure e)))
      (is (= (t/hours 1) (:duration-ms e)) "the time entered is still here")
      (is (:has-time-to-submit (worklog/day sys today))
          "and submitting the day again sends it once more"))))

(deftest a-day-cannot-be-submitted-without-jira
  (let [sys (testing*/system {:jira jira-script})]
    ;; GitHub is connected, Jira never was.
    (items/resolve! sys "ABC-1")
    (worklog/add-task! sys today "ABC-1")
    (let [e (first (:entries (worklog/day sys today)))]
      (worklog/enter-time! sys (:id e) "1h"))
    (is (= :credential-unavailable (:fault (worklog/submit-day! sys today))))))

(deftest an-entry-in-flight-is-not-editable
  (let [sys (installation)
        entry-id (filled-day sys today "ABC-1" "1h")]
    ;; Put the entry in flight by hand: submitted is a state the operator
    ;; cannot type over.
    (odato.db.interface/put! (odato.db.interface/durable (:store sys))
                             :wl-entry/id entry-id {:wl-entry/status :submitted})
    (is (= :entry-in-flight (:fault (worklog/enter-time! sys entry-id "2h"))))
    (is (= :entry-in-flight (:fault (worklog/remove-entry! sys entry-id))))))

(deftest time-jira-holds-is-withdrawn-before-the-row-goes
  (let [sys (installation)
        entry-id (filled-day sys today "ABC-1" "1h")]
    (worklog/submit-day! sys today)
    (is (= 1 (count (jira/worklogs (:jira sys)))))
    (is (:ok (worklog/remove-entry! sys entry-id)))
    (is (empty? (jira/worklogs (:jira sys))))
    (is (empty? (:entries (worklog/day sys today))))))

(deftest a-worklog-jira-will-not-give-up-keeps-its-row
  (let [sys (installation :jira (assoc jira-script
                                       :failures {:worklog-delete :jira-unreachable}))
        entry-id (filled-day sys today "ABC-1" "1h")]
    (worklog/submit-day! sys today)
    (let [outcome (worklog/remove-entry! sys entry-id)]
      (is (false? (:ok outcome)))
      (is (= :jira-unreachable (:fault outcome)))
      (is (some? (worklog/entry sys entry-id)))
      (is (testing*/saw? sys :worklog/still-in-jira)))))

(deftest the-total-is-the-sum-of-the-day-whatever-the-state
  (let [sys (installation)]
    (filled-day sys today "ABC-1" "1h")
    (filled-day sys today "ABC-2" "30m")
    (let [d (worklog/day sys today)]
      (is (= (t/minutes 90) (:total-logged d)))
      (is (= 2 (:drafted-count d))))
    (worklog/submit-day! sys today)
    (is (= (t/minutes 90) (:total-logged (worklog/day sys today))))
    (is (= (t/minutes 90) (worklog/week-total sys)))))

(deftest the-worklog-outlives-the-process
  (let [file (str (System/getProperty "java.io.tmpdir") "/odato-worklog-"
                  (random-uuid) ".edn")]
    (try
      (let [sys (installation :file file)]
        (filled-day sys today "ABC-1" "1h 30m")
        (worklog/submit-day! sys today))
      (let [sys (installation :file file)
            d (worklog/day sys today)
            e (first (:entries d))]
        (is (= "1h 30m" (:entered-text e)))
        (is (= :acknowledged (:status e)))
        (is (some? (:jira-worklog-id e)))
        (is (= "Ship it" (get-in e [:work-item :summary]))))
      (finally (.delete (java.io.File. file))
               (.delete (java.io.File. (str file ".tmp")))))))
