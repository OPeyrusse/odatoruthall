(ns odato.review.tuning
  "Tuning (specs/owldesk/tuning.allium): the operator's remarks on a review,
  drafted and changed review by review and sent to Owllie in one batch; the
  tuning run read back from its `tune/` branch; its pull request on Owllie's
  rules; and the reviewers' comments offered as candidate misses.

  Remarks and batches outlive the process; what the branches, the pull
  requests and the comments say is read again after a restart."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [odato.access.interface :as access]
            [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.github.interface :as github]
            [odato.owllie.interface :as owllie]
            [odato.review.core :as core]
            [odato.review.diff :as diff]
            [odato.review.results :as results]
            [odato.review.settings :as settings]
            [odato.time.interface :as t]))

(def timing
  "The spec's constants. A tuning reads and writes more than a review: it is
  given longer before it reads as stalled."
  {:start-timeout-ms (t/minutes 5)
   :stall-after-ms (t/minutes 30)
   :fires-per-hour 30
   :pull-request-read-interval-ms (t/minutes 1)
   :comments-read-interval-ms (t/minutes 1)})

(def limits
  {:max-remarks-per-batch 20
   :max-note-length 2000
   :excerpt-context-lines 5
   :candidate-distance-lines 3
   :max-quote-length 200})

(def kinds [:false-positive :relevant :wrong-severity :unclear :missed :general])
(def finding-kinds #{:false-positive :relevant :wrong-severity :unclear})
(def ^:private note-required #{:unclear :missed :general})
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
                                             (log/warn e "a tuning task failed")))))
    (f)))

