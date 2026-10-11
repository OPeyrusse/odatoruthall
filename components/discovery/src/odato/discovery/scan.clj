(ns odato.discovery.scan
  "One day scan: the queue, the pages, the attribution and the end."
  (:require [odato.access.interface :as access]
            [odato.db.interface :as db]
            [odato.discovery.core :as core]
            [odato.github.interface :as github]
            [odato.jira.interface :as jira]
            [odato.time.interface :as t]
            [odato.work-item.interface :as items]
            [odato.worklog.interface :as worklog]))

;; ------------------------------------------------------------ asking for one

(defn- refusal [sys day-starts-at]
  (cond
    (or (not (access/usable? sys :github)) (not (access/usable? sys :jira)))
    :credential-unavailable
    (not (worklog/within-window? sys day-starts-at)) :day-outside-window
    :else nil))

(defn- claim-day!
  "The refusal, or the id of a scan now running on the day. The day is taken
  from the worklog, which holds it for one process at a time: a day being
  scanned is not scanned again, and a day being submitted is not scanned."
  [sys day-starts-at]
  (or (refusal sys day-starts-at)
      (let [taken (worklog/occupy-day! sys day-starts-at :scan)]
        (if-not (:ok taken)
          (case (:busy-with taken)
            :submission :day-being-submitted
            :already-scanning)
          (let [scan-id (str (random-uuid))]
            (db/transact! (core/volatile* sys)
                          [{:scan/id scan-id
                            :scan/day-starts-at day-starts-at
                            :scan/status :running
                            :scan/started-at (core/now sys)}])
            {:scan-id scan-id})))))

(defn request-scan!
  "ScanStarts / ScanNotStarted. Both services are scanned, so both credentials
  are needed: a scan with half the picture would look like a day with half the
  work on it. Other days' scans do not stand in the way; a scan of the same
  day does, and so does the day being submitted."
  [sys day-starts-at]
  (let [claim (claim-day! sys day-starts-at)]
    (if (keyword? claim)
      (do (core/emit! sys {:type :scan/refused :day day-starts-at :reason claim})
          {:started false :reason claim})
      (let [scan-id (:scan-id claim)]
        ;; The day has to exist before anything can be hung off it: a scan is
        ;; one of the things that brings a day into being.
        (worklog/ensure-day! sys day-starts-at)
        (doseq [[order {:keys [source service]}] (map-indexed vector core/sources)]
          (db/transact! (core/volatile* sys)
                        [{:scan-task/id (str scan-id "|" (name source))
                          :scan-task/scan [:scan/id scan-id]
                          :scan-task/source source
                          :scan-task/service service
                          :scan-task/status :queued
                          :scan-task/order order
                          :scan-task/found-count 0}]))
        (core/emit! sys {:type :scan/started :day day-starts-at :scan scan-id})
        {:started true :scan scan-id}))))

;; -------------------------------------------------------------- attribution

(defn- put-keys! [sys activity-id {:keys [attributed pending]}]
  (db/put! (core/durable sys) :activity/ident activity-id
           (cond-> {}
             (some? attributed) (assoc :activity/attributed-keys (vec attributed))
             (some? pending) (assoc :activity/pending-keys (vec pending)))))

(declare key-found!)

(defn- key-resolved! [sys activity-id issue-key]
  (let [a (core/activity sys activity-id)]
    (put-keys! sys activity-id
               {:attributed (conj (:attributed-keys a) issue-key)
                :pending (disj (:pending-keys a) issue-key)})
    (worklog/task-found-on-day! sys (:day-starts-at a) issue-key)
    (core/emit! sys {:type :scan/line-attributed :activity activity-id :key issue-key})))

(defn- key-never-resolved! [sys activity-id issue-key failure]
  (let [a (core/activity sys activity-id)]
    (put-keys! sys activity-id {:pending (disj (:pending-keys a) issue-key)})
    (core/emit! sys {:type :scan/line-left-orphan :activity activity-id
                     :key issue-key :failure failure})))

(defn key-found!
  "KeyFoundInActivity. The key is what the activity carries; what the task *is*
  comes from Jira. Until Jira answers, the activity is on the day, waiting, and
  is not yet an orphan."
  [sys activity-id issue-key]
  (let [a (core/activity sys activity-id)]
    (cond
      (nil? a) nil
      (contains? (:attributed-keys a) issue-key) nil

      (items/known sys issue-key) (key-resolved! sys activity-id issue-key)

      :else
      (do
        (put-keys! sys activity-id {:pending (conj (:pending-keys a) issue-key)})
        (let [answer (items/resolve! sys issue-key)]
          (if (:known answer)
            (key-resolved! sys activity-id issue-key)
            (key-never-resolved! sys activity-id issue-key (:failure answer))))))))

