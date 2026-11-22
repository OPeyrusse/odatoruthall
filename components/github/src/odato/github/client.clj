(ns odato.github.client
  "GitHub over its own API.

  Pull requests come through GraphQL: one query answers with everything a
  classification needs — the check rollup on the newest commits, the reviews
  with the commit each reviewer looked at, the comment count, whether
  auto-merge is armed — where REST would want four calls per pull request. The
  budget is the reason: a dashboard that watches a dozen repositories on a
  60-minute cadence should not spend its hour's allowance on round trips.

  Activity for a day scan comes through the search API, which is REST. An
  activity names the Jira issues in its title and nowhere else: the pull
  request's title, or a commit's first line. A description or a commit body
  often cites older tickets to explain the change, and citing a ticket is not
  working on it."
  (:require [clojure.string :as str]
            [odato.github.protocol :as protocol]
            [odato.http.interface :as http]
            [odato.time.interface :as t]))

(def ^:private issue-key-re #"\b([A-Z][A-Z0-9]{1,9}-\d+)\b")

(defn issue-keys
  "The Jira issues a piece of text names."
  [& texts]
  (into #{} (comp (keep identity) (mapcat #(map second (re-seq issue-key-re (str %)))))
        texts))

;; ------------------------------------------------------------------ budget

(defn- rest-budget [response]
  {:remaining (some-> (http/header response "x-ratelimit-remaining") parse-long)
   :resets-at (some-> (http/header response "x-ratelimit-reset") parse-long (* 1000))})

(defn- graphql-budget [body]
  (let [rate (get-in body [:data :rateLimit])]
    {:remaining (:remaining rate)
     :resets-at (t/parse-instant (:resetAt rate))}))

(defn- exhausted?
  "GitHub answers 403 or 429 when the budget is spent. That is a wait, not a
  failure, so the caller is told to come back rather than told it broke."
  [response budget]
  (and (contains? #{403 429} (:status response))
       (or (zero? (long (or (:remaining budget) 1)))
           (some? (http/header response "retry-after")))))

(defn- failure-of [response]
  (cond
    (:error response) :service-unreachable
    (= 401 (:status response)) :credential-rejected
    (and (= 403 (:status response))
         (str/includes? (str (get-in response [:body :message])) "credential"))
    :credential-rejected
    :else :service-error))

;; ------------------------------------------------------------------ GraphQL

(def ^:private pr-fields "
fragment prFields on PullRequest {
  number title url isDraft state mergeable createdAt updatedAt mergedAt
  headRefName
  comments { totalCount }
  author { login }
  repository { name owner { login } }
  assignees(first: 20) { nodes { login } }
  reviewRequests(first: 20) { nodes { requestedReviewer { ... on User { login } } } }
  labels(first: 20) { nodes { name } }
  autoMergeRequest { enabledAt }
  commits(last: 20) { totalCount nodes { commit { oid committedDate
                                                  statusCheckRollup { state } } } }
  reviews(first: 50) { nodes { author { login } state submittedAt commit { oid } } }
}")

(def ^:private repository-query (str pr-fields "
query($owner: String!, $name: String!, $cursor: String) {
  rateLimit { remaining resetAt }
  repository(owner: $owner, name: $name) {
    pullRequests(states: OPEN, first: 50, after: $cursor,
                 orderBy: {field: UPDATED_AT, direction: DESC}) {
      pageInfo { hasNextPage endCursor }
      nodes { ...prFields }
    }
  }
}"))

(def ^:private search-query (str pr-fields "
query($q: String!, $cursor: String) {
  rateLimit { remaining resetAt }
  search(query: $q, type: ISSUE, first: 50, after: $cursor) {
    pageInfo { hasNextPage endCursor }
    nodes { ... on PullRequest { ...prFields } }
  }
}"))

(def ^:private assigned-query "
query($q: String!, $login: String!, $cursor: String) {
  rateLimit { remaining resetAt }
  search(query: $q, type: ISSUE, first: 50, after: $cursor) {
    pageInfo { hasNextPage endCursor }
    nodes {
      ... on PullRequest {
        number title url
        repository { name owner { login } }
        author { __typename login }
        reviews(author: $login, last: 100) { nodes { author { login } state submittedAt } }
        timelineItems(itemTypes: [ASSIGNED_EVENT], last: 100) {
          nodes { ... on AssignedEvent { createdAt assignee { ... on User { login } } } }
        }
      }
    }
  }
}")

;; ------------------------------------------------------------- translation

(def ^:private rollup-states
  {"SUCCESS" :success "FAILURE" :failure "ERROR" :failure
   "PENDING" :pending "EXPECTED" :pending})

(def ^:private review-verdicts
  {"APPROVED" :approved "CHANGES_REQUESTED" :changes-requested
   "COMMENTED" :commented "DISMISSED" :dismissed})

(defn- logins [nodes k] (into #{} (keep #(get-in % k)) nodes))

(defn- ->facts [node]
  {:title (:title node)
   :url (:url node)
   :branch (:headRefName node)
   :author (get-in node [:author :login])
   :assignees (logins (get-in node [:assignees :nodes]) [:login])
   :requested-reviewers (logins (get-in node [:reviewRequests :nodes])
                                [:requestedReviewer :login])
   :labels (logins (get-in node [:labels :nodes]) [:name])
   :is-draft (boolean (:isDraft node))
   :state (if (= "MERGED" (:state node)) :merged :open)
   :mergeable (= "MERGEABLE" (:mergeable node))
   :auto-merge-enabled (some? (:autoMergeRequest node))
   :comment-count (or (get-in node [:comments :totalCount]) 0)
   :opened-at (t/parse-instant (:createdAt node))
   :updated-at (t/parse-instant (:updatedAt node))
   :merged-at (t/parse-instant (:mergedAt node))
   :commit-count (or (get-in node [:commits :totalCount]) 0)
   ;; GraphQL hands the last commits oldest first; the snapshot wants the
   ;; newest first, because that is the one the checks are read from.
   :commits (->> (get-in node [:commits :nodes])
                 (map (fn [{:keys [commit]}]
                        {:sha (:oid commit)
                         :committed-at (t/parse-instant (:committedDate commit))
                         :check-rollup (get rollup-states
                                            (get-in commit [:statusCheckRollup :state])
                                            :none)}))
                 reverse
                 vec)
   :reviews (->> (get-in node [:reviews :nodes])
                 (keep (fn [r]
                         (when-let [verdict (get review-verdicts (:state r))]
                           {:reviewer (get-in r [:author :login])
                            :verdict verdict
                            :submitted-at (t/parse-instant (:submittedAt r))
                            :reviewed-sha (get-in r [:commit :oid])})))
                 (sort-by :submitted-at)
                 vec)})

(defn- ->review [r]
  (when-let [verdict (get review-verdicts (:state r))]
    {:reviewer (get-in r [:author :login])
     :verdict verdict
     :submitted-at (t/parse-instant (:submittedAt r))}))

(defn- ->assigned-pull-request [node]
  (when (:number node)
    {:repository {:owner (get-in node [:repository :owner :login])
                  :name (get-in node [:repository :name])}
     :number (:number node)
     :title (:title node)
     :url (:url node)
     :author (get-in node [:author :login])
     :author-is-user (= "User" (get-in node [:author :__typename]))
     :reviews (into [] (keep ->review) (get-in node [:reviews :nodes]))
     :assignments (vec (for [event (get-in node [:timelineItems :nodes])
                             :let [assignee (get-in event [:assignee :login])]
                             :when assignee]
                         {:assignee assignee
                          :assigned-at (t/parse-instant (:createdAt event))}))}))

(defn- ->observation [node]
  (when (:number node)
    {:repository {:owner (get-in node [:repository :owner :login])
                  :name (get-in node [:repository :name])}
     :number (:number node)
     :facts (->facts node)}))

;; -------------------------------------------------------------- the queries

(defn- graphql [c query variables]
  (http/request (:api c) {:method :post :path "/graphql"
                          :body {:query query :variables variables}}))

(defn- merged-since [merged-window-ms now-ms]
  (t/format-day (t/utc-midnight-of (- now-ms (long (or merged-window-ms (t/days 3)))))))

(defn- search-terms
  "Every search is held to the tracked repositories: what happens elsewhere is
  of no interest, and would only spend the budget."
  [{:keys [kind login repositories merged-window-ms now-ms]}]
  (str (case kind
         :mentions (str "is:pr is:open mentions:" login)
         :review-requests (str "is:pr is:open review-requested:" login)
         :assigned (str "is:pr is:open assignee:" login)
         :recently-merged (str "is:pr is:merged merged:>="
                               (merged-since merged-window-ms now-ms)))
       " " (str/join " " (for [r (sort-by (juxt :owner :name) repositories)]
                           (str "repo:" (:owner r) "/" (:name r))))))

(defn- page-of [c request]
  (if (= :repository-pull-requests (:kind request))
    (let [{:keys [owner name]} (:repository request)]
      [(graphql c repository-query {:owner owner :name name :cursor (:cursor request)})
       [:data :repository :pullRequests]])
    [(graphql c search-query {:q (search-terms request) :cursor (:cursor request)})
     [:data :search]]))

(defn- pull-requests [c request]
  (let [[response path] (page-of c request)
        budget (graphql-budget (:body response))]
    (cond
      (exhausted? response budget) {:retry true :budget budget}
      (not (http/ok? response)) {:failure (failure-of response) :budget budget}
      (seq (get-in response [:body :errors])) {:failure :service-error :budget budget}
      :else
      (let [page (get-in (:body response) path)]
        {:observations (into [] (keep ->observation) (:nodes page))
         :next-cursor (when (get-in page [:pageInfo :hasNextPage])
                        (get-in page [:pageInfo :endCursor]))
         :budget budget}))))

(defn- assigned-pull-requests [c {:keys [login cursor] :as request}]
  (let [response (graphql c assigned-query {:q (search-terms (assoc request :kind :assigned))
                                            :login login
                                            :cursor cursor})
        budget (graphql-budget (:body response))]
    (cond
      (exhausted? response budget) {:retry true :budget budget}
      (not (http/ok? response)) {:failure (failure-of response) :budget budget}
      (seq (get-in response [:body :errors])) {:failure :service-error :budget budget}
      :else
      (let [page (get-in response [:body :data :search])]
        {:pull-requests (into [] (keep ->assigned-pull-request) (:nodes page))
         :next-cursor (when (get-in page [:pageInfo :hasNextPage])
                        (get-in page [:pageInfo :endCursor]))
         :budget budget}))))

;; ------------------------------------------------------------ day activity

(defn- day-range [day-starts-at]
  (let [d (t/format-day day-starts-at)] (str d ".." d)))

(defn- activity-request [{:keys [source day-starts-at login]}]
  (let [range (day-range day-starts-at)]
    (case source
      :github-commits
      {:path "/search/commits"
       :query {:q (str "author:" login " author-date:" range) :per_page 50 :sort "author-date"}}
      :github-comments
      {:path "/search/issues"
       :query {:q (str "commenter:" login " updated:" range) :per_page 50}}
      :github-pull-requests
      {:path "/search/issues"
       :query {:q (str "is:pr author:" login " created:" range) :per_page 50}}
      :github-reviews
      {:path "/search/issues"
       :query {:q (str "is:pr reviewed-by:" login " updated:" range) :per_page 50}})))

(defn- ->commit-activity [item]
  (let [title (first (str/split-lines (str (get-in item [:commit :message]))))]
    {:source :github-commits
     :happened-at (t/parse-instant (get-in item [:commit :author :date]))
     :reference (:sha item)
     :title title
     :issue-keys (issue-keys title)}))

(defn- ->issue-activity [source item day-starts-at]
  {:source source
   ;; A comment or a review is dated by when the thread last moved, which is
   ;; the best the search API offers; the day it is filed under is the day
   ;; being scanned, and the collector drops anything outside it.
   :happened-at (or (t/parse-instant (if (= source :github-pull-requests)
                                       (:created_at item)
                                       (:updated_at item)))
                    day-starts-at)
   :reference (str (:html_url item))
   :title (:title item)
   :issue-keys (issue-keys (:title item))})

(defn- activity [c {:keys [source day-starts-at cursor] :as request}]
  (let [{:keys [path query]} (activity-request request)
        page (if cursor (parse-long cursor) 1)
        response (http/request (:api c) {:method :get :path path
                                         :query (assoc query :page page)})
        budget (rest-budget response)]
    (cond
      (exhausted? response budget) {:retry true :budget budget}
      (not (http/ok? response)) {:failure (failure-of response) :budget budget}
      :else
      (let [items (get-in response [:body :items])]
        {:activities (mapv (fn [item]
                             (if (= source :github-commits)
                               (->commit-activity item)
                               (->issue-activity source item day-starts-at)))
                           items)
         :next-cursor (http/link-cursor response)
         :budget budget}))))

;; ------------------------------------------------------------ repositories

(defn- repo-path [{:keys [owner name]}]
  (str "/repos/" owner "/" name))

(defn- rest-call
  "One REST call, answered as `(f body)` merged with the budget, or as a
  failure. A 404 is `:not-found`: for owldesk, a repository, a pull request or
  a file that is not there is an answer, not an error."
  [c request f]
  (let [response (http/request (:api c) request)
        budget (rest-budget response)]
    (cond
      (exhausted? response budget) {:retry true :budget budget}
      (= 404 (:status response)) {:failure :not-found :budget budget}
      (not (http/ok? response)) {:failure (failure-of response) :budget budget}
      :else (merge (f (:body response) response) {:budget budget}))))

(defn- pull-request-state [body]
  (cond
    (:merged_at body) :merged
    (= "closed" (:state body)) :closed
    :else :open))

(defn- decode-base64 [s]
  (String. (.decode (java.util.Base64/getMimeDecoder) (str s)) "UTF-8"))

(defn- repository [c request]
  (rest-call c {:method :get :path (repo-path request)}
             (fn [body _]
               {:repository {:default-branch (:default_branch body)
                             :private (boolean (:private body))}})))

(defn- file-exists [c {:keys [ref path] :as request}]
  (let [answer (rest-call c {:method :get
                             :path (str (repo-path request) "/contents/" path)
                             :query (when ref {:ref ref})}
                          (fn [_ _] {:exists true}))]
    (if (= :not-found (:failure answer))
      {:exists false :budget (:budget answer)}
      answer)))

(defn- pull-request [c {:keys [number] :as request}]
  (rest-call c {:method :get :path (str (repo-path request) "/pulls/" number)}
             (fn [body _]
               {:pull-request {:title (:title body)
                               :author (get-in body [:user :login])
                               :state (pull-request-state body)
                               :closed-at (t/parse-instant (:closed_at body))
                               :head-sha (get-in body [:head :sha])
                               :url (:html_url body)
                               :draft (boolean (:draft body))
                               :mergeable-state (:mergeable_state body)}})))

(defn- branches [c {:keys [prefix] :as request}]
  (let [response (http/request (:api c)
                               {:method :get
                                :path (str (repo-path request) "/git/matching-refs/heads/" prefix)})
        budget (rest-budget response)]
    (cond
      (exhausted? response budget) {:retry true :budget budget}
      ;; A repository with no commit at all has no refs to match.
      (= 409 (:status response)) {:branches [] :budget budget}
      (= 404 (:status response)) {:failure :not-found :budget budget}
      (not (http/ok? response)) {:failure (failure-of response) :budget budget}
      :else {:branches (vec (for [ref (:body response)
                                  :let [n (str (:ref ref))]
                                  :when (str/starts-with? n "refs/heads/")]
                              {:name (subs n (count "refs/heads/"))
                               :sha (get-in ref [:object :sha])}))
             :budget budget})))

(defn- tree [c {:keys [sha] :as request}]
  (rest-call c {:method :get :path (str (repo-path request) "/git/trees/" sha)
                :query {:recursive 1}}
             (fn [body _]
               {:entries (vec (for [e (:tree body) :when (= "blob" (:type e))]
                                {:path (:path e) :sha (:sha e)}))})))

(defn- blob [c {:keys [sha] :as request}]
  (rest-call c {:method :get :path (str (repo-path request) "/git/blobs/" sha)}
             (fn [body _]
               {:content (if (= "base64" (:encoding body))
                           (decode-base64 (:content body))
                           (str (:content body)))})))

(def ^:private review-comment-pages
  "Review comments are read 100 at a time, and no further than this: a pull
  request with more is not one whose misses anyone sifts by hand."
  3)

(defn- ->review-comment [c]
  {:id (str (:id c))
   :author (get-in c [:user :login])
   :path (:path c)
   :line (:line c)
   :start-line (:start_line c)
   :original-line (:original_line c)
   :original-start-line (:original_start_line c)
   :side (:side c)
   :commit-id (:commit_id c)
   :original-commit-id (:original_commit_id c)
   :in-reply-to (some-> (:in_reply_to_id c) str)
   :diff-hunk (:diff_hunk c)
   :body (:body c)
   :url (:html_url c)})

(defn- review-comments [c {:keys [number] :as request}]
  (loop [page 1 comments []]
    (let [answer (rest-call c {:method :get
                               :path (str (repo-path request) "/pulls/" number "/comments")
                               :query {:per_page 100 :page page}}
                            (fn [body _] {:items (if (sequential? body) body [])}))]
      (if (or (:failure answer) (:retry answer))
        answer
        (let [comments (into comments (map ->review-comment) (:items answer))]
          (if (and (= 100 (count (:items answer))) (< page review-comment-pages))
            (recur (inc page) comments)
            {:comments comments :budget (:budget answer)}))))))

(defn- delete-branch [c {:keys [branch] :as request}]
  (let [answer (rest-call c {:method :delete
                             :path (str (repo-path request) "/git/refs/heads/" branch)}
                          (fn [_ _] {:deleted true}))]
    ;; A branch already gone is as deleted as it will ever be.
    (if (= :not-found (:failure answer))
      {:deleted true :budget (:budget answer)}
      answer)))

;; --------------------------------------------------------------- assignees

(defn- assignees-call [c method {:keys [number login] :as request}]
  (rest-call c {:method method
                :path (str (repo-path request) "/issues/" number "/assignees")
                :body {:assignees [login]}}
             (fn [_ _] {:written true})))

;; ------------------------------------------------------------------ client

(defrecord GithubClient [api]
  protocol/GithubClient
  (-whoami [_]
    (let [response (http/request api {:method :get :path "/user"})]
      (if (http/ok? response)
        {:login (get-in response [:body :login]) :budget (rest-budget response)}
        {:failure (failure-of response)})))
  (-fetch-pull-requests [this request] (pull-requests this request))
  (-fetch-activity [this request] (activity this request))

  protocol/GithubRepositories
  (-repository [this request] (repository this request))
  (-file-exists [this request] (file-exists this request))
  (-pull-request [this request] (pull-request this request))
  (-branches [this request] (branches this request))
  (-tree [this request] (tree this request))
  (-blob [this request] (blob this request))
  (-delete-branch [this request] (delete-branch this request))
  (-review-comments [this request] (review-comments this request))

  protocol/GithubAssignees
  (-assigned-pull-requests [this request] (assigned-pull-requests this request))
  (-add-assignee [this request] (assignees-call this :post request))
  (-remove-assignee [this request] (assignees-call this :delete request)))

(defn client
  [{:keys [token base-url timeout-ms]
    :or {base-url "https://api.github.com"}}]
  (->GithubClient
   (http/client {:base-url base-url
                 :timeout-ms timeout-ms
                 :headers (cond-> {"accept" "application/vnd.github+json"
                                   "x-github-api-version" "2022-11-28"
                                   "user-agent" "odatoruthall"}
                            token (assoc "authorization" (str "Bearer " token)))})))
