(ns odato.discovery.jira-time-test
  "The time Jira already holds.

  Jira is the authority on what Jira holds: once the activity sources are
  drained, a day scan reads, on each task the day carries, the operator's own
  worklogs for that day — whether this installation wrote them or they logged
  them in Jira itself. A task worked on and logged elsewhere does not read as
  an empty task.

  Logging time is not an activity: a worklog on a ticket the day does not
  otherwise carry puts nothing on the day."
  (:require [clojure.test :refer [deftest is testing]]
            [odato.discovery.interface :as discovery]
            [odato.fixtures :as fix]
            [odato.jira.interface :as jira]
            [odato.testing :as testing*]
            [odato.time.interface :as t]
            [odato.worklog.interface :as worklog]))

(def today (t/parse-day "2026-09-15"))
(def morning (+ today (t/hours 9)))

(def issues
  {"PIVOT-15358" {:summary "remove comment blocks for related reading"
                  :status "Resolved" :url "https://jira/PIVOT-15358" :is-closed true}
   "PIVOT-15359" {:summary "the same, one version down"
                  :status "Resolved" :url "https://jira/PIVOT-15359" :is-closed true}})

(defn- comment-on [key at]
  (fix/activity {:source :jira-comments
                 :happened-at at
                 :reference (str "https://jira/" key "?focusedCommentId=" at)
                 :title (str "Comment on " key)
                 :issue-keys #{key}}))

(def commented
  "The operator commented on both tickets: that is what puts them on the day."
  [(comment-on "PIVOT-15358" (+ morning (t/minutes 5)))
   (comment-on "PIVOT-15359" (+ morning (t/minutes 10)))])

(defn- jira-script [& {:keys [worklogs comments failures]
                       :or {comments commented}}]
  (cond-> {:login "ada"
           :issues issues
           :activities {:jira-comments (vec comments)}
           :time-logged (vec worklogs)}
    failures (assoc :failures failures)))

(defn- installation [& opts]
  (let [sys (testing*/connected!
             {:github {:login "ada" :activities {}}
              :jira (apply jira-script opts)})]
    (worklog/window sys)
    sys))

(defn- with-jira [sys & opts]
  (assoc sys :jira (jira/mock-client (apply jira-script opts))))

(defn- entry-for [sys key]
  (first (filter #(= key (:work-item-key %)) (:entries (worklog/day sys today)))))

(def one-hour
  [(fix/worklog {:id 76812 :key "PIVOT-15358" :at morning :logged-ms (t/hours 1)})])

(deftest time-logged-in-jira-is-read-back-onto-the-task
  (let [sys (installation :worklogs one-hour)]
    (discovery/scan! sys today)
    (let [entry (entry-for sys "PIVOT-15358")]
      (is (some? entry) "the comment put the task on the day")
      (is (= (t/hours 1) (:duration-ms entry)) "with the time Jira holds on it")
      (is (= "1h" (:entered-text entry)))
      (is (= :acknowledged (:status entry)) "Jira holds it, so it is acknowledged")
      (is (= :discovered (:origin entry)))
      (is (= "76812" (:jira-worklog-id entry)))
      (is (= (t/hours 1) (:jira-logged-ms entry)))
      (is (not (:differs-from-jira? entry))))
    (is (nil? (:jira-logged-ms (entry-for sys "PIVOT-15359")))
        "a task Jira holds no time for says so")
    (is (= (t/hours 1) (:total-logged (worklog/day sys today)))
        "and the time counts towards the day")
    (is (= 1 (count (discovery/lines-for sys today "PIVOT-15358")))
        "the worklog is not a line: only the comment is")))

(deftest a-worklog-alone-puts-nothing-on-the-day
  (let [sys (installation :worklogs one-hour :comments [])]
    (discovery/scan! sys today)
    (is (empty? (:entries (worklog/day sys today)))
        "logging time is not work found")
    (is (empty? (discovery/activities-on sys today)))
    (is (= 0 (:total-logged (worklog/day sys today))))
    (is (not-any? #(= :task-time (:op %)) (jira/calls (:jira sys)))
        "and Jira is not even asked: the reading follows the tasks")))

(deftest somebody-elses-time-is-not-read-back
  (let [sys (installation :worklogs [(fix/worklog {:id 1 :key "PIVOT-15358" :at morning
                                                   :logged-ms (t/hours 4)
                                                   :author "bob"})])]
    (discovery/scan! sys today)
    (is (nil? (:jira-logged-ms (entry-for sys "PIVOT-15358"))))
    (is (= 0 (:total-logged (worklog/day sys today))))))

(deftest time-logged-on-another-day-is-not-this-days
  (let [sys (installation :worklogs [(fix/worklog {:id 1 :key "PIVOT-15358"
                                                   :at (- morning (t/days 1))
                                                   :logged-ms (t/hours 2)})])]
    (discovery/scan! sys today)
    (is (nil? (:jira-logged-ms (entry-for sys "PIVOT-15358"))))))

(deftest a-task-the-operator-named-is-read-too
  (let [sys (installation :worklogs one-hour :comments [])]
    (worklog/name-task! sys today "PIVOT-15358")
    (discovery/scan! sys today)
    (is (= (t/hours 1) (:duration-ms (entry-for sys "PIVOT-15358")))
        "every task the day carries is read, whoever put it there")))

(deftest scanning-twice-does-not-log-it-twice
  (let [sys (installation :worklogs one-hour)]
    (discovery/scan! sys today)
    (testing*/tick! sys (t/minutes 5))
    (discovery/scan! sys today)
    (is (= (t/hours 1) (:total-logged (worklog/day sys today))))
    (is (= 2 (count (:entries (worklog/day sys today)))))))

(deftest a-worklog-edited-in-jira-is-read-again
  (let [sys (installation :worklogs [(fix/worklog {:id 76812 :key "PIVOT-15358"
                                                   :at morning
                                                   :logged-ms (t/minutes 1)})])]
    (discovery/scan! sys today)
    (is (= (t/minutes 1) (:total-logged (worklog/day sys today))))
    (testing "the operator corrects it in Jira, and scans again"
      (let [sys (with-jira sys :worklogs one-hour)]
        (testing*/tick! sys (t/minutes 5))
        (discovery/scan! sys today)
        (is (= (t/hours 1) (:total-logged (worklog/day sys today))))
        (is (= "1h" (:entered-text (entry-for sys "PIVOT-15358"))))))))

(deftest a-worklog-withdrawn-in-jira-stops-counting
  (let [sys (installation :worklogs one-hour)]
    (discovery/scan! sys today)
    (is (= (t/hours 1) (:jira-logged-ms (entry-for sys "PIVOT-15358"))))
    (let [sys (with-jira sys :worklogs [])]
      (testing*/tick! sys (t/minutes 5))
      (discovery/scan! sys today)
      (is (nil? (:jira-logged-ms (entry-for sys "PIVOT-15358")))
          "Jira holds nothing for it any more, and says so")
      (is (= 0 (:jira-worklog-count (entry-for sys "PIVOT-15358")))))))

(deftest time-the-operator-typed-is-never-overwritten
  (let [sys (installation :worklogs one-hour)]
    ;; The operator puts the task on the day themselves and types their own
    ;; time, without submitting it.
    (worklog/name-task! sys today "PIVOT-15358")
    (worklog/enter-time! sys (:id (entry-for sys "PIVOT-15358")) "3h")
    (discovery/scan! sys today)
    (let [entry (entry-for sys "PIVOT-15358")]
      (is (= (t/hours 3) (:duration-ms entry)) "their time is still theirs")
      (is (= "3h" (:entered-text entry)))
      (is (= :drafted (:status entry)) "and still on its way nowhere")
      (is (= (t/hours 1) (:jira-logged-ms entry))
          "and what Jira holds is recorded beside it")
      (is (:differs-from-jira? entry) "so the day can say both"))))

(deftest an-entry-already-acknowledged-follows-jira
  (let [sys (installation :worklogs one-hour)]
    (worklog/name-task! sys today "PIVOT-15358")
    (worklog/enter-time! sys (:id (entry-for sys "PIVOT-15358")) "30m")
    (worklog/submit-day! sys today)
    (is (= :acknowledged (:status (entry-for sys "PIVOT-15358"))))
    (discovery/scan! sys today)
    (is (= (t/hours 1) (:duration-ms (entry-for sys "PIVOT-15358")))
        "what Jira holds is what the entry says, once Jira holds it")))

(deftest an-entry-on-its-way-to-jira-is-left-alone
  (let [sys (installation :worklogs one-hour)]
    (worklog/name-task! sys today "PIVOT-15358")
    (worklog/enter-time! sys (:id (entry-for sys "PIVOT-15358")) "3h")
    (odato.db.interface/put! (odato.db.interface/durable (:store sys))
                             :wl-entry/id (:id (entry-for sys "PIVOT-15358"))
                             {:wl-entry/status :submitted})
    (discovery/scan! sys today)
    (let [entry (entry-for sys "PIVOT-15358")]
      (is (= :submitted (:status entry)))
      (is (= (t/hours 3) (:duration-ms entry))))))

(deftest several-worklogs-on-one-task-are-one-row
  (let [sys (installation :worklogs [(fix/worklog {:id 1 :key "PIVOT-15358"
                                                   :at morning :logged-ms (t/hours 1)})
                                     (fix/worklog {:id 2 :key "PIVOT-15358"
                                                   :at (+ morning (t/hours 4))
                                                   :logged-ms (t/minutes 30)})])]
    (discovery/scan! sys today)
    (let [entry (entry-for sys "PIVOT-15358")]
      (is (= (t/minutes 90) (:duration-ms entry)) "totalled")
      (is (= 2 (:jira-worklog-count entry)))
      (is (nil? (:jira-worklog-id entry))
          "and no single worklog a submission could update")
      (is (not (:editable? entry))
          "so it is read here and changed in Jira"))))

(deftest several-tasks-each-get-their-own-time
  (let [sys (installation :worklogs [(fix/worklog {:id 1 :key "PIVOT-15358"
                                                   :at morning :logged-ms (t/hours 1)})
                                     (fix/worklog {:id 2 :key "PIVOT-15359"
                                                   :at (+ morning (t/hours 2))
                                                   :logged-ms (t/minutes 45)})])]
    (discovery/scan! sys today)
    (is (= (t/minutes 105) (:total-logged (worklog/day sys today))))
    (is (= (t/hours 1) (:duration-ms (entry-for sys "PIVOT-15358"))))
    (is (= (t/minutes 45) (:duration-ms (entry-for sys "PIVOT-15359"))))))

(deftest a-reading-that-fails-leaves-the-task-as-it-was
  (let [sys (installation :worklogs one-hour)]
    (discovery/scan! sys today)
    (is (= (t/hours 1) (:total-logged (worklog/day sys today))))
    (let [sys (with-jira sys :failures {:task-time :service-error})]
      (testing*/tick! sys (t/minutes 5))
      (let [outcome (discovery/scan! sys today)]
        (is (= (t/hours 1) (:total-logged (worklog/day sys today)))
            "a reading that failed hands nothing over")
        (is (= 2 (:unread outcome)) "and the scan says which tasks it could not read")
        (is (= 2 (:unread (discovery/last-scan sys today))))
        (is (= 0 (:failed (discovery/last-scan sys today)))
            "every activity source answered")))))
