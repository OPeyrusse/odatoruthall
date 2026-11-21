(ns odato.collector.cycle
  "One collection cycle: the queue, the pages, the quota waits and the end.

  The cycle runs on one thread and asks GitHub for one source at a time, each
  paginating to exhaustion before the next starts. That thread is a virtual
  one in the running server, so the blocking reads below cost a stack rather
  than a platform thread — which is why the code can be written as the
  specification reads, one page after another, with a wait that is simply a
  wait."
  (:require [odato.access.interface :as access]
            [odato.collector.core :as core]
            [odato.config.interface :as config]
            [odato.db.interface :as db]
            [odato.github.interface :as github]
            [odato.pull-request.interface :as prs]))

;; ------------------------------------------------------------ starting a cycle

(defn- refusal [sys kind]
  (cond
    (not (access/usable? sys :github)) :credential-unavailable
    (not (config/collectable? sys)) :nothing-tracked
    (core/collecting? sys) :already-collecting
    :else nil))

(defn- queue-tasks!
  "CycleQueuesItsSources. A repository that left the configuration leaves the
  snapshot here, with everything it had collected."
  [sys cycle-id]
  (let [in-force (config/current sys)
        tracked (:repositories in-force)
        tracked-idents (into #{} (map #(core/source-ident :repository-pull-requests %)) tracked)
        d (db/db (core/conn sys))
        stale (->> (db/all d :source/ident)
                   (filter #(and (= :repository-pull-requests (:source/kind %))
                                 (not (contains? tracked-idents (:source/ident %))))))
        stale-eids (set (map :db/id stale))
        stale-entries (->> (db/all d :src-entry/ident)
                           (filter #(contains? stale-eids
                                               (get-in % [:src-entry/source :db/id]))))]
    (when (seq stale)
      (db/transact! (core/conn sys)
                    (mapv db/retract-entity
                          (concat (map :db/id stale-entries) stale-eids))))
    ;; The sources take their turn in the order they are queued: the tracked
    ;; repositories first, then the three that are about the operator.
    (let [queued (concat (for [r (sort-by config/repository-key tracked)]
                           {:kind :repository-pull-requests :repository r})
                         [{:kind :mentions} {:kind :review-requests}
                          {:kind :recently-merged}])]
      (doseq [[order {:keys [kind repository]}] (map-indexed vector queued)]
        (let [ident (core/source-ident kind repository)]
          (db/put! (core/conn sys) :source/ident ident
                   (cond-> {:source/kind kind}
                     repository (assoc :source/repository (config/repository-key repository))
                     (nil? (db/lookup (db/db (core/conn sys)) :source/ident ident))
                     (assoc :source/last-outcome :never-collected)))
          (db/transact! (core/conn sys)
                        [{:task/id (str cycle-id "|" ident)
                          :task/cycle [:cycle/id cycle-id]
                          :task/source [:source/ident ident]
                          :task/status :queued
                          :task/order order
                          :task/collected-count 0}]))))))

(defn request-collection!
  "CollectionStarts / CollectionNotStarted."
  [sys kind]
  (if-let [reason (refusal sys kind)]
    (do (core/emit! sys {:type :collection/refused :kind kind :reason reason})
        {:started false :reason reason})
    (let [cycle-id (str (random-uuid))]
      (db/transact! (core/conn sys)
                    [{:cycle/id cycle-id
                      :cycle/kind kind
                      :cycle/status :running
                      :cycle/started-at (core/now sys)}])
      (queue-tasks! sys cycle-id)
      (core/emit! sys {:type :collection/started :kind kind :cycle cycle-id})
      {:started true :cycle cycle-id})))

;; ------------------------------------------------------------ one source's turn

(defn- task-row [sys task-id]
  (db/entity-map (db/db (core/conn sys)) :task/id task-id))

