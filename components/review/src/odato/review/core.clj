(ns odato.review.core
  "Reviews: launching one, what each one's state is, what the operator thinks
  of its findings, and removing the old ones."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [odato.access.interface :as access]
            [odato.config.interface :as config]
            [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.github.interface :as github]
            [odato.owllie.interface :as owllie]
            [odato.review.results :as results]
            [odato.review.settings :as settings]
            [odato.time.interface :as t]))

(def timing
  "The spec's constants: when a launch that left no branch never started, when
  a silent run is stalled, and how often the branches are read."
  {:start-timeout-ms (t/minutes 5)
   :stall-after-ms (t/minutes 20)
   :active-read-interval-ms (t/minutes 1)
   :idle-read-interval-ms (t/minutes 10)
   :page-read-interval-ms 20000
   :fires-per-hour 30
   :pull-request-state-ttl-ms (t/hours 1)})

(def agents
  "The review agents, in the order Owllie runs them."
  ["intent-reader" "general-rules" "edge-case-tests" "impact" "naming"
   "intent-comparator" "concurrency" "performance"])

(def severities ["blocker" "major" "minor" "info"])

(defn- durable [sys] (db/durable (:store sys)))
(defn- volatile [sys] (db/volatile* (:store sys)))
(defn- now [sys] (t/now-ms (:clock sys)))
(defn- emit! [sys event] (events/publish! (:bus sys) event))

(defn- submit!
  "Runs `f` on the installation's executor, or here when there is none (a
  test)."
  [sys f]
  (if-let [executor ^java.util.concurrent.ExecutorService (:executor sys)]
    (.submit executor ^Runnable (fn []
                                  (try (f) (catch Throwable e
                                             (log/warn e "a review task failed")))))
    (f)))

;; ------------------------------------------------------------------ reading

(defn- data [files path]
  (let [d (get-in files [path :data])]
    (when (map? d) d)))

(defn- run-view [run]
  (when run
    (let [files (:review-run/files run)]
      {:branch (:review-run/branch run)
       :head (:review-run/head run)
       :read-at (:review-run/read-at run)
       :meta (data files "owllie/meta.json")
       :progress (data files "owllie/progress.json")
       :progress-unreadable? (= ::results/invalid (get-in files ["owllie/progress.json" :data]))
       :summary (data files "owllie/summary.json")
       :agents (into {} (for [a agents
                              :let [d (data files (str "owllie/agents/" a ".json"))]
                              :when d]
                          [a d]))})))

(defn- launch-view [row]
  (when row
    {:run-id (:review-launch/run-id row)
     :branch (:review-launch/branch row)
     :title (:review-launch/title row)
     :results-repository {:owner (:review-launch/results-owner row)
                          :name (:review-launch/results-name row)}
     :brain-ref (:review-launch/brain-ref row)
     :status (:review-launch/status row)
     :fired-at (:review-launch/fired-at row)
     :session-url (:review-launch/session-url row)
     :refusal (:review-launch/refusal row)}))