(defn attach!
  "OperatorAttachesActivity."
  [sys activity-id issue-key]
  (let [a (core/activity sys activity-id)]
    (cond
      (nil? a) {:ok false :fault :no-such-line}
      (not (worklog/within-window? sys (:day-starts-at a)))
      {:ok false :fault :day-outside-window}
      :else
      (do (key-found! sys activity-id issue-key)
          (let [after (core/activity sys activity-id)]
            (if (contains? (:attributed-keys after) issue-key)
              {:ok true :activity after}
              {:ok false :fault :unknown-task}))))))

;; ------------------------------------------------------------ recording a line

(defn- record-activity!
  "ActivityRecorded. An activity that did not happen on the day being scanned
  is not that day's work. An activity the day already carries is not recorded
  again: scanning a day twice leaves it with one line per thing done, and
  leaves an orphan the operator has since attached attached. Answers the ident
  of a line recorded now."
  [sys day-starts-at observation]
  (let [ident (core/activity-ident day-starts-at (:source observation)
                                   (:reference observation))
        known (db/lookup (db/db (core/durable sys)) :activity/ident ident)]
    (when (and (t/falls-within-day? (:happened-at observation) day-starts-at)
               (not known))
      (db/transact! (core/durable sys)
                    [{:activity/ident ident
                      :activity/day [:work-day/starts-at day-starts-at]
                      :activity/source (:source observation)
                      :activity/happened-at (:happened-at observation)
                      :activity/reference (:reference observation)
                      :activity/title (:title observation)
                      :activity/named-keys (vec (:issue-keys observation))}])
      (doseq [issue-key (:issue-keys observation)]
        (key-found! sys ident issue-key))
      ident)))

;; ------------------------------------------------------------- one source's turn

(defn- task-row [sys task-id]
  (db/entity-map (db/db (core/volatile* sys)) :scan-task/id task-id))

(defn- next-task [sys scan-id]
  (let [d (db/db (core/volatile* sys))
        scan-eid (db/lookup d :scan/id scan-id)]
    (->> (db/all d :scan-task/id)
         (filter #(and (= :queued (:scan-task/status %))
                       (= scan-eid (get-in % [:scan-task/scan :db/id]))))
         (sort-by :scan-task/order)
         first)))

(defn- fetch-page [sys task day-starts-at cursor]
  (let [request {:source (:scan-task/source task)
                 :day-starts-at day-starts-at
                 :cursor cursor
                 :login (access/operator-login sys)}]
    (try
      (if (= :github (:scan-task/service task))
        (github/fetch-activity (:github sys) request)
        (jira/fetch-activity (:jira sys) (assoc request :login
                                                (:connection/account-login
                                                 (access/connection sys :jira)))))
      (catch Exception _ {:failure :service-unreachable}))))

(defn- scan-source!
  "One source, paginating to exhaustion. As with collection, quota exhaustion
  is a wait: everything found so far stays on the day, and the source in flight
  keeps its cursor."
  [sys scan-id day-starts-at task-id]
  (db/put! (core/volatile* sys) :scan-task/id task-id {:scan-task/status :scanning})
  (loop [cursor (:scan-task/page-cursor (task-row sys task-id))]
    (let [task (task-row sys task-id)
          github? (= :github (:scan-task/service task))]
      (when (and github? (not (access/budget-available? sys)))
        (core/emit! sys {:type :scan/waiting-on-quota :scan scan-id
                         :until (:budget/resets-at (access/budget sys))})
        (access/await-budget! sys))
      (let [answer (fetch-page sys task day-starts-at cursor)]
        (when (and github? (:budget answer))
          (access/budget-observed! sys (or (get-in answer [:budget :remaining]) 1)
                                   (get-in answer [:budget :resets-at])))
        (cond
          (:retry answer) (do (access/await-budget! sys) (recur cursor))

          (:failure answer)
          (do (db/put! (core/volatile* sys) :scan-task/id task-id
                       {:scan-task/status :failed
                        :scan-task/page-cursor nil
                        :scan-task/failure (:failure answer)})
              (core/emit! sys {:type :scan/source-failed :scan scan-id
                               :source (:scan-task/source task)
                               :failure (:failure answer)}))

          :else
          (let [recorded (doall (keep #(record-activity! sys day-starts-at %)
                                      (:activities answer)))]
            (db/put! (core/volatile* sys) :scan-task/id task-id
                     {:scan-task/page-cursor (:next-cursor answer)
                      :scan-task/found-count (+ (long (or (:scan-task/found-count task) 0))
                                                (count recorded))})
            (if-let [next-cursor (:next-cursor answer)]
              (recur next-cursor)
              (db/put! (core/volatile* sys) :scan-task/id task-id
                       {:scan-task/status :succeeded :scan-task/page-cursor nil}))))))))

