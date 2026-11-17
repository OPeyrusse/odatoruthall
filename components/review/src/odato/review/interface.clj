(ns odato.review.interface
  "Automated reviews run by Owllie in a Claude cloud session, followed from
  here (specs/owldesk).

  The installation asks a launcher to start a session and hands it a request
  naming the pull request, the results repository and the branch. From then
  on it reads everything back from that branch. It keeps only the launch —
  asked for when, what the launcher answered — and what the operator thinks of
  the findings; both outlive the process. What the branches hold is read again
  after a restart.

  The system map carries, besides the store, the clock and the bus:

    :github          the GitHub client, with the operator's read credential
    :results-writer  a GitHub client that may delete branches of the results
                     repository, or nil
    :launchers       {:scheduled-task <launcher|nil> :claude-cli <launcher|nil>}
    :tune-launchers  the same, for the tuning (specs/owldesk/tuning.allium)
    :executor        where launches and readings run (inline without one)"
  (:require [odato.review.core :as core]
            [odato.review.diff :as diff]
            [odato.review.results :as results]
            [odato.review.settings :as settings]
            [odato.review.tuning :as tuning]))

(def timing core/timing)
(def schema core/schema)
(def launchers settings/launchers)

(defn ensure-defaults! [sys] (results/ensure-defaults! sys))

;; ----------------------------------------------------------------- settings

(def settings-defaults settings/defaults)
(def settings-bounds settings/bounds)

