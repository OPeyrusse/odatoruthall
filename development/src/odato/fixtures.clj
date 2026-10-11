(ns odato.fixtures
  "Pull requests, activities and issues to hand to the mock services — in
  tests, and as the demo data the installation runs on when it has no
  credentials."
  (:require [odato.pull-request.interface :as prs]
            [odato.time.interface :as t]))

(defn observation
  "One pull request as a source reported it."
  [{:keys [owner name number] :or {owner "acme" name "widget"} :as m}]
  {:repository {:owner owner :name name}
   :number (or number 1)
   :facts (prs/facts (merge {:title "Ship it"
                             :url (str "https://github.com/" owner "/" name
                                       "/pull/" (or number 1))
                             :author "ada"
                             :state :open}
                            (dissoc m :owner :name :number)))})

(defn commits [n rollup at]
  (vec (for [i (range n)]
         {:sha (str "sha-" i) :committed-at (- at (* i (t/hours 1)))
          :check-rollup rollup})))

(defn approval [reviewer at sha]
  {:reviewer reviewer :verdict :approved :submitted-at at :reviewed-sha sha})

(defn change-request [reviewer at sha]
  {:reviewer reviewer :verdict :changes-requested :submitted-at at :reviewed-sha sha})

(defn activity
  "One thing the operator did. It says what was done, never how long."
  [{:keys [source happened-at reference title issue-keys]}]
  {:source source
   :happened-at happened-at
   :reference reference
   :title title
   :issue-keys (set issue-keys)})

(defn worklog
  "One worklog Jira holds — the operator's, unless an `:author` says otherwise.
  It goes in the mock Jira's `:time-logged`, not among the activities: logging
  time is not something done."
  [{:keys [id key at logged-ms author]}]
  (cond-> {:id id :key key :at at :logged-ms logged-ms}
    author (assoc :author author)))
