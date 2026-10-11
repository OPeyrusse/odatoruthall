(ns odato.time.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [odato.time.interface :as t]))

;; 2026-09-15 is a Tuesday.
(def tuesday (t/parse-instant "2026-09-15T10:30:00Z"))

(deftest utc-days
  (is (= (t/parse-instant "2026-09-15T00:00:00Z") (t/utc-midnight-of tuesday)))
  (is (= :tuesday (t/day-of-week tuesday)))
  (is (t/working-day? tuesday))
  (is (not (t/working-day? (t/parse-instant "2026-09-19T10:00:00Z"))))
  (is (= "2026-09-15" (t/format-day (t/utc-midnight-of tuesday))))
  (is (= (t/utc-midnight-of tuesday) (t/parse-day "2026-09-15"))))

(deftest instants-as-jira-writes-them
  (is (= (t/parse-instant "2026-09-15T09:00:00Z")
         (t/parse-instant "2026-09-15T09:00:00.000+0000")))
  (is (= (t/parse-instant "2026-09-15T07:00:00Z")
         (t/parse-instant "2026-09-15T09:00:00.000+0200")))
  (is (nil? (t/parse-instant "not a time"))))

(deftest an-activity-belongs-to-one-day
  (let [d (t/utc-midnight-of tuesday)]
    (is (t/falls-within-day? tuesday d))
    (is (t/falls-within-day? d d))
    (is (not (t/falls-within-day? (+ d (t/days 1)) d)))
    (is (not (t/falls-within-day? (- d 1) d)))))

(deftest the-timesheet-window-is-five-working-days
  (testing "today included when today is a working day"
    (let [days (t/working-days-ending-at tuesday 5)]
      (is (= 5 (count days)))
      (is (= ["2026-09-09" "2026-09-10" "2026-09-11" "2026-09-14" "2026-09-15"]
             (map t/format-day days)))))
  (testing "a weekend falls back to the five working days before it"
    (let [saturday (t/parse-instant "2026-09-19T12:00:00Z")
          days (t/working-days-ending-at saturday 5)]
      (is (= ["2026-09-14" "2026-09-15" "2026-09-16" "2026-09-17" "2026-09-18"]
             (map t/format-day days)))))
  (testing "the window bounds what can be filled"
    (is (t/within-timesheet-window? (t/parse-day "2026-09-09") tuesday 5))
    (is (not (t/within-timesheet-window? (t/parse-day "2026-09-08") tuesday 5)))
    (is (not (t/within-timesheet-window? (t/parse-day "2026-09-16") tuesday 5)))))

(def window {:opens-hour 5 :closes-hour 21
             :active-days #{:monday :tuesday :wednesday :thursday :friday}})

(deftest the-collection-window
  (testing "inside"
    (is (t/within-collection-window? (t/parse-instant "2026-09-15T05:00:00Z") window))
    (is (t/within-collection-window? (t/parse-instant "2026-09-15T20:59:00Z") window)))
  (testing "outside"
    (is (not (t/within-collection-window? (t/parse-instant "2026-09-15T04:59:00Z") window)))
    (is (not (t/within-collection-window? (t/parse-instant "2026-09-15T21:00:00Z") window)))
    (is (not (t/within-collection-window? (t/parse-instant "2026-09-19T10:00:00Z") window))
        "nothing at the weekend"))
  (testing "the schedule rolls forward to the next opening"
    (is (= (t/parse-instant "2026-09-15T05:00:00Z")
           (t/next-window-opening (t/parse-instant "2026-09-15T03:00:00Z") window))
        "later today when it has not opened yet")
    (is (= (t/parse-instant "2026-09-16T05:00:00Z")
           (t/next-window-opening (t/parse-instant "2026-09-15T22:00:00Z") window))
        "tomorrow once it has closed")
    (is (= (t/parse-instant "2026-09-21T05:00:00Z")
           (t/next-window-opening (t/parse-instant "2026-09-18T22:00:00Z") window))
        "over the weekend to Monday")))

(deftest durations-are-typed-as-text
  (testing "text that says a length of time"
    (is (= (t/minutes 90) (t/parse-duration "1h 30m")))
    (is (= (t/minutes 90) (t/parse-duration "1h30m")))
    (is (= (t/minutes 90) (t/parse-duration "90m")))
    (is (= (t/minutes 90) (t/parse-duration "1.5h")))
    (is (= (t/minutes 90) (t/parse-duration "1,5h")))
    (is (= (t/minutes 90) (t/parse-duration "1:30")))
    (is (= (t/minutes 45) (t/parse-duration "45")) "a bare number is minutes")
    (is (= (t/hours 24) (t/parse-duration "1d")))
    (is (= (t/minutes 30) (t/parse-duration "30min"))))
  (testing "text that says none"
    (is (nil? (t/parse-duration "soon")))
    (is (nil? (t/parse-duration "1h of meetings")))
    (is (nil? (t/parse-duration "")))
    (is (nil? (t/parse-duration nil)))
    (is (not (t/readable-duration? "0m")) "nothing is not a length of time")
    (is (t/readable-duration? "1m")))
  (testing "it reads back the way it was filled"
    (is (= "1h 30m" (t/format-duration (t/minutes 90))))
    (is (= "2h" (t/format-duration (t/hours 2))))
    (is (= "45m" (t/format-duration (t/minutes 45))))
    (is (= "0m" (t/format-duration 0)))))

(deftest a-clock-can-be-held-still
  (let [clock (t/fixed-clock "2026-09-15T10:30:00Z")]
    (is (= tuesday (t/now-ms clock)))
    (t/advance! clock (t/hours 2))
    (is (= (t/parse-instant "2026-09-15T12:30:00Z") (t/now-ms clock)))))
