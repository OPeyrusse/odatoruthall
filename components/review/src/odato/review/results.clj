(ns odato.review.results
  "Reading the results branches.

  One request lists every branch under the review prefix with the commit it
  points at, and one more those under the tuning prefix. Only a branch whose commit moved is read again: its tree says
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

(def process-file "owllie/process.json")
(def status-file "owllie/status.json")

(defn- step-files
  "The files a process says its steps write."
  [process]
  (when (map? process)
    (set (for [phase (:phases process)
               step (:steps phase)
               :let [file (:file step)]
               :when (string? file)]
           file))))

(defn- volatile [sys] (db/volatile* (:store sys)))
(defn- now [sys] (t/now-ms (:clock sys)))

(defn parse-json
  "A file's JSON, or `::invalid` when it is not JSON."
  [content]
  (try (json/read-str (str content) :key-fn keyword)
       (catch Exception _ ::invalid)))

(defn- parse-file
  "What a file holds: JSON parsed, markdown as text, anything else as text."
  [path content]
  (if (str/ends-with? path ".json") (parse-json content) (str content)))

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

(defn tuning-runs
  "Every tuning read from a `tune/` branch, keyed by run id."
  [sys]
  (into {} (map (juxt :tuning-run/id identity))
        (db/all (db/db (volatile sys)) :tuning-run/id)))

(defn read-branch
  "What changed on one branch since it was last read, as the run's new files,
  or `{:failure ...}`. The process is read first: it says which other files
  matter."
  [sys repository {:keys [sha]} previous-files]
  (let [tree (github/tree (:github sys) (assoc repository :sha sha))
        fetch (fn [path blob-sha]
                (if (= blob-sha (get-in previous-files [path :sha]))
                  (get previous-files path)
                  (let [blob (github/blob (:github sys) (assoc repository :sha blob-sha))]
                    (settings/observe-budget! sys blob)
                    (if (or (:failure blob) (:retry blob))
                      {:failure (or (:failure blob) :budget-exhausted)}
                      {:sha blob-sha :data (parse-file path (:content blob))}))))]
    (settings/observe-budget! sys tree)
    (if (or (:failure tree) (:retry tree))
      {:failure (or (:failure tree) :budget-exhausted)}
      (let [shas (into {} (map (juxt :path :sha)) (:entries tree))
            process (when-let [blob-sha (get shas process-file)] (fetch process-file blob-sha))]
        (if (:failure process)
          process
          (reduce
           (fn [files path]
             (if-let [blob-sha (get shas path)]
               (let [file (fetch path blob-sha)]
                 (if (:failure file) (reduced file) (assoc files path file)))
               files))
           (cond-> {} process (assoc process-file process))
           (cons status-file (step-files (:data process)))))))))

(defn- pull-request-of
  "The pull request a run reviews, with its names as GitHub writes them: from
  its process when it can be read, from the branch otherwise."
  [branch files]
  (or (some-> (get-in files [process-file :data])
              (#(when (map? %) (get-in % [:pull_request :url])))
              owllie/parse-pull-request-url)
      {:repository {:owner (:owner branch) :name (:name branch)} :number (:number branch)}))

(def ^:private kinds
  "The two kinds of branch the results repository holds, and where what each
  says is kept: a review's run, and a tuning's (specs/owldesk/tuning.allium,
  read in the same reading)."
  {:review {:prefix owllie/branch-prefix
            :parse owllie/parse-branch-name
            :known runs
            :attr (fn [k] (keyword "review-run" (name k)))
            :extra (fn [branch files]
                     (let [pr (pull-request-of branch files)]
                       {:review-run/owner (get-in pr [:repository :owner])
                        :review-run/name (get-in pr [:repository :name])
                        :review-run/number (:number pr)}))}
   :tuning {:prefix owllie/tune-branch-prefix
            :parse owllie/parse-tune-branch-name
            :known tuning-runs
            :attr (fn [k] (keyword "tuning-run" (name k)))
            :extra (constantly {})}})

(defn- read-kind!
  "Reads the branches of one kind: `{:changed bool}`, or `{:failure ...}` when
  they cannot be listed."
  [sys repository {:keys [prefix parse known attr extra]}]
  (let [listed (github/branches (:github sys) (assoc repository :prefix prefix))]
    (settings/observe-budget! sys listed)
    (if (or (:failure listed) (:retry listed))
      {:failure (or (:failure listed) :budget-exhausted)}
      (let [known (known sys)
            ;; The parsed name may carry the repository's `:name`: the
            ;; branch's own name is kept apart as `:branch`.
            branches (keep (fn [b] (when-let [parsed (parse (:name b))]
                                     (assoc parsed :branch (:name b) :sha (:sha b))))
                           (:branches listed))
            listed-ids (set (map :run-id branches))
            ;; A run removed here may still be listed for a moment (a reading
            ;; that began before the deletion, GitHub's own lag): it must not
            ;; come back. Once its branch is no longer listed, the mark has
            ;; done its work.
            removed (set (map :review-removed/run-id
                              (db/all (db/db (volatile sys)) :review-removed/run-id)))
            branches (remove (comp removed :run-id) branches)
            gone (remove (comp listed-ids key) known)
            changed (atom (boolean (seq gone)))]
        (doseq [branch branches
                :let [previous (get known (:run-id branch))]
                :when (not= (:sha branch) (get previous (attr :head)))]
          (let [files (read-branch sys repository branch (get previous (attr :files)))]
            (if (:failure files)
              (log/warn "results branch" (:branch branch) "could not be read:" (:failure files))
              (do (reset! changed true)
                  (db/put! (volatile sys) (attr :id) (:run-id branch)
                           (merge {(attr :branch) (:branch branch)
                                   (attr :head) (:sha branch)
                                   (attr :files) files
                                   (attr :read-at) (now sys)}
                                  (extra branch files)))))))
        (when (seq gone)
          (db/transact! (volatile sys)
                        (for [[_ run] gone] (db/retract-entity (:db/id run)))))
        {:changed @changed :listed listed-ids}))))

(defn read!
  "ResultsBranchRead / ResultsBranchGone for every branch of the results
  repository, reviews and tunings alike. Answers what happened: `:read`, or
  why nothing was read."
  [sys]
  (let [repository (:results-repository (settings/current sys))]
    (cond
      (nil? repository) :no-results-repository
      (not (access/usable? sys :github)) :github-unavailable
      (not (access/budget-available? sys)) :budget-exhausted
      (not (try-lock! sys)) :already-reading
      :else
      (try
        (events/publish! (:bus sys) {:type :review/reading})
        (let [reviews (read-kind! sys repository (:review kinds))
              tunings (when-not (:failure reviews) (read-kind! sys repository (:tuning kinds)))
              failure (or (:failure reviews) (:failure tunings))]
          (if failure
            (do (db/put! (volatile sys) :results-polling/scope :installation
                         {:results-polling/failure failure})
                (events/publish! (:bus sys) {:type :review/results-unreadable :failure failure})
                :unreadable)
            (let [listed (into (:listed reviews) (:listed tunings))
                  removed (map :review-removed/run-id
                               (db/all (db/db (volatile sys)) :review-removed/run-id))]
              (when-let [done (seq (remove listed removed))]
                (db/transact! (volatile sys)
                              (for [id done
                                    :let [eid (db/lookup (db/db (volatile sys)) :review-removed/run-id id)]
                                    :when eid]
                                (db/retract-entity eid))))
              (db/put! (volatile sys) :results-polling/scope :installation
                       {:results-polling/read-at (now sys)
                        :results-polling/failure nil})
              (when (or (:changed reviews) (:changed tunings))
                (events/publish! (:bus sys) {:type :review/results-read}))
              :read)))
        (finally
          (unlock! sys)
          (events/publish! (:bus sys) {:type :review/reading-ended}))))))

(defn forget-run!
  "A run removed here: off the screen now, and kept off until its branch is no
  longer listed. Reviews and tunings alike."
  [sys run-id]
  (db/put! (volatile sys) :review-removed/run-id run-id {})
  (doseq [attr [:review-run/id :tuning-run/id]
          :let [eid (db/lookup (db/db (volatile sys)) attr run-id)]
          :when eid]
    (db/transact! (volatile sys) [(db/retract-entity eid)])))
