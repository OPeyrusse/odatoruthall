(ns odato.worklog.core
  (:require [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.time.interface :as t]
            [odato.work-item.interface :as items]))

(def config
  {:timesheet-days 5
   :min-entry-duration-ms (t/minutes 1)
   :max-entry-duration-ms (t/hours 24)})

(defn conn [sys] (db/durable (:store sys)))
(defn now [sys] (t/now-ms (:clock sys)))
(defn emit! [sys event] (events/publish! (:bus sys) event))

(defn within-window? [sys day-starts-at]
  (and day-starts-at
       (t/within-timesheet-window? day-starts-at (now sys) (:timesheet-days config))))

;; ------------------------------------------------------------------ reading

(defn- ->entry [row]
  (let [status (:wl-entry/status row)
        duration (:wl-entry/duration-ms row)
        filled? (boolean (and duration (pos? (long duration))))
        jira-logged (:wl-entry/jira-logged-ms row)
        jira-worklogs (long (or (:wl-entry/jira-worklogs row) 0))]
    {:id (:wl-entry/id row)
     :day-starts-at (get-in row [:wl-entry/day :work-day/starts-at])
     :work-item-key (get-in row [:wl-entry/work-item :work-item/key])
     :work-item {:key (get-in row [:wl-entry/work-item :work-item/key])
                 :summary (get-in row [:wl-entry/work-item :work-item/summary])
                 :status (get-in row [:wl-entry/work-item :work-item/status])
                 :url (get-in row [:wl-entry/work-item :work-item/url])
                 :closed? (boolean (get-in row [:wl-entry/work-item :work-item/closed]))}
     :origin (:wl-entry/origin row)
     :status status
     :entered-text (:wl-entry/entered-text row)
     :duration-ms duration
     :jira-worklog-id (:wl-entry/jira-worklog-id row)
     :submitted-at (:wl-entry/submitted-at row)
     :acknowledged-at (:wl-entry/acknowledged-at row)
     :last-failure (:wl-entry/last-failure row)
     :filled? filled?
     :in-flight? (= :submitted status)
     ;; One field cannot edit two worklogs: a submission would update one of
     ;; them and the total would be wrong. Time Jira holds under several is
     ;; read here and changed in Jira.
     :editable? (and (not= :submitted status) (<= jira-worklogs 1))
     :known-to-jira? (some? (:wl-entry/jira-worklog-id row))
     :pending-submission? (and filled? (contains? #{:drafted :rejected} status))

     ;; What Jira itself holds for this task on this day, as the last scan read
     ;; it. Not the same thing as the duration: that is what this entry says,
     ;; which the operator may have typed and not sent.
     :jira-logged-ms jira-logged
     :jira-worklog-count jira-worklogs
     :differs-from-jira? (and (some? jira-logged) (not= duration jira-logged))
     ;; Whether reading Jira may set this entry's time: it holds nothing the
     ;; operator typed that Jira does not already have.
     :takes-jira-time? (and (not= :submitted status)
                            (or (not filled?) (= :acknowledged status)))}))

(defn- entry-rows [sys]
  (let [d (db/db (conn sys))]
    (->> (db/all d :wl-entry/id)
         (map (fn [row]
                (-> row
                    (assoc :wl-entry/day (db/pull d '[:work-day/starts-at]
                                                  (get-in row [:wl-entry/day :db/id])))
                    (assoc :wl-entry/work-item
                           (db/pull d '[*] (get-in row [:wl-entry/work-item :db/id])))))))))

(defn entry [sys entry-id]
  (some->> (entry-rows sys)
           (filter #(= entry-id (:wl-entry/id %)))
           first
           ->entry))

(defn entries-on [sys day-starts-at]
  (->> (entry-rows sys)
       (map ->entry)
       (filter #(= (long day-starts-at) (long (or (:day-starts-at %) -1))))
       (sort-by :work-item-key)
       vec))

(defn day
  "One WorkDay: its tasks, its total, and when it was last scanned. A day with
  nothing on it shows zero rather than being absent."
  [sys day-starts-at]
  (let [row (db/entity-map (db/db (conn sys)) :work-day/starts-at day-starts-at)
        entries (entries-on sys day-starts-at)
        filled (filterv :filled? entries)
        acknowledged (filterv #(= :acknowledged (:status %)) entries)]
    {:starts-at day-starts-at
     :last-scanned-at (:work-day/last-scanned-at row)
     :has-been-scanned (some? (:work-day/last-scanned-at row))
     :entries entries
     :total-logged (reduce + 0 (map :duration-ms filled))
     :drafted-count (count (filter #(and (= :drafted (:status %)) (:filled? %)) entries))
     :submitted-count (count (filter #(= :submitted (:status %)) entries))
     :acknowledged-count (count acknowledged)
     :rejected-count (count (filter #(= :rejected (:status %)) entries))
     :has-time-to-submit (boolean (some :pending-submission? entries))
     :is-in-flight (boolean (some :in-flight? entries))
     :is-settled (and (pos? (count filled)) (= (count acknowledged) (count filled)))}))

(defn ensure-day! [sys day-starts-at]
  (when-not (db/lookup (db/db (conn sys)) :work-day/starts-at day-starts-at)
    (db/transact! (conn sys) [{:work-day/starts-at day-starts-at}]))
  day-starts-at)

(defn window!
  "TimesheetDaysExist. The window slides — it is read from the clock, not
  stored — and the days it shows always exist."
  [sys]
  (let [days (t/working-days-ending-at (now sys) (:timesheet-days config))]
    (doseq [d days] (ensure-day! sys d))
    (mapv #(day sys %) days)))

(defn week-total [sys]
  (reduce + 0 (map :total-logged (window! sys))))

(defn mark-scanned! [sys day-starts-at at]
  (ensure-day! sys day-starts-at)
  (db/put! (conn sys) :work-day/starts-at day-starts-at
           {:work-day/last-scanned-at at}))

;; ------------------------------------------------- putting a task on a day

(defn add-task!
  "TaskAddedToDay / TaskDiscoveredOnDay / TaskNotAddedToDay.

  One entry per task per day: asking for a task the day already has changes
  nothing, and the time already on that entry is untouched."
  [sys day-starts-at issue-key origin]
  (let [item (items/known sys issue-key)]
    (cond
      (nil? item)
      {:ok false :fault :unknown-task}

      (not (within-window? sys day-starts-at))
      (do (emit! sys {:type :worklog/entry-refused :day day-starts-at
                      :fault :day-outside-window})
          {:ok false :fault :day-outside-window})

      (some #(= issue-key (:work-item-key %)) (entries-on sys day-starts-at))
      (do (emit! sys {:type :worklog/entry-refused :day day-starts-at
                      :fault :task-already-on-day})
          {:ok false :fault :task-already-on-day})

      :else
      (let [id (str (random-uuid))]
        (ensure-day! sys day-starts-at)
        (db/transact! (conn sys)
                      [{:wl-entry/id id
                        :wl-entry/day [:work-day/starts-at day-starts-at]
                        :wl-entry/work-item [:work-item/key issue-key]
                        :wl-entry/origin origin
                        :wl-entry/status :drafted}])
        (emit! sys {:type :worklog/task-put-on-day :day day-starts-at
                    :key issue-key :origin origin})
        {:ok true :entry (entry sys id)}))))

(defn jira-time-on-task!
  "JiraTimePutsTheTaskOnTheDay / JiraTimeReadBackOntoTheTask.

  Time logged in Jira on a task the day did not carry puts that task on the
  day, with the time already on it. A task the day does carry records what Jira
  holds either way, and takes it only when it holds nothing the operator typed
  that Jira does not already have."
  [sys day-starts-at issue-key logged-ms worklogs worklog]
  (let [item (items/known sys issue-key)]
    (when item
      (ensure-day! sys day-starts-at)
      (let [existing (first (filter #(= issue-key (:work-item-key %))
                                    (entries-on sys day-starts-at)))
            id (or (:id existing) (str (random-uuid)))
            jira-facts {:wl-entry/jira-logged-ms logged-ms
                        :wl-entry/jira-worklogs (long worklogs)}
            takes? (or (nil? existing) (:takes-jira-time? existing))
            jira-time (when takes?
                        {:wl-entry/duration-ms logged-ms
                         :wl-entry/entered-text (t/format-duration logged-ms)
                         :wl-entry/jira-worklog-id worklog
                         :wl-entry/status :acknowledged
                         :wl-entry/acknowledged-at (now sys)
                         :wl-entry/last-failure nil})]
        (if existing
          (db/put! (conn sys) :wl-entry/id id (merge jira-facts jira-time))
          (do (db/transact! (conn sys)
                            ;; DataScript stores no nil, and a brand new entity
                            ;; map is refused outright for one — unlike a put,
                            ;; where nil retracts. Time Jira holds under
                            ;; several worklogs has no single id to carry.
                            [(into {:wl-entry/id id
                                    :wl-entry/day [:work-day/starts-at day-starts-at]
                                    :wl-entry/work-item [:work-item/key issue-key]
                                    :wl-entry/origin :discovered}
                                   (remove (comp nil? val))
                                   (merge jira-facts jira-time))])
              (emit! sys {:type :worklog/task-put-on-day :day day-starts-at
                          :key issue-key :origin :discovered})))
        (when takes?
          (emit! sys {:type :worklog/time-read-back-from-jira
                      :entry id :key issue-key :logged logged-ms}))
        {:ok true :entry (entry sys id)}))))

(defn name-task!
  "TaskNamed / TaskNamedButUnknown / HeldTaskArrives / HeldTaskNeverArrives.

  The key is held while Jira is asked, and what Jira answers decides whether
  anything lands on the day."
  [sys day-starts-at issue-key]
  (let [issue-key (some-> issue-key clojure.string/trim)]
    (if-not (within-window? sys day-starts-at)
      (do (emit! sys {:type :worklog/entry-refused :day day-starts-at
                      :fault :day-outside-window})
          {:ok false :fault :day-outside-window})
      (if (items/known sys issue-key)
        (add-task! sys day-starts-at issue-key :added-by-operator)
        (let [held-id (str (random-uuid))]
          (ensure-day! sys day-starts-at)
          (db/transact! (conn sys) [{:held-name/id held-id
                                     :held-name/day [:work-day/starts-at day-starts-at]
                                     :held-name/key issue-key
                                     :held-name/named-at (now sys)}])
          (let [answer (items/resolve! sys issue-key)
                held-eid (db/lookup (db/db (conn sys)) :held-name/id held-id)]
            (when held-eid
              (db/transact! (conn sys) [(db/retract-entity held-eid)]))
            (if (:known answer)
              (add-task! sys day-starts-at issue-key :added-by-operator)
              (do (emit! sys {:type :worklog/entry-refused :day day-starts-at
                              :fault :unknown-task :key issue-key})
                  {:ok false :fault :unknown-task}))))))))

;; ------------------------------------------------------------ entering time

(defn jira-time-observed!
  "JiraTimeRecorded. What Jira holds for the day, replaced all at once by what
  the scan read.

  `totals` is one entry per task: `{:key … :logged-ms … :worklogs n :worklog
  id-or-nil}`. A task the scan saw no time for is a task Jira holds no time
  for, so a worklog withdrawn in Jira stops counting here too."
  [sys day-starts-at totals]
  (let [by-key (into {} (map (juxt :key identity)) totals)]
    ;; Every entry forgets what Jira held, then is told again.
    (doseq [e (entries-on sys day-starts-at)]
      (when (or (some? (:jira-logged-ms e)) (pos? (:jira-worklog-count e)))
        (db/put! (conn sys) :wl-entry/id (:id e)
                 {:wl-entry/jira-logged-ms nil :wl-entry/jira-worklogs 0})))
    (doseq [{:keys [key logged-ms worklogs worklog]} totals]
      (jira-time-on-task! sys day-starts-at key logged-ms worklogs worklog))
    (emit! sys {:type :worklog/jira-time-read :day day-starts-at
                :tasks (vec (sort (keys by-key)))})
    {:ok true :tasks (count totals)}))

(defn enter-time!
  "TimeEntered / TimeRefused."
  [sys entry-id text]
  (let [e (entry sys entry-id)
        entered (t/parse-duration text)]
    (cond
      (nil? e) {:ok false :fault :no-such-entry}
      (not (:editable? e)) {:ok false :fault :entry-in-flight}
      (not (within-window? sys (:day-starts-at e))) {:ok false :fault :day-outside-window}
      (not (t/readable-duration? text)) {:ok false :fault :unreadable-duration}
      (< (long entered) (long (:min-entry-duration-ms config)))
      {:ok false :fault :duration-out-of-bounds}
      (> (long entered) (long (:max-entry-duration-ms config)))
      {:ok false :fault :duration-out-of-bounds}
      :else
      (do
        ;; An acknowledged entry edited here is drafted again and keeps the
        ;; Jira worklog it already has.
        (db/put! (conn sys) :wl-entry/id entry-id
                 {:wl-entry/entered-text text
                  :wl-entry/duration-ms entered
                  :wl-entry/status :drafted
                  :wl-entry/last-failure nil})
        (emit! sys {:type :worklog/time-drafted :entry entry-id :duration entered})
        {:ok true :entry (entry sys entry-id)}))))
