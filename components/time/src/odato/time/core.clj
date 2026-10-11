(ns odato.time.core
  (:require [clojure.string :as str])
  (:import (java.time Instant OffsetDateTime ZoneOffset ZonedDateTime LocalDate
                      DayOfWeek)
           (java.time.format DateTimeFormatter)))

(def ^:const minute 60000)
(def ^:const hour (* 60 minute))
(def ^:const day (* 24 hour))

;; ---------------------------------------------------------------- the clock

(defprotocol Clock
  (-now-ms [this]))

(defrecord SystemClock []
  Clock
  (-now-ms [_] (System/currentTimeMillis)))

(defrecord FixedClock [state]
  Clock
  (-now-ms [_] @state))

(defn system-clock [] (->SystemClock))

(def ^:private offset-without-colon
  ;; Jira writes its instants this way — `2026-09-15T09:00:00.000+0000` — which
  ;; ISO-8601 allows and `Instant/parse` refuses.
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSSZ"))

(defn parse-instant [s]
  (when (and s (not (str/blank? (str s))))
    (let [s (str s)]
      (or (try (.toEpochMilli (Instant/parse s)) (catch Exception _ nil))
          (try (.toEpochMilli (.toInstant (OffsetDateTime/parse s offset-without-colon)))
               (catch Exception _ nil))))))

(defn fixed-clock [ms-or-string]
  (->FixedClock (atom (if (number? ms-or-string)
                        (long ms-or-string)
                        (or (parse-instant ms-or-string)
                            (throw (ex-info "not an instant" {:value ms-or-string})))))))

(defn now-ms ^long [clock] (-now-ms clock))

(defn advance! [clock delta-ms]
  (swap! (:state clock) + (long delta-ms))
  clock)

(defn set-now! [clock ms]
  (reset! (:state clock) (long ms))
  clock)

;; ------------------------------------------------------------------- instants

(defn format-instant [ms]
  (when ms (.toString (Instant/ofEpochMilli (long ms)))))

(defn- ^ZonedDateTime utc [ms]
  (.atZone (Instant/ofEpochMilli (long ms)) ZoneOffset/UTC))

;; ----------------------------------------------------------------- UTC days

(defn utc-midnight-of ^long [ms]
  (-> (utc ms) (.toLocalDate) (.atStartOfDay ZoneOffset/UTC) (.toInstant) (.toEpochMilli)))

(def ^:private day-names
  {DayOfWeek/MONDAY :monday DayOfWeek/TUESDAY :tuesday DayOfWeek/WEDNESDAY :wednesday
   DayOfWeek/THURSDAY :thursday DayOfWeek/FRIDAY :friday DayOfWeek/SATURDAY :saturday
   DayOfWeek/SUNDAY :sunday})

(defn day-of-week [ms] (day-names (.getDayOfWeek (utc ms))))

(def ^:private working-days #{:monday :tuesday :wednesday :thursday :friday})

(defn working-day? [ms] (contains? working-days (day-of-week ms)))

(defn falls-within-day? [ms day-starts-at]
  (and (some? ms)
       (>= (long ms) (long day-starts-at))
       (< (long ms) (+ (long day-starts-at) day))))

(def ^:private day-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
(def ^:private label-formatter (DateTimeFormatter/ofPattern "EEE d MMM"))

(defn format-day [day-starts-at]
  (.format (.toLocalDate (utc day-starts-at)) day-formatter))

(defn parse-day [s]
  (when (and s (not (str/blank? (str s))))
    (try (-> (LocalDate/parse (str s) day-formatter)
             (.atStartOfDay ZoneOffset/UTC) (.toInstant) (.toEpochMilli))
         (catch Exception _ nil))))

(defn day-label [day-starts-at]
  (.format (.toLocalDate (utc day-starts-at)) label-formatter))

;; ------------------------------------------------------- the timesheet window

(defn working-days-ending-at
  "The n working days ending with today when today is one, otherwise with the
  last working day before it. Oldest first."
  [now-ms n]
  (loop [d (utc-midnight-of now-ms)
         acc ()]
    (cond
      (= (count acc) n) (vec acc)
      (working-day? d) (recur (- d day) (conj acc d))
      :else (recur (- d day) acc))))

(defn within-timesheet-window? [day-starts-at now-ms n]
  (boolean (some #(= (long %) (long day-starts-at))
                 (working-days-ending-at now-ms n))))

;; ------------------------------------------------------ the collection window

(defn within-collection-window?
  "Inside the window means: on one of its active days, at an hour at or after
  it opens and before it closes, read in UTC."
  [now-ms {:keys [opens-hour closes-hour active-days]}]
  (let [z (utc now-ms)
        h (.getHour z)]
    (boolean (and (contains? (set active-days) (day-of-week now-ms))
                  (>= h (long opens-hour))
                  (< h (long closes-hour))))))

(defn next-window-opening
  "Later today if the window has not opened yet, otherwise the first active day
  that follows. Scans at most a fortnight, which covers any active-day set."
  [now-ms {:keys [opens-hour active-days] :as window}]
  (let [active (set active-days)
        midnight (utc-midnight-of now-ms)
        opening-on (fn [d] (+ d (* (long opens-hour) hour)))]
    (loop [d midnight
           budget 15]
      (let [opens-at (opening-on d)]
        (cond
          (zero? budget) (+ now-ms day)         ; no active day at all: try again tomorrow
          (and (contains? active (day-of-week d)) (> opens-at now-ms)) opens-at
          :else (recur (+ d day) (dec budget)))))))

;; ------------------------------------------------------------------ durations

(def ^:private duration-unit
  {"w" (* 7 day) "d" day "h" hour "m" minute "min" minute "mn" minute "s" 1000})

(def ^:private amount-re #"(?i)^(\d+(?:[.,]\d+)?)(w|d|h|min|mn|m|s)?")
(def ^:private clock-re #"^(\d{1,3}):([0-5]\d)$")

(defn parse-duration
  "The milliseconds `text` means, or nil when it says no length of time.

  A bare number is minutes, which is how `45` reads on a timesheet. The whole
  text has to be amounts and units: `1h of meetings` says a length of time and
  then some, so it says none."
  [text]
  (when (and text (not (str/blank? (str text))))
    (let [compact (str/replace (str/trim (str text)) #"\s+" "")]
      (if-let [[_ h m] (re-matches clock-re compact)]
        (+ (* (parse-long h) hour) (* (parse-long m) minute))
        (loop [remaining compact
               total 0.0
               seen 0]
          (if (empty? remaining)
            (when (pos? seen) (long (Math/round total)))
            (if-let [[whole amount unit] (re-find amount-re remaining)]
              (recur (subs remaining (count whole))
                     (+ total (* (Double/parseDouble (str/replace amount "," "."))
                                 (get duration-unit (some-> unit str/lower-case) minute)))
                     (inc seen))
              nil)))))))

(defn readable-duration? [text]
  (let [ms (parse-duration text)]
    (boolean (and ms (pos? ms)))))

(defn format-duration [ms]
  (let [ms (long (or ms 0))]
    (if (<= ms 0)
      "0m"
      (let [h (quot ms hour)
            m (quot (rem ms hour) minute)]
        (cond
          (and (pos? h) (pos? m)) (str h "h " m "m")
          (pos? h) (str h "h")
          :else (str m "m"))))))

(defn humanize-age [ms]
  (let [ms (long (or ms 0))]
    (cond
      (< ms minute) "just now"
      (< ms hour) (str (quot ms minute) "m")
      (< ms day) (str (quot ms hour) "h")
      :else (str (quot ms day) "d"))))
