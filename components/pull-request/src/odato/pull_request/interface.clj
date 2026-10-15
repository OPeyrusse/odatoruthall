(ns odato.pull-request.interface
  "What gitdesk knows about a pull request, and every judgement it makes about
  one.

  A pull request is an identity — which repository, which number — and a set of
  facts: what GitHub last said about it. The facts are replaced wholesale by
  each collection; nothing here decides when that happens, only what the facts
  mean.

  Every classification reads the thresholds in force at the moment it is read,
  which is what makes a threshold change reclassify the snapshot already on
  screen without fetching anything."
  (:require [odato.pull-request.core :as core]))

(defn ident
  "`\"owner/name#number\"` — one pull request per repository and number."
  [repository number]
  (core/ident repository number))

(defn facts
  "Normalises what a client reported into the facts the snapshot holds. Used by
  the clients, by the demo data and by tests, so they cannot drift apart."
  [m]
  (core/facts m))

(defn view
  "One pull request with every judgement made about it, against the
  configuration in force and the clock as it stands.

  Adds: `:head-commit` `:head-check` `:decisive-reviews` `:approvals`
  `:change-requests` `:approved?` `:awaiting-rereview` `:open?` `:age`
  `:aging?` `:conflicting?` `:difficult?` `:stuck?` `:merging?`
  `:recently-merged?`."
  [pull-request config now-ms]
  (core/view pull-request config now-ms))

(defn of-interest?
  "A pull request is of interest when its author or one of its assignees is the
  operator or a tracked member. Anything else is not recorded."
  [facts {:keys [members operator]}]
  (core/of-interest? facts members operator))

;; ----------------------------------------------------------- slices by person

(defn authored-by? [view login] (core/authored-by? view login))
(defn assigned-to? [view login] (core/assigned-to? view login))
(defn review-requested-from? [view login] (core/review-requested-from? view login))
(defn addressed-for? [view login] (core/addressed-for? view login))
(defn involves? [view login] (core/involves? view login))
(defn drafted-by? [view login] (core/drafted-by? view login))

(defn by-recent-activity
  "Most recent activity first, which is how every list on the dashboard reads."
  [views]
  (core/by-recent-activity views))
