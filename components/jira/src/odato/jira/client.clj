(ns odato.jira.client
  "Jira Cloud over its REST API.

  Three things are worth saying about the search JQL below, because they are
  approximations Jira's own query language forces and not choices:

    * issues created — `creator = currentUser() AND created` over the day, exact;
    * issues closed  — `status CHANGED TO (Done, Closed, Resolved) BY
      currentUser() DURING (...)`, exact;
    * comments       — core JQL cannot ask \"issues this account commented on\".
      So the day's touched issues are asked for, and each one's comments are
      read and kept only when this account wrote them on this day. It costs a
      call per issue, which is why it is the last source in the queue.

  Every activity a Jira source returns names its issue, so a Jira activity is
  never an orphan."
  (:require [clojure.string :as str]
            [odato.http.interface :as http]
            [odato.jira.protocol :as protocol]
            [odato.time.interface :as t])
  (:import (java.util Base64)))

(def ^:private page-size 50)

(defn- failure-of [response]
  (cond
    (:error response) :lookup-unavailable
    (= 401 (:status response)) :not-permitted
    (= 403 (:status response)) :not-permitted
    (= 404 (:status response)) :unknown-issue
    :else :lookup-unavailable))

(defn- issue-url [base-url issue-key]
  (str (str/replace (str base-url) #"/+$" "") "/browse/" issue-key))

(defn- ->facts [base-url body]
  {:summary (get-in body [:fields :summary])
   :status (get-in body [:fields :status :name])
   :url (issue-url base-url (:key body))
   :is-closed (= "Done" (get-in body [:fields :status :statusCategory :key]))})

;; -------------------------------------------------------------------- JQL

(defn- day-bounds [day-starts-at]
  ;; Jira reads these in the site's timezone; the day is stated in UTC, which
  ;; is what the rest of the installation means by a day.
  [(str (t/format-day day-starts-at) " 00:00")
   (str (t/format-day (+ day-starts-at (t/days 1))) " 00:00")])

(defn- jql-for [source day-starts-at]
  (let [[from to] (day-bounds day-starts-at)]
    (case source
      :jira-issues-created
      (format "creator = currentUser() AND created >= \"%s\" AND created < \"%s\"" from to)
      :jira-issues-closed
      (format "status CHANGED TO (Done, Closed, Resolved) BY currentUser() DURING (\"%s\", \"%s\")"
              from to)
      :jira-comments
      (format "updated >= \"%s\" AND updated < \"%s\" AND (assignee = currentUser() OR reporter = currentUser() OR watcher = currentUser())"
              from to))))

(defn- search [api jql start-at]
  (http/request api {:method :get :path "/rest/api/3/search/jql"
                     :query {:jql jql :startAt start-at :maxResults page-size
                             :fields "summary,status,created,updated"}}))

(defn- ->issue-activity [base-url source day-starts-at issue]
  {:source source
   :happened-at (or (t/parse-instant (get-in issue [:fields
                                                    (if (= source :jira-issues-created)
                                                      :created :updated)]))
                    day-starts-at)
   :reference (issue-url base-url (:key issue))
   :title (str (:key issue) " " (get-in issue [:fields :summary]))
   :issue-keys #{(:key issue)}})

(defn- comments-on [api base-url login day-starts-at issue]
  (let [response (http/request api {:method :get
                                    :path (str "/rest/api/3/issue/" (:key issue) "/comment")
                                    :query {:maxResults 100 :orderBy "-created"}})]
    (when (http/ok? response)
      (for [c (get-in response [:body :comments])
            :let [at (t/parse-instant (:created c))
                  author (or (get-in c [:author :accountId])
                             (get-in c [:author :displayName]))]
            :when (and at (t/falls-within-day? at day-starts-at) (= author login))]
        {:source :jira-comments
         :happened-at at
         :reference (str (issue-url base-url (:key issue)) "?focusedCommentId=" (:id c))
         :title (str "Comment on " (:key issue) " — " (get-in issue [:fields :summary]))
         :issue-keys #{(:key issue)}}))))

(defn- activity [{:keys [api base-url]} {:keys [source day-starts-at cursor login]}]
  (let [start-at (if cursor (parse-long cursor) 0)
        response (search api (jql-for source day-starts-at) start-at)]
    (if-not (http/ok? response)
      {:failure (case (failure-of response)
                  :not-permitted :credential-rejected
                  :unknown-issue :service-error
                  :service-unreachable)}
      (let [issues (get-in response [:body :issues])
            next-cursor (when (get-in response [:body :nextPageToken])
                          (str (+ start-at (count issues))))
            next-cursor (or next-cursor
                            (when (= page-size (count issues))
                              (str (+ start-at (count issues)))))]
        {:activities (vec (if (= source :jira-comments)
                            (mapcat #(comments-on api base-url login day-starts-at %) issues)
                            (map #(->issue-activity base-url source day-starts-at %) issues)))
         :next-cursor next-cursor}))))

;; ----------------------------------------------------------------- worklogs

(defn- started-field [ms]
  ;; Jira wants its own offset-carrying shape, and refuses a bare `Z`.
  (-> (java.time.Instant/ofEpochMilli (long ms))
      (.atZone java.time.ZoneOffset/UTC)
      (.format (java.time.format.DateTimeFormatter/ofPattern
                "yyyy-MM-dd'T'HH:mm:ss.SSSZ"))))

(defrecord JiraClient [api base-url]
  protocol/JiraClient
  (-whoami [_]
    (let [response (http/request api {:method :get :path "/rest/api/3/myself"})]
      (if (http/ok? response)
        {:login (or (get-in response [:body :accountId])
                    (get-in response [:body :emailAddress]))}
        {:failure (if (= :not-permitted (failure-of response))
                    :credential-rejected
                    :service-unreachable)})))

  (-fetch-issue [_ issue-key]
    (let [response (http/request api {:method :get
                                      :path (str "/rest/api/3/issue/" issue-key)
                                      :query {:fields "summary,status"}})]
      (if (http/ok? response)
        {:facts (->facts base-url (:body response))}
        {:failure (failure-of response)})))

  (-fetch-activity [this request] (activity this request))

  (-write-worklog [_ {:keys [issue worklog-id started-at duration-ms]}]
    (let [body {:started (started-field started-at)
                :timeSpentSeconds (max 60 (quot (long duration-ms) 1000))}
          response (http/request api
                                 (if worklog-id
                                   {:method :put
                                    :path (str "/rest/api/3/issue/" issue "/worklog/" worklog-id)
                                    :body body}
                                   {:method :post
                                    :path (str "/rest/api/3/issue/" issue "/worklog")
                                    :body body}))]
      (cond
        (http/ok? response) {:worklog-id (str (get-in response [:body :id]))}
        (:error response) {:failure :jira-unreachable}
        :else {:failure :worklog-refused})))

  (-delete-worklog [_ {:keys [issue worklog-id]}]
    (let [response (http/request api {:method :delete
                                      :path (str "/rest/api/3/issue/" issue
                                                 "/worklog/" worklog-id)})]
      (cond
        (http/ok? response) {:deleted true}
        ;; A worklog Jira no longer holds is a worklog withdrawn.
        (= 404 (:status response)) {:deleted true}
        (:error response) {:failure :jira-unreachable}
        :else {:failure :worklog-refused}))))

(defn client [{:keys [base-url email token timeout-ms]}]
  (let [basic (when (and email token)
                (.encodeToString (Base64/getEncoder)
                                 (.getBytes (str email ":" token) "UTF-8")))]
    (->JiraClient
     (http/client {:base-url base-url
                   :timeout-ms timeout-ms
                   :headers (cond-> {"accept" "application/json"
                                     "user-agent" "odatoruthall"}
                              basic (assoc "authorization" (str "Basic " basic))
                              (and token (not basic))
                              (assoc "authorization" (str "Bearer " token)))})
     base-url)))