(defn parse-kind
  "`false-positive`, `false_positive` or the keyword itself; nil for anything
  else."
  [s]
  (let [s (str/replace (if (keyword? s) (name s) (str s)) "_" "-")]
    (some #(when (= s (name %)) %) kinds)))

(defn parse-severity [s]
  (let [s (str/lower-case (str (if (keyword? s) (name s) s)))]
    (some #{s} severities)))

(defn- truncate [s n]
  (let [s (str s)] (if (> (count s) n) (subs s 0 n) s)))

;; --------------------------------------------------------------- the diff

(def ^:private parsed
  "The last diffs parsed: a review's diff is read on every render of its page."
  (atom {}))

(defn- parse-cached [text]
  (or (get @parsed text)
      (let [files (diff/parse text)]
        (swap! parsed #(assoc (if (> (count %) 16) {} %) text files))
        files)))

(defn diff-text
  "The diff a review read, as the `diff` block of one of its steps says it;
  nil before that step is read, or for a run written before Owllie wrote it."
  [review]
  (first (for [step (get-in review [:run :steps])
               block (get-in step [:result :blocks])
               :when (and (map? block) (= "diff" (:type block)) (string? (:text block)))]
           (:text block))))

(defn review-diff
  "The diff a review read, parsed (see odato.review.diff), or nil."
  [review]
  (some-> (diff-text review) parse-cached))

(defn head-sha [review] (get-in review [:run :head-sha]))

(defn finding-lines
  "The lines a finding points at, on the new side."
  [finding]
  (let [{:keys [file line]} (when (map? (:location finding)) (:location finding))]
    (when (and (string? file) (int? line))
      {:file file :side :new :line line :end-line line})))

(defn finding-excerpt
  "The lines of the review's diff around a finding, or nil when they are not
  in it."
  [review finding]
  (when-let [files (review-diff review)]
    (when-let [lines (finding-lines finding)]
      (diff/excerpt files lines (:excerpt-context-lines limits)))))

(declare normalize-lines)

(defn lines-excerpt
  "The lines of the review's diff around `{:file :side :line :end-line}`,
  those marked, or nil when they are not in it."
  [review lines]
  (when-let [files (review-diff review)]
    (when-let [lines (normalize-lines lines)]
      (when (diff/lines-in? files lines)
        (diff/excerpt files lines (:excerpt-context-lines limits))))))

(defn- finding-agents [finding]
  (vec (or (some-> (get-in finding [:fields :agents]) str (str/split #",\s*"))
           (when (:step finding) [(str (:step finding))]))))

(defn- finding-copy [finding]
  {:fingerprint (:fingerprint finding)
   :rule (some-> (first (:tags finding)) str)
   :agents (finding-agents finding)
   :severity (some-> (:severity finding) str)
   :lines (finding-lines finding)
   :quote (some-> (:quote finding) str)
   :observation (some-> (:body finding) str)})

(defn- normalize-lines [{:keys [file side line end-line]}]
  (let [line (if (string? line) (parse-long (str/trim line)) line)
        end-line (if (string? end-line) (parse-long (str/trim end-line)) end-line)]
    (when (and (not (str/blank? (str file))) (int? line))
      {:file (str file)
       :side (if (#{:old "old" "LEFT"} side) :old :new)
       :line line
       :end-line (if (int? end-line) end-line line)})))

;; ---------------------------------------------------------------- remarks

(defn- remark-view [row]
  {:id (:remark/id row)
   :review-run-id (:remark/review-run-id row)
   :pull-request {:repository {:owner (:remark/owner row) :name (:remark/name row)}
                  :number (:remark/number row)}
   :kind (:remark/kind row)
   :finding (:remark/finding row)
   :lines (:remark/lines row)
   :excerpt (:remark/excerpt row)
   :quote (:remark/quote row)
   :rule (:remark/rule row)
   :note (:remark/note row)
   :proposed-severity (:remark/proposed-severity row)
   :from-comment (:remark/from-comment row)
   :status (:remark/status row)
   :batch (:remark/batch row)
   :created-at (:remark/created-at row)
   :updated-at (:remark/updated-at row)})

(defn- remark-rows [sys]
  (db/all (db/db (durable sys)) :remark/id))

(defn remarks
  "A review's remarks, in the order they were written."
  [sys run-id]
  (->> (remark-rows sys)
       (filter #(= run-id (:remark/review-run-id %)))
       (sort-by :remark/seq)
       (mapv remark-view)))

(defn remark [sys id]
  (some-> (db/entity-map (db/db (durable sys)) :remark/id id) remark-view))

(defn- note-fault [kind note]
  (cond
    (and (note-required kind) (str/blank? note)) :note-required
    (> (count note) (:max-note-length limits)) :note-too-long
    :else nil))

(defn- severity-fault [kind proposed finding-severity]
  (when (and (= :wrong-severity kind)
             (or (nil? proposed) (= proposed finding-severity)))
    :severity-not-changed))

(defn- new-remark!
  "Records a draft. Remarks are numbered in the order they are written: that
  is the order they are listed and sent in."
  [sys row]
  (let [id (str (java.util.UUID/randomUUID))
        seq* (inc (reduce max 0 (keep :remark/seq (remark-rows sys))))]
    (db/transact! (durable sys)
                  [(into {:remark/id id
                          :remark/seq seq*
                          :remark/status :draft
                          :remark/created-at (now sys)
                          :remark/updated-at (now sys)}
                         (remove (comp nil? val))
                         row)])
    id))

(defn draft!
  "OperatorDraftsRemark. `{:kind :fingerprint :lines {:file :side :line
  :end-line} :note :proposed-severity :rule}`: answers `{:ok true :remark-id
  ...}` or `{:ok false :fault ...}`, and a refused remark records nothing."
  [sys run-id {:keys [kind fingerprint lines note proposed-severity rule]}]
  (let [r (core/review sys run-id)
        kind (parse-kind kind)
        note (str/trimr (str note))
        files (when r (review-diff r))
        finding (when (and r (not (str/blank? (str fingerprint))))
                  (get (core/findings r) (str fingerprint)))
        on-finding (contains? finding-kinds kind)
        proposed (parse-severity proposed-severity)
        lines (normalize-lines lines)
        fault (cond
                (nil? kind) :invalid-kind
                (or (nil? r) (nil? (:run r))) :no-results
                (and on-finding (nil? finding)) :finding-not-found
                (and (= :missed kind) (not (and lines files (diff/lines-in? files lines))))
                :lines-not-in-diff
                :else (or (note-fault kind note)
                          (severity-fault kind proposed (some-> (:severity finding) str))))]
    (if fault
      {:ok false :fault fault}
      (let [copy (when on-finding (finding-copy finding))
            lines (cond on-finding (:lines copy) (= :general kind) nil :else lines)
            quote (cond on-finding (:quote copy)
                        lines (some-> (diff/line-at files (:file lines) (:side lines) (:line lines)) :text))
            id (new-remark! sys {:remark/review-run-id run-id
                                 :remark/owner (get-in r [:pull-request :repository :owner])
                                 :remark/name (get-in r [:pull-request :repository :name])
                                 :remark/number (get-in r [:pull-request :number])
                                 :remark/kind kind
                                 :remark/finding copy
                                 :remark/lines lines
                                 :remark/excerpt (when (and files lines)
                                                   (diff/excerpt files lines (:excerpt-context-lines limits)))
                                 :remark/quote (some-> quote (truncate (:max-quote-length limits)))
                                 :remark/rule (if on-finding
                                                (:rule copy)
                                                (when-not (or (= :general kind) (str/blank? (str rule)))
                                                  (str/trim (str rule))))
                                 :remark/note note
                                 :remark/proposed-severity (when (#{:wrong-severity :missed} kind) proposed)})]
        (when (#{:false-positive :relevant} kind)
          (core/judge! sys run-id (:fingerprint copy) kind))
        (emit! sys {:type :review/remarked :run-id run-id})
        {:ok true :remark-id id}))))

(defn edit!
  "OperatorEditsRemark: a draft's note and proposed severity."
  [sys id {:keys [note proposed-severity]}]
  (let [r (remark sys id)
        note (str/trimr (str note))
        proposed (parse-severity proposed-severity)
        fault (cond
                (nil? r) :no-such-remark
                (not= :draft (:status r)) :not-a-draft
                :else (or (note-fault (:kind r) note)
                          (severity-fault (:kind r) proposed (get-in r [:finding :severity]))))]
    (if fault
      {:ok false :fault fault}
      (do (db/put! (durable sys) :remark/id id
                   {:remark/note note
                    :remark/proposed-severity (when (#{:wrong-severity :missed} (:kind r)) proposed)
                    :remark/updated-at (now sys)})
          (emit! sys {:type :review/remarked :run-id (:review-run-id r)})
          {:ok true}))))

(defn delete!
  "OperatorDeletesRemark: only a draft. A verdict it recorded stays."
  [sys id]
  (let [r (remark sys id)]
    (cond
      (nil? r) {:ok false :fault :no-such-remark}
      (not= :draft (:status r)) {:ok false :fault :not-a-draft}
      :else (do (db/transact! (durable sys)
                              [(db/retract-entity (db/lookup (db/db (durable sys)) :remark/id id))])
                (emit! sys {:type :review/remarked :run-id (:review-run-id r)})
                {:ok true}))))

;; ---------------------------------------------------------------- tunings

(defn- launch-rows [sys]
  (into {} (map (juxt :tuning-launch/run-id identity))
        (db/all (db/db (durable sys)) :tuning-launch/run-id)))

(defn- launch-view [row]
  (when row
    {:run-id (:tuning-launch/run-id row)
     :branch (:tuning-launch/branch row)
     :review-run-id (:tuning-launch/review-run-id row)
     :pull-request {:repository {:owner (:tuning-launch/owner row) :name (:tuning-launch/name row)}
                    :number (:tuning-launch/number row)}
     :head-sha (:tuning-launch/head-sha row)
     :results-repository {:owner (:tuning-launch/results-owner row)
                          :name (:tuning-launch/results-name row)}
     :brain-ref (:tuning-launch/brain-ref row)
     :status (:tuning-launch/status row)
     :fired-at (:tuning-launch/fired-at row)
     :session-url (:tuning-launch/session-url row)
     :refusal (:tuning-launch/refusal row)}))

(defn- state
  "Where a tuning stands, as a review's state is read (core/state), with the
  tuning's own timeouts."
  [{:keys [launch run asked-at]} now-ms]
  (let [{:keys [start-timeout-ms stall-after-ms]} timing]
    (if run
      (let [heartbeat (or (:updated-at run) asked-at)]
        (case (:state run)
          :done :done
          :failed :failed
          :running (if (and heartbeat (<= (+ heartbeat stall-after-ms) now-ms)) :stalled :running)
          :unreadable))
      (cond
        (nil? launch) :unreadable
        (= :refused (:status launch)) :never-started
        (> (+ (:fired-at launch) start-timeout-ms) now-ms) :starting
        :else :never-started))))

(defn- pull-request-of [sys run]
  (when-let [url (some (fn [l] (when (= "pull_request" (str (:kind l))) (str (:url l))))
                       (:links run))]
    (let [row (db/entity-map (db/db (volatile sys)) :tuning-pr/url url)]
      {:url url
       :state (:tuning-pr/state row)
       :draft (:tuning-pr/draft row)
       :mergeable-state (:tuning-pr/mergeable-state row)
       :read-at (:tuning-pr/read-at row)})))

(defn- tuning-of [sys now-ms run-id launch run]
  (let [view (when run
               (core/branch-view {:branch (:tuning-run/branch run)
                                  :head (:tuning-run/head run)
                                  :read-at (:tuning-run/read-at run)
                                  :files (:tuning-run/files run)}))
        pr (when view (pull-request-of sys view))
        tuning {:run-id run-id
                :review-run-id (:tuning-launch/review-run-id launch)
                :launch (launch-view launch)
                :run view
                :asked-at (owllie/run-id-instant run-id)
                :pull-request pr
                :title (let [title (get-in view [:process :title])] (when (string? title) title))}
        state (state tuning now-ms)]
    (assoc tuning
           :state state
           :active? (contains? #{:starting :running} state)
           :given-up? (boolean (or (#{:never-started :failed :stalled} state)
                                   (= :closed (:state pr))
                                   (and (= :done state) (nil? pr)))))))

(defn tunings
  "Every tuning the installation knows of, the most recently asked for first."
  [sys]
  (let [launched (launch-rows sys)
        read (results/tuning-runs sys)
        now-ms (now sys)]
    (->> (into (set (keys launched)) (keys read))
         (map #(tuning-of sys now-ms % (get launched %) (get read %)))
         (sort-by :run-id #(compare %2 %1))
         vec)))

(defn tuning [sys run-id]
  (let [launch (get (launch-rows sys) run-id)
        run (get (results/tuning-runs sys) run-id)]
    (when (or launch run)
      (tuning-of sys (now sys) run-id launch run))))

(defn tunings-of
  "The batches sent from one review, the most recent first."
  [sys review-run-id]
  (filterv #(= review-run-id (:review-run-id %)) (tunings sys)))

(defn active? [sys] (boolean (some :active? (tunings sys))))

(defn redraft!
  "OperatorRedraftsRemark: a sent remark of a batch given up, copied into a
  new draft of the same review."
  [sys id]
  (let [r (remark sys id)
        batch (when (:batch r) (tuning sys (:batch r)))]
    (cond
      (nil? r) {:ok false :fault :no-such-remark}
      (not= :sent (:status r)) {:ok false :fault :not-sent}
      (not (or (nil? batch) (:given-up? batch))) {:ok false :fault :batch-not-given-up}
      :else
      (let [row (db/entity-map (db/db (durable sys)) :remark/id id)
            copy (new-remark! sys (-> row
                                      (dissoc :db/id :remark/id :remark/seq :remark/status :remark/batch
                                              :remark/created-at :remark/updated-at)))]
        (emit! sys {:type :review/remarked :run-id (:review-run-id r)})
        {:ok true :remark-id copy}))))

;; ----------------------------------------------------------------- sending

(defn launcher [sys kind] (get-in sys [:tune-launchers kind]))

(defn launcher-available? [sys kind] (some? (launcher sys kind)))

(defn fires-in-last-hour [sys]
  (let [since (- (now sys) (t/hours 1))]
    (count (filter #(> (long (:tuning-launch/fired-at % 0)) since) (vals (launch-rows sys))))))

(defn- owllie-remark
  "A remark in the field names of Owllie's `shared/tune-layout.md`."
  [i row]
  (let [kind (:remark/kind row)
        finding (:remark/finding row)
        lines (:remark/lines row)]
    (cond-> {:id (str "r" (inc i))
             :kind (str/replace (name kind) "-" "_")
             :agents (if (finding-kinds kind) (vec (:agents finding)) [])
             :rule (:remark/rule row)
             :note (str (:remark/note row))}
      (finding-kinds kind) (assoc :fingerprint (:fingerprint finding)
                                  :severity (:severity finding)
                                  :observation (:observation finding))
      (:remark/proposed-severity row) (assoc :proposed_severity (:remark/proposed-severity row))
      lines (assoc :file (:file lines)
                   :side (name (:side lines))
                   :line (:line lines)
                   :end_line (:end-line lines)
                   :quote (str (:remark/quote row))))))

(defn request-text
  "The `OWLLIE_TUNE v1` block a batch is sent as."
  [sys run-id]
  (let [row (get (launch-rows sys) run-id)
        sent (->> (remark-rows sys)
                  (filter #(= run-id (:remark/batch %)))
                  (sort-by :remark/seq))]
    (owllie/tune-request-block
     {:pull-request {:repository {:owner (:tuning-launch/owner row) :name (:tuning-launch/name row)}
                     :number (:tuning-launch/number row)}
      :head-sha (:tuning-launch/head-sha row)
      :review-run-id (:tuning-launch/review-run-id row)
      :results-repository {:owner (:tuning-launch/results-owner row)
                           :name (:tuning-launch/results-name row)}
      :branch (:tuning-launch/branch row)
      :brain-ref (:tuning-launch/brain-ref row)
      :remarks (vec (map-indexed owllie-remark sent))})))

(defn- fire! [sys run-id]
  (let [row (get (launch-rows sys) run-id)
        answer (owllie/fire! (launcher sys (:tuning-launch/launcher row)) (request-text sys run-id))]
    (if (:failure answer)
      (db/put! (durable sys) :tuning-launch/run-id run-id
               {:tuning-launch/status :refused
                :tuning-launch/refusal (:failure answer)})
      (db/put! (durable sys) :tuning-launch/run-id run-id
               {:tuning-launch/status :fired
                :tuning-launch/session-url (:session-url answer)
                :tuning-launch/session-id (:session-id answer)}))
    (db/put! (volatile sys) :results-polling/scope :installation
             {:results-polling/next-read-at (+ (now sys) (:active-read-interval-ms core/timing))})
    (emit! sys {:type :review/tuning-fired :run-id run-id :failure (:failure answer)})))

(defn send!
  "OperatorSendsRemarks: every draft of a review, in one batch. Answers `{:ok
  true :run-id ...}` once the request is handed to the launcher, or `{:ok
  false :refusal ...}`, and a refused batch leaves its remarks drafts."
  [sys review-run-id]
  (let [r (core/review sys review-run-id)
        drafts (filterv #(= :draft (:status %)) (remarks sys review-run-id))
        in-force (settings/current sys)
        status (delay (if (= :unchecked (settings/access-status sys))
                        (settings/check-results-repository! sys)
                        (settings/access-status sys)))
        refusal (cond
                  (empty? drafts) :no-draft-remarks
                  (> (count drafts) (:max-remarks-per-batch limits)) :too-many-remarks
                  (or (nil? r) (not (re-matches #"[0-9a-f]{40}" (str (head-sha r))))) :head-unknown
                  (not (access/usable? sys :github)) :github-unavailable
                  (nil? (:results-repository in-force)) :no-results-repository
                  (not= :ready @status) :results-repository-not-ready
                  (not (launcher-available? sys (:launcher in-force))) :launcher-unavailable
                  (some :active? (tunings-of sys review-run-id)) :tuning-in-flight
                  (>= (fires-in-last-hour sys) (:fires-per-hour timing)) :fire-limit-reached
                  :else nil)]
    (if refusal
      {:ok false :refusal refusal}
      (let [run-id (owllie/new-run-id (now sys))
            repository (:results-repository in-force)
            pr (:pull-request r)]
        (db/transact! (durable sys)
                      (into [(cond-> {:tuning-launch/run-id run-id
                                      :tuning-launch/branch (owllie/tune-branch-name run-id)
                                      :tuning-launch/review-run-id review-run-id
                                      :tuning-launch/owner (get-in pr [:repository :owner])
                                      :tuning-launch/name (get-in pr [:repository :name])
                                      :tuning-launch/number (:number pr)
                                      :tuning-launch/head-sha (head-sha r)
                                      :tuning-launch/results-owner (:owner repository)
                                      :tuning-launch/results-name (:name repository)
                                      :tuning-launch/launcher (:launcher in-force)
                                      :tuning-launch/status :firing
                                      :tuning-launch/fired-at (now sys)}
                               (:brain-ref in-force) (assoc :tuning-launch/brain-ref (:brain-ref in-force)))]
                            (for [d drafts]
                              {:remark/id (:id d)
                               :remark/status :sent
                               :remark/batch run-id
                               :remark/updated-at (now sys)})))
        (emit! sys {:type :review/tuning-sent :run-id run-id :review-run-id review-run-id})
        (submit! sys #(fire! sys run-id))
        {:ok true :run-id run-id}))))

;; ------------------------------------------------------- the pull requests

(defn refresh-pull-requests!
  "TuningPullRequestRead: the pull request of every tuning, while it is open,
  at most once a minute."
  [sys]
  (when (access/usable? sys :github)
    (doseq [{:keys [pull-request]} (tunings sys)
            :let [{:keys [url state read-at]} pull-request]
            :when (and url
                       (not (#{:merged :closed} state))
                       (or (nil? read-at)
                           (<= (+ read-at (:pull-request-read-interval-ms timing)) (now sys))))]
      (when-let [pr (owllie/parse-pull-request-url url)]
        (let [answer (github/pull-request (:github sys) (assoc (:repository pr) :number (:number pr)))]
          (settings/observe-budget! sys answer)
          (when-let [p (:pull-request answer)]
            (db/put! (volatile sys) :tuning-pr/url url
                     {:tuning-pr/state (:state p)
                      :tuning-pr/draft (boolean (:draft p))
                      :tuning-pr/mergeable-state (some-> (:mergeable-state p) str)
                      :tuning-pr/read-at (now sys)})
            (when (not= state (:state p))
              (emit! sys {:type :review/tuning-pull-request :url url}))))))))

;; ------------------------------------------------------------------ cleanup

(defn remove!
  "OperatorRemovesTuning: deletes its branch, if it has one, and forgets its
  launch. Its remarks stay, sent; its pull request is not touched."
  [sys run-id]
  (let [tn (tuning sys run-id)
        repository (:results-repository (settings/current sys))]
    (cond
      (nil? tn) {:ok false :fault :no-such-tuning}
      (:active? tn) {:ok false :fault :tuning-in-flight}
      (and (:run tn) (not (core/writer-available? sys))) {:ok false :fault :no-results-writer}
      :else
      (let [deleted (if (:run tn)
                      (github/delete-branch (:results-writer sys)
                                            (assoc repository :branch (get-in tn [:run :branch])))
                      {:deleted true})]
        (if (:failure deleted)
          {:ok false :fault :deletion-failed :failure (:failure deleted)}
          (do (results/forget-run! sys run-id)
              (when-let [eid (db/lookup (db/db (durable sys)) :tuning-launch/run-id run-id)]
                (db/transact! (durable sys) [(db/retract-entity eid)]))
              (emit! sys {:type :review/tuning-removed :run-id run-id})
              {:ok true}))))))

;; ------------------------------------------------- reviewers' comments

(defn- comments-row [sys pull-request]
  (db/entity-map (db/db (volatile sys)) :review-comments/ident (core/pull-request-key pull-request)))

(defn read-comments!
  "Reads the review comments of a review's pull request, with its author."
  [sys review]
  (let [pr (:pull-request review)
        request (assoc (:repository pr) :number (:number pr))
        details (github/pull-request (:github sys) request)
        _ (settings/observe-budget! sys details)
        answer (github/review-comments (:github sys) request)]
    (settings/observe-budget! sys answer)
    (db/put! (volatile sys) :review-comments/ident (core/pull-request-key pr)
             (if (:comments answer)
               {:review-comments/items (vec (:comments answer))
                :review-comments/author (get-in details [:pull-request :author])
                :review-comments/read-at (now sys)
                :review-comments/failure nil}
               {:review-comments/read-at (now sys)
                :review-comments/failure (or (:failure answer) :budget-exhausted)}))
    (when (:comments answer)
      (emit! sys {:type :review/comments-read :run-id (:run-id review)}))))

(defn read-comments-soon!
  "Opening the page of a done review reads its pull request's comments,
  unless they were read a moment ago."
  [sys review]
  (when (and (= :done (:state review)) (access/usable? sys :github)
             (get-in review [:pull-request :repository :name]))
    (let [read-at (:review-comments/read-at (comments-row sys (:pull-request review)))]
      (when (or (nil? read-at)
                (<= (+ read-at (:comments-read-interval-ms timing)) (now sys)))
        (submit! sys #(read-comments! sys review))))))

(defn- comment-lines
  "Where a comment falls in the review's diff, or nil. As it is when it was
  written on the commit the review read; otherwise only when its line, with
  the same text, is in that diff."
  [files head comment]
  (let [side (if (= "LEFT" (:side comment)) :old :new)
        on-head (fn [start end]
                  (when (int? end)
                    {:file (:path comment) :side side
                     :line (if (int? start) start end) :end-line end}))
        lines (cond
                (= head (:original-commit-id comment))
                (on-head (:original-start-line comment) (:original-line comment))
                (= head (:commit-id comment))
                (on-head (:start-line comment) (:line comment))
                :else
                (let [last-line (last (str/split-lines (str (:diff-hunk comment))))
                      text (when (seq last-line) (subs last-line 1))
                      n (when text (diff/locate-text files (:path comment) side text))]
                  (when n {:file (:path comment) :side side :line n :end-line n})))]
    (when (and lines (diff/lines-in? files lines)) lines)))

(defn- near-a-finding? [review lines]
  (let [distance (:candidate-distance-lines limits)]
    (and (= :new (:side lines))
         (some (fn [finding]
                 (when-let [fl (finding-lines finding)]
                   (and (= (:file fl) (:file lines))
                        (<= (- (:line lines) distance) (:line fl) (+ (:end-line lines) distance)))))
               (vals (core/findings review))))))

(defn- dismissed-id [run-id comment-id] (str run-id "/" comment-id))

(defn candidates
  "MissesAreSuggestedFromComments: the reviewers' comments on lines of the
  review's diff with no finding near them, neither turned into a remark nor
  dismissed; the operator's first. Each is the comment with `:lines` and
  `:by-operator`."
  [sys review]
  (let [files (review-diff review)
        head (head-sha review)
        row (comments-row sys (:pull-request review))
        author (:review-comments/author row)
        operator (access/operator-login sys)
        drafted (set (keep :from-comment (remarks sys (:run-id review))))
        dismissed (set (for [d (db/all (db/db (durable sys)) :dismissed-comment/id)
                             :when (= (:run-id review) (:dismissed-comment/run-id d))]
                         (:dismissed-comment/comment-id d)))]
    (when (and files head (= :done (:state review)))
      (->> (:review-comments/items row)
           (remove :in-reply-to)
           (remove #(and author (= author (:author %))))
           (remove #(or (drafted (:id %)) (dismissed (:id %))))
           (keep (fn [c] (when-let [lines (comment-lines files head c)]
                           (when-not (near-a-finding? review lines)
                             (assoc c :lines lines :by-operator (= operator (:author c)))))))
           (sort-by (juxt (complement :by-operator) (comp :file :lines) (comp :line :lines)))
           vec))))

(defn draft-from-comment!
  "OperatorDraftsRemarkFromComment: a missed draft from a candidate comment,
  its text as the note."
  [sys run-id comment-id]
  (let [r (core/review sys run-id)
        comment (when r (first (filter #(= comment-id (:id %)) (candidates sys r))))]
    (if-not comment
      {:ok false :fault :no-such-comment}
      (let [files (review-diff r)
            lines (:lines comment)
            id (new-remark! sys {:remark/review-run-id run-id
                                 :remark/owner (get-in r [:pull-request :repository :owner])
                                 :remark/name (get-in r [:pull-request :repository :name])
                                 :remark/number (get-in r [:pull-request :number])
                                 :remark/kind :missed
                                 :remark/lines lines
                                 :remark/excerpt (diff/excerpt files lines (:excerpt-context-lines limits))
                                 :remark/quote (some-> (diff/line-at files (:file lines) (:side lines) (:line lines))
                                                       :text (truncate (:max-quote-length limits)))
                                 :remark/note (truncate (str/trim (str (:body comment)))
                                                        (:max-note-length limits))
                                 :remark/from-comment comment-id})]
        (emit! sys {:type :review/remarked :run-id run-id})
        {:ok true :remark-id id}))))

(defn dismiss-comment!
  "OperatorDismissesComment: that comment is not a miss of the review."
  [sys run-id comment-id]
  (db/put! (durable sys) :dismissed-comment/id (dismissed-id run-id comment-id)
           {:dismissed-comment/run-id run-id
            :dismissed-comment/comment-id (str comment-id)
            :dismissed-comment/dismissed-at (now sys)})
  (emit! sys {:type :review/remarked :run-id run-id})
  {:ok true})
