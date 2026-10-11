(ns odato.jira.mock
  "A Jira that answers from a script, and remembers what was written to it.

  The script:

    {:login  \"ada\"
     :whoami {:failure :credential-rejected}      ; optional
     :issues {\"ABC-1\" {:summary \"Ship it\" :status \"In Progress\"
                       :url \"https://jira/ABC-1\" :is-closed false}}
     :unknown #{\"ABC-9\"}                          ; keys Jira does not know
     :page-size 2
     :activities {:jira-comments [activity ...] :jira-issues-created [...]}
     :time-logged [{:id 76812 :key \"ABC-1\" :at <ms> :logged-ms <ms>
                    :author \"bob\"}]                ; worklogs Jira holds; the
                                                    ; author defaults to :login
     :failures {:jira-comments :service-error      ; a source that fails
                :task-time :service-error          ; every time reading fails
                :worklog :jira-unreachable         ; every write fails
                :worklog-delete :jira-unreachable}}

  Worklogs written through it are kept, so a test can show that a day
  submitted twice logs the time once: the same entry updates the worklog it
  already has rather than adding a second."
  (:require [odato.jira.protocol :as protocol]
            [odato.time.interface :as t]))

(defrecord MockJira [script state]
  protocol/JiraClient
  (-whoami [_]
    (swap! state update :calls conj {:op :whoami})
    (or (:whoami script) {:login (or (:login script) "operator")}))

  (-fetch-issue [_ issue-key]
    (swap! state update :calls conj {:op :issue :key issue-key})
    (cond
      (get-in script [:failures :issue]) {:failure (get-in script [:failures :issue])}
      (contains? (set (:unknown script)) issue-key) {:failure :unknown-issue}
      (get-in script [:issues issue-key]) {:facts (get-in script [:issues issue-key])}
      :else {:failure :unknown-issue}))

  (-fetch-activity [_ {:keys [source cursor day-starts-at]}]
    (swap! state update :calls conj {:op :activity :source source :cursor cursor
                                     :day day-starts-at})
    (if-let [failure (get-in script [:failures source])]
      {:failure failure}
      (let [results (get-in script [:activities source] [])
            size (or (:page-size script) 1000)
            from (if cursor (parse-long cursor) 0)
            taken (vec (take size (drop from results)))
            next-from (+ from (count taken))]
        {:activities taken
         :next-cursor (when (< next-from (count results)) (str next-from))})))

  (-fetch-task-time [_ {:keys [issue day-starts-at]}]
    (swap! state update :calls conj {:op :task-time :key issue :day day-starts-at})
    (if-let [failure (get-in script [:failures :task-time])]
      {:failure failure}
      (let [operator (or (:login script) "operator")
            mine (filter #(and (= issue (:key %))
                               (= operator (or (:author %) operator))
                               (t/falls-within-day? (:at %) day-starts-at))
                         (:time-logged script))]
        {:logged-ms (reduce + 0 (map :logged-ms mine))
         :worklogs (count mine)
         :worklog (when (= 1 (count mine)) (str (:id (first mine))))})))

  (-write-worklog [_ {:keys [issue worklog-id started-at duration-ms]}]
    (swap! state update :calls conj {:op :write-worklog :issue issue :worklog worklog-id})
    (if-let [failure (get-in script [:failures :worklog])]
      {:failure failure}
      (let [id (or worklog-id (str "wl-" (:next-id (swap! state update :next-id inc))))]
        (swap! state assoc-in [:worklogs id]
               {:issue issue :started-at started-at :duration-ms duration-ms})
        {:worklog-id id})))

  (-delete-worklog [_ {:keys [worklog-id]}]
    (swap! state update :calls conj {:op :delete-worklog :worklog worklog-id})
    (if-let [failure (get-in script [:failures :worklog-delete])]
      {:failure failure}
      (do (swap! state update :worklogs dissoc worklog-id)
          {:deleted true}))))

(defn client [script]
  (->MockJira script (atom {:calls [] :worklogs {} :next-id 0})))

(defn worklogs [c]
  (when (instance? MockJira c) (:worklogs @(:state c))))

(defn calls [c]
  (when (instance? MockJira c) (:calls @(:state c))))
