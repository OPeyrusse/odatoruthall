(ns odato.worklog.interface
  "The operator's days, the tasks they worked on, the time they put against
  each, and what it means for that time to have reached Jira.

  A day is a UTC calendar day and holds one entry per task worked that day. An
  entry carries the time exactly as the operator typed it — \"1h 30m\" — and
  the duration that text means. Text that means nothing is refused and changes
  nothing.

  Time is drafted here and submitted to Jira on the operator's word:

    drafted       time is entered here, and Jira does not have it
    submitted     it is on its way to Jira
    acknowledged  Jira holds it, under a worklog of its own
    rejected      Jira refused it; the time entered is still here

  Editing an acknowledged entry drafts it again and keeps the Jira worklog it
  already has, so the next submission updates that worklog rather than logging
  the time twice.

  Jira is the authority on what Jira holds: a day scan reads, on each task the
  day carries, the operator's own worklogs for that day, and an entry holding
  no time of its own takes them. An entry holding time the operator typed keeps
  it and says what Jira holds beside it, so the two are never silently added
  together nor silently replaced. Logging time is not work found: a worklog on
  a task the day does not carry puts nothing on the day.

  Unlike everything gitdesk collects, the worklog outlives the process."
  (:require [odato.worklog.core :as core]
            [odato.worklog.submit :as submit]))

;; The number of days, the working days and the bounds on one entry are spec
;; constants, not settings: the operator's Settings surface stays gitdesk's.
(def config core/config)

;; ------------------------------------------------------------------ reading

(defn window
  "OperatorOpensTimesheet. The five working days the timesheet shows, oldest
  first — they always exist, whether or not anything has been put on them."
  [sys]
  (core/window! sys))

(defn day
  "One day in full, or nil when it is not one the timesheet shows."
  [sys day-starts-at]
  (core/day sys day-starts-at))

(defn entry [sys entry-id] (core/entry sys entry-id))

(defn week-total [sys] (core/week-total sys))

(defn key-order
  "Ticket order, as a sort key: by project, then by ticket number taken as a
  number — PIVOT-2 before PIVOT-10."
  [issue-key]
  (core/key-order issue-key))

(defn within-window? [sys day-starts-at] (core/within-window? sys day-starts-at))

(defn ensure-day!
  "The UTC day opening at `day-starts-at`, created if it was not there. A day
  exists because something was put on it — a scan, an entry, a submission — and
  because the timesheet shows it."
  [sys day-starts-at]
  (core/ensure-day! sys day-starts-at))

(defn mark-scanned!
  "A day records when it was last scanned, whether or not the scan found
  anything: a day scanned and empty is a different thing from a day never
  scanned."
  [sys day-starts-at at]
  (core/mark-scanned! sys day-starts-at at))

;; ------------------------------------------------- putting a task on a day

(defn name-task!
  "OperatorNamesTask. The operator names a task by its key; what the task *is*
  comes from Jira. A key Jira does not know puts nothing on the day.

  `{:ok true :entry ...}` or `{:ok false :fault :unknown-task|:day-outside-window|:task-already-on-day}`."
  [sys day-starts-at issue-key]
  (core/name-task! sys day-starts-at issue-key))

(defn add-task!
  "OperatorAddsTask, for a work item already known."
  [sys day-starts-at issue-key]
  (core/add-task! sys day-starts-at issue-key :added-by-operator))

(defn jira-time-on-task!
  "JiraTimeOnTask. What Jira holds of the operator's own time on one task, on
  one day, as a scan read it — `{:logged-ms … :worklogs n :worklog id-or-nil}`
  — replacing what the last scan read.

  An entry holding nothing the operator typed takes Jira's time and reads as
  acknowledged; one holding time they typed and have not sent keeps it and
  records Jira's beside it. No worklog says Jira holds nothing. A task the day
  does not carry stays off it: `{:ok false :fault :task-not-on-day}`."
  [sys day-starts-at issue-key time]
  (core/jira-time-on-task! sys day-starts-at issue-key time))

(defn task-found-on-day!
  "TaskFoundOnDay. A scan adds tasks and takes none away: a task the day
  already carries keeps its entry, its origin and its time."
  [sys day-starts-at issue-key]
  (core/add-task! sys day-starts-at issue-key :discovered))

;; ------------------------------------------------------------ entering time

(defn enter-time!
  "OperatorEntersTime. The text is kept as typed alongside the duration it
  means. A refused entry changes nothing.

  `{:ok true :entry ...}` or
  `{:ok false :fault :entry-in-flight|:day-outside-window|:unreadable-duration|:duration-out-of-bounds}`."
  [sys entry-id text]
  (core/enter-time! sys entry-id text))

(defn remove-entry!
  "OperatorRemovesEntry. Time Jira already holds is not forgotten by dropping
  the row that carries it: the worklog is withdrawn from Jira first, and the
  entry goes when Jira says it is gone."
  [sys entry-id]
  (submit/remove-entry! sys entry-id))

;; ------------------------------------------------------ submitting to Jira

(defn submit-day!
  "OperatorSubmitsDay. Only what Jira does not already hold is sent; an entry
  Jira holds is updated under the same worklog, never added a second time.

  A day is scanned or submitted, never both at once: a day being scanned, or
  already being submitted, is not submitted.

  `{:ok true :submitted n :acknowledged n :rejected n}` or
  `{:ok false :fault :credential-unavailable|:day-being-scanned|:already-submitting|:nothing-to-submit}`."
  [sys day-starts-at]
  (submit/submit-day! sys day-starts-at))

(defn busy-with
  "What holds the day: `:scan`, `:submission`, or nil when it is free."
  [sys day-starts-at]
  (core/busy-with sys day-starts-at))

(defn occupy-day!
  "Takes the day for `process` (`:scan` or `:submission`), so that nothing
  else does. `{:ok true}`, or `{:ok false :busy-with p}`."
  [sys day-starts-at process]
  (core/occupy! sys day-starts-at process))

(defn release-day!
  "Frees the day, if `process` is what holds it."
  [sys day-starts-at process]
  (core/release! sys day-starts-at process))
