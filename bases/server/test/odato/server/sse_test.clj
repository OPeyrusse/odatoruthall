(ns odato.server.sse-test
  (:require [clojure.test :refer [deftest is testing]]
            [odato.server.sse :as sse]))

(def ^:private monday 1757894400000)
(def ^:private tuesday (+ monday 86400000))

(defn- carries [& ks] (constantly (set ks)))

(deftest a-day-page-is-patched-by-what-happens-on-that-day
  (let [relevant? (partial sse/worklog-day-event? tuesday (carries "ABC-1"))]
    (testing "its own scan and its own entries"
      (is (relevant? {:type :scan/started :day tuesday :scan "s"}))
      (is (relevant? {:type :scan/line-attributed :day tuesday :key "ABC-1"}))
      (is (relevant? {:type :worklog/time-drafted :day tuesday :entry "e"})))
    (testing "the credentials every day depends on"
      (is (relevant? {:type :connection/established}))
      (is (relevant? {:type :access/revoked})))
    (testing "a ticket the day carries"
      (is (relevant? {:type :work-item/known :key "ABC-1"})))))

(deftest a-day-page-is-left-alone-by-another-day
  ;; A scan on Monday morphing Tuesday's page would reset the time field the
  ;; operator is typing into.
  (let [relevant? (partial sse/worklog-day-event? tuesday (carries "ABC-1"))]
    (is (not (relevant? {:type :scan/started :day monday :scan "s"})))
    (is (not (relevant? {:type :scan/waiting-on-quota :day monday :scan "s"})))
    (is (not (relevant? {:type :scan/line-attributed :day monday :key "ABC-1"})))
    (is (not (relevant? {:type :worklog/task-put-on-day :day monday :key "XYZ-9"})))
    (is (not (relevant? {:type :worklog/time-drafted :day monday :entry "e"})))
    (is (not (relevant? {:type :work-item/known :key "XYZ-9"})))
    (is (not (relevant? {:type :collection/completed})))))
