(ns odato.ui.worklog
  "The timesheet — five working days at a time, what each adds up to, and what
  it is made of. The days are columns; each ticket worked in them is one row
  across all five, the ones the operator named first, then the most recently
  touched.

  A day reads as one row per task. Under a task sit the lines that led to it:
  the pull requests, the reviews, the comments, the issues touched that day.
  Several lines under one task is the normal case, and there is still exactly
  one place to put the time: the task. Lines that belong to no task are shown
  apart, with their titles, and can be attached to a task from here."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [odato.access.interface :as access]
            [odato.discovery.interface :as discovery]
            [odato.time.interface :as t]
            [odato.ui.parts :as parts]
            [odato.worklog.interface :as worklog]))

(defn- day-href [day] (str "/worklog/" (t/format-day (:starts-at day))))

(defn- status-chip [entry]
  (case (:status entry)
    :drafted (if (:filled? entry)
               (parts/chip "drafted" :amber)
               (parts/chip "no time" :neutral))
    :submitted (parts/chip "on its way" :sky)
    :acknowledged (parts/chip (if (> (:jira-worklog-count entry) 1)
                                (str "in Jira · " (:jira-worklog-count entry) " worklogs")
                                "in Jira")
                              :green)
    :rejected (parts/chip (str "refused: " (parts/humanise (:last-failure entry))) :red)
    nil))

(defn- jira-time-note
  "What Jira holds, when it is not what the entry says. Neither is added to the
  other and neither is thrown away: the operator decides which to keep."
  [entry]
  (when (:differs-from-jira? entry)
    [:p {:class "mt-1 text-xs text-amber-700 dark:text-amber-400"}
     (str "Jira holds " (t/format-duration (:jira-logged-ms entry))
          " on this task today"
          (when (> (:jira-worklog-count entry) 1)
            (str ", under " (:jira-worklog-count entry) " worklogs"))
          ". Submitting the day sends "
          (t/format-duration (:duration-ms entry)) " instead.")]))

(defn- command-form
  "Every command is a real form that posts, and Datastar posts it without a
  page load when it is there. The dashboard works either way."
  [{:keys [action class]} & content]
  [:form (cond-> {:method "post"
                  :action action
                  :data-on-submit (str "@post('" action "', {contentType: 'form'})")}
           class (assoc :class class))
   content])

;; ------------------------------------------------------------- a day at work

;; A day is scanned or submitted, never both at once and neither twice: while
;; one runs, the day's commands wait for it.

(def ^:private in-flight-text
  "A day being scanned is not final yet: its tickets and their time read a
  shade lighter until the scan ends."
  "text-slate-600 dark:text-slate-400")

(def ^:private in-flight-input
  "The time field, in the same shade."
  (-> parts/input
      (str/replace "text-slate-900 " "text-slate-600 ")
      (str/replace "dark:text-slate-100" "dark:text-slate-400")))

(def ^:private busy-title
  {:scan "This day is being scanned"
   :submission "This day is being submitted to Jira"})

(defn- scan-progress
  "How far a running scan has gone: the sources done out of all of them."
  [scan]
  (str (- (count (:tasks scan)) (:queued scan)) "/" (count (:tasks scan))))

(defn- scan-chip
  "One running scan, said in a chip. `label` names what is being scanned."
  [label scan]
  (parts/chip (str label " " (scan-progress scan)
                   (when (:waiting-on-quota? scan) " · waiting on GitHub quota"))
              (if (:waiting-on-quota? scan) :amber :sky)))

(defn- scan-button
  "The scan command, waiting while the day is scanned or submitted."
  [day label]
  (command-form {:action (str (day-href day) "/scan")}
                [:button {:class parts/button :type "submit"
                          :disabled (some? (:busy-with day))
                          :title (busy-title (:busy-with day))}
                 label]))

