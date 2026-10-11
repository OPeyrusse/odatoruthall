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
  "One thing the operator did. Only a worklog carries `:logged-ms`: the time
  Jira already holds under it."
  [{:keys [source happened-at reference title issue-keys logged-ms]}]
  (cond-> {:source source
           :happened-at happened-at
           :reference reference
           :title title
           :issue-keys (set issue-keys)}
    logged-ms (assoc :logged-ms logged-ms)))

(defn worklog
  "One worklog Jira holds for the operator."
  [{:keys [id key at logged-ms]}]
  (activity {:source :jira-worklogs
             :happened-at at
             :reference (str id)
             :title (str key " — logged in Jira")
             :issue-keys #{key}
             :logged-ms logged-ms}))
