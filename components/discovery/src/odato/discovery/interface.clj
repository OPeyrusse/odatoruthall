(ns odato.discovery.interface
  "Finding out what the operator worked on, on one day.

  The operator picks a day and asks for it to be scanned. The scan looks at
  what they did that day — on GitHub their commits, their comments, the pull
  requests they opened and the ones they reviewed; on Jira the comments they
  wrote, the issues they created and the issues they closed — one source at a
  time, paginating to exhaustion, with quota exhaustion a wait rather than a
  failure, exactly as gitdesk collects.

  Every activity names the issues it belongs to, and each named issue becomes a
  task on that day with no time on it: the scan says *what* was worked on,
  never *how long*.

  Two promises make a scan safe to ask for twice:

    * a scan only ever adds — it never removes a task or an activity from a
      day, never records the same activity twice, and never touches the time
      the operator entered, submitted or had acknowledged;
    * a scan is asked for, one day at a time. Nothing scans on a schedule."
  (:require [odato.discovery.core :as core]
            [odato.discovery.scan :as scan]))

(defn request-scan!
  "ScanRequested. `{:started true :scan <id>}`, or
  `{:started false :reason :credential-unavailable|:already-scanning|:day-outside-window}`."
  [sys day-starts-at]
  (scan/request-scan! sys day-starts-at))

(defn run-scan!
  "Runs a scan to completion on the calling thread."
  [sys scan-id]
  (scan/run-scan! sys scan-id))

(defn scan!
  "Asks for a day to be scanned and scans it here and now."
  [sys day-starts-at]
  (scan/scan! sys day-starts-at))

(defn start!
  "Asks for a day to be scanned and scans it on a virtual thread."
  [sys day-starts-at]
  (scan/start! sys day-starts-at))

(defn attach!
  "OperatorAttachesActivity. An orphan is attached by naming the task it
  belongs to, and from there it is an activity like any other: the task lands
  on the day if it was not there, and the activity joins the lines grouped
  under it. The key the operator gave is not added to what the activity
  *names* — that stays what the scan read."
  [sys activity-id issue-key]
  (scan/attach! sys activity-id issue-key))

;; ------------------------------------------------------------------ reading

(defn activities-on
  "Every line the day carries, most recent last."
  [sys day-starts-at]
  (core/activities-on sys day-starts-at))

(defn lines-for
  "The lines grouped under one task on one day."
  [sys day-starts-at issue-key]
  (core/lines-for sys day-starts-at issue-key))

(defn orphans-on
  "The lines belonging to no task: kept with their title and their moment
  rather than dropped."
  [sys day-starts-at]
  (core/orphans-on sys day-starts-at))

(defn activity [sys activity-id] (core/activity sys activity-id))

(defn active-scans [sys] (core/active-scans sys))

(defn scanning? [sys] (core/scanning? sys))

(defn last-scan
  "The most recent scan of a day, with what it found and what did not answer."
  [sys day-starts-at]
  (core/last-scan sys day-starts-at))

(def sources
  "The seven sources a scan reads, in the order it reads them."
  core/sources)
