(ns odato.jira.interface
  "The Jira side of the world, behind one seam.

  Jira answers three kinds of question here: what an issue is, what the
  operator did on a day, and what became of a worklog. `client` asks the real
  Jira; `mock-client` answers from a script, which is how the installation runs
  and is tested without a Jira token."
  (:require [odato.jira.client :as client]
            [odato.jira.mock :as mock]
            [odato.jira.protocol :as protocol]))

(defn client
  "A client against the real Jira Cloud. `:base-url` is the site
  (`https://acme.atlassian.net`), `:email` and `:token` the operator's
  credential."
  [opts]
  (client/client opts))

(defn mock-client [script] (mock/client script))

(defn whoami
  "`{:login ...}`, or `{:failure ...}`."
  [c]
  (protocol/-whoami c))

(defn fetch-issue
  "What Jira says about one key: `{:facts {:summary ... :status ... :url ...
  :is-closed ...}}`, or `{:failure :unknown-issue|:not-permitted|:lookup-unavailable}`."
  [c issue-key]
  (protocol/-fetch-issue c issue-key))

(defn fetch-activity
  "One page of one Jira activity source, for one UTC day.

  Takes `{:source :jira-worklogs|:jira-comments|:jira-issues-created|:jira-issues-closed
           :day-starts-at <ms> :cursor <string|nil> :login <the operator>}`,
  and answers `{:activities [...] :next-cursor ...}` or `{:failure ...}`.

  `:jira-worklogs` is the one source whose activities carry `:logged-ms`: the
  time Jira already holds for that issue on that day, under one worklog. It is
  the operator's own — somebody else's time on the same issue is not read back."
  [c request]
  (protocol/-fetch-activity c request))

(defn write-worklog
  "Creates the worklog, or updates the one the entry already holds.

  Takes `{:issue \"ABC-1\" :worklog-id <string|nil> :started-at <ms>
           :duration-ms <long>}` and answers `{:worklog-id ...}` or
  `{:failure :jira-unreachable|:worklog-refused}`."
  [c request]
  (protocol/-write-worklog c request))

(defn delete-worklog
  "Withdraws a worklog. `{:deleted true}` or `{:failure ...}`."
  [c request]
  (protocol/-delete-worklog c request))

(defn worklogs
  "What a mock client holds, keyed by worklog id. Nothing for a real one."
  [c]
  (mock/worklogs c))

(defn calls
  "What a mock client was asked, in order. Nothing for a real one."
  [c]
  (mock/calls c))

(def activity-sources
  "What the operator did on Jira, as a day scan reads it."
  [:jira-worklogs :jira-comments :jira-issues-created :jira-issues-closed])