(def ^:private under-way #{"preparing" "phase1" "phase2" "synthesis"})

(defn state
  "What a review is, now: `:starting`, `:running`, `:stalled`, `:done`,
  `:failed`, `:never-started`, or `:unreadable` when its branch holds no
  progress that can be read."
  [{:keys [launch run asked-at]} now-ms]
  (let [{:keys [start-timeout-ms stall-after-ms]} timing]
    (if run
      (let [progress (:progress run)
            status (:status progress)
            heartbeat (or (t/parse-instant (:updated_at progress)) asked-at)]
        (cond
          (= "done" status) :done
          (= "failed" status) :failed
          (contains? under-way status)
          (if (and heartbeat (<= (+ heartbeat stall-after-ms) now-ms)) :stalled :running)
          :else :unreadable))
      (cond
        (= :refused (:status launch)) :never-started
        (> (+ (:fired-at launch) start-timeout-ms) now-ms) :starting
        :else :never-started))))

(defn active? [review] (contains? #{:starting :running} (:state review)))

(defn- launches [sys]
  (into {} (map (juxt :review-launch/run-id identity))
        (db/all (db/db (durable sys)) :review-launch/run-id)))

(defn- review-of [now-ms run-id launch run]
  (let [launch-pr (when launch
                    {:repository {:owner (:review-launch/owner launch) :name (:review-launch/name launch)}
                     :number (:review-launch/number launch)})
        run-pr (when run
                 {:repository {:owner (:review-run/owner run) :name (:review-run/name run)}
                  :number (:review-run/number run)})
        review {:run-id run-id
                :launch (launch-view launch)
                :run (run-view run)
                :asked-at (owllie/run-id-instant run-id)
                :pull-request (or launch-pr run-pr)}
        meta-pr (get-in review [:run :meta :pr])]
    (assoc review
           :state (state review now-ms)
           :title (or (:title meta-pr) (get-in review [:launch :title])))))

(defn reviews
  "Every review the installation knows of — launched here, or found on a
  branch — the most recently asked for first."
  [sys]
  (let [launched (launches sys)
        read (results/runs sys)
        now-ms (now sys)]
    (->> (into (set (keys launched)) (keys read))
         (map #(review-of now-ms % (get launched %) (get read %)))
         (sort-by :run-id #(compare %2 %1))
         vec)))

(defn review [sys run-id]
  (let [launch (get (launches sys) run-id)
        run (get (results/runs sys) run-id)]
    (when (or launch run)
      (review-of (now sys) run-id launch run))))

(defn pull-request-key [{:keys [repository number]}]
  (str (str/lower-case (str (:owner repository))) "/"
       (str/lower-case (str (:name repository))) "#" number))

(defn by-pull-request
  "Reviews grouped by pull request: `[{:pull-request :reviews [...]}]`, by
  repository then number, each group's reviews most recent first."
  [sys]
  (->> (reviews sys)
       (group-by (comp pull-request-key :pull-request))
       (map (fn [[_ rs]] {:pull-request (:pull-request (first rs)) :reviews rs}))
       (sort-by (fn [{:keys [pull-request]}]
                  [(str/lower-case (str (get-in pull-request [:repository :owner])))
                   (str/lower-case (str (get-in pull-request [:repository :name])))
                   (- (long (or (:number pull-request) 0)))]))
       vec))

;; ---------------------------------------------------------------- launchers

(defn launcher [sys kind] (get-in sys [:launchers kind]))

(defn launcher-available? [sys kind] (some? (launcher sys kind)))

(defn fires-in-last-hour [sys]
  (let [since (- (now sys) (t/hours 1))]
    (count (filter #(> (long (:review-launch/fired-at % 0)) since)
                   (vals (launches sys))))))

;; ---------------------------------------------------------------- launching

(defn- fire! [sys run-id]
  (let [row (get (launches sys) run-id)
        request {:pull-request {:repository {:owner (:review-launch/owner row)
                                             :name (:review-launch/name row)}
                                :number (:review-launch/number row)}
                 :results-repository {:owner (:review-launch/results-owner row)
                                      :name (:review-launch/results-name row)}
                 :branch (:review-launch/branch row)
                 :brain-ref (:review-launch/brain-ref row)}
        answer (owllie/fire! (launcher sys (:review-launch/launcher row))
                             (owllie/request-block request))]
    (if (:failure answer)
      (db/put! (durable sys) :review-launch/run-id run-id
               {:review-launch/status :refused
                :review-launch/refusal (:failure answer)})
      (db/put! (durable sys) :review-launch/run-id run-id
               {:review-launch/status :fired
                :review-launch/session-url (:session-url answer)
                :review-launch/session-id (:session-id answer)}))
    ;; The branch appears about a minute later: read it at the active pace.
    (db/put! (volatile sys) :results-polling/scope :installation
             {:results-polling/next-read-at (+ (now sys) (:active-read-interval-ms timing))})
    (emit! sys {:type :review/fired :run-id run-id :failure (:failure answer)})))

(defn launch!
  "OperatorRequestsReview. Answers `{:ok true :run-id ...}` once the request is
  handed to the launcher, or `{:ok false :refusal ...}` — and a refused review
  sends nothing and records nothing."
  [sys {:keys [url brain-ref]}]
  (let [pull-request (owllie/parse-pull-request-url url)
        in-force (settings/current sys)
        brain-ref (if (str/blank? (str brain-ref)) (:brain-ref in-force) (str/trim brain-ref))
        status (delay (if (= :unchecked (settings/access-status sys))
                        (settings/check-results-repository! sys)
                        (settings/access-status sys)))
        in-flight (delay (some #(and (active? %)
                                     (= (pull-request-key (:pull-request %))
                                        (pull-request-key pull-request)))
                               (reviews sys)))
        details (delay (let [answer (github/pull-request
                                     (:github sys)
                                     (assoc (:repository pull-request) :number (:number pull-request)))]
                         (settings/observe-budget! sys answer)
                         answer))
        refusal (cond
                  (nil? pull-request) :invalid-pull-request-url
                  (and brain-ref (not (owllie/ref-name? brain-ref))) :invalid-brain-ref
                  (not (access/usable? sys :github)) :github-unavailable
                  (nil? (:results-repository in-force)) :no-results-repository
                  (not= :ready @status) :results-repository-not-ready
                  (not (launcher-available? sys (:launcher in-force))) :launcher-unavailable
                  @in-flight :already-reviewing
                  (>= (fires-in-last-hour sys) (:fires-per-hour timing)) :fire-limit-reached
                  (:failure @details) :pull-request-not-found
                  :else nil)]
    (if refusal
      ;; Nothing is announced: a refused review changes nothing on screen
      ;; but the refusal the operator is shown.
      {:ok false :refusal refusal}
      (let [run-id (owllie/new-run-id (now sys))
            results-repository (:results-repository in-force)]
        (db/transact! (durable sys)
                      [(cond-> {:review-launch/run-id run-id
                                :review-launch/branch (owllie/branch-name pull-request run-id)
                                :review-launch/owner (get-in pull-request [:repository :owner])
                                :review-launch/name (get-in pull-request [:repository :name])
                                :review-launch/number (:number pull-request)
                                :review-launch/results-owner (:owner results-repository)
                                :review-launch/results-name (:name results-repository)
                                :review-launch/launcher (:launcher in-force)
                                :review-launch/status :firing
                                :review-launch/fired-at (now sys)}
                         (get-in @details [:pull-request :title])
                         (assoc :review-launch/title (get-in @details [:pull-request :title]))
                         brain-ref (assoc :review-launch/brain-ref brain-ref))])
        (emit! sys {:type :review/launched :run-id run-id})
        (submit! sys #(fire! sys run-id))
        {:ok true :run-id run-id}))))

;; ------------------------------------------------------------------ polling

(defn tick!
  "ResultsReadDue: the branches are read at the active pace while a review is
  in flight, and at the idle pace otherwise."
  [sys]
  (results/ensure-defaults! sys)
  (let [due (:results-polling/next-read-at (results/polling sys))]
    (when (<= (long (or due 0)) (now sys))
      (let [interval (if (some active? (reviews sys))
                       (:active-read-interval-ms timing)
                       (:idle-read-interval-ms timing))]
        (db/put! (volatile sys) :results-polling/scope :installation
                 {:results-polling/next-read-at (+ (now sys) interval)})
        (submit! sys #(results/read! sys))))))

(defn read-soon!
  "Opening a review page asks for a reading, unless one happened just now."
  [sys]
  (results/ensure-defaults! sys)
  (let [read-at (:results-polling/read-at (results/polling sys))]
    (when (or (nil? read-at) (<= (+ read-at (:page-read-interval-ms timing)) (now sys)))
      (submit! sys #(results/read! sys)))))

;; ----------------------------------------------------------------- findings

(defn findings
  "Every finding a review shows, by fingerprint: those of the summary first,
  then those the agents kept or merged."
  [review]
  (let [summary (get-in review [:run :summary :findings])
        from-agents (for [[agent file] (get-in review [:run :agents])
                          f (get-in file [:output :findings])
                          :when (and (map? f) (#{"kept" "merged"} (get-in f [:validation :status])))]
                      (assoc f :agents [agent] :rules [(:rule f)]))]
    (reduce (fn [m f] (if (contains? m (:fingerprint f)) m (assoc m (:fingerprint f) f)))
            {}
            (filter (comp string? :fingerprint) (concat summary from-agents)))))

(defn- feedback-id [run-id fingerprint] (str run-id "/" fingerprint))

(defn judgements
  "What the operator thought of a review's findings: fingerprint → verdict."
  [sys run-id]
  (into {} (for [f (db/all (db/db (durable sys)) :feedback/id)
                 :when (= run-id (:feedback/run-id f))]
             [(:feedback/fingerprint f) (:feedback/verdict f)])))

(defn feedback [sys] (db/all (db/db (durable sys)) :feedback/id))

(defn judge!
  "OperatorJudgesFinding: `verdict` is `:relevant` or `:false-positive`."
  [sys run-id fingerprint verdict]
  (let [r (review sys run-id)
        finding (when r (get (findings r) fingerprint))]
    (if (and finding (#{:relevant :false-positive} verdict))
      (do (db/put! (durable sys) :feedback/id (feedback-id run-id fingerprint)
                   {:feedback/run-id run-id
                    :feedback/fingerprint fingerprint
                    :feedback/pull-request (pull-request-key (:pull-request r))
                    :feedback/verdict verdict
                    :feedback/rule (str/join ", " (or (seq (:rules finding)) [(:rule finding)]))
                    :feedback/severity (:severity finding)
                    :feedback/file (:file finding)
                    :feedback/line (:line finding)
                    :feedback/quote (:quote finding)
                    :feedback/observation (:observation finding)
                    :feedback/agents (vec (:agents finding))
                    :feedback/judged-at (now sys)})
          (emit! sys {:type :review/judged :run-id run-id})
          {:ok true})
      {:ok false :fault :no-such-finding})))

(defn withdraw-judgement!
  "OperatorWithdrawsJudgement."
  [sys run-id fingerprint]
  (when-let [eid (db/lookup (db/db (durable sys)) :feedback/id (feedback-id run-id fingerprint))]
    (db/transact! (durable sys) [(db/retract-entity eid)])
    (emit! sys {:type :review/judged :run-id run-id}))
  {:ok true})

;; ------------------------------------------------------------------ cleanup

(defn writer-available? [sys] (some? (:results-writer sys)))

(defn- pull-request-state [sys pull-request]
  (:review-pr/state (db/entity-map (db/db (volatile sys)) :review-pr/ident
                                   (pull-request-key pull-request))))

(defn refresh-pull-request-states!
  "Reads, for every pull request reviewed, whether it is still open — what
  the closed retention needs. A state read within the hour is kept."
  [sys]
  (doseq [{:keys [pull-request]} (by-pull-request sys)
          :let [k (pull-request-key pull-request)
                row (db/entity-map (db/db (volatile sys)) :review-pr/ident k)]
          :when (and (get-in pull-request [:repository :name])
                     (or (nil? row) (<= (+ (:review-pr/read-at row) (:pull-request-state-ttl-ms timing))
                                        (now sys))))]
    (let [answer (github/pull-request (:github sys) (assoc (:repository pull-request)
                                                           :number (:number pull-request)))]
      (settings/observe-budget! sys answer)
      (when-let [pr (:pull-request answer)]
        (db/put! (volatile sys) :review-pr/ident k
                 {:review-pr/state {:state (:state pr) :closed-at (:closed-at pr)}
                  :review-pr/read-at (now sys)})))))

(defn cleanup-candidates
  "The reviews a cleanup would remove, each with why: `:beyond-kept`,
  `:pull-request-closed`, `:failed-long-ago`. Never a review in flight."
  [sys]
  (let [{:keys [keep-runs closed-retention-ms failed-retention-ms]} (settings/current sys)
        now-ms (now sys)]
    (vec
     (for [{:keys [pull-request reviews]} (by-pull-request sys)
           :let [pr-state (pull-request-state sys pull-request)
                 closed? (and pr-state (#{:closed :merged} (:state pr-state))
                              (:closed-at pr-state)
                              (<= (+ (:closed-at pr-state) closed-retention-ms) now-ms))]
           [index r] (map-indexed vector reviews)
           :when (not (active? r))
           :let [reasons (cond-> #{}
                           (>= index keep-runs) (conj :beyond-kept)
                           closed? (conj :pull-request-closed)
                           (and (#{:failed :stalled :never-started :unreadable} (:state r))
                                (:asked-at r)
                                (<= (+ (:asked-at r) failed-retention-ms) now-ms))
                           (conj :failed-long-ago))]
           :when (seq reasons)]
       {:review r :reasons reasons}))))

(defn remove!
  "OperatorRemovesReview: deletes its branch, if it has one, and forgets its
  launch. What the operator judged of its findings stays."
  [sys run-id]
  (let [r (review sys run-id)
        repository (:results-repository (settings/current sys))]
    (cond
      (nil? r) {:ok false :fault :no-such-review}
      (active? r) {:ok false :fault :review-in-flight}
      (and (:run r) (not (writer-available? sys))) {:ok false :fault :no-results-writer}
      :else
      (let [deleted (if (:run r)
                      (github/delete-branch (:results-writer sys)
                                            (assoc repository :branch (get-in r [:run :branch])))
                      {:deleted true})]
        (if (:failure deleted)
          {:ok false :fault :deletion-failed :failure (:failure deleted)}
          (do (results/forget-run! sys run-id)
              (when-let [eid (db/lookup (db/db (durable sys)) :review-launch/run-id run-id)]
                (db/transact! (durable sys) [(db/retract-entity eid)]))
              (emit! sys {:type :review/removed :run-id run-id})
              {:ok true}))))))

(defn clean-up!
  "OperatorConfirmsCleanup: removes every candidate. Answers how many went and
  which could not."
  [sys]
  (let [outcomes (for [{:keys [review]} (cleanup-candidates sys)]
                   (assoc (remove! sys (:run-id review)) :run-id (:run-id review)))
        outcomes (doall outcomes)]
    {:removed (count (filter :ok outcomes))
     :failed (vec (remove :ok outcomes))}))

(defn settings-repository-key [sys]
  (config/repository-key (:results-repository (settings/current sys))))
