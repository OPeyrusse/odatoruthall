(ns odato.collector.core
  "Reading the snapshot, and the shapes the cycle writes into it."
  (:require [odato.access.interface :as access]
            [odato.config.interface :as config]
            [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.pull-request.interface :as prs]
            [odato.time.interface :as t]))

(defn conn [sys] (db/volatile* (:store sys)))
(defn now [sys] (t/now-ms (:clock sys)))
(defn emit! [sys event] (events/publish! (:bus sys) event))

(defn source-ident [kind repository]
  (if (= kind :repository-pull-requests)
    (str "repository-pull-requests|" (config/repository-key repository))
    (name kind)))

;; ------------------------------------------------------------------ reading

(defn sources [sys]
  (->> (db/all (db/db (conn sys)) :source/ident)
       (map (fn [s]
              {:ident (:source/ident s)
               :kind (:source/kind s)
               :repository (:source/repository s)
               :last-outcome (or (:source/last-outcome s) :never-collected)
               :last-success-at (:source/last-success-at s)
               :last-failure-at (:source/last-failure-at s)
               :last-failure-reason (:source/last-failure-reason s)
               :data-age (when-let [at (:source/last-success-at s)]
                           (- (now sys) (long at)))}))
       (sort-by (juxt (comp str :kind) (comp str :repository)))
       vec))

(defn snapshot [sys]
  (let [all (sources sys)
        collected (filterv #(= :succeeded (:last-outcome %)) all)
        failed (filterv #(= :failed (:last-outcome %)) all)
        pending (filterv #(= :never-collected (:last-outcome %)) all)]
    {:sources all
     :collected-sources collected
     :failed-sources failed
     :pending-sources pending
     :has-ever-collected (pos? (count collected))
     ;; The least recent of the successes it is made of, so a single stale
     ;; source ages the whole snapshot.
     :oldest-success-at (when (seq collected)
                          (apply min (map :last-success-at collected)))}))

(defn active-cycles [sys]
  (let [d (db/db (conn sys))]
    (->> (db/all d :cycle/id)
         (filter #(= :running (:cycle/status %)))
         (map (fn [c]
                (let [tasks (->> (db/all d :task/id)
                                 (filter #(= (:db/id c) (get-in % [:task/cycle :db/id]))))
                      by-status (group-by :task/status tasks)]
                  {:id (:cycle/id c)
                   :kind (:cycle/kind c)
                   :status (:cycle/status c)
                   :started-at (:cycle/started-at c)
                   :queued (count (:queued by-status))
                   :collecting (count (:collecting by-status))
                   :failed (count (:failed by-status))
                   :succeeded (count (:succeeded by-status))
                   :total (count tasks)
                   :waiting-on-quota (not (access/budget-available? sys))})))
         vec)))

(defn collecting? [sys] (pos? (count (active-cycles sys))))

(defn pull-request-views
  "Every pull request the snapshot holds, classified. A pull request reached by
  two sources has one entry per source and is counted once here."
  [sys]
  (let [d (db/db (conn sys))
        in-force (config/current sys)
        at (now sys)
        held (into #{} (map #(get-in % [:src-entry/pull-request :db/id]))
                   (db/all d :src-entry/ident))]
    (->> (db/all d :pr/ident)
         (filter #(contains? held (:db/id %)))
         (map (fn [row]
                (prs/view {:repository (:pr/repository row)
                           :number (:pr/number row)
                           :facts (:pr/facts row)
                           :first-seen-at (:pr/first-seen-at row)
                           :last-seen-at (:pr/last-seen-at row)}
                          in-force at)))
         vec)))

;; ----------------------------------------------------------- the installation

(defn- clear-snapshot! [sys]
  (let [d (db/db (conn sys))
        eids (mapcat #(map :db/id (db/all d %))
                     [:src-entry/ident :pr/ident :source/ident :task/id :cycle/id])]
    (when (seq eids)
      (db/transact! (conn sys) (mapv db/retract-entity eids)))))

(defn server-started!
  "InstallationStarts. What GitHub gave us does not cross a restart."
  [sys]
  (clear-snapshot! sys)
  (db/put! (conn sys) :schedule/id :collection
           {:schedule/next-run-at (+ (now sys) (:refresh-cadence-ms (config/current sys)))})
  (emit! sys {:type :installation/started})
  {:requested :startup})
