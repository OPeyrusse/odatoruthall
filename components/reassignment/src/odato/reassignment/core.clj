(ns odato.reassignment.core
  (:require [clojure.tools.logging :as log]
            [odato.access.interface :as access]
            [odato.config.interface :as config]
            [odato.db.interface :as db]
            [odato.github.interface :as github]
            [odato.time.interface :as t]))

(def pass-interval-ms (t/minutes 1))

(def reassignments-listed 20)

(defn- conn [sys] (db/volatile* (:store sys)))
(defn- now [sys] (t/now-ms (:clock sys)))

(defn- ensure-schedule! [sys]
  (when-not (db/lookup (db/db (conn sys)) :reassignment/scope :installation)
    (db/transact! (conn sys) [{:reassignment/scope :installation
                               :reassignment/next-pass-at 0
                               :reassignment/passing false}])))

(defn- schedule [sys]
  (ensure-schedule! sys)
  (db/entity-map (db/db (conn sys)) :reassignment/scope :installation))

(defn- set-next! [sys at]
  (db/put! (conn sys) :reassignment/scope :installation {:reassignment/next-pass-at at}))

(defn- try-lock! [sys]
  (let [eid (db/lookup (db/db (conn sys)) :reassignment/scope :installation)]
    (try (db/transact! (conn sys) [[:db.fn/cas eid :reassignment/passing false true]])
         true
         (catch Exception _ false))))

(defn- unlock! [sys]
  (db/put! (conn sys) :reassignment/scope :installation {:reassignment/passing false}))

(defn- submit!
  [sys f]
  (if-let [executor ^java.util.concurrent.ExecutorService (:executor sys)]
    (.submit executor ^Runnable (fn []
                                  (try (f) (catch Throwable e
                                             (log/warn e "the reassignment pass failed")))))
    (f)))

(defn- verdict
  [login {:keys [reviews assignments]}]
  (let [latest (->> reviews
                    (filter #(and (= login (:reviewer %))
                                  (not= :commented (:verdict %))
                                  (:submitted-at %)))
                    (sort-by :submitted-at)
                    last)
        assigned-at (->> assignments
                         (filter #(= login (:assignee %)))
                         (keep :assigned-at)
                         (reduce max Long/MIN_VALUE))]
    (when (and (contains? #{:approved :changes-requested} (:verdict latest))
               (> (long (:submitted-at latest)) (long assigned-at)))
      (:verdict latest))))

(defn- writes
  "What the ruling asks of the assignees, in the order it is written: the
  author first, so that a failure on either write leaves the pull request a
  candidate for the next pass."
  [login pull-request]
  (case (verdict login pull-request)
    :approved [[:remove login]]
    :changes-requested (if (:author-is-user pull-request)
                         [[:add (:author pull-request)] [:remove login]]
                         [[:remove login]])
    nil []))

(defn- observe-budget! [sys budget]
  (when budget
    (access/budget-observed! sys (or (:remaining budget) 1) (:resets-at budget))))

(defn- search
  [sys login repositories]
  (loop [cursor nil found []]
    (let [answer (github/assigned-pull-requests (:github sys) {:login login
                                                               :repositories repositories
                                                               :cursor cursor})
          found (into found (:pull-requests answer))]
      (observe-budget! sys (:budget answer))
      (cond
        (:retry answer) {:failure :budget-exhausted}
        (:failure answer) {:failure (:failure answer)}
        (:next-cursor answer) (recur (:next-cursor answer) found)
        :else {:pull-requests found}))))

(defn- label [{:keys [repository number]}]
  (str (:owner repository) "/" (:name repository) "#" number))

(defn- write! [assigner pull-request [op login]]
  (let [request (assoc (select-keys (:repository pull-request) [:owner :name])
                       :number (:number pull-request)
                       :login login)
        answer (case op
                 :add (github/add-assignee assigner request)
                 :remove (github/remove-assignee assigner request))
        failure (or (:failure answer) (when (:retry answer) :budget-exhausted))
        write (cond-> {:repository (:repository pull-request)
                       :number (:number pull-request)
                       :title (:title pull-request)
                       :url (:url pull-request)
                       :op op
                       :login login}
                failure (assoc :failure failure))]
    (if failure
      (log/warn "reassignment:" (label pull-request) (name op) login "failed:" (name failure))
      (log/info "reassignment:" (label pull-request) (name op) login))
    write))

(defn- reassign!
  [assigner login pull-request]
  (reduce (fn [done step]
            (let [write (write! assigner pull-request step)]
              (cond-> (conj done write) (:failure write) reduced)))
          []
          (writes login pull-request)))

(defn- reassignments
  [at writes]
  (for [[first-write :as on-one] (partition-by (juxt :repository :number) writes)]
    (assoc (select-keys first-write [:repository :number :title :url])
           :at at
           :writes (mapv #(select-keys % [:op :login :failure]) on-one))))

(defn- latest-of-each
  "A write failing every minute replaces its own line, rather than pushing the
  other pull requests off the list."
  [newest-first]
  (let [pull-request (juxt :repository :number)
        listed? (fn [kept r] (some #(= (pull-request r) (pull-request %)) kept))]
    (->> newest-first
         (reduce (fn [kept r] (if (listed? kept r) kept (conj kept r))) [])
         (take reassignments-listed)
         vec)))

(defn latest [sys]
  (vec (:reassignment/latest (db/entity-map (db/db (conn sys)) :reassignment/scope :installation))))

(defn- record! [sys writes]
  (when (seq writes)
    (ensure-schedule! sys)
    (db/put! (conn sys) :reassignment/scope :installation
             {:reassignment/latest
              (latest-of-each (concat (reverse (reassignments (now sys) writes)) (latest sys)))})))

(defn pass! [sys]
  (let [in-force (config/current sys)
        assigner (:assigner sys)]
    (cond
      (not (:reassignment-enabled in-force)) {:skipped :switched-off}
      (nil? assigner) {:skipped :no-assignment-credential}
      (not (access/usable? sys :github)) {:skipped :github-unavailable}
      (empty? (:repositories in-force)) {:skipped :nothing-tracked}
      (not (access/budget-available? sys))
      (do (log/info "reassignment: pass skipped, the GitHub budget is spent")
          {:skipped :budget-exhausted})

      :else
      (let [login (access/operator-login sys)
            found (search sys login (:repositories in-force))]
        (if-let [failure (:failure found)]
          (do (log/warn "reassignment: pass skipped, the search failed:" (name failure))
              {:skipped failure})
          (let [writes (into [] (mapcat #(reassign! assigner login %)) (:pull-requests found))]
            (record! sys writes)
            {:writes writes}))))))

(defn tick! [sys]
  (access/replenish-due! sys)
  (let [window (:window (config/current sys))
        at (now sys)
        {:reassignment/keys [next-pass-at passing]} (schedule sys)]
    (cond
      (> (long next-pass-at) at) :waiting
      passing :passing

      (not (t/within-collection-window? at window))
      (do (set-next! sys (t/next-window-opening at window))
          :outside-window)

      (try-lock! sys)
      (do (set-next! sys (+ at pass-interval-ms))
          (submit! sys #(try (pass! sys) (finally (unlock! sys))))
          :started)

      :else :passing)))