(defn- submit-button
  "The submit command: there must be time to send, and the day must not be
  busy being scanned or submitted already."
  [day]
  (command-form {:action (str (day-href day) "/submit")}
                [:button {:class (if (and (:has-time-to-submit day) (not (:busy-with day)))
                                   parts/primary-button
                                   parts/button)
                          :type "submit"
                          :disabled (or (not (:has-time-to-submit day))
                                        (some? (:busy-with day)))
                          :title (busy-title (:busy-with day))}
                 "Submit to Jira"]))

;; ------------------------------------------------------------- the five days

;; The five days are columns, and the tickets worked in them are rows that span
;; all five: a ticket is read once, with room for its title, and under it what
;; each day holds of it.

(defn last-interactions
  "The latest moment of a line under each ticket, across `activities`. Logging
  time is not an interaction, and is not a line: worklogs move nothing here."
  [activities]
  (reduce (fn [acc {:keys [attributed-keys happened-at]}]
            (if happened-at
              (reduce #(update %1 %2 (fnil max happened-at) happened-at)
                      acc attributed-keys)
              acc))
          {}
          activities))

(defn ticket-rows
  "One row per ticket the days carry: the ticket, whether the operator named it
  themselves on every day it is on, its last interaction, and its entry on each
  day, keyed by the day's start.

  The tickets the operator named come first, in ticket order; the others
  follow, the most recently touched first, then in ticket order; a ticket no
  line dates reads last."
  [days last-interaction]
  (->> (mapcat :entries days)
       (group-by :work-item-key)
       (map (fn [[k entries]]
              {:work-item-key k
               :work-item (:work-item (first entries))
               :named? (every? #(= :added-by-operator (:origin %)) entries)
               :last-interaction-at (get last-interaction k)
               :entries (into {} (map (juxt :day-starts-at identity)) entries)}))
       (sort-by (fn [{:keys [work-item-key named? last-interaction-at]}]
                  [(cond named? 0 last-interaction-at 1 :else 2)
                   (if (and (not named?) last-interaction-at) (- last-interaction-at) 0)
                   (worklog/key-order work-item-key)]))
       vec))

(defn- cell-class
  "A cell of the grid: a rule between the days, and today a shade apart."
  [index today?]
  (str "min-w-0 px-3 py-2"
       (when (pos? index) " border-l border-slate-200 dark:border-slate-800")
       (when today? " bg-sky-50/60 dark:bg-sky-950/30")))

(defn- day-heading [today? index day]
  [:div {:class (str (cell-class index today?)
                     " flex items-start justify-between gap-2 py-3")}
   [:div {:class "min-w-0"}
    [:a {:href (day-href day) :class "text-sm font-semibold hover:underline"}
     (t/day-label (:starts-at day))]
    [:div {:class parts/faint} (t/format-day (:starts-at day))]]
   [:div {:class "text-lg font-semibold tabular-nums"}
    (t/format-duration (:total-logged day))]])

(defn- day-commands
  "The cell under a day's heading: what can be done to that day, and where it
  stands."
  [sys now-ms today? index day]
  (let [orphans (discovery/orphans-on sys (:starts-at day))]
    [:div {:class (str (cell-class index today?)
                       " flex flex-col items-start gap-2 border-t border-slate-200 "
                       "dark:border-slate-800")}
     [:div {:class "flex flex-wrap gap-2"}
      (scan-button day "Scan")
      (submit-button day)]
     [:div {:class "flex flex-wrap items-center gap-1.5"}
      (when (:is-settled day) (parts/chip "settled" :green))
      (when (pos? (count orphans))
        [:a {:href (day-href day)} (parts/chip (str (count orphans) " loose") :amber)])
      [:span {:class parts/faint}
       (if (:has-been-scanned day)
         (list "scanned " (parts/relative-time now-ms (:last-scanned-at day)))
         "never scanned")]]]))

(def ^:private ticket-heading-class
  "A ticket's title row: shaded and closed off from its day cells by a rule,
  and set apart from the ticket above by a heavier one."
  (str "col-span-5 flex min-w-0 items-baseline gap-2 px-3 py-1.5 text-sm "
       "border-t-2 border-t-slate-300 dark:border-t-slate-700 "
       "border-b border-b-slate-200 dark:border-b-slate-800 "
       "bg-slate-100/70 dark:bg-slate-800/50"))

(defn- ticket-heading [row]
  (let [k (:work-item-key row)]
    [:div {:class ticket-heading-class}
     (if-let [url (get-in row [:work-item :url])]
       [:a {:href url :target "_blank" :rel "noreferrer"
            :class "shrink-0 font-semibold hover:underline"}
        k]
       [:span {:class "shrink-0 font-semibold"} k])
     [:span {:class (str parts/muted " min-w-0 truncate")}
      (get-in row [:work-item :summary])]]))

(defn editor-attributes
  "What a ticket's cell on a day carries for the cell editor: the day, the
  ticket, and its time as typed, empty when the day does not carry it. A cell
  whose entry cannot be edited carries none of it, and a title saying why."
  [day k entry]
  (if (or (nil? entry) (:editable? entry))
    {:data-cell "" :data-day (t/format-day (:starts-at day)) :data-key k
     :data-time (or (:entered-text entry) "") :style "cursor: text"}
    {:title (if (= :submitted (:status entry))
              "On its way to Jira: it can be edited once Jira has answered."
              (str "Jira holds this task's time under " (:jira-worklog-count entry)
                   " worklogs; change them in Jira."))}))

(defn- ticket-cell
  "What one ticket holds on one day: its time and where that time stands with
  Jira, or nothing when the day does not carry it, and what the cell editor
  needs to open over it (`editor-attributes`)."
  [today? scanning? index day k entry]
  [:div (merge {:class (str (cell-class index today?) " flex flex-wrap items-center gap-1.5 text-sm")}
               (editor-attributes day k entry))
   (when entry
     (list
      [:span {:class (str "font-medium tabular-nums"
                          (when scanning? (str " " in-flight-text)))}
       (or (:entered-text entry) "—")]
      (status-chip entry)
      (when (:differs-from-jira? entry)
        [:span {:class "text-xs text-amber-700 dark:text-amber-400"
                :title (str "Submitting the day sends "
                            (t/format-duration (:duration-ms entry)) " instead.")}
         (str "Jira holds " (t/format-duration (:jira-logged-ms entry)))])))])

(def ^:private open-editor-js
  "Opens the editor over the cell double-clicked, holding the cell's time as
  the field's default value. Scrolling or resizing leaves the field, so it
  never floats over another cell."
  (str "document.addEventListener('dblclick', function (evt) {"
       " var cell = evt.target.closest('[data-cell]'); if (!cell) return;"
       " var r = cell.getBoundingClientRect(), form = document.getElementById('cell-editor'),"
       " field = form.elements.text;"
       " form.style.top = r.top + 'px'; form.style.left = r.left + 'px';"
       " form.style.width = r.width + 'px'; form.style.height = r.height + 'px';"
       " form.dataset.day = cell.dataset.day; form.elements.key.value = cell.dataset.key;"
       " field.defaultValue = cell.dataset.time; field.value = cell.dataset.time;"
       " form.style.display = 'flex'; field.focus(); field.select() });"
       "function leaveCell (evt) {"
       " var field = document.getElementById('cell-editor').elements.text;"
       " if (evt.target !== field && document.activeElement === field) field.blur() }"
       "window.addEventListener('scroll', leaveCell, true);"
       "window.addEventListener('resize', leaveCell);"))

(defn cell-editor
  "The timesheet's cell editor: leaving its field posts the text to the cell's
  day when it differs from the cell's; Enter leaves it, Escape leaves it
  unchanged."
  []
  (list
   [:script (h/raw open-editor-js)]
   [:form {:id "cell-editor" :style "display: none; position: fixed; z-index: 20"
           :class "items-center bg-white dark:bg-slate-950"
           :data-on-submit "ctx.el.elements.text.blur()"}
    [:input {:type "hidden" :name "key"}]
    [:input {:type "text" :name "text" :autocomplete "off" :placeholder "1h 30m"
             :aria-label "time"
             :class (str parts/input " h-full text-right tabular-nums")
             :data-on-blur (str "ctx.el.form.style.display = 'none'; "
                                "ctx.el.value !== ctx.el.defaultValue && "
                                "@post('/worklog/' + ctx.el.form.dataset.day + '/cells', "
                                "{contentType: 'form'})")
             :data-on-keydown (str "evt.key === 'Escape' && "
                                   "(ctx.el.value = ctx.el.defaultValue, ctx.el.blur())")}]]))

(defn body
  "The fragment SSE patches in place while a day scan runs."
  [sys]
  (let [now-ms (t/now-ms (:clock sys))
        today (t/utc-midnight-of now-ms)
        days (worklog/window sys)
        week-total (reduce + 0 (map :total-logged days))
        scanning (discovery/active-scans sys)
        scanned-days (set (map :day-starts-at scanning))
        rows (ticket-rows days (last-interactions
                                (mapcat #(discovery/activities-on sys (:starts-at %)) days)))
        indexed (map-indexed vector days)]
    [:div {:id "worklog" :class "flex flex-col gap-4"}
     [:section {:class parts/card}
      [:div {:class "flex flex-wrap items-center justify-between gap-3 px-4 py-3"}
       [:div
        [:h1 {:class "text-base font-semibold"} "Last five working days"]
        [:p {:class parts/muted}
         (str (t/format-day (:starts-at (first days))) " → "
              (t/format-day (:starts-at (last days))) ", UTC")]]
       [:div {:class "flex items-center gap-3"}
        (when-not (access/usable? sys :jira)
          (parts/chip "no Jira credential — nothing can be submitted" :amber))
        [:div {:class "text-right"}
         [:div {:class "text-2xl font-semibold tabular-nums"}
          (t/format-duration week-total)]
         [:div {:class "text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400"}
          "this week"]]]]
      ;; Every scan in flight, oldest first: several days are scanned side by
      ;; side, and each one leaves the list as it ends.
      (when (seq scanning)
        [:div {:class (str "flex flex-wrap items-center gap-2 border-t border-slate-200 "
                           "px-4 py-2 dark:border-slate-800")}
         [:span {:class parts/muted} "Scanning"]
         (for [scan scanning]
           [:a {:href (str "/worklog/" (t/format-day (:day-starts-at scan)))
                :class "hover:opacity-80"}
            (scan-chip (t/day-label (:day-starts-at scan)) scan)])])]
     [:section {:class (str parts/card " overflow-x-auto")}
      [:div {:class "grid min-w-[48rem] grid-cols-5"}
       (for [[i day] indexed]
         (day-heading (= today (:starts-at day)) i day))
       (for [[i day] indexed]
         (day-commands sys now-ms (= today (:starts-at day)) i day))
       (if (seq rows)
         (for [row rows]
           (list
            (ticket-heading row)
            (for [[i day] indexed]
              (ticket-cell (= today (:starts-at day))
                           (contains? scanned-days (:starts-at day))
                           i day (:work-item-key row)
                           (get-in row [:entries (:starts-at day)])))))
         [:div {:class "col-span-5 border-t border-slate-200 dark:border-slate-800"}
          (parts/empty-state "Nothing on these days yet. Name a task on a day, or scan it.")])]]]))

;; ------------------------------------------------------------------ one day

(defn- line-row [now-ms line]
  [:li {:class "flex flex-wrap items-baseline gap-x-2 gap-y-0.5 py-1 text-sm"}
   [:span {:class parts/faint} (parts/humanise (:source line))]
   [:span {:class "min-w-0 flex-1 truncate"} (:title line)]
   [:span {:class parts/faint} (parts/relative-time now-ms (:happened-at line))]])

(def ^:private lines-class
  (str "mt-2 divide-y divide-slate-100 border-l-2 border-slate-200 pl-3 "
       "dark:divide-slate-800/60 dark:border-slate-800"))

;; ------------------------------------------------------ what is left to fill
;;
;; Two toggles over the day's tasks narrow them to the ones still to be dealt
;; with: the drafted ones, the ones with no time, or both. Neither on, every
;; task shows. They are Datastar signals, off whenever the page is opened: they
;; live in the page, so flipping them fetches nothing, and they are declared
;; only if missing, so the day being patched in place leaves them as they were.

(def ^:private task-rule "border-t border-slate-200 dark:border-slate-800")

(def ^:private filter-signals
  {:drafted "onlyDrafted" :no-time "onlyNoTime"})

(defn entry-kind
  "Which of the toggles a task answers to: drafted with time, drafted without
  any, or neither."
  [entry]
  (when (= :drafted (:status entry))
    (if (:filled? entry) :drafted :no-time)))

(defn- shown-when
  "The Datastar expression showing a task of `kind` under the toggles: shown by
  its own toggle, or by none being on."
  [kind]
  (let [d (str "$" (:drafted filter-signals))
        n (str "$" (:no-time filter-signals))]
    (case kind
      :drafted (str d " || !" n)
      :no-time (str n " || !" d)
      (str "!" d " && !" n))))

(def ^:private toggle-off
  (str "border-slate-300 bg-white text-slate-700 hover:bg-slate-50 "
       "dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 dark:hover:bg-slate-700"))

(def ^:private toggle-on
  "border-sky-500 bg-sky-50 text-sky-700 dark:bg-sky-950 dark:text-sky-300")

(def ^:private toggle-class
  "A button whose colours say whether it is pressed: off until Datastar says
  otherwise."
  (str (-> parts/button
           (str/replace "border-slate-300 bg-white " "")
           (str/replace "text-slate-700 " "")
           (str/replace " hover:bg-slate-50" "")
           (str/replace "dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 " "")
           (str/replace "dark:hover:bg-slate-700 " ""))
       " " toggle-off))

(defn- filter-toggle [kind label n]
  (let [signal (filter-signals kind)]
    [:button {:type "button" :class toggle-class
              :data-on-click (str "$" signal " = !$" signal)
              :data-class (str "{'" toggle-on "': $" signal ", '"
                               toggle-off "': !$" signal "}")
              :data-attr-aria-pressed (str "$" signal)
              :aria-pressed "false"
              :title (str "Show only the tasks " label)}
     label [:span {:class "tabular-nums opacity-70"} (str n)]]))

(defn- filter-toggles [counts]
  [:div {:class "flex items-center gap-2"
         :data-signals__ifmissing (str "{" (:drafted filter-signals) ": false, "
                                       (:no-time filter-signals) ": false}")}
   (filter-toggle :drafted "drafted" (get counts :drafted 0))
   (filter-toggle :no-time "no time" (get counts :no-time 0))])

(defn- nothing-left-when
  "The expression showing the day has nothing under the toggles that are on."
  [counts]
  (let [d (str "$" (:drafted filter-signals))
        n (str "$" (:no-time filter-signals))]
    (str "(" d " || " n ") && "
         "(" d " ? " (get counts :drafted 0) " : 0) + "
         "(" n " ? " (get counts :no-time 0) " : 0) === 0")))

(defn- task-block
  "One group per task: the ticket, the single time field, and the lines that
  led to it."
  [sys now-ms day scanning entry]
  (let [lines (discovery/lines-for sys (:starts-at day) (:work-item-key entry))
        key-class (str "font-semibold" (when scanning (str " " in-flight-text)))]
    [:li {:class (str task-rule " px-4 py-3")
          :data-show (shown-when (entry-kind entry))}
     [:div {:class "flex flex-wrap items-start justify-between gap-3"}
      [:div {:class "min-w-0"}
       [:div {:class "flex flex-wrap items-center gap-2"}
        (if-let [url (get-in entry [:work-item :url])]
          [:a {:href url :target "_blank" :rel "noreferrer"
               :class (str key-class " hover:underline")}
           (:work-item-key entry)]
          [:span {:class key-class} (:work-item-key entry)])
        (parts/chip (or (get-in entry [:work-item :status]) "unknown") :neutral)
        (when (= :discovered (:origin entry)) (parts/chip "found by a scan" :sky))
        (status-chip entry)]
       [:p {:class (str parts/muted " mt-0.5")} (get-in entry [:work-item :summary])]
       (jira-time-note entry)]
      [:div {:class "flex items-center gap-2"}
       (command-form
        {:action (str "/worklog/entries/" (:id entry) "/time")
         :class "flex items-center gap-2"}
        [:input {:class (str (if scanning in-flight-input parts/input)
                             " w-28 text-right tabular-nums")
                 :type "text" :name "text" :placeholder "1h 30m"
                 :value (or (:entered-text entry) "")
                 :disabled (not (:editable? entry))
                 :title (when (> (:jira-worklog-count entry) 1)
                          (str "Jira holds this task's time under "
                               (:jira-worklog-count entry)
                               " worklogs; one field cannot edit them. "
                               "Change them in Jira."))
                 :aria-label (str "time on " (:work-item-key entry))}]
        [:button {:class parts/button :type "submit"
                  :disabled (not (:editable? entry))}
         "Save"])
       (command-form
        {:action (str "/worklog/entries/" (:id entry) "/remove")}
        [:button {:class parts/button :type "submit"
                  :disabled (not (:editable? entry))
                  :title (if (:known-to-jira? entry)
                           "Withdraws the worklog from Jira first"
                           "Removes the row")}
         "Remove"])]]
     (when (seq lines)
       [:ul {:class lines-class} (map #(line-row now-ms %) lines)])]))

(def ^:private partial-notice
  (str "border-t border-slate-200 px-4 py-2 text-sm text-amber-700 "
       "dark:border-slate-800 dark:text-amber-400"))

(defn- orphan-row [now-ms day orphan]
  [:li {:class "flex flex-wrap items-center justify-between gap-3 px-4 py-2"}
   [:div {:class "min-w-0"}
    [:div {:class "flex items-center gap-2"}
     [:span {:class parts/faint} (parts/humanise (:source orphan))]
     [:span {:class "truncate text-sm"} (:title orphan)]]
    [:div {:class parts/faint}
     (list (parts/relative-time now-ms (:happened-at orphan))
           (when (seq (:named-keys orphan))
             (str " · named " (str/join ", " (sort (:named-keys orphan)))
                  ", which Jira did not know")))]]
   (command-form
    {:action (str "/worklog/lines/"
                  (java.net.URLEncoder/encode (str (:id orphan)) "UTF-8")
                  "/attach")
     :class "flex shrink-0 items-center gap-2"}
    [:input {:class (str parts/input " w-32") :type "text" :name "key"
             :placeholder "ABC-123" :aria-label "attach to issue"}]
    [:button {:class parts/button :type "submit"} "Attach"])])

(defn- day-nav [days day]
  [:nav {:class "flex flex-wrap gap-2"}
   (for [d days]
     [:a {:href (day-href d)
          :class (str "rounded-lg border px-3 py-1.5 text-sm "
                      (if (= (:starts-at d) (:starts-at day))
                        "border-sky-500 bg-sky-50 text-sky-700 dark:bg-sky-950 dark:text-sky-300"
                        "border-slate-200 hover:bg-slate-100 dark:border-slate-800 dark:hover:bg-slate-800"))}
      (t/day-label (:starts-at d))])])

(defn day-page
  "One day, in full: its tasks, its time, its last scan, and what that scan
  found but could not attribute."
  [sys day]
  (let [now-ms (t/now-ms (:clock sys))
        orphans (discovery/orphans-on sys (:starts-at day))
        last-scan (discovery/last-scan sys (:starts-at day))
        scanning (discovery/active-scan sys (:starts-at day))
        days (worklog/window sys)
        counts (frequencies (keep entry-kind (:entries day)))]
    [:div {:id "worklog-day" :class "flex flex-col gap-4"}
     [:section {:class (str parts/card (when (:busy-with day) " busy-glow"))}
      [:div {:class "flex flex-wrap items-center justify-between gap-3 px-4 py-4"}
       [:div
        (parts/back-link "/worklog")
        [:div {:class "flex flex-wrap items-center gap-2"}
         [:h1 {:class "text-xl font-semibold"} (t/day-label (:starts-at day))]
         (when scanning (scan-chip "scanning" scanning))
         (when (= :submission (:busy-with day))
           (parts/chip "submitting to Jira" :violet))]
        [:p {:class parts/muted}
         (list (str (:acknowledged-count day) " in Jira · "
                    (:drafted-count day) " drafted · "
                    (:submitted-count day) " on their way · "
                    (:rejected-count day) " refused")
               (when (:has-been-scanned day)
                 (list " · scanned "
                       (parts/relative-time now-ms (:last-scanned-at day)))))]]
       [:div {:class "flex flex-wrap items-center gap-3"}
        [:div {:class "text-right"}
         [:div {:class "text-2xl font-semibold tabular-nums"}
          (t/format-duration (:total-logged day))]
         [:div {:class "text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400"}
          "logged"]]
        (scan-button day "Scan this day")
        (submit-button day)]]
      ;; What the last scan missed is only known once it has ended.
      (when (and last-scan (not scanning) (pos? (:failed last-scan)))
        [:p {:class partial-notice}
         (str (:failed last-scan) " of the " (count (:tasks last-scan))
              " sources did not answer, so this day is a partial picture. "
              "Scanning again adds what was missed.")])
      (when (and last-scan (not scanning) (pos? (:unread last-scan)))
        [:p {:class partial-notice}
         (str "Jira did not say what it holds on " (:unread last-scan)
              (if (= 1 (:unread last-scan)) " task" " tasks")
              ", which keep what the last scan read. "
              "Scanning again reads them once more.")])]

     [:section {:class parts/card}
      ;; Every task carries its own top rule, so the ones the toggles hide
      ;; leave no stray line behind.
      [:div {:class "flex flex-wrap items-center justify-between gap-3 px-4 py-3"}
       [:div {:class "flex flex-wrap items-center gap-3"}
        [:h2 {:class parts/heading-text} "Tasks"]
        (filter-toggles counts)]
       (command-form {:action (str (day-href day) "/tasks")
                      :class "flex items-center gap-2"}
                     [:input {:class (str parts/input " w-40")
                              :type "text" :name "key" :placeholder "ABC-123"
                              :aria-label "issue key"}]
                     [:button {:class parts/button :type "submit"} "Add task"])]
      (if (seq (:entries day))
        (list
         [:ul (map #(task-block sys now-ms day scanning %) (:entries day))]
         [:div {:class task-rule :style "display: none"
                :data-show (nothing-left-when counts)}
          (parts/empty-state "Nothing left to fill on this day.")])
        [:div {:class task-rule}
         (parts/empty-state
          "No tasks on this day. Name one, or scan the day to find them.")])]

     [:section {:class parts/card}
      [:div {:class parts/card-heading}
       [:h2 {:class parts/heading-text} "Loose ends"]
       [:span {:class parts/muted} (str (count orphans))]]
      (if (seq orphans)
        [:ul {:class "divide-y divide-slate-200 dark:divide-slate-800"}
         (map #(orphan-row now-ms day %) orphans)]
        (parts/empty-state "Everything found on this day belongs to a task."))]

     (day-nav days day)]))
