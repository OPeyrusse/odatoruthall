(ns odato.ui.worklog-test
  (:require [clojure.test :refer [deftest is testing]]
            [odato.time.interface :as t]
            [odato.ui.worklog :as sheet]))

(defn- entry [day k origin]
  {:day-starts-at day :work-item-key k :origin origin
   :work-item {:key k :summary (str "about " k)}})

(defn- day [starts-at & entries]
  {:starts-at starts-at :entries (vec entries)})

(deftest a-ticket-is-dated-by-its-last-line
  (is (= {"A-1" 30 "A-2" 20}
         (sheet/last-interactions
          [{:attributed-keys #{"A-1"} :happened-at 10}
           {:attributed-keys #{"A-1" "A-2"} :happened-at 20}
           {:attributed-keys #{"A-1"} :happened-at 30}
           {:attributed-keys #{} :happened-at 40}
           {:attributed-keys #{"A-2"} :happened-at nil}]))))

(deftest tickets-read-named-first-then-most-recently-touched
  (let [days [(day 1
                   (entry 1 "PIVOT-10" :discovered)
                   (entry 1 "PIVOT-999" :added-by-operator))
              (day 2
                   (entry 2 "PIVOT-10" :discovered)
                   (entry 2 "PIVOT-2" :added-by-operator)
                   (entry 2 "LIM-7" :discovered)
                   (entry 2 "LIM-3" :discovered)
                   (entry 2 "LIM-9" :discovered))]
        rows (sheet/ticket-rows days {"PIVOT-10" 100 "LIM-7" 300 "LIM-3" 100
                                      ;; A named ticket is not moved by its lines.
                                      "PIVOT-999" 900})]
    (is (= ["PIVOT-2" "PIVOT-999" "LIM-7" "LIM-3" "PIVOT-10" "LIM-9"]
           (map :work-item-key rows))
        "named in ticket order; then the latest line first, ties in ticket order; undated last")
    (testing "a ticket worked on two days is one row holding both entries"
      (let [row (first (filter #(= "PIVOT-10" (:work-item-key %)) rows))]
        (is (= #{1 2} (set (keys (:entries row)))))))
    (testing "a ticket found by a scan on one of its days is not one the operator named"
      (let [rows (sheet/ticket-rows [(day 1 (entry 1 "A-1" :added-by-operator))
                                     (day 2 (entry 2 "A-1" :discovered))]
                                    {"A-1" 5})]
        (is (false? (:named? (first rows))))
        (is (= 5 (:last-interaction-at (first rows))))))))

(deftest a-cell-carries-what-its-editor-needs
  (let [day {:starts-at (t/parse-day "2026-09-15")}]
    (testing "an empty cell opens on the ticket's day with no time"
      (is (= {:data-cell "" :data-day "2026-09-15" :data-key "A-1" :data-time ""
              :style "cursor: text"}
             (sheet/editor-attributes day "A-1" nil))))
    (testing "a cell holding time opens on the time as typed"
      (is (= "1h 30m" (:data-time (sheet/editor-attributes
                                   day "A-1" {:editable? true :entered-text "1h 30m"}))))
      (is (= "" (:data-time (sheet/editor-attributes
                             day "A-1" {:editable? true :entered-text nil})))
          "and on nothing when the task has no time yet"))
    (testing "a cell that cannot be edited carries none of it, and says why"
      (is (= {:title "On its way to Jira: it can be edited once Jira has answered."}
             (sheet/editor-attributes day "A-1" {:editable? false :status :submitted})))
      (is (= {:title "Jira holds this task's time under 2 worklogs; change them in Jira."}
             (sheet/editor-attributes day "A-1" {:editable? false :status :acknowledged
                                                 :jira-worklog-count 2}))))))
