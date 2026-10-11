(ns odato.discovery.core
  (:require [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.github.interface :as github]
            [odato.jira.interface :as jira]
            [odato.time.interface :as t]))

(def sources
  "One source at a time, GitHub first. The two that cost a call per issue — the
  worklogs and the comments — come last of their service; the worklogs first of
  those two, because they are the only source that says how long."
  [{:source :github-commits :service :github}
   {:source :github-comments :service :github}
   {:source :github-pull-requests :service :github}
   {:source :github-reviews :service :github}
   {:source :jira-issues-created :service :jira}
   {:source :jira-issues-closed :service :jira}
   {:source :jira-worklogs :service :jira}
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
     ;; Only a worklog carries time: it is what Jira already holds under it.
     :logged-ms (:activity/logged-ms row)
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

(defn time-per-task
  "What a worklog source read, totalled per task — which is what the day has
  one row for. Jira happily holds two worklogs on one issue on one day.

  It totals what *this* scan read, not what the day stores: a worklog withdrawn
  in Jira is one the scan does not see, and must stop counting."
  [observations]
  (->> observations
       (filter :logged-ms)
       (mapcat (fn [line]
                 (for [key (:issue-keys line)]
                   (assoc line :key key))))
       (group-by :key)
       (map (fn [[key grouped]]
              {:key key
               :logged-ms (reduce + 0 (keep :logged-ms grouped))
               :worklogs (count grouped)
               ;; The one worklog a submission would update, when there is one.
               :worklog (when (= 1 (count grouped))
                          (str (:reference (first grouped))))}))
       (sort-by :key)
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
     :found (reduce + 0 (map #(or (:scan-task/found-count %) 0) tasks))}))

(defn scans [sys]
  (let [d (db/db (volatile* sys))]
    (mapv #(->scan d %) (db/all d :scan/id))))

(defn active-scans [sys]
  (filterv #(= :running (:status %)) (scans sys)))

(defn scanning? [sys] (pos? (count (active-scans sys))))

(defn last-scan [sys day-starts-at]
  (->> (scans sys)
       (filter #(= (long day-starts-at) (long (or (:day-starts-at %) -1))))
       (sort-by #(or (:started-at %) 0))
       last))
