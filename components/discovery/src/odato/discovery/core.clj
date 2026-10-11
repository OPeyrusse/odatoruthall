(ns odato.discovery.core
  (:require [odato.access.interface :as access]
            [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.github.interface :as github]
            [odato.jira.interface :as jira]
            [odato.time.interface :as t]))

(def sources
  "One source at a time, GitHub first. The comments cost a call per issue, so
  they come last.

  Logging time is not an activity, so the worklogs are not a source: the time
  Jira holds is read per task once the sources are drained."
  [{:source :github-commits :service :github}
   {:source :github-comments :service :github}
   {:source :github-pull-requests :service :github}
   {:source :github-reviews :service :github}
   {:source :jira-issues-created :service :jira}
   {:source :jira-issues-closed :service :jira}
   {:source :jira-comments :service :jira}])

(defn durable [sys] (db/durable (:store sys)))
(defn volatile* [sys] (db/volatile* (:store sys)))
(defn now [sys] (t/now-ms (:clock sys)))
(defn emit! [sys event] (events/publish! (:bus sys) event))

(defn activity-ident [day-starts-at source reference]
  (str day-starts-at "|" (name source) "|" reference))

;; ------------------------------------------------------------------ reading

(defn- ->activity [row]
  (let [attributed (set (:activity/attributed-keys row))
        pending (set (:activity/pending-keys row))]
    {:id (:activity/ident row)
     :day-starts-at (get-in row [:activity/day :work-day/starts-at])
     :source (:activity/source row)
     :happened-at (:activity/happened-at row)
     :reference (:activity/reference row)
     :title (:activity/title row)
     :named-keys (set (:activity/named-keys row))
     :pending-keys pending
     :attributed-keys attributed
     :attributed? (pos? (count attributed))
     ;; An orphan: nothing on the day claims it, and nothing is still being
     ;; asked about. It keeps its title and its moment.
     :orphan? (and (zero? (count attributed)) (zero? (count pending)))}))

(defn- activity-rows [sys]
  (let [d (db/db (durable sys))]
    (->> (db/all d :activity/ident)
         (map #(assoc % :activity/day
                      (db/pull d '[:work-day/starts-at]
                               (get-in % [:activity/day :db/id])))))))

(defn activity [sys activity-id]
  (some->> (activity-rows sys)
           (filter #(= activity-id (:activity/ident %)))
           first
           ->activity))

(defn activities-on [sys day-starts-at]
  (->> (activity-rows sys)
       (map ->activity)
       (filter #(= (long day-starts-at) (long (or (:day-starts-at %) -1))))
       (sort-by #(or (:happened-at %) 0))
       vec))

(defn lines-for [sys day-starts-at issue-key]
  (filterv #(contains? (:attributed-keys %) issue-key)
           (activities-on sys day-starts-at)))

(defn orphans-on [sys day-starts-at]
  (filterv :orphan? (activities-on sys day-starts-at)))

(defn- ->scan [d row]
  (let [tasks (->> (db/all d :scan-task/id)
                   (filter #(= (:db/id row) (get-in % [:scan-task/scan :db/id])))
                   (sort-by :scan-task/order))]
    {:id (:scan/id row)
     :day-starts-at (:scan/day-starts-at row)
     :status (:scan/status row)
     :started-at (:scan/started-at row)
     :finished-at (:scan/finished-at row)
     :tasks (mapv (fn [t] {:source (:scan-task/source t)
                           :service (:scan-task/service t)
                           :status (:scan-task/status t)
                           :found-count (or (:scan-task/found-count t) 0)
                           :failure (:scan-task/failure t)})
                  tasks)
     :queued (count (filter #(= :queued (:scan-task/status %)) tasks))
     :failed (count (filter #(= :failed (:scan-task/status %)) tasks))
     ;; Tasks whose time Jira could not be asked for: they keep what the last
     ;; scan read.
     :unread (or (:scan/unread row) 0)
     :found (reduce + 0 (map #(or (:scan-task/found-count %) 0) tasks))}))

(defn scans [sys]
  (let [d (db/db (volatile* sys))]
    (mapv #(->scan d %) (db/all d :scan/id))))

(defn- waiting-on-quota?
  "Running, holding a GitHub source, and the budget spent: the scan is not
  stuck, it is waiting for the budget to come back."
  [sys scan]
  (boolean (and (not (access/budget-available? sys))
                (some #(and (= :scanning (:status %)) (= :github (:service %)))
                      (:tasks scan)))))

(defn active-scans
  "Every running scan, oldest first. Several days are scanned side by side."
  [sys]
  (->> (scans sys)
       (filter #(= :running (:status %)))
       (sort-by #(or (:started-at %) 0))
       (mapv #(assoc % :waiting-on-quota? (waiting-on-quota? sys %)))))

(defn active-scan
  "The scan running on that day, if any: a day is scanned once at a time."
  [sys day-starts-at]
  (->> (active-scans sys)
       (filter #(= (long day-starts-at) (long (or (:day-starts-at %) -1))))
       first))

(defn scanning?
  ([sys] (pos? (count (active-scans sys))))
  ([sys day-starts-at] (some? (active-scan sys day-starts-at))))

(defn last-scan [sys day-starts-at]
  (->> (scans sys)
       (filter #(= (long day-starts-at) (long (or (:day-starts-at %) -1))))
       (sort-by #(or (:started-at %) 0))
       last))
