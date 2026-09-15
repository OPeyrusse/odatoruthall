(ns odato.work-item.interface
  "The Jira issues the operator logs time against.

  A work item is an identity — the issue key — and a set of facts: what Jira
  last said about the issue. Items reach the installation two ways, and both
  end here: the operator names one by key, or a day scan finds an activity that
  names it. Either way the installation asks Jira, and what Jira answers
  becomes the facts.

  A key Jira does not know is not a work item and never becomes an entry on a
  day."
  (:require [odato.work-item.core :as core]))

(defn known
  "The work item under `key`, or nil."
  [sys issue-key]
  (core/known sys issue-key))

(defn all [sys] (core/all sys))

(defn resolve!
  "Asks Jira about a key, unless the key is already known — the facts in hand
  are the facts until the next lookup refreshes them.

  Answers `{:known {...}}`, or `{:failure :unknown-issue|:not-permitted|:lookup-unavailable}`.
  Announces `:work-item/known` or `:work-item/unavailable` either way."
  [sys issue-key]
  (core/resolve! sys issue-key))

(defn refresh!
  "Asks Jira about a key even when it is known, and takes the answer as the
  facts."
  [sys issue-key]
  (core/refresh! sys issue-key))

(defn lookup-state
  "What became of the last question put to Jira about this key: `:requested`,
  `:resolved`, `:failed`, or nil if it was never asked."
  [sys issue-key]
  (core/lookup-state sys issue-key))

(defn valid-key?
  "Whether a string could be an issue key at all. A key that could not be one
  is not worth a round trip."
  [s]
  (core/valid-key? s))
