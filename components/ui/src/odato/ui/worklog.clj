(ns odato.ui.worklog
  "The timesheet — five working days at a time, what each adds up to, and what
  it is made of.

  A day reads as one row per task. Under a task sit the lines that led to it:
  the pull requests, the reviews, the comments, the issues touched that day.
  Several lines under one task is the normal case, and there is still exactly
  one place to put the time: the task. Lines that belong to no task are shown
  apart, with their titles, and can be attached to a task from here."
  (:require [clojure.string :as str]
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

(def ^:private day-actions
  (str "flex flex-wrap items-center gap-2 border-t border-slate-200 px-4 py-2 "
       "dark:border-slate-800"))

(defn- day-card [sys now-ms day]
  (let [today? (= (t/utc-midnight-of now-ms) (:starts-at day))
        orphans (discovery/orphans-on sys (:starts-at day))
        scanning (discovery/active-scan sys (:starts-at day))]
    [:section {:class (str parts/card (when today? " ring-2 ring-sky-500/40"))}
     [:div {:class parts/card-heading}
      [:div
       [:a {:href (day-href day) :class "text-sm font-semibold hover:underline"}
        (t/day-label (:starts-at day))]
       [:div {:class parts/faint} (t/format-day (:starts-at day))]]
      [:div {:class "text-right"}
       [:div {:class "text-lg font-semibold tabular-nums"}
        (t/format-duration (:total-logged day))]
       (when (:is-settled day) (parts/chip "settled" :green))]]
     (if (seq (:entries day))
       [:ul {:class "divide-y divide-slate-200 text-sm dark:divide-slate-800"}
        (for [entry (:entries day)]
          [:li {:class "flex items-center justify-between gap-2 px-4 py-2"}
           [:div {:class "min-w-0"}
            [:div {:class (str "font-medium" (when scanning (str " " in-flight-text)))}
             (:work-item-key entry)]
            [:div {:class (str parts/muted " truncate")}
             (get-in entry [:work-item :summary])]]
           [:div {:class "flex shrink-0 items-center gap-2"}
            [:span {:class (str "tabular-nums" (when scanning (str " " in-flight-text)))}
             (or (:entered-text entry) "—")]
            (status-chip entry)]])]
       (parts/empty-state (if (:has-been-scanned day)
                            "Scanned, and nothing was found."
                            "Nothing on this day yet.")))
     [:div {:class day-actions}
      (scan-button day "Scan")
      (submit-button day)
      (when (pos? (count orphans))
        [:a {:href (day-href day)} (parts/chip (str (count orphans) " loose") :amber)])
      (when (:has-been-scanned day)
        [:span {:class parts/faint}
         (list "scanned " (parts/relative-time now-ms (:last-scanned-at day)))])]]))

(defn body
  "The fragment SSE patches in place while a day scan runs."
  [sys]
  (let [now-ms (t/now-ms (:clock sys))
        days (worklog/window sys)
        week-total (reduce + 0 (map :total-logged days))
        scanning (discovery/active-scans sys)]
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
     [:div {:class "grid gap-4 md:grid-cols-2 xl:grid-cols-5"}
      (map #(day-card sys now-ms %) days)]]))

;; ------------------------------------------------------------------ one day

(defn- line-row [now-ms line]
  [:li {:class "flex flex-wrap items-baseline gap-x-2 gap-y-0.5 py-1 text-sm"}
   [:span {:class parts/faint} (parts/humanise (:source line))]
   [:span {:class "min-w-0 flex-1 truncate"} (:title line)]
   [:span {:class parts/faint} (parts/relative-time now-ms (:happened-at line))]])

(def ^:private lines-class
  (str "mt-2 divide-y divide-slate-100 border-l-2 border-slate-200 pl-3 "
       "dark:divide-slate-800/60 dark:border-slate-800"))

(defn- task-block
  "One group per task: the ticket, the single time field, and the lines that
  led to it."
  [sys now-ms day scanning entry]
  (let [lines (discovery/lines-for sys (:starts-at day) (:work-item-key entry))
        key-class (str "font-semibold" (when scanning (str " " in-flight-text)))]
    [:li {:class "px-4 py-3"}
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
        days (worklog/window sys)]
    [:div {:id "worklog-day" :class "flex flex-col gap-4"}
     [:section {:class (str parts/card (when scanning " scan-glow"))}
      [:div {:class "flex flex-wrap items-center justify-between gap-3 px-4 py-4"}
       [:div
        [:a {:href "/worklog" :class (str parts/muted " hover:underline")} "← timesheet"]
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
      [:div {:class parts/card-heading}
       [:h2 {:class parts/heading-text} "Tasks"]
       (command-form {:action (str (day-href day) "/tasks")
                      :class "flex items-center gap-2"}
                     [:input {:class (str parts/input " w-40")
                              :type "text" :name "key" :placeholder "ABC-123"
                              :aria-label "issue key"}]
                     [:button {:class parts/button :type "submit"} "Add task"])]
      (if (seq (:entries day))
        [:ul {:class "divide-y divide-slate-200 dark:divide-slate-800"}
         (map #(task-block sys now-ms day scanning %) (:entries day))]
        (parts/empty-state
         "No tasks on this day. Name one, or scan the day to find them."))]

     [:section {:class parts/card}
      [:div {:class parts/card-heading}
       [:h2 {:class parts/heading-text} "Loose ends"]
       [:span {:class parts/muted} (str (count orphans))]]
      (if (seq orphans)
        [:ul {:class "divide-y divide-slate-200 dark:divide-slate-800"}
         (map #(orphan-row now-ms day %) orphans)]
        (parts/empty-state "Everything found on this day belongs to a task."))]

     (day-nav days day)]))
