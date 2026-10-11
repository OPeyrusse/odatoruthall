(ns odato.ui.settings
  "The settings surface: what the operator tells the installation to watch, how
  often to look, and where the thresholds fall.

  The form says which half of the configuration each field belongs to, because
  that is when it takes effect — collection settings shape the next collection,
  presentation settings apply to the snapshot already on screen. Saving starts
  no collection."
  (:require [clojure.string :as str]
            [odato.config.interface :as config]
            [odato.time.interface :as t]
            [odato.ui.parts :as parts]))

(defn- field [{:keys [label name value type hint suffix]}]
  [:label {:class "flex flex-col gap-1"}
   [:span {:class "text-sm font-medium"} label]
   [:span {:class "flex items-center gap-2"}
    [:input {:class parts/input :type (or type "text") :name name :value (str value)}]
    (when suffix [:span {:class parts/muted} suffix])]
   (when hint [:span {:class parts/faint} hint])])

(defn- textarea [{:keys [label name value hint rows]}]
  [:label {:class "flex flex-col gap-1"}
   [:span {:class "text-sm font-medium"} label]
   [:textarea {:class (str parts/input " font-mono") :name name :rows (or rows 5)}
    (str value)]
   (when hint [:span {:class parts/faint} hint])])

(def ^:private ok-notice
  (str "border-emerald-300 bg-emerald-50 text-emerald-800 "
       "dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-300"))

(def ^:private refused-notice
  (str "border-rose-300 bg-rose-50 text-rose-800 "
       "dark:border-rose-900 dark:bg-rose-950 dark:text-rose-300"))

(def ^:private day-box
  (str "flex cursor-pointer items-center gap-2 rounded-lg border "
       "border-slate-300 px-3 py-1.5 text-sm dark:border-slate-700"))

(defn- outcome-notice [outcome]
  (when outcome
    [:div {:class (str "rounded-xl border px-4 py-3 text-sm "
                       (if (:ok outcome) ok-notice refused-notice))}
     (if (:ok outcome)
       (str "Saved. Collection settings take effect at the next collection — "
            "refresh gitdesk when you want them applied.")
       (str "Refused: " (parts/humanise (:fault outcome))
            ". Nothing changed; the configuration in force is the one that was."))]))

(defn- collection-settings [in-force]
  (let [window (:window in-force)]
    [:section {:class parts/card}
     [:div {:class parts/card-heading}
      [:h2 {:class parts/heading-text} "Collection settings"]
      (parts/chip "take effect at the next collection" :sky)]
     [:div {:class "grid gap-4 p-4 md:grid-cols-2"}
      (textarea {:label "Tracked repositories"
                 :name "repositories"
                 :value (str/join "\n" (sort (map config/repository-key
                                                  (:repositories in-force))))
                 :hint "One owner/name per line. At least one is required."})
      (textarea {:label "Tracked members"
                 :name "members"
                 :value (str/join "\n" (sort (:members in-force)))
                 :hint (str "GitHub logins, one per line. One general list, "
                            "not scoped per repository.")})
      (field {:label "Refresh cadence" :name "cadence-minutes" :type "number"
              :value (quot (:refresh-cadence-ms in-force) (t/minutes 1))
              :suffix "minutes"
              :hint "Between 5 minutes and 24 hours."})
      [:div {:class "grid grid-cols-2 gap-3"}
       (field {:label "Window opens" :name "window-opens" :type "number"
               :value (:opens-hour window) :suffix "UTC"})
       (field {:label "Window closes" :name "window-closes" :type "number"
               :value (:closes-hour window) :suffix "UTC"})]
      [:fieldset {:class "md:col-span-2"}
       [:legend {:class "text-sm font-medium"}
        "Days a scheduled collection may start"]
       [:p {:class parts/faint}
        (str "A collection already running when the window closes finishes. "
             "A manual refresh is always honoured.")]
       [:div {:class "mt-2 flex flex-wrap gap-2"}
        (for [day config/day-order]
          [:label {:class day-box}
           [:input {:type "checkbox" :name "active-days" :value (name day)
                    :class "accent-sky-600"
                    :checked (contains? (:active-days window) day)}]
           (str/capitalize (name day))])]]]]))

