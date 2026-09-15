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
    (core/scanning? sys) :already-scanning
    (not (worklog/within-window? sys day-starts-at)) :day-outside-window
    :else nil))

(defn request-scan!
  "ScanStarts / ScanNotStarted. Both services are scanned, so both credentials
  are needed: a scan with half the picture would look like a day with half the
  work on it."
  [sys day-starts-at]
  (if-let [reason (refusal sys day-starts-at)]
    (do (core/emit! sys {:type :scan/refused :day day-starts-at :reason reason})
        {:started false :reason reason})
    (let [scan-id (str (random-uuid))]
      (db/transact! (core/volatile* sys)
                    [{:scan/id scan-id
                      :scan/day-starts-at day-starts-at
                      :scan/status :running
                      :scan/started-at (core/now sys)}])
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
      {:started true :scan scan-id})))

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
  leaves an orphan the operator has since attached attached."
  [sys day-starts-at observation]
  (let [ident (core/activity-ident day-starts-at (:source observation)
                                   (:reference observation))]
    (when (and (t/falls-within-day? (:happened-at observation) day-starts-at)
               (nil? (db/lookup (db/db (core/durable sys)) :activity/ident ident)))
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

;; --------------------------------------------------------------- the whole scan

(defn run-scan! [sys scan-id]
  (let [day-starts-at (:scan/day-starts-at
                       (db/entity-map (db/db (core/volatile* sys)) :scan/id scan-id))]
    (loop []
      (if-let [task (next-task sys scan-id)]
        (do (scan-source! sys scan-id day-starts-at (:scan-task/id task))
            (recur))
        (let [scan (core/last-scan sys day-starts-at)
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
                           :unattributed orphans})
          {:scan scan-id :day day-starts-at :failures (:failed scan)
           :unattributed orphans})))))

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
