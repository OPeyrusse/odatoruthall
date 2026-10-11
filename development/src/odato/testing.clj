(ns odato.testing
  "One installation, wired for a test: a store in memory, a clock held still,
  a bus nobody is listening to, and mock services in place of GitHub and Jira."
  (:require [odato.access.interface :as access]
            [odato.config.interface :as config]
            [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.github.interface :as github]
            [odato.jira.interface :as jira]
            [odato.time.interface :as t]))

(def default-instant "2026-09-15T10:30:00Z")

(defn system
  "An installation ready to be exercised. `:github` and `:jira` take the mock
  scripts described in those components; `:at` sets the clock."
  ([] (system {}))
  ([{:keys [at github jira file poll-ms]
     :or {at default-instant}}]
   (let [sys {:store (db/open (when file {:file file}))
              :clock (t/fixed-clock at)
              :bus (events/bus)
              :poll-ms (or poll-ms 5)
              :github (github/mock-client (merge {:login "ada"} github))
              :jira (jira/mock-client (merge {:login "ada"} jira))}]
     (access/ensure-defaults! sys)
     (config/ensure-defaults! sys)
     sys)))

(defn connected!
  "An installation whose credentials have both been accepted."
  ([] (connected! {}))
  ([opts]
   (let [sys (system opts)]
     (access/check-connection! sys :github #(github/whoami (:github sys)))
     (access/check-connection! sys :jira #(jira/whoami (:jira sys)))
     sys)))

(defn configured!
  "A connected installation watching something."
  ([] (configured! {}))
  ([opts]
   (let [sys (connected! opts)]
     (config/save! sys (merge {:repositories #{{:owner "acme" :name "widget"}}
                               :members #{"ada" "grace"}
                               :refresh-cadence-ms (t/minutes 60)
                               :window {:opens-hour 5 :closes-hour 21
                                        :active-days #{:monday :tuesday :wednesday
                                                       :thursday :friday}}
                               :thresholds {:aging-ms (t/days 15)
                                            :comments 15
                                            :commits 10
                                            :failing-commits 10
                                            :merged-window-ms (t/days 3)}
                               :show-drafts false}
                              (:configuration opts)))
     sys)))

(defn events-of
  "Every event of `type` the bus saw."
  [sys type]
  (filterv #(= type (:type %)) (events/recent (:bus sys))))

(defn saw? [sys type] (boolean (seq (events-of sys type))))

(defn tick! [sys ms] (t/advance! (:clock sys) ms) sys)
(defn now [sys] (t/now-ms (:clock sys)))