(defn- source-of [sys task-id]
  (let [d (db/db (core/conn sys))
        task (db/entity-map d :task/id task-id)]
    (db/pull d '[*] (get-in task [:task/source :db/id]))))

(defn- next-task
  "The first source still queued, in the order they were queued."
  [sys cycle-id]
  (let [d (db/db (core/conn sys))]
    (->> (db/all d :task/id)
         (filter #(and (= :queued (:task/status %))
                       (= cycle-id (:cycle/id (db/entity d (get-in % [:task/cycle :db/id]))))))
         (sort-by :task/order)
         first)))

(defn- observe-budget! [sys budget]
  (when budget
    (access/budget-observed! sys (or (:remaining budget) 1) (:resets-at budget))))

(defn- record-observation!
  "PullRequestEntersTheSnapshot / PullRequestRefreshed. A pull request outside
  the tracked repositories, or that neither the operator nor a tracked member
  authored or was assigned, is not of interest: it is not recorded."
  [sys task-id cycle-id source-ident {:keys [repository number facts]}]
  (let [in-force (config/current sys)
        operator (access/operator-login sys)]
    (when (and (core/tracked-repository? in-force repository)
               (prs/of-interest? facts {:members (:members in-force) :operator operator}))
      (let [ident (prs/ident repository number)
            d (db/db (core/conn sys))
            known (db/lookup d :pr/ident ident)]
        (db/put! (core/conn sys) :pr/ident ident
                 (cond-> {:pr/repository repository
                          :pr/number number
                          :pr/facts facts
                          :pr/last-seen-at (core/now sys)}
                   (nil? known) (assoc :pr/first-seen-at (core/now sys))))
        (db/transact! (core/conn sys)
                      [{:src-entry/ident (str source-ident "|" ident)
                        :src-entry/source [:source/ident source-ident]
                        :src-entry/pull-request [:pr/ident ident]
                        :src-entry/cycle [:cycle/id cycle-id]}])))))

(defn- sweep-orphans!
  "A pull request no source holds any more leaves the snapshot with the entries
  that held it."
  [sys]
  (let [d (db/db (core/conn sys))
        held (into #{} (map #(get-in % [:src-entry/pull-request :db/id]))
                   (db/all d :src-entry/ident))
        orphans (->> (db/all d :pr/ident)
                     (remove #(contains? held (:db/id %)))
                     (map :db/id))]
    (when (seq orphans)
      (db/transact! (core/conn sys) (mapv db/retract-entity orphans)))))

(defn- source-succeeded!
  "SourceCollected. The source's previous result is replaced, all at once, by
  what this cycle collected. Until this moment the old result was on screen."
  [sys task-id cycle-id source-ident]
  (let [d (db/db (core/conn sys))
        source-eid (db/lookup d :source/ident source-ident)
        cycle-eid (db/lookup d :cycle/id cycle-id)
        stale (->> (db/all d :src-entry/ident)
                   (filter #(and (= source-eid (get-in % [:src-entry/source :db/id]))
                                 (not= cycle-eid (get-in % [:src-entry/cycle :db/id])))))]
    (when (seq stale)
      (db/transact! (core/conn sys) (mapv (comp db/retract-entity :db/id) stale)))
    (db/put! (core/conn sys) :task/id task-id {:task/status :succeeded
                                               :task/page-cursor nil})
    (db/put! (core/conn sys) :source/ident source-ident
             {:source/last-outcome :succeeded
              :source/last-success-at (core/now sys)
              :source/last-failure-reason nil})
    (sweep-orphans! sys)
    (core/emit! sys {:type :collection/source-refreshed :source source-ident})))

(defn- source-failed!
  "SourceFailed. Nothing collected is discarded: the source keeps the entries
  of its last successful collection, and the dashboard goes on showing them
  alongside the failure and the age of the data."
  [sys task-id source-ident failure]
  (db/put! (core/conn sys) :task/id task-id {:task/status :failed
                                             :task/page-cursor nil})
  (db/put! (core/conn sys) :source/ident source-ident
           {:source/last-outcome :failed
            :source/last-failure-at (core/now sys)
            :source/last-failure-reason failure})
  (core/emit! sys {:type :collection/source-failed :source source-ident :failure failure}))

(defn- request-for [sys source]
  (let [in-force (config/current sys)]
    {:kind (:source/kind source)
     :repository (config/parse-repository (:source/repository source))
     :login (access/operator-login sys)
     :repositories (:repositories in-force)
     :merged-window-ms (get-in in-force [:thresholds :merged-window-ms])
     :now-ms (core/now sys)}))

(defn- collect-source!
  "One source, paginating to exhaustion. A quota wait keeps the cursor and
  resumes where it stopped; it is not a failure and costs nothing collected."
  [sys cycle-id task-id]
  (let [source (source-of sys task-id)
        source-ident (:source/ident source)]
    (db/put! (core/conn sys) :task/id task-id {:task/status :collecting})
    (loop [cursor (:task/page-cursor (task-row sys task-id))]
      (when-not (access/budget-available? sys)
        (core/emit! sys {:type :collection/waiting-on-quota
                         :cycle cycle-id
                         :until (:budget/resets-at (access/budget sys))})
        (access/await-budget! sys))
      (let [answer (github/fetch-pull-requests
                    (:github sys)
                    (assoc (request-for sys source) :cursor cursor))]
        (observe-budget! sys (:budget answer))
        (cond
          ;; GitHub said the budget is spent before it answered: come back.
          (:retry answer)
          (do (access/await-budget! sys) (recur cursor))

          (:failure answer)
          (source-failed! sys task-id source-ident (:failure answer))

          :else
          (do
            (doseq [observation (:observations answer)]
              (record-observation! sys task-id cycle-id source-ident observation))
            (db/put! (core/conn sys) :task/id task-id
                     {:task/page-cursor (:next-cursor answer)
                      :task/collected-count (+ (long (or (:task/collected-count
                                                          (task-row sys task-id)) 0))
                                               (count (:observations answer)))})
            (if-let [next-cursor (:next-cursor answer)]
              (recur next-cursor)
              (source-succeeded! sys task-id cycle-id source-ident))))))))

;; ------------------------------------------------------------- the whole cycle

(defn run-cycle!
  "A cycle that has started runs to completion. The collection window governs
  when a cycle may start, never when it must stop."
  [sys cycle-id]
  (loop []
    (if-let [task (next-task sys cycle-id)]
      (do (collect-source! sys cycle-id (:task/id task))
          (recur))
      (let [d (db/db (core/conn sys))
            cycle-eid (db/lookup d :cycle/id cycle-id)
            failures (->> (db/all d :task/id)
                          (filter #(and (= cycle-eid (get-in % [:task/cycle :db/id]))
                                        (= :failed (:task/status %))))
                          count)]
        (db/put! (core/conn sys) :cycle/id cycle-id
                 {:cycle/status :completed :cycle/finished-at (core/now sys)})
        (core/emit! sys {:type :collection/completed :cycle cycle-id :failures failures})
        {:cycle cycle-id :failures failures}))))

(defn collect! [sys kind]
  (let [outcome (request-collection! sys kind)]
    (if (:started outcome)
      (merge outcome (run-cycle! sys (:cycle outcome)))
      outcome)))

(defn start! [sys kind]
  (let [outcome (request-collection! sys kind)]
    (when (:started outcome)
      (if-let [executor (:executor sys)]
        (.submit executor ^Runnable (fn [] (run-cycle! sys (:cycle outcome))))
        (run-cycle! sys (:cycle outcome))))
    outcome))

(defn server-started! [sys]
  (core/server-started! sys)
  (start! sys :startup))