(defn- presentation-settings [in-force]
  (let [thresholds (:thresholds in-force)]
    [:section {:class parts/card}
     [:div {:class parts/card-heading}
      [:h2 {:class parts/heading-text} "Presentation settings"]
      (parts/chip "apply to the snapshot in hand" :violet)]
     [:div {:class "grid gap-4 p-4 md:grid-cols-3"}
      (field {:label "Aging after" :name "aging-days" :type "number"
              :value (quot (:aging-ms thresholds) (t/days 1)) :suffix "days"
              :hint "Open longer than this, and it is aging."})
      (field {:label "Conflicting at" :name "comment-threshold" :type "number"
              :value (:comments thresholds) :suffix "comments"
              :hint "At least this many comments, and it is conflicting."})
      (field {:label "Difficult at" :name "commit-threshold" :type "number"
              :value (:commits thresholds) :suffix "commits"
              :hint "At least this many commits, and, if approved, difficult."})
      (field {:label "Stuck window" :name "failing-commits" :type "number"
              :value (:failing-commits thresholds) :suffix "commits"
              :hint "This many newest commits failing, and, if approved, stuck."})
      (field {:label "Merged window" :name "merged-days" :type "number"
              :value (quot (:merged-window-ms thresholds) (t/days 1)) :suffix "days"
              :hint "Merged within this, and it is still worth showing."})
      [:label {:class "flex items-center gap-2 self-end pb-2"}
       [:input {:type "checkbox" :name "show-drafts" :value "true"
                :class "accent-sky-600" :checked (:show-drafts in-force)}]
       [:span {:class "text-sm font-medium"} "Show drafts in the lists"]]]]))

(defn body
  "`outcome` is what the last save answered, so a refused save says which fault
  refused it — and the configuration in force is still the one that was."
  [sys {:keys [outcome]}]
  (let [in-force (config/current sys)]
    [:div {:id "settings" :class "flex flex-col gap-4"}
     (outcome-notice outcome)
     [:form {:method "post" :action "/settings"
             :data-on-submit "@post('/settings', {contentType: 'form'})"
             :class "flex flex-col gap-4"}
      (collection-settings in-force)
      (presentation-settings in-force)
      [:div {:class "flex items-center gap-3"}
       [:button {:class parts/primary-button :type "submit"} "Save configuration"]
       [:span {:class parts/muted}
        (if-let [at (:updated-at in-force)]
          (str "Last saved " (t/format-instant at))
          "Never saved — these are the defaults.")]]]
     [:p {:class (str "px-1 " parts/faint)}
      (str "The worklog's own parameters — five working days, one minute to "
           "24 hours on an entry — are fixed by the specification and are not "
           "settings.")]]))

(defn read-proposal
  "The form, as a proposal the configuration component can judge."
  [params]
  (let [number (fn [k default] (or (parse-long (str (get params k ""))) default))]
    {:repositories (config/parse-repositories (get params "repositories"))
     :members (config/parse-members (get params "members"))
     :refresh-cadence-ms (* (number "cadence-minutes" 60) (t/minutes 1))
     :window {:opens-hour (number "window-opens" 5)
              :closes-hour (number "window-closes" 21)
              :active-days (config/parse-days (get params "active-days"))}
     :thresholds {:aging-ms (* (number "aging-days" 15) (t/days 1))
                  :comments (number "comment-threshold" 15)
                  :commits (number "commit-threshold" 10)
                  :failing-commits (number "failing-commits" 10)
                  :merged-window-ms (* (number "merged-days" 3) (t/days 1))}
     :show-drafts (boolean (get params "show-drafts"))}))