(defn settings
  "`{:results-repository {:owner :name}|nil :brain-ref :launcher :keep-runs
  :closed-retention-ms :failed-retention-ms :updated-at}`."
  [sys]
  (settings/current sys))

(defn save-settings!
  "ReviewSettingsSaved: `{:ok true :settings ...}` or `{:ok false :fault ...}`.
  The proposal holds the repository and the brain ref as typed."
  [sys proposal]
  (settings/save! sys proposal))

(defn results-access
  "`:unchecked`, `:ready`, `:unreadable` or `:unmarked`."
  [sys]
  (settings/access-status sys))

(defn results-access-checked-at [sys] (settings/access-checked-at sys))

(defn check-results-repository!
  "Reads the results repository and looks for its marker. Answers the status."
  [sys]
  (settings/check-results-repository! sys))

(def marker settings/marker)

;; ---------------------------------------------------------------- launching

(defn launcher-available?
  "Whether `:scheduled-task` or `:claude-cli` is configured."
  [sys kind]
  (core/launcher-available? sys kind))

(defn fires-in-last-hour [sys] (core/fires-in-last-hour sys))

(defn launch!
  "OperatorRequestsReview `{:url :brain-ref}`: `{:ok true :run-id ...}` or
  `{:ok false :refusal ...}`."
  [sys request]
  (core/launch! sys request))

(defn launch-again!
  "Another run of the pull request the review `run-id` is of: answers as
  `launch!` does, or `{:ok false :refusal :no-such-review}`."
  [sys run-id brain-ref]
  (core/launch-again! sys run-id brain-ref))

;; ------------------------------------------------------------------ reviews

(defn reviews
  "Every review, most recently asked for first. Each is `{:run-id
  :pull-request :title :state :asked-at :launch {...}|nil :run {...}|nil}`;
  `:state` is `:starting`, `:running`, `:stalled`, `:done`, `:failed`,
  `:never-started` or `:unreadable`. `:run` holds what the branch says:
  `:process`, `:status`, `:state`, `:updated-at`, `:error`, `:headline`,
  `:summary-step`, and `:steps`, each `{:phase :phase-title :id :title :file
  :state :result}`, `:state` among `:done :failed :skipped :running :pending
  :not-run :unreadable` and `:result` the step's file (`{:markdown ...}` for
  markdown)."
  [sys]
  (core/reviews sys))

(defn review [sys run-id] (core/review sys run-id))

(defn by-pull-request
  "`[{:pull-request ... :reviews [...]}]`, by repository then pull request."
  [sys]
  (core/by-pull-request sys))

(defn active? [review] (core/active? review))

(defn pull-request-key [pull-request] (core/pull-request-key pull-request))

(defn tick!
  "Called every so often by the scheduler: reads the results branches when
  they are due, and the tunings' pull requests while they are open."
  [sys]
  (core/tick! sys (tuning/active? sys))
  (tuning/refresh-pull-requests! sys))

(defn read-soon! [sys] (core/read-soon! sys))

(defn last-read
  "`{:at <ms|nil> :failure <kw|nil> :reading <bool>}`: when the branches were
  last read, why the last attempt failed if it did, and whether a reading is
  running now."
  [sys]
  (let [p (results/polling sys)]
    {:at (:results-polling/read-at p)
     :failure (:results-polling/failure p)
     :reading (boolean (:results-polling/reading p))}))

(defn read-results!
  "Reads the results branches now, on this thread."
  [sys]
  (results/read! sys))

;; ----------------------------------------------------------------- findings

(defn findings
  "A review's findings by fingerprint, as its steps show them, the summary
  step's first."
  [review]
  (core/findings review))

(defn judgements [sys run-id] (core/judgements sys run-id))

(defn feedback
  "Every judgement kept, with its copy of the finding."
  [sys]
  (core/feedback sys))

(defn judge!
  "Marks a finding `:relevant` or `:false-positive`."
  [sys run-id fingerprint verdict]
  (core/judge! sys run-id fingerprint verdict))

(defn withdraw-judgement! [sys run-id fingerprint]
  (core/withdraw-judgement! sys run-id fingerprint))

;; ------------------------------------------------------------------ cleanup

(defn writer-available?
  "Whether branches of the results repository can be deleted."
  [sys]
  (core/writer-available? sys))

(defn refresh-pull-request-states! [sys] (core/refresh-pull-request-states! sys))

(defn cleanup-candidates
  "`[{:review ... :reasons #{...}}]`, reasons among `:beyond-kept`,
  `:pull-request-closed` and `:failed-long-ago`."
  [sys]
  (core/cleanup-candidates sys))

(defn remove!
  "Removes one review: `{:ok true}` or `{:ok false :fault ...}`."
  [sys run-id]
  (core/remove! sys run-id))

(defn clean-up!
  "Removes every candidate: `{:removed n :failed [...]}`."
  [sys]
  (core/clean-up! sys))

;; ------------------------------------------------------------------- tuning

(def tuning-timing tuning/timing)
(def tuning-limits tuning/limits)
(def remark-kinds tuning/kinds)
(def finding-remark-kinds tuning/finding-kinds)
(def severities tuning/severities)

(defn parse-remark-kind [s] (tuning/parse-kind s))

(defn diff-text
  "The diff a review read, as Owllie wrote it, or nil."
  [review]
  (tuning/diff-text review))

(defn review-diff
  "The diff a review read, parsed: `[{:file :old-file :hunks [{:header :lines
  [{:kind :add|:del|:ctx :old :new :text}]}]}]`, or nil."
  [review]
  (tuning/review-diff review))

(defn parse-diff [text] (diff/parse text))

(defn finding-excerpt
  "The lines of the review's diff around a finding, its own marked, or nil."
  [review finding]
  (tuning/finding-excerpt review finding))

(defn lines-excerpt
  "The lines of the review's diff around `{:file :side :line :end-line}`,
  those marked, or nil when they are not in it."
  [review lines]
  (tuning/lines-excerpt review lines))

(defn remarks
  "A review's remarks, in the order they were written: `{:id :kind :finding
  :lines :excerpt :quote :rule :note :proposed-severity :from-comment :status
  :batch ...}`."
  [sys review-run-id]
  (tuning/remarks sys review-run-id))

(defn remark [sys id] (tuning/remark sys id))

(defn draft-remark!
  "OperatorDraftsRemark: `{:ok true :remark-id ...}` or `{:ok false :fault ...}`."
  [sys review-run-id proposal]
  (tuning/draft! sys review-run-id proposal))

(defn edit-remark! [sys id proposal] (tuning/edit! sys id proposal))
(defn delete-remark! [sys id] (tuning/delete! sys id))
(defn redraft-remark! [sys id] (tuning/redraft! sys id))

(defn send-remarks!
  "OperatorSendsRemarks: `{:ok true :run-id ...}` or `{:ok false :refusal ...}`."
  [sys review-run-id]
  (tuning/send! sys review-run-id))

(defn tune-launcher-available? [sys kind] (tuning/launcher-available? sys kind))

(defn tunings
  "Every tuning, the most recent first: `{:run-id :review-run-id :launch :run
  :pull-request {:url :state :draft :mergeable-state} :state :active?
  :given-up? :title}`."
  [sys]
  (tuning/tunings sys))

(defn tuning [sys run-id] (tuning/tuning sys run-id))
(defn tunings-of [sys review-run-id] (tuning/tunings-of sys review-run-id))
(defn tuning-request-text [sys run-id] (tuning/request-text sys run-id))
(defn refresh-tuning-pull-requests! [sys] (tuning/refresh-pull-requests! sys))
(defn remove-tuning! [sys run-id] (tuning/remove! sys run-id))

(defn read-comments-soon! [sys review] (tuning/read-comments-soon! sys review))
(defn read-comments! [sys review] (tuning/read-comments! sys review))

(defn miss-candidates
  "Reviewers' comments that may point at misses of the review."
  [sys review]
  (tuning/candidates sys review))

(defn draft-from-comment! [sys review-run-id comment-id]
  (tuning/draft-from-comment! sys review-run-id comment-id))

(defn dismiss-comment! [sys review-run-id comment-id]
  (tuning/dismiss-comment! sys review-run-id comment-id))
