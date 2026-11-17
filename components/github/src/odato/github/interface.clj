(ns odato.github.interface
  "The GitHub side of the world, behind one seam.

  Two clients implement it: `client`, which talks to GitHub over its REST API,
  and `mock-client`, which answers from a script. The domain never knows which
  one it has — which is the point: the collection cycle, the day scan and every
  test that exercises them run the same code against either.

  Every answer carries the API budget, because GitHub reports it on every
  answer it gives. Every answer is either a page, with the cursor the next page
  starts at, or a failure."
  (:require [odato.github.client :as client]
            [odato.github.mock :as mock]
            [odato.github.protocol :as protocol]))

(defn client
  "A client against the real GitHub. `:token` is the operator's credential and
  never leaves this component; `:base-url` moves it at a GitHub Enterprise."
  [opts]
  (client/client opts))

(defn mock-client
  "A client that answers from a script. See `odato.github.mock` for its shape:
  pages, per-source failures, and a budget that can be made to run out."
  [script]
  (mock/client script))

(defn whoami
  "`{:login ... :budget {...}}`, or `{:failure ...}`."
  [c]
  (protocol/-whoami c))

(defn fetch-pull-requests
  "One page of one collection source.

  Takes `{:kind :repository-pull-requests|:mentions|:review-requests|:recently-merged
           :repository {:owner ... :name ...}   ; repository sources only
           :cursor <string|nil>
           :login <the operator>
           :repositories #{...}                 ; what is tracked, for the searches
           :merged-window-ms <long>}`.

  Answers `{:observations [{:repository ... :number ... :facts ...}]
            :next-cursor <string|nil> :budget {...}}`
  or `{:failure ... :budget {...}}`."
  [c request]
  (protocol/-fetch-pull-requests c request))

(defn fetch-activity
  "One page of one GitHub activity source, for one UTC day.

  Takes `{:source :github-commits|:github-comments|:github-pull-requests|:github-reviews
           :day-starts-at <ms> :cursor <string|nil> :login <the operator>}`.

  Answers `{:activities [...] :next-cursor ... :budget {...}}` or
  `{:failure ... :budget {...}}`."
  [c request]
  (protocol/-fetch-activity c request))

;; ------------------------------------------------------------ repositories

(defn repository
  "`{:owner :name}` → `{:repository {:default-branch :private}}`, or
  `{:failure :not-found|...}`."
  [c request]
  (protocol/-repository c request))

(defn file-exists
  "`{:owner :name :path :ref}` (no `:ref`: the default branch) →
  `{:exists boolean}` or a failure."
  [c request]
  (protocol/-file-exists c request))

(defn pull-request
  "`{:owner :name :number}` → `{:pull-request {:title :author :state :closed-at
  :head-sha :url}}`, `:state` being `:open`, `:closed` or `:merged`."
  [c request]
  (protocol/-pull-request c request))

(defn branches
  "`{:owner :name :prefix}` → `{:branches [{:name :sha}]}`: every branch whose
  name starts with the prefix, with the commit it points at."
  [c request]
  (protocol/-branches c request))

(defn tree
  "`{:owner :name :sha}` → `{:entries [{:path :sha}]}`: every file of that
  commit, with its blob."
  [c request]
  (protocol/-tree c request))

(defn blob
  "`{:owner :name :sha}` → `{:content string}`."
  [c request]
  (protocol/-blob c request))

(defn review-comments
  "`{:owner :name :number}` → `{:comments [{:id :author :path :line
  :start-line :original-line :original-start-line :side :commit-id
  :original-commit-id :in-reply-to :diff-hunk :body :url}]}`: the comments
  reviewers wrote on lines of a pull request."
  [c request]
  (protocol/-review-comments c request))

(defn delete-branch
  "`{:owner :name :branch}` → `{:deleted true}` or a failure. A branch already
  gone counts as deleted."
  [c request]
  (protocol/-delete-branch c request))

(defn mock-write-branch!
  "Moves a branch of a mock client, as a running review does."
  [c repository branch files]
  (mock/write-branch! c repository branch files))

(defn mock-branch-files [c repository branch]
  (mock/branch-files c repository branch))

(defn calls
  "What a mock client was asked, in order. Nothing for a real one."
  [c]
  (mock/calls c))

(def sources
  "The four sources that feed the snapshot."
  [:repository-pull-requests :mentions :review-requests :recently-merged])

(def activity-sources
  "What the operator did on GitHub, as a day scan reads it."
  [:github-commits :github-comments :github-pull-requests :github-reviews])
