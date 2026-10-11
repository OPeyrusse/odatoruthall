(ns odato.worklog.submit
  "What it means for entered time to reach Jira, and to be taken back.

  Nothing is written to Jira while the operator types. A worklog is created,
  updated or withdrawn only because they submitted a day or removed an entry."
  (:require [odato.access.interface :as access]
            [odato.db.interface :as db]
            [odato.jira.interface :as jira]
            [odato.worklog.core :as core]))

(defn- acknowledged! [sys entry-id worklog-id]
  (db/put! (core/conn sys) :wl-entry/id entry-id
           {:wl-entry/status :acknowledged
            :wl-entry/jira-worklog-id worklog-id
            :wl-entry/acknowledged-at (core/now sys)
            :wl-entry/last-failure nil})
  (core/emit! sys {:type :worklog/time-reached-jira :entry entry-id :worklog worklog-id}))

(defn- rejected! [sys entry-id failure]
  (db/put! (core/conn sys) :wl-entry/id entry-id
           {:wl-entry/status :rejected :wl-entry/last-failure failure})
  (core/emit! sys {:type :worklog/time-did-not-reach-jira
                   :entry entry-id :failure failure}))

(defn- submit-entry!
  "EntrySubmitted, and what Jira made of it. An entry Jira already holds is
  updated under the same worklog, never added a second time: a day submitted
  twice logs the time once."
  [sys e]
  (db/put! (core/conn sys) :wl-entry/id (:id e)
           {:wl-entry/status :submitted
            :wl-entry/submitted-at (core/now sys)
            :wl-entry/last-failure nil})
  (let [answer (try (jira/write-worklog (:jira sys)
                                        {:issue (:work-item-key e)
                                         :worklog-id (:jira-worklog-id e)
                                         :started-at (:day-starts-at e)
                                         :duration-ms (:duration-ms e)})
                    (catch Exception _ {:failure :jira-unreachable}))]
    (if-let [failure (:failure answer)]
      (do (rejected! sys (:id e) failure) :rejected)
      (do (acknowledged! sys (:id e) (:worklog-id answer)) :acknowledged))))

(defn submit-day!
  "DaySubmitted / DayNotSubmitted. Only what Jira does not already hold is
  sent: entries acknowledged and untouched since are left alone, and an entry
  with no time on it is not a worklog."
  [sys day-starts-at]
  (let [refused (fn [fault]
                  (core/emit! sys {:type :worklog/day-submission-refused
                                   :day day-starts-at :failure fault})
                  {:ok false :fault fault})]
    (if-not (access/usable? sys :jira)
      (refused :credential-unavailable)
      ;; The day is held for the whole submission, so that no scan starts on
      ;; it, and no second submission, until Jira has answered for every entry.
      (let [taken (core/occupy! sys day-starts-at :submission)]
        (if-not (:ok taken)
          (refused (case (:busy-with taken)
                     :scan :day-being-scanned
                     :already-submitting))
          (try
            (let [pending (filterv :pending-submission?
                                   (:entries (core/day sys day-starts-at)))]
              (if (empty? pending)
                (do (core/emit! sys {:type :worklog/nothing-to-submit :day day-starts-at})
                    {:ok false :fault :nothing-to-submit})
                (let [outcomes (mapv #(submit-entry! sys %) pending)
                      counted (frequencies outcomes)]
                  (core/emit! sys {:type :worklog/day-submitted :day day-starts-at
                                   :acknowledged (get counted :acknowledged 0)
                                   :rejected (get counted :rejected 0)})
                  {:ok true
                   :submitted (count pending)
                   :acknowledged (get counted :acknowledged 0)
                   :rejected (get counted :rejected 0)})))
            (finally (core/release! sys day-starts-at :submission))))))))

(defn remove-entry!
  "EntryRemoved / EntryWithdrawn / EntryWithdrawalRefused."
  [sys entry-id]
  (let [e (core/entry sys entry-id)]
    (cond
      (nil? e) {:ok false :fault :no-such-entry}
      (not (:editable? e)) {:ok false :fault :entry-in-flight}

      (:known-to-jira? e)
      (let [answer (try (jira/delete-worklog (:jira sys)
                                             {:issue (:work-item-key e)
                                              :worklog-id (:jira-worklog-id e)})
                        (catch Exception _ {:failure :jira-unreachable}))]
        (if-let [failure (:failure answer)]
          (do (db/put! (core/conn sys) :wl-entry/id entry-id
                       {:wl-entry/last-failure failure})
              (core/emit! sys {:type :worklog/still-in-jira :entry entry-id
                               :failure failure})
              {:ok false :fault failure})
          (do (db/transact! (core/conn sys)
                            [(db/retract-entity
                              (db/lookup (db/db (core/conn sys)) :wl-entry/id entry-id))])
              (core/emit! sys {:type :worklog/entry-removed :entry entry-id})
              {:ok true})))

      :else
      (do (db/transact! (core/conn sys)
                        [(db/retract-entity
                          (db/lookup (db/db (core/conn sys)) :wl-entry/id entry-id))])
          (core/emit! sys {:type :worklog/entry-removed :entry entry-id})
          {:ok true}))))
