(ns odato.owllie.request
  "What a review request says, written the way Owllie reads it.

  Owllie's `shared/run-layout.md` is the reference: the `OWLLIE_RUN v1` block,
  the branch name `review/<owner>/<repo>/<number>/<run id>`, and how an owner
  or a repository name becomes one ref component. Owllie refuses a request
  whose branch does not match its pull request, so both sides must encode the
  names the same way."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def ^:private name-re #"[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
(def ^:private repo-re #"[A-Za-z0-9._-]{1,100}")

(def ^:private pull-request-url-re
  (re-pattern (str "^\\s*https?://github\\.com/(" name-re ")/(" repo-re ")/pull/([1-9][0-9]{0,9})"
                   "(?:[/?#].*)?\\s*$")))

(defn parse-pull-request-url
  "`https://github.com/<owner>/<repo>/pull/<n>`, possibly followed by a tab of
  the pull request (`/files`) or a query, as
  `{:repository {:owner :name} :number n}`; nil for anything else."
  [s]
  (when-let [[_ owner name number] (re-matches pull-request-url-re (str s))]
    (when-not (or (#{"." ".."} name) (str/ends-with? name ".git"))
      {:repository {:owner owner :name name} :number (parse-long number)})))

(defn pull-request-url [{:keys [repository number]}]
  (str "https://github.com/" (:owner repository) "/" (:name repository) "/pull/" number))

(def ^:private ref-re #"^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$")

(defn ref-name?
  "Whether `s` can be handed to Owllie as `BRAIN_REF`."
  [s]
  (let [s (str s)]
    (boolean (and (re-matches ref-re s)
                  (not (str/includes? s ".."))
                  (not (str/includes? s "//"))
                  (not (str/includes? s "/."))
                  (not (str/ends-with? s "/"))
                  (not (str/ends-with? s ".lock"))))))

(defn ref-component
  "An owner or repository name as one git ref component."
  [s]
  (let [c (loop [c (str/lower-case (str s))]
            (if (str/includes? c "..") (recur (str/replace c ".." "__")) c))
        c (if (str/starts-with? c ".") (str "_" (subs c 1)) c)]
    (if (str/ends-with? c ".lock")
      (str (subs c 0 (- (count c) (count ".lock"))) "_lock")
      c)))

(def branch-prefix "review/")

(defn branch-name [{:keys [repository number]} run-id]
  (str branch-prefix (ref-component (:owner repository)) "/" (ref-component (:name repository))
       "/" number "/" run-id))

(def ^:private run-id-re #"[0-9]{8}T[0-9]{4}Z-[0-9a-f]{4,12}")

(def ^:private branch-re
  (re-pattern (str "^review/([^/]+)/([^/]+)/([1-9][0-9]*)/(" run-id-re ")$")))

(defn parse-branch-name
  "A results branch as `{:owner :name :number :run-id}`, owner and name as they
  are encoded in the branch; nil for a branch that is not a run's."
  [s]
  (when-let [[_ owner name number run-id] (re-matches branch-re (str s))]
    {:owner owner :name name :number (parse-long number) :run-id run-id}))

(def ^:private minute-format
  (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmm'Z'") ZoneOffset/UTC))

(defn run-id
  "The UTC minute of `now-ms`, then `suffix` (lower-case hex)."
  [now-ms suffix]
  (str (.format minute-format (Instant/ofEpochMilli now-ms)) "-" suffix))

(defn random-suffix []
  (format "%04x" (rand-int 0x10000)))

(defn run-id-instant
  "When the run was asked for, read back from its id; nil for a malformed id."
  [run-id]
  (when-let [[_ y mo d h mi] (re-matches #"(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})Z-.*" (str run-id))]
    (try (.toEpochMilli (Instant/parse (str y "-" mo "-" d "T" h ":" mi ":00Z")))
         (catch Exception _ nil))))

;; ------------------------------------------------------------------ tuning

(def tune-branch-prefix "tune/")

(defn tune-branch-name [run-id] (str tune-branch-prefix run-id))

(def ^:private tune-branch-re (re-pattern (str "^tune/(" run-id-re ")$")))

(defn parse-tune-branch-name
  "A tuning's branch as `{:run-id}`; nil for a branch that is not one."
  [s]
  (when-let [[_ run-id] (re-matches tune-branch-re (str s))]
    {:run-id run-id}))

(defn- remark-line
  "One remark as the single-line JSON Owllie's `shared/tune-layout.md` reads:
  a line break in a note stays inside the JSON string."
  [remark]
  (str "REMARK=" (json/write-str remark :escape-slash false)))

(defn tune-request-block
  "The `OWLLIE_TUNE v1` block for one batch of remarks: `{:pull-request
  :head-sha :review-run-id :results-repository :branch :brain-ref :remarks}`,
  each remark a map already in Owllie's field names."
  [{:keys [pull-request head-sha review-run-id results-repository branch brain-ref remarks]}]
  (str "OWLLIE_TUNE v1\n"
       "PR_URL=" (pull-request-url pull-request) "\n"
       "HEAD_SHA=" head-sha "\n"
       "REVIEW_RUN=" review-run-id "\n"
       "RESULTS_REPO=" (:owner results-repository) "/" (:name results-repository) "\n"
       "RUN_BRANCH=" branch "\n"
       (when-not (str/blank? brain-ref) (str "BRAIN_BASE=" brain-ref "\n"))
       (apply str (for [r remarks] (str (remark-line r) "\n")))))

(defn request-block
  "The `OWLLIE_RUN v1` block for one run."
  [{:keys [pull-request results-repository branch brain-ref]}]
  (str "OWLLIE_RUN v1\n"
       "PR_URL=" (pull-request-url pull-request) "\n"
       "RESULTS_REPO=" (:owner results-repository) "/" (:name results-repository) "\n"
       "RUN_BRANCH=" branch "\n"
       (when-not (str/blank? brain-ref) (str "BRAIN_REF=" brain-ref "\n"))))