;; ------------------------------------------------------ the time Jira holds

(defn- read-task-time
  "One task's reading. Jira answers with a total — zero under none when the
  operator logged nothing — or a failure."
  [sys day-starts-at issue-key]
  (try
    (jira/fetch-task-time (:jira sys)
                          {:issue issue-key
                           :day-starts-at day-starts-at
                           :login (:connection/account-login
                                   (access/connection sys :jira))})
    (catch Exception _ {:failure :service-unreachable})))

(defn- read-jira-time!
  "ScanReadsJiraTime / TaskTimeRead / TaskTimeNotRead. What the operator did is
  known; now, on every task the day carries, the time Jira holds for them on
  it. The reading follows the tasks: a worklog on a ticket the day does not
  carry is never asked for, and puts nothing on the day.

  A reading that failed hands nothing over, so that task keeps what the last
  scan read rather than appearing to have lost its time. Answers how many
  could not be read."
  [sys scan-id day-starts-at]
  (let [unread (reduce
                (fn [unread issue-key]
                  (let [answer (read-task-time sys day-starts-at issue-key)]
                    (if-let [failure (:failure answer)]
                      (do (core/emit! sys {:type :scan/task-time-unread
                                           :scan scan-id :key issue-key
                                           :failure failure})
                          (inc unread))
                      (do (worklog/jira-time-on-task! sys day-starts-at issue-key answer)
                          unread))))
                0
                (map :work-item-key (:entries (worklog/day sys day-starts-at))))]
    (db/put! (core/volatile* sys) :scan/id scan-id {:scan/unread unread})
    unread))

;; --------------------------------------------------------------- the whole scan

(defn- scan-to-the-end! [sys scan-id day-starts-at]
  (loop []
    (if-let [task (next-task sys scan-id)]
      (do (scan-source! sys scan-id day-starts-at (:scan-task/id task))
          (recur))
      (let [unread (read-jira-time! sys scan-id day-starts-at)
            scan (core/last-scan sys day-starts-at)
            orphans (count (core/orphans-on sys day-starts-at))]
        (db/put! (core/volatile* sys) :scan/id scan-id
                 {:scan/status :completed :scan/finished-at (core/now sys)})
        ;; The day records when it was last scanned, whether or not the scan
        ;; found anything: a day scanned and empty is a different thing from
        ;; a day never scanned.
        (worklog/mark-scanned! sys day-starts-at (core/now sys))
        (core/emit! sys {:type :scan/completed
                         :scan scan-id
                         :day day-starts-at
                         :failures (:failed scan)
                         :unread unread
                         :unattributed orphans})
        {:scan scan-id :day day-starts-at :failures (:failed scan)
         :unread unread :unattributed orphans}))))

(defn run-scan!
  "Runs a scan to its end, then hands the day back to the worklog. A scan
  that blew up on the way ends too, rather than holding the day for good."
  [sys scan-id]
  (let [day-starts-at (:scan/day-starts-at
                       (db/entity-map (db/db (core/volatile* sys)) :scan/id scan-id))]
    (try
      (scan-to-the-end! sys scan-id day-starts-at)
      (finally
        (when (= :running (:scan/status (db/entity-map (db/db (core/volatile* sys))
                                                       :scan/id scan-id)))
          (db/put! (core/volatile* sys) :scan/id scan-id
                   {:scan/status :completed :scan/finished-at (core/now sys)}))
        (worklog/release-day! sys day-starts-at :scan)))))

(defn scan! [sys day-starts-at]
  (let [outcome (request-scan! sys day-starts-at)]
    (if (:started outcome)
      (merge outcome (run-scan! sys (:scan outcome)))
      outcome)))

(defn start! [sys day-starts-at]
  (let [outcome (request-scan! sys day-starts-at)]
    (when (:started outcome)
      (if-let [executor (:executor sys)]
        (.submit executor ^Runnable (fn [] (run-scan! sys (:scan outcome))))
        (run-scan! sys (:scan outcome))))
    outcome))
