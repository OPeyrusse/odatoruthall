(ns odato.owllie.interface
  "Owllie, seen from here: how a review request is written, and the launchers
  that start a cloud session to run it.

  The request format and the branch names are Owllie's (its
  `shared/run-layout.md`); this component writes them the same way, because
  Owllie refuses a request whose branch does not match its pull request."
  (:require [odato.owllie.launcher :as launcher]
            [odato.owllie.request :as request]))

;; ---------------------------------------------------------------- requests

(defn parse-pull-request-url
  "A pull request link as `{:repository {:owner :name} :number n}`, or nil."
  [s]
  (request/parse-pull-request-url s))

(defn pull-request-url [pull-request] (request/pull-request-url pull-request))

(defn ref-name? [s] (request/ref-name? s))

(def branch-prefix request/branch-prefix)

(defn branch-name
  "`review/<owner>/<repo>/<number>/<run id>`, names encoded as Owllie does."
  [pull-request run-id]
  (request/branch-name pull-request run-id))

(defn parse-branch-name
  "`{:owner :name :number :run-id}` for a run's branch, or nil."
  [s]
  (request/parse-branch-name s))

(defn new-run-id
  "A run id for a review asked for at `now-ms`."
  [now-ms]
  (request/run-id now-ms (request/random-suffix)))

(defn run-id-instant [run-id] (request/run-id-instant run-id))

(defn request-block
  "The `OWLLIE_RUN v1` block: `{:pull-request :results-repository :branch
  :brain-ref}`."
  [run]
  (request/request-block run))

;; ------------------------------------------------------------------- tuning

(def tune-branch-prefix request/tune-branch-prefix)

(defn tune-branch-name
  "`tune/<run id>`: where a tuning writes in the results repository, and the
  branch of its pull request on Owllie."
  [run-id]
  (request/tune-branch-name run-id))

(defn parse-tune-branch-name
  "`{:run-id}` for a tuning's branch, or nil."
  [s]
  (request/parse-tune-branch-name s))

(defn tune-request-block
  "The `OWLLIE_TUNE v1` block (Owllie's `shared/tune-layout.md`):
  `{:pull-request :head-sha :review-run-id :results-repository :branch
  :brain-ref :remarks}`, each remark a map in Owllie's field names."
  [batch]
  (request/tune-request-block batch))

(defn skill-for
  "The Owllie skill a request asks for: `owllie-tune` or `owllie-run`."
  [request-text]
  (launcher/skill-for request-text))

;; --------------------------------------------------------------- launchers

(defn scheduled-task
  "A launcher firing a Claude scheduled task through its API trigger."
  [opts]
  (launcher/scheduled-task opts))

(defn claude-cli
  "A launcher running `claude --cloud` from a clone of Owllie."
  [opts]
  (launcher/claude-cli opts))

(defn mock-launcher
  "A launcher answering with `(on-fire request-text)`."
  [on-fire]
  (launcher/mock on-fire))

(defn mock-calls [launcher] (launcher/mock-calls launcher))

(defn fire!
  "Starts a session for `request-text`: `{:session-url :session-id}` or
  `{:failure \"...\"}`. Never throws."
  [launcher request-text]
  (try (launcher/-fire launcher request-text)
       (catch Exception e {:failure (str "the launcher failed: " (.getMessage e))})))
