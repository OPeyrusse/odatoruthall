(ns odato.time.interface
  "Time, in UTC, everywhere.

  The whole installation has one rule about time: a day is a UTC calendar day,
  the collection window is read in UTC, and the timesheet window is derived
  from the clock rather than stored. Everything that needs to know what time it
  is asks a `Clock`, so a test can hold the clock still."
  (:require [odato.time.core :as core]))

;; ---------------------------------------------------------------- the clock

(defn system-clock
  "The real clock."
  []
  (core/system-clock))

(defn fixed-clock
  "A clock frozen at `ms`, or at the instant `s` (ISO-8601) names. It can be
  moved with `advance!` and `set-now!`, which is how a test walks a cadence,
  a quota reset or a sliding timesheet window forward."
  [ms-or-string]
  (core/fixed-clock ms-or-string))

(defn now-ms
  "Epoch milliseconds, as this clock sees them."
  [clock]
  (core/now-ms clock))

(defn advance! [clock delta-ms] (core/advance! clock delta-ms))
(defn set-now! [clock ms] (core/set-now! clock ms))

;; ------------------------------------------------------------------- instants

(defn parse-instant
  "Epoch milliseconds for an ISO-8601 instant, or nil when it says nothing."
  [s]
  (core/parse-instant s))

(defn format-instant
  "The ISO-8601 instant for epoch milliseconds."
  [ms]
  (core/format-instant ms))

(defn format-minute
  "`2026-09-15 10:30 UTC` for epoch milliseconds, or nil: an instant as the
  operator reads it."
  [ms]
  (core/format-minute ms))

;; ----------------------------------------------------------------- UTC days

(defn utc-midnight-of
  "The UTC midnight the day containing `ms` opens on."
  [ms]
  (core/utc-midnight-of ms))

(defn day-of-week
  "`:monday` … `:sunday`, in UTC."
  [ms]
  (core/day-of-week ms))

(defn working-day?
  "Monday to Friday, in UTC."
  [ms]
  (core/working-day? ms))

(defn falls-within-day?
  "Whether `ms` happened on the UTC day opening at `day-starts-at`."
  [ms day-starts-at]
  (core/falls-within-day? ms day-starts-at))

(defn format-day
  "The `YYYY-MM-DD` of a UTC day."
  [day-starts-at]
  (core/format-day day-starts-at))

(defn parse-day
  "The UTC midnight a `YYYY-MM-DD` names, or nil."
  [s]
  (core/parse-day s))

(defn day-label
  "`Mon 15 Sep` — how a day reads on the timesheet."
  [day-starts-at]
  (core/day-label day-starts-at))

;; ------------------------------------------------------- the timesheet window

(defn working-days-ending-at
  "The `n` working days ending with today when today is a working day, and
  with the last working day before it when it is not. Oldest first."
  [now-ms n]
  (core/working-days-ending-at now-ms n))

(defn within-timesheet-window?
  "Whether the day opening at `day-starts-at` is one of the `n` working days
  the timesheet shows."
  [day-starts-at now-ms n]
  (core/within-timesheet-window? day-starts-at now-ms n))

;; ------------------------------------------------------ the collection window

(defn within-collection-window?
  "Whether a scheduled collection may start now: on one of the window's active
  days, at an hour at or after it opens and before it closes, read in UTC."
  [now-ms window]
  (core/within-collection-window? now-ms window))

(defn next-window-opening
  "The next moment the window opens: later today if it has not opened yet,
  otherwise the first active day that follows."
  [now-ms window]
  (core/next-window-opening now-ms window))

;; ------------------------------------------------------------------ durations

(defn readable-duration?
  "Whether the text says a length of time at all. `\"1h 30m\"` does; `\"soon\"`
  does not, and neither does `\"0m\"` — nothing is not a length of time."
  [text]
  (core/readable-duration? text))

(defn parse-duration
  "The milliseconds `text` means, or nil when it says no length of time.
  Understands `1h30m`, `1h 30m`, `90m`, `1.5h`, `45`, `2d`, `1:30`."
  [text]
  (core/parse-duration text))

(defn format-duration
  "`5400000` reads back as `1h 30m`."
  [ms]
  (core/format-duration ms))

(defn humanize-age
  "How old something is, in words: `just now`, `12m`, `3h`, `4d`."
  [ms]
  (core/humanize-age ms))

;; -------------------------------------------------------------- plain amounts

(def minute core/minute)
(def hour core/hour)
(def day core/day)
(defn minutes [n] (* n core/minute))
(defn hours [n] (* n core/hour))
(defn days [n] (* n core/day))
