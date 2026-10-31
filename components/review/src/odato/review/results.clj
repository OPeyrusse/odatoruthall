(ns odato.review.results
  "Reading the results branches.

  One request lists every branch under the review prefix with the commit it
  points at. Only a branch whose commit moved is read again: its tree says
  which files changed, and only those blobs are fetched. A quiet results
  repository therefore costs one request per reading, which is what lets a
  running review be followed every minute without eating the GitHub budget
  gitdesk needs."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [odato.access.interface :as access]
            [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.github.interface :as github]
            [odato.owllie.interface :as owllie]
            [odato.review.settings :as settings]
            [odato.time.interface :as t]))

(def results-dir "owllie/")

(def read-files
  "What is read of a branch: everything a page shows. The inputs and the
  markdown report stay on the branch."
  #{"owllie/meta.json" "owllie/progress.json" "owllie/summary.json"})

(defn- wanted? [path]
  (or (contains? read-files path)
      (and (str/starts-with? path "owllie/agents/") (str/ends-with? path ".json"))))

(defn- volatile [sys] (db/volatile* (:store sys)))
(defn- now [sys] (t/now-ms (:clock sys)))

(defn parse-json
  "A file's JSON, or `::invalid` when it is not JSON."
  [content]
  (try (json/read-str (str content) :key-fn keyword)
       (catch Exception _ ::invalid)))

;; ------------------------------------------------------------------ polling

(defn ensure-defaults! [sys]
  (when-not (db/lookup (db/db (volatile sys)) :results-polling/scope :installation)
    (db/transact! (volatile sys) [{:results-polling/scope :installation
                                   :results-polling/reading false
                                   :results-polling/next-read-at 0}]))
  sys)

(defn polling [sys]
  (db/entity-map (db/db (volatile sys)) :results-polling/scope :installation))

(defn- try-lock!
  "Takes the right to read. A second reading asked for while one runs is
  dropped: the one running will see what there is to see."
  [sys]
  (ensure-defaults! sys)
  (let [eid (db/lookup (db/db (volatile sys)) :results-polling/scope :installation)]
    (try (db/transact! (volatile sys) [[:db.fn/cas eid :results-polling/reading false true]])
         true
         (catch Exception _ false))))

(defn- unlock! [sys]
  (db/transact! (volatile sys) [{:results-polling/scope :installation
                                 :results-polling/reading false}]))

;; ------------------------------------------------------------------ reading

(defn runs
  "Every run read from a branch, keyed by run id."
  [sys]
  (into {} (map (juxt :review-run/id identity))
        (db/all (db/db (volatile sys)) :review-run/id)))

(defn- read-branch
  "What changed on one branch since it was last read, as the run's new files,
  or `{:failure ...}`."
  [sys repository {:keys [sha]} previous-files]
  (let [tree (github/tree (:github sys) (assoc repository :sha sha))]
    (settings/observe-budget! sys tree)
    (if (or (:failure tree) (:retry tree))
      {:failure (or (:failure tree) :budget-exhausted)}
      (reduce
       (fn [files {:keys [path] blob-sha :sha}]
         (if (= blob-sha (get-in previous-files [path :sha]))
           (assoc files path (get previous-files path))
           (let [blob (github/blob (:github sys) (assoc repository :sha blob-sha))]
             (settings/observe-budget! sys blob)
             (if (or (:failure blob) (:retry blob))
               (reduced {:failure (or (:failure blob) :budget-exhausted)})
               (assoc files path {:sha blob-sha :data (parse-json (:content blob))})))))
       {}
       (filter (comp wanted? :path) (:entries tree))))))

(defn- pull-request-of
  "The pull request a run reviews, with its names as GitHub writes them: from
  its meta file when it has one, from the branch otherwise."
  [branch files]
  (or (some-> (get-in files ["owllie/meta.json" :data])
              (#(when (map? %) (get-in % [:request :pr_url])))
              owllie/parse-pull-request-url)
      {:repository {:owner (:owner branch) :name (:name branch)} :number (:number branch)}))

(defn read!
  "ResultsBranchRead / ResultsBranchGone for every branch of the results
  repository. Answers what happened: `:read`, or why nothing was read."
  [sys]
  (let [repository (:results-repository (settings/current sys))]
    (cond
      (nil? repository) :no-results-repository
      (not (access/usable? sys :github)) :github-unavailable
      (not (access/budget-available? sys)) :budget-exhausted
      (not (try-lock! sys)) :already-reading
      :else
      (try
        (let [listed (github/branches (:github sys) (assoc repository :prefix owllie/branch-prefix))]
          (settings/observe-budget! sys listed)
          (if (or (:failure listed) (:retry listed))
            (do (db/put! (volatile sys) :results-polling/scope :installation
                         {:results-polling/failure (or (:failure listed) :budget-exhausted)})
                (events/publish! (:bus sys) {:type :review/results-unreadable
                                             :failure (:failure listed)})
                :unreadable)
            (let [known (runs sys)
                  ;; The parsed name carries the repository's `:name`: the
                  ;; branch's own name is kept apart as `:branch`.
                  branches (keep (fn [b] (when-let [parsed (owllie/parse-branch-name (:name b))]
                                           (assoc parsed :branch (:name b) :sha (:sha b))))
                                 (:branches listed))
                  listed-ids (set (map :run-id branches))
                  gone (remove (comp listed-ids key) known)
                  changed (atom (boolean (seq gone)))]
              (doseq [branch branches
                      :let [previous (get known (:run-id branch))]
                      :when (not= (:sha branch) (:review-run/head previous))]
                (let [files (read-branch sys repository branch (:review-run/files previous))]
                  (if (:failure files)
                    (log/warn "results branch" (:branch branch) "could not be read:" (:failure files))
                    (let [pr (pull-request-of branch files)]
                      (reset! changed true)
                      (db/put! (volatile sys) :review-run/id (:run-id branch)
                               {:review-run/branch (:branch branch)
                                :review-run/head (:sha branch)
                                :review-run/owner (get-in pr [:repository :owner])
                                :review-run/name (get-in pr [:repository :name])
                                :review-run/number (:number pr)
                                :review-run/files files
                                :review-run/read-at (now sys)})))))
              (when (seq gone)
                (db/transact! (volatile sys)
                              (for [[_ run] gone] (db/retract-entity (:db/id run)))))
              (db/put! (volatile sys) :results-polling/scope :installation
                       {:results-polling/read-at (now sys)
                        :results-polling/failure nil})
              (when @changed
                (events/publish! (:bus sys) {:type :review/results-read}))
              :read)))
        (finally (unlock! sys))))))

(defn forget-run! [sys run-id]
  (when-let [eid (db/lookup (db/db (volatile sys)) :review-run/id run-id)]
    (db/transact! (volatile sys) [(db/retract-entity eid)])))
