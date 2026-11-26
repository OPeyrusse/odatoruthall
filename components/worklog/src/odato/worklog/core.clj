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
(defn- volatile* [sys] (db/volatile* (:store sys)))
(defn now [sys] (t/now-ms (:clock sys)))
(defn emit! [sys event] (events/publish! (:bus sys) event))

(defn within-window? [sys day-starts-at]
  (and day-starts-at
       (t/within-timesheet-window? day-starts-at (now sys) (:timesheet-days config))))

;; ------------------------------------------------------- what a day is busy with

;; A day is scanned or submitted, never both at once and neither twice: a scan
;; would read back time a submission is still writing, and a submission would
;; send entries a scan is still adding to. Which one holds the day dies with
;; the process, like the scans themselves.

(defonce ^:private occupancy
  ;; Looking and taking are one step: two processes arriving together must not
  ;; both find the day free.
  (Object.))

(defn busy-with
  "`:scan`, `:submission`, or nil when the day is free."
  [sys day-starts-at]
  (:day-process/kind
   (db/entity-map (db/db (volatile* sys)) :day-process/starts-at day-starts-at)))

(defn occupy!
  "Takes the day for `process`. `{:ok true}`, or `{:ok false :busy-with p}`."
  [sys day-starts-at process]
  (let [outcome (locking occupancy
                  (if-let [holder (busy-with sys day-starts-at)]
                    {:ok false :busy-with holder}
                    (do (db/transact! (volatile* sys)
                                      [{:day-process/starts-at day-starts-at
                                        :day-process/kind process}])
                        {:ok true})))]
    (when (:ok outcome)
      (emit! sys {:type :worklog/day-busy :day day-starts-at :process process}))
    outcome))

(defn release!
  "Frees the day, if `process` is what holds it."
  [sys day-starts-at process]
  (let [released (locking occupancy
                   (when-let [eid (db/lookup (db/db (volatile* sys))
                                             :day-process/starts-at day-starts-at)]
                     (when (= process (busy-with sys day-starts-at))
                       (db/transact! (volatile* sys) [[:db/retractEntity eid]])
                       true)))]
    (when released
      (emit! sys {:type :worklog/day-free :day day-starts-at :process process}))
    nil))

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

(defn key-order
  "Sorts a key by its project, then by its number as a number: PIVOT-2 comes
  before PIVOT-10. A key without a number falls back to its text."
  [k]
  (if-let [[_ project number] (and k (re-matches #"(.*)-(\d+)" k))]
    [project (parse-long number) k]
    [(str k) Long/MAX_VALUE (str k)]))

(defn entries-on [sys day-starts-at]
  (->> (entry-rows sys)
       (map ->entry)
       (filter #(= (long day-starts-at) (long (or (:day-starts-at %) -1))))
       (sort-by (comp key-order :work-item-key))
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
     :is-settled (and (pos? (count filled)) (= (count acknowledged) (count filled)))
     :busy-with (busy-with sys day-starts-at)}))

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
  "JiraTimeReadBackOntoTheTask / JiraTimeOnATaskTheDayDoesNotCarry.

  What Jira holds for one task the day carries replaces what the last scan
  read; no worklog at all says Jira holds nothing, so a worklog withdrawn in
  Jira stops counting. The entry takes Jira's time only when it holds nothing
  the operator typed that Jira does not already have.

  Logging time is not work found: a task the day does not carry stays off it."
  [sys day-starts-at issue-key {:keys [logged-ms worklogs worklog]}]
  (if-let [existing (first (filter #(= issue-key (:work-item-key %))
                                   (entries-on sys day-starts-at)))]
    (let [id (:id existing)
          held? (pos? (long worklogs))
          takes? (and held? (:takes-jira-time? existing))]
      ;; A put of nil retracts, which is how Jira holding nothing is said.
      (db/put! (conn sys) :wl-entry/id id
               (merge {:wl-entry/jira-logged-ms (when held? logged-ms)
                       :wl-entry/jira-worklogs (if held? (long worklogs) 0)}
                      (when takes?
                        {:wl-entry/duration-ms logged-ms
                         :wl-entry/entered-text (t/format-duration logged-ms)
                         :wl-entry/jira-worklog-id worklog
                         :wl-entry/status :acknowledged
                         :wl-entry/acknowledged-at (now sys)
                         :wl-entry/last-failure nil})))
      (when takes?
        (emit! sys {:type :worklog/time-read-back-from-jira :day day-starts-at
                    :entry id :key issue-key :logged logged-ms}))
      {:ok true :entry (entry sys id)})
    (do (emit! sys {:type :worklog/jira-time-ignored :day day-starts-at :key issue-key})
        {:ok false :fault :task-not-on-day})))

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

(defn duration-fault
  "The fault that refuses `text` as an entry's time, or nil when it can be one."
  [text]
  (let [entered (t/parse-duration text)]
    (cond
      (not (t/readable-duration? text)) :unreadable-duration
      (< (long entered) (long (:min-entry-duration-ms config))) :duration-out-of-bounds
      (> (long entered) (long (:max-entry-duration-ms config))) :duration-out-of-bounds)))

(defn enter-time!
  "TimeEntered / TimeRefused."
  [sys entry-id text]
  (let [e (entry sys entry-id)
        entered (t/parse-duration text)
        fault (cond
                (nil? e) :no-such-entry
                (not (:editable? e)) :entry-in-flight
                (not (within-window? sys (:day-starts-at e))) :day-outside-window
                :else (duration-fault text))]
    (if fault
      {:ok false :fault fault}
      (do
        ;; An acknowledged entry edited here is drafted again and keeps the
        ;; Jira worklog it already has.
        (db/put! (conn sys) :wl-entry/id entry-id
                 {:wl-entry/entered-text text
                  :wl-entry/duration-ms entered
                  :wl-entry/status :drafted
                  :wl-entry/last-failure nil})
        (emit! sys {:type :worklog/time-drafted :day (:day-starts-at e)
                    :entry entry-id :duration entered})
        {:ok true :entry (entry sys entry-id)}))))
