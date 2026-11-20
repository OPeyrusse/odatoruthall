(ns odato.ui.owldesk
  "What owldesk shows the operator: every review grouped by pull request and
  the way to ask for one; one review in full, while it runs and once it is
  done; the review settings; and the cleanup of old results.

  Everything about a run is read from its results branch; the pages only say
  what the last reading found, and when."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [odato.access.interface :as access]
            [odato.config.interface :as config]
            [odato.owllie.interface :as owllie]
            [odato.review.interface :as review]
            [odato.time.interface :as t]
            [odato.ui.code :as code]
            [odato.ui.markdown :as markdown]
            [odato.ui.parts :as parts]))

(def ^:private separated "border-t border-slate-200 dark:border-slate-800")

(defn- now [sys] (t/now-ms (:clock sys)))

(defn- plural [n word]
  (str n " " word (when (not= 1 n) (if (str/ends-with? word "ch") "es" "s"))))

(defn- pr-label [{:keys [repository number]}]
  (str (:owner repository) "/" (:name repository) " #" number))

(defn- pr-url [pull-request] (owllie/pull-request-url pull-request))

(defn review-href
  ([run-id] (str "/owldesk/review/" run-id))
  ([run-id step] (str "/owldesk/review/" run-id "/" step)))

(defn tuning-href
  ([run-id] (str "/owldesk/tuning/" run-id))
  ([run-id step] (str "/owldesk/tuning/" run-id "/" step)))

(defn- query-string [params]
  (str/join "&" (for [[k v] params]
                  (str (java.net.URLEncoder/encode (str k) "UTF-8") "="
                       (java.net.URLEncoder/encode (str v) "UTF-8")))))

(defn remark-href
  "The form of a new remark on a review: `params` say on what (`fingerprint`,
  `file` `side` `line`, or `kind` general)."
  [run-id params]
  (str "/owldesk/review/" run-id "/remark?" (query-string params)))

(defn- results-branch-url [sys branch]
  (when-let [{:keys [owner name]} (:results-repository (review/settings sys))]
    (str "https://github.com/" owner "/" name "/tree/" branch)))

(def ^:private state-tones
  {:starting [:sky "starting"] :running [:sky "running"] :stalled [:amber "stalled"]
   :done [:green "done"] :failed [:red "failed"] :never-started [:red "never started"]
   :unreadable [:neutral "unreadable"]})

(defn- state-chip [r]
  (let [[tone label] (state-tones (:state r) [:neutral (name (:state r))])
        running (when (= :running (:state r))
                  (->> (get-in r [:run :steps]) (filter #(= :running (:state %))) first :phase-title))]
    (parts/chip (if running (str label " · " running) label) tone)))

(def ^:private tones
  "The tones a run may give a value, as Owllie's layout names them."
  {"neutral" :neutral "green" :green "amber" :amber "red" :red "sky" :sky "violet" :violet})

(defn- tone [s] (get tones (str s) :neutral))

(def ^:private severity-tones
  {"blocker" :red "major" :amber "minor" :neutral "info" :sky})

(defn- severity-chip [severity]
  (parts/chip (str/upper-case (str severity)) (severity-tones (str severity) :neutral)))

(defn- headline [r]
  (when-let [items (seq (get-in r [:run :headline]))]
    [:span {:class "tabular-nums"}
     (interpose " · "
                (for [{:keys [label value] :as item} items]
                  [:span {:class (case (tone (:tone item))
                                   :red "font-semibold text-rose-700 dark:text-rose-300"
                                   :amber "text-amber-700 dark:text-amber-300"
                                   nil)}
                   (str value " " label)]))]))

(def ^:private ok-notice
  (str "border-emerald-300 bg-emerald-50 text-emerald-800 "
       "dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-300"))

(def ^:private warn-notice
  (str "border-amber-300 bg-amber-50 text-amber-900 "
       "dark:border-amber-900 dark:bg-amber-950 dark:text-amber-200"))

(def ^:private refused-notice
  (str "border-rose-300 bg-rose-50 text-rose-800 "
       "dark:border-rose-900 dark:bg-rose-950 dark:text-rose-300"))

(defn- notice [tone & content]
  [:div {:class (str "rounded-xl border px-4 py-3 text-sm "
                     (case tone :ok ok-notice :warn warn-notice refused-notice))}
   content])

;; ---------------------------------------------------------------- launching

(def ^:private refusal-text
  {:invalid-pull-request-url "That is not a link to a GitHub pull request."
   :invalid-brain-ref "That brain ref is not a ref name."
   :github-unavailable "No valid GitHub credential: the pull request cannot be read."
   :no-results-repository "No results repository is set: choose one in the settings."
   :results-repository-not-ready "The results repository is not ready: check it in the settings."
   :launcher-unavailable "The launcher chosen in the settings is not configured."
   :already-reviewing "A review of that pull request is already starting or running."
   :fire-limit-reached "The scheduled task has taken all the reviews it accepts this hour."
   :pull-request-not-found "GitHub does not show that pull request."
   :no-such-review "That review is gone: ask for one from the list."})

(defn- readiness
  "What would refuse a review before the operator types anything."
  [sys]
  (let [in-force (review/settings sys)
        status (review/results-access sys)]
    (cond
      (not (access/usable? sys :github)) (:github-unavailable refusal-text)
      (nil? (:results-repository in-force)) (:no-results-repository refusal-text)
      (= :unreadable status) "The GitHub credential cannot read the results repository."
      (= :unmarked status) (str "The results repository has no " review/marker
                                " file at the root of its default branch.")
      (not (review/launcher-available? sys (:launcher in-force)))
      (str "The " (parts/humanise (:launcher in-force)) " launcher is not configured.")
      :else nil)))

(defn launch-outcome
  "What the last launch answered. It sits outside `#owldesk`, so the list
  patching itself as the launch proceeds leaves it on screen."
  [outcome]
  [:div {:id "owldesk-outcome"}
   (when outcome
    (if (:ok outcome)
      (notice :ok "Review asked for. "
              [:a {:href (review-href (:run-id outcome)) :class "font-medium underline"}
               "Follow it"]
              " — its branch appears within a minute or two.")
      (notice :refused (get refusal-text (:refusal outcome) (parts/humanise (:refusal outcome))))))])

(defn- launch-card [sys outcome]
  (let [in-force (review/settings sys)
        blocker (readiness sys)]
    [:section {:class parts/card}
     [:div {:class parts/card-heading}
      [:h2 {:class parts/heading-text} "Launch a review"]
      [:span {:class parts/faint}
       (str (review/fires-in-last-hour sys) "/" (:fires-per-hour review/timing) " this hour")]]
     [:form {:method "post" :action "/owldesk/launch"
             :data-on-submit "@post('/owldesk/launch', {contentType: 'form'})"
             :class "flex flex-col gap-3 p-4"}
      (when blocker (notice :warn blocker))
      [:div {:class "flex flex-wrap items-end gap-3"}
       [:label {:class "flex min-w-0 flex-[999_1_24rem] flex-col gap-1"}
        [:span {:class "text-sm font-medium"} "Pull request link"]
        [:input {:class (str parts/input " font-mono") :type "url" :name "url" :required true
                 :placeholder "https://github.com/owner/repo/pull/123"}]]
       [:label {:class "flex flex-[1_1_10rem] flex-col gap-1"}
        [:span {:class "text-sm font-medium"} "Brain ref"]
        [:input {:class (str parts/input " font-mono") :type "text" :name "brain-ref"
                 :placeholder (or (:brain-ref in-force) "default branch")}]]
       [:button {:class parts/primary-button :type "submit"} "Review with Owllie"]]
      [:p {:class parts/faint}
       (if-let [{:keys [owner name]} (:results-repository in-force)]
         (str "Results go to " owner "/" name ", on a branch review/<owner>/<repo>/<number>/<run id>.")
         "Results go to the results repository set in the settings.")]]]))

;; ------------------------------------------------------------------ the list

(defn- trash-button
  "Deletes a review and its results branch, from the list: the same action as
  the review page's Remove, drawn as a bin."
  [r]
  (when-not (review/active? r)
    [:form {:method "post" :action (str "/owldesk/review/" (:run-id r) "/remove")
            :class "ml-auto"
            :onsubmit "return confirm('Delete this review and its results branch?')"}
     [:button {:type "submit" :title "Delete this review" :aria-label "Delete this review"
               :class (str parts/button " px-2!")}
      [:svg {:viewBox "0 0 24 24" :class "h-4 w-4" :fill "none" :stroke "currentColor"
             :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"
             :aria-hidden "true"}
       [:path {:d "M3 6h18"}]
       [:path {:d "M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2"}]
       [:path {:d "M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"}]
       [:path {:d "M10 11v6M14 11v6"}]]]]))

(defn- review-line [sys r]
  (let [now-ms (now sys)]
    [:li {:class "flex flex-wrap items-center gap-x-3 gap-y-1 py-1 text-sm"}
     [:a {:href (review-href (:run-id r)) :class "font-mono text-xs hover:underline"} (:run-id r)]
     (state-chip r)
     [:span {:class parts/muted} "asked " (parts/relative-time now-ms (:asked-at r))]
     (headline r)
     (when-let [failure (get-in r [:run :error :code])]
       [:span {:class parts/faint} failure])
     (trash-button r)]))

(defn- run-count [n]
  (if (> n 1)
    (parts/chip (plural n "run") :violet)
    [:span {:class parts/faint} (plural n "run")]))

(defn- pull-request-row
  "One pull request: its latest run, and how many it had. The earlier runs are
  chosen from the review page."
  [sys {:keys [pull-request reviews]}]
  (let [latest (first reviews)]
    [:li {:class (str "flex flex-col gap-1 px-4 py-3 " separated)}
     [:div {:class "flex flex-wrap items-center gap-x-2 gap-y-1"}
      [:a {:href (review-href (:run-id latest))
           :class "font-medium text-slate-900 hover:underline dark:text-slate-100"}
       (or (:title latest) (pr-label pull-request))]
      [:a {:href (pr-url pull-request) :target "_blank" :rel "noreferrer"
           :class (str parts/faint " hover:underline")} (pr-label pull-request)]
      (run-count (count reviews))]
     [:ul (review-line sys latest)]]))

(defn- reviews-card [sys]
  (let [groups (review/by-pull-request sys)
        by-repository (group-by #(config/repository-key (get-in % [:pull-request :repository]))
                                groups)
        {read-at :at polling-failure :failure reading :reading} (review/last-read sys)]
    ;; While the branches are read, the card carries the same rainbow ring as
    ;; a day being scanned in the worklog.
    [:section {:class (str parts/card (when reading " busy-glow"))}
     [:div {:class parts/card-heading}
      [:h2 {:class parts/heading-text} "Reviews"]
      [:div {:class "flex items-center gap-2"}
       (when reading (parts/chip "reading the branches" :sky))
       [:form {:method "post" :action "/owldesk/refresh"
               :data-on-submit "@post('/owldesk/refresh')"}
        [:button {:class parts/button :type "submit" :disabled reading
                  :title (when reading "The branches are being read")}
         "Read the branches now"]]]]
     (if (empty? groups)
       (parts/empty-state "No review yet. Paste a pull request link above.")
       (for [[repository gs] (sort-by key by-repository)]
         [:div
          [:div {:class (str "bg-slate-50 px-4 py-2 text-xs font-semibold text-slate-600 "
                             "dark:bg-slate-800/50 dark:text-slate-300 " separated)}
           repository]
          [:ul (map #(pull-request-row sys %) gs)]]))
     [:p {:class (str "px-4 py-2 " parts/faint " " separated)}
      (if read-at
        (list "Branches last read " (parts/relative-time (now sys) read-at) ".")
        "The branches have not been read yet.")
      (when polling-failure (str " The last reading failed: " (parts/humanise polling-failure) "."))]]))

(defn body
  "The way to ask for a review, and the list of reviews."
  [sys {:keys [outcome]}]
  [:div {:id "owldesk" :class "flex flex-col gap-4"}
   (launch-card sys outcome)
   (reviews-card sys)])

;; ----------------------------------------------------------- one review
;;
;; Nothing below knows what a review is made of: the phases and steps come
;; from the branch's process, and each step is drawn from its blocks.

(def ^:private step-tones
  {:done [:green "done"] :failed [:red "failed"] :skipped [:neutral "skipped"]
   :running [:sky "running"] :pending [:neutral "pending"] :not-run [:neutral "not run"]
   :unreadable [:amber "unreadable"]})

(defn- step-chip [state]
  (let [[tone label] (step-tones state [:neutral (name state)])]
    (parts/chip label tone)))

(defn- progress-card [sys r href]
  (let [steps (get-in r [:run :steps])
        heartbeat (get-in r [:run :updated-at])]
    [:section {:class parts/card}
     [:div {:class parts/card-heading}
      [:h2 {:class parts/heading-text} "Progress"]
      [:span {:class parts/faint}
       (when heartbeat (list "last heartbeat " (parts/relative-time (now sys) heartbeat)))]]
     (if (empty? steps)
       (parts/empty-state "The branch does not say what the run is made of yet.")
       [:ol {:class "flex flex-col gap-3 p-4"}
        (for [phase-steps (partition-by :phase steps)
              :let [title (:phase-title (first phase-steps))]]
          [:li {:class "flex flex-col gap-2"}
           [:span {:class parts/heading-text} (str title)]
           [:ul {:class "grid gap-2 sm:grid-cols-2 lg:grid-cols-4"}
            (for [step phase-steps]
              [:li {:class (str "flex items-center justify-between gap-2 rounded-lg border px-3 py-2 text-sm "
                                (if (= :running (:state step))
                                  "border-sky-300 bg-sky-50 dark:border-sky-900 dark:bg-sky-950"
                                  "border-slate-200 dark:border-slate-800"))}
               (if (:result step)
                 [:a {:href (href (:run-id r) (:id step)) :class "hover:underline"} (str (:title step))]
                 [:span (str (:title step))])
               (step-chip (:state step))])]])])]))

(defn- state-banner [sys r]
  (let [now-ms (now sys)]
    (case (:state r)
      :starting (notice :warn "Asked for "
                        (parts/relative-time now-ms (get-in r [:launch :fired-at]))
                        ". Waiting for its branch to appear.")
      :stalled (notice :warn "Its branch has said nothing since "
                       (parts/relative-time now-ms (get-in r [:run :updated-at]))
                       ". Open the session to see what it is doing.")
      :failed (let [{:keys [code message]} (get-in r [:run :error])]
                (notice :refused [:span {:class "font-semibold"} (str code)] (when message (str " — " message))))
      :never-started (notice :refused
                             (or (get-in r [:launch :refusal])
                                 "No branch appeared: the session never started, or could not push to the results repository."))
      :unreadable (notice :warn "Its branch holds no status that can be read.")
      nil)))

(defn- schema-warning [r]
  (let [schema (get-in r [:run :schema])
        major (fn [s] (second (re-matches #".*/(\d+).*" (str s))))]
    (when (and schema (not= (major schema) (major review/schema)))
      (notice :warn (str "This run is written in " schema ", and this installation reads " review/schema
                         ". What can be read of it is shown.")))))

(defn- verdict-buttons [r finding judged]
  (let [fingerprint (:fingerprint finding)
        verdict (get judged fingerprint)
        action (str "/owldesk/review/" (:run-id r) "/judge")
        button (fn [value label]
                 [:form {:method "post" :action action
                         :data-on-submit (str "@post('" action "', {contentType: 'form'})")}
                  [:input {:type "hidden" :name "fingerprint" :value fingerprint}]
                  [:input {:type "hidden" :name "verdict"
                           :value (if (= verdict value) "none" (name value))}]
                  [:button {:type "submit" :aria-pressed (str (= verdict value))
                            :class (str parts/button " text-xs"
                                        (when (= verdict value)
                                          " ring-2 ring-sky-500 dark:ring-sky-400"))}
                   label]])]
    [:div {:class "flex flex-wrap items-center gap-2"}
     (button :relevant "Relevant")
     (button :false-positive "False positive")
     [:a {:href (remark-href (:run-id r) {"fingerprint" fingerprint})
          :class (str parts/button " text-xs")}
      "Remark for Owllie…"]]))

(defn- quote-block [text]
  [:pre {:class (str "overflow-x-auto rounded-lg bg-slate-50 px-3 py-2 font-mono text-xs "
                     "dark:bg-slate-950")}
   (str text)])

(def ^:private finding-status-tones
  {"kept" :green "merged" :violet "dropped" :amber "rejected" :neutral})

(defn- finding-card
  "One finding: where it is, the code around it as the review's diff showed
  it (its quote alone when its lines are not in the diff), what it says, and,
  on a review page, the way to judge it and to remark on it."
  [r finding {:keys [judged remarked review?]}]
  (let [{:keys [file line]} (when (map? (:location finding)) (:location finding))
        status (when (map? (:status finding)) (:status finding))
        excerpt (when review? (review/finding-excerpt r finding))]
    [:article {:class "flex flex-col gap-2 rounded-xl border border-slate-200 p-4 dark:border-slate-800"}
     [:div {:class "flex flex-wrap items-center gap-2"}
      (severity-chip (:severity finding))
      (when file [:code {:class "font-mono text-xs"} (str file (when line (str ":" line)))])
      (when (seq (:tags finding))
        [:span {:class parts/faint} (str/join ", " (map str (:tags finding)))])
      (when-let [value (:value status)]
        (parts/chip (str value) (finding-status-tones (str value) :neutral)))
      (when-let [reason (:reason status)] [:span {:class parts/faint} (str reason)])
      (when (contains? remarked (:fingerprint finding))
        (parts/chip "remark drafted" :violet))]
     (cond
       (seq excerpt) (code/excerpt-view excerpt)
       (:quote finding) (quote-block (:quote finding)))
     (when (:body finding) (markdown/render (:body finding)))
     (when (and (map? (:fields finding)) (seq (:fields finding)))
       [:p {:class parts/faint}
        (interpose " · " (for [[k v] (:fields finding)] (str (name k) ": " v)))])
     (when (and review? (string? (:fingerprint finding))
                (contains? #{nil "kept" "merged"} (:value status)))
       (verdict-buttons r finding judged))]))

(defn- unknown-block [block]
  [:div {:class "flex flex-col gap-2"}
   (notice :warn (str "A block of type " (pr-str (:type block)) " this installation does not show:"))
   (quote-block (json/write-str block :escape-slash false))])

(defn- block-view
  "One block of a step, by its type. A type not known here is shown as such."
  [r ctx block]
  (if-not (map? block)
    (unknown-block {:type nil :value block})
    (case (:type block)
      "markdown" (markdown/render (:text block))
      "code" (quote-block (:text block))
      "diff" (if (and (:diff-href ctx) (= (:text block) (review/diff-text r)))
               ;; The review's own diff has a tab of its own.
               [:p {:class parts/muted}
                (str (or (:title block) "Diff") ": ")
                [:a {:href (:diff-href ctx) :class "font-medium underline"} "shown in the Diff tab"]
                "."]
               [:div {:class "flex flex-col gap-2"}
                (when (:title block) [:span {:class parts/heading-text} (str (:title block))])
                (code/diff-view (review/parse-diff (str (:text block))) {})])
      "stats" [:div {:class "grid grid-cols-2 gap-2 sm:grid-cols-4"}
               (for [item (:items block) :when (map? item)]
                 (parts/stat {:label (str (:label item)) :count (:value item) :tone (tone (:tone item))}))]
      "table" [:div {:class "flex flex-col gap-1"}
               (when (:title block) [:span {:class parts/heading-text} (str (:title block))])
               [:div {:class "overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800"}
                [:table {:class "w-full text-sm"}
                 [:thead [:tr {:class "text-left text-xs text-slate-500"}
                          (for [c (:columns block)] [:th {:class "px-3 py-2 font-medium"} (str c)])]]
                 [:tbody
                  (for [row (:rows block)]
                    [:tr {:class separated}
                     (for [cell (if (sequential? row) row [row])]
                       [:td {:class "px-3 py-2 align-top"} (str cell)])])]]]]
      "findings" [:div {:class "flex flex-col gap-3"}
                  (when (:title block) [:span {:class parts/heading-text} (str (:title block))])
                  (if (empty? (:items block))
                    [:p {:class parts/muted} "Nothing to report."]
                    (for [item (:items block) :when (map? item)] (finding-card r item ctx)))]
      (unknown-block block))))

(defn- tab [href current? title chip]
  [:a {:href href
       :class (str "flex items-center gap-1.5 whitespace-nowrap px-3 py-2 text-sm "
                   (if current?
                     "border-b-2 border-slate-900 font-semibold dark:border-slate-100"
                     "text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100"))}
   title chip])

(defn- dead-tab [title]
  [:span {:class "flex items-center gap-1.5 px-3 py-2 text-sm text-slate-400 dark:text-slate-600"} title])

(defn- tabs
  "A tab per step, and the `extra` tabs after them (`[current? href title]`,
  `href` nil while there is nothing to show)."
  [r current-id href & extra]
  [:nav {:class "flex flex-wrap gap-1 border-b border-slate-200 dark:border-slate-800"}
   (for [step (get-in r [:run :steps])]
     (if (:result step)
       (tab (href (:run-id r) (:id step)) (= current-id (:id step)) (str (:title step))
            (when (#{:failed :skipped} (:state step)) (step-chip (:state step))))
       (dead-tab (str (:title step)))))
   (for [[current? h title] extra]
     (if h (tab h current? title nil) (dead-tab title)))])

(defn- step-view [r step ctx]
  (let [result (:result step)]
    (cond
      (nil? step) nil
      (nil? result)
      [:section {:class parts/card}
       (parts/empty-state (case (:state step)
                            :running "This step is running: its result appears when it ends."
                            :pending "This step has not started yet."
                            :unreadable "This step's result cannot be read."
                            "This step was not run."))]
      (contains? result :markdown)
      [:section {:class (str parts/card " px-4 py-3")} (markdown/render (:markdown result))]
      :else
      [:div {:class "flex flex-col gap-4"}
       (when (seq (:meta result))
         [:section {:class (str parts/card " flex flex-wrap gap-6 px-4 py-3 text-sm")}
          [:div [:div {:class parts/faint} "Step"] [:div (step-chip (:state step))]]
          (for [m (:meta result) :when (map? m)]
            [:div [:div {:class parts/faint} (str (:label m))] [:div (str (:value m))]])])
       [:section {:class (str parts/card " flex flex-col gap-4 px-4 py-4")}
        (if (empty? (:blocks result))
          [:p {:class parts/muted} "Nothing to show."]
          (for [block (:blocks result)] (block-view r ctx block)))]])))

(defn- current-step
  "The step a page shows: the one asked for, or the summary step once it has
  a result, or the last step that has one."
  [r step-id]
  (let [steps (get-in r [:run :steps])
        by-id (fn [id] (first (filter #(= id (:id %)) steps)))]
    (or (when step-id (by-id step-id))
        (let [summary (by-id (get-in r [:run :summary-step]))]
          (when (:result summary) summary))
        (last (filter :result steps)))))

(defn step-ids [r] (set (map :id (get-in r [:run :steps]))))

(def diff-tab
  "The tab of a review's page that shows the diff it read. A step of the
  same id, should a process name one, keeps its own tab."
  "diff")

(defn diff-tab? [r step] (and (= diff-tab step) (not (contains? (step-ids r) step))))

(defn- diff-view
  "The diff the review read, file by file, each line starting a missed remark."
  [r ctx]
  (if-let [files (review/review-diff r)]
    [:section {:class (str parts/card " flex flex-col gap-3 px-4 py-4")}
     [:p {:class parts/faint}
      "A line number starts a missed remark on that line: something the review should have flagged."]
     (code/diff-view files {:line-href (:line-href ctx)})]
    [:section {:class parts/card}
     (parts/empty-state (if (review/active? r)
                          "The diff appears once the step that reads it is done."
                          "This run does not show the diff it read."))]))

;; ---------------------------------------------------------------- remarks

(def kind-text
  {:false-positive "False positive" :relevant "Relevant" :wrong-severity "Wrong severity"
   :unclear "Unclear" :missed "Missed" :general "General"})

(def ^:private kind-hints
  {:false-positive "The finding should not have been raised. Say why, if you can: the session turns it into an exception."
   :relevant "The finding is right: it becomes an example the rule must keep finding."
   :wrong-severity "The finding is right, at the wrong severity."
   :unclear "The text does not say what is wrong. Say what it should have said."
   :missed "The review should have flagged these lines. Say what, and why."
   :general "About the review as a whole: an agent too verbose, a topic nobody covers…"})

(def ^:private kind-tones
  {:false-positive :amber :relevant :green :wrong-severity :amber :unclear :sky :missed :red :general :neutral})

(def remark-fault-text
  {:invalid-kind "choose what the remark says"
   :no-results "the review has no results to point into"
   :finding-not-found "the review does not show that finding"
   :lines-not-in-diff "those lines are not lines of the review's diff"
   :note-required "this kind of remark needs a note"
   :note-too-long (str "the note is longer than " (:max-note-length review/tuning-limits) " characters")
   :severity-not-changed "propose a severity other than the finding's"
   :not-a-draft "a sent remark no longer changes"
   :no-such-remark "that remark is gone"
   :not-sent "only a sent remark is copied"
   :batch-not-given-up "its batch may still become a pull request"
   :no-such-comment "that comment is no longer offered"})

(def ^:private send-refusal-text
  {:no-draft-remarks "No draft to send."
   :too-many-remarks (str "More than " (:max-remarks-per-batch review/tuning-limits)
                          " drafts: delete some, or send them in two batches.")
   :head-unknown "The review's results do not say which commit it read: they were written before Owllie recorded it."
   :github-unavailable "No valid GitHub credential."
   :no-results-repository "No results repository is set: choose one in the settings."
   :results-repository-not-ready "The results repository is not ready: check it in the settings."
   :launcher-unavailable "No tuning launcher is configured: set OWLLIE_TUNE_FIRE_URL and OWLLIE_TUNE_FIRE_TOKEN, or OWLLIE_CLI_DIR."
   :tuning-in-flight "A batch of this review is still being turned into a pull request."
   :fire-limit-reached "The tuning scheduled task has taken all it accepts this hour."})

(defn- lines-label [{:keys [file side line end-line]}]
  (str file ":" line (when (and end-line (not= end-line line)) (str "-" end-line))
       (when (= :old side) " (removed lines)")))

(defn- send-blocker
  "What would refuse a batch of this review before the operator asks."
  [sys r drafts]
  (let [in-force (review/settings sys)]
    (cond
      (empty? drafts) nil
      (> (count drafts) (:max-remarks-per-batch review/tuning-limits)) (:too-many-remarks send-refusal-text)
      (not (re-matches #"[0-9a-f]{40}" (str (get-in r [:run :head-sha])))) (:head-unknown send-refusal-text)
      (not (access/usable? sys :github)) (:github-unavailable send-refusal-text)
      (nil? (:results-repository in-force)) (:no-results-repository send-refusal-text)
      (not (review/tune-launcher-available? sys (:launcher in-force))) (:launcher-unavailable send-refusal-text)
      (some :active? (review/tunings-of sys (:run-id r))) (:tuning-in-flight send-refusal-text)
      :else nil)))

(def ^:private tuning-tones
  {:starting [:sky "starting"] :running [:sky "running"] :stalled [:amber "stalled"]
   :done [:green "done"] :failed [:red "failed"] :never-started [:red "never started"]
   :unreadable [:neutral "unreadable"]})

(defn- tuning-chip [tn]
  (let [[tone label] (tuning-tones (:state tn) [:neutral (name (:state tn))])]
    (parts/chip label tone)))

(def ^:private mergeable-text
  {"clean" [:green "ready to merge"] "dirty" [:red "conflicts"] "unstable" [:amber "checks failing"]
   "blocked" [:amber "blocked"] "behind" [:amber "behind its base"] "draft" [:neutral "draft"]})

(defn- pull-request-chips [{:keys [state draft mergeable-state]}]
  (list
   (case state
     :merged (parts/chip "merged" :violet)
     :closed (parts/chip "closed" :neutral)
     :open (parts/chip (if draft "open · draft" "open") (if draft :neutral :green))
     (parts/chip "not read yet" :neutral))
   (when (and (= :open state) (not draft))
     (when-let [[tone label] (mergeable-text (str mergeable-state))]
       (parts/chip label tone)))))

(defn- remark-summary
  "One remark, as the remarks section lists it."
  [r remark actions]
  [:li {:class (str "flex flex-col gap-2 px-4 py-3 " separated)}
   [:div {:class "flex flex-wrap items-center gap-2 text-sm"}
    (parts/chip (kind-text (:kind remark)) (kind-tones (:kind remark) :neutral))
    (when (:rule remark) [:span {:class "font-mono text-xs"} (:rule remark)])
    (when (:lines remark) [:code {:class "font-mono text-xs"} (lines-label (:lines remark))])
    (when-let [s (:proposed-severity remark)]
      [:span {:class parts/faint} (str "→ " s)])
    (when (:from-comment remark) [:span {:class parts/faint} "from a reviewer's comment"])
    [:span {:class (str parts/faint " ml-auto")} actions]]
   (when-let [obs (get-in remark [:finding :observation])]
     [:details {:class "text-xs"}
      [:summary {:class (str parts/faint " cursor-pointer")} "What the finding said"]
      (markdown/render obs)])
   (when (seq (:excerpt remark)) (code/excerpt-view (:excerpt remark)))
   (when-not (str/blank? (:note remark))
     [:p {:class "whitespace-pre-wrap text-sm"} (:note remark)])])

(defn- post-button [action label & [{:keys [confirm danger]}]]
  [:form {:method "post" :action action :class "inline"
          :onsubmit (when confirm (str "return confirm('" confirm "')"))}
   [:button {:type "submit" :class (str parts/button " text-xs" (when danger " text-rose-700 dark:text-rose-300"))}
    label]])

(defn remarks-outcome
  "What the last batch sent answered. It sits outside `#owldesk-review`, so
  the page patching itself leaves it on screen."
  [outcome]
  [:div {:id "owldesk-remarks-outcome"}
   (when outcome
     (if (:ok outcome)
       (notice :ok "Remarks sent to Owllie. "
               [:a {:href (tuning-href (:run-id outcome)) :class "font-medium underline"} "Follow the tuning"]
               " — its branch appears within a minute or two.")
       (notice :refused (get send-refusal-text (:refusal outcome) (parts/humanise (:refusal outcome))))))])

(defn- remarks-section [sys r]
  (let [run-id (:run-id r)
        all (review/remarks sys run-id)
        drafts (filter #(= :draft (:status %)) all)
        sent (group-by :batch (filter #(= :sent (:status %)) all))
        tunings (review/tunings-of sys run-id)
        candidates (review/miss-candidates sys r)
        blocker (send-blocker sys r drafts)
        base (str "/owldesk/review/" run-id)]
    [:section {:id "remarks" :class parts/card}
     [:div {:class parts/card-heading}
      [:h2 {:class parts/heading-text} "Remarks for Owllie"]
      [:span {:class parts/faint}
       (str (plural (count drafts) "draft") " · " (plural (count tunings) "batch") " sent")]]
     [:div {:class "flex flex-col gap-3 px-4 py-3"}
      [:p {:class parts/muted}
       "Write what Owllie should learn from this review, then send every draft at once: "
       "Owllie turns them into a pull request on its rules, with the code anonymized."]
      [:div {:class "flex flex-wrap items-center gap-2"}
       [:a {:href (remark-href run-id {"kind" "general"}) :class parts/button} "Add a general remark"]
       (when (:run r)
         [:a {:href (review-href run-id diff-tab) :class parts/button} "Point at a miss in the diff"])]]
     (if (empty? drafts)
       [:p {:class (str "px-4 py-3 " parts/muted " " separated)} "No draft."]
       [:ul
        (for [d drafts]
          (remark-summary r d
                          (list [:a {:href (str base "/remarks/" (:id d)) :class (str parts/button " text-xs")} "Edit"]
                                " "
                                (post-button (str base "/remarks/" (:id d) "/delete") "Delete"
                                             {:confirm "Delete this draft?" :danger true}))))])
     (when (seq drafts)
       [:form {:method "post" :action (str base "/send")
               :data-on-submit (str "@post('" base "/send', {contentType: 'form'})")
               :class (str "flex flex-wrap items-center gap-3 px-4 py-3 " separated)}
        (when blocker (notice :warn blocker))
        [:button {:type "submit" :class parts/primary-button :disabled (boolean blocker)}
         (str "Send " (plural (count drafts) "draft") " to Owllie")]
        [:span {:class parts/faint} "One batch, one pull request on Owllie's rules."]])
     (when (seq candidates)
       [:div {:class separated}
        [:h3 {:class (str parts/heading-text " px-4 pt-3")} "Reviewers' comments where the review found nothing"]
        [:ul
         (for [c candidates]
           [:li {:class (str "flex flex-col gap-1 px-4 py-2 " separated)}
            [:div {:class "flex flex-wrap items-center gap-2 text-sm"}
             [:span {:class "font-medium"} (str (:author c))]
             (when (:by-operator c) (parts/chip "you" :sky))
             [:code {:class "font-mono text-xs"} (lines-label (:lines c))]
             [:a {:href (str (:url c)) :target "_blank" :rel "noreferrer" :class (str parts/faint " hover:underline")}
              "on GitHub ↗"]
             [:span {:class "ml-auto flex gap-2"}
              (post-button (str base "/comments/" (:id c) "/draft") "Make it a missed remark")
              (post-button (str base "/comments/" (:id c) "/dismiss") "Not a miss")]]
            [:p {:class "whitespace-pre-wrap text-sm text-slate-600 dark:text-slate-300"} (str (:body c))]])]])
     (when (seq tunings)
       [:div {:class separated}
        [:h3 {:class (str parts/heading-text " px-4 pt-3")} "Batches sent"]
        [:ul
         (for [tn tunings
               :let [remarks (get sent (:run-id tn))]]
           [:li {:class (str "flex flex-col gap-1 px-4 py-2 " separated)}
            [:div {:class "flex flex-wrap items-center gap-2 text-sm"}
             [:a {:href (tuning-href (:run-id tn)) :class "font-mono text-xs hover:underline"} (:run-id tn)]
             (tuning-chip tn)
             [:span {:class parts/faint} (plural (count remarks) "remark")]
             (when-let [pr (:pull-request tn)]
               (list [:a {:href (:url pr) :target "_blank" :rel "noreferrer" :class "text-sm underline"}
                      "Pull request ↗"]
                     (pull-request-chips pr)))]
            (when (seq remarks)
              [:details {:class "text-sm"}
               [:summary {:class (str parts/muted " cursor-pointer")} "Its remarks"]
               [:ul
                (for [s remarks]
                  (remark-summary r s (when (:given-up? tn)
                                        (post-button (str base "/remarks/" (:id s) "/redraft")
                                                     "Copy into a new draft"))))]])])]])]))

(defn- asked-at [r] (or (:asked-at r) (get-in r [:launch :fired-at])))

(defn- run-option-label
  "A run as the picker names it: when it was asked for, where it stands, and
  what it found."
  [r latest?]
  (let [[_ label] (state-tones (:state r) [nil (some-> (:state r) name)])
        found (for [{:keys [label value]} (get-in r [:run :headline])] (str value " " label))]
    (str (str/join " · " (concat [(or (t/format-minute (asked-at r)) (:run-id r)) label] found))
         (when latest? " (latest)"))))

(defn- input-of-width
  "The input style, at `width` rather than the whole line."
  [width]
  (str/replace parts/input #"\bw-full\b" width))

(defn- run-picker
  "Every run of the pull request, the latest first; choosing one shows it.
  Without scripts, the form's button does what the change does."
  [r runs]
  [:form {:method "get" :action "/owldesk/review" :class "flex min-w-0 flex-wrap items-center gap-2"}
   [:label {:class "flex min-w-0 items-center gap-2"}
    [:span {:class "text-sm font-medium"} "Run"]
    [:select {:name "run" :class (str (input-of-width "w-auto") " min-w-0 max-w-full")
              :onchange "this.form.submit()"}
     (for [[i o] (map-indexed vector runs)]
       [:option {:value (:run-id o) :selected (= (:run-id o) (:run-id r))}
        (run-option-label o (zero? i))])]]
   [:noscript [:button {:type "submit" :class parts/button} "Show"]]
   (run-count (count runs))])

(defn- run-again-form
  "Asks for another run of the same pull request. Refused, with the reason
  on the page, like any other launch."
  [sys r runs]
  (let [in-force (review/settings sys)
        blocker (or (when (some review/active? runs)
                      "A run of this pull request is starting or running.")
                    (readiness sys))
        action (str "/owldesk/review/" (:run-id r) "/run-again")]
    [:form {:method "post" :action action :class "flex flex-wrap items-center gap-2"}
     [:label {:class "flex items-center gap-2"}
      [:span {:class "text-sm font-medium"} "Brain ref"]
      [:input {:class (str (input-of-width "w-40") " font-mono") :type "text" :name "brain-ref"
               :title "Empty: the brain ref the settings name"
               :placeholder (or (:brain-ref in-force) "default branch")}]]
     [:button {:class parts/primary-button :type "submit" :disabled (some? blocker) :title blocker}
      "Run again"]]))

(defn- when-asked
  "When the run was asked for and, once it has ended, when it did."
  [sys r]
  (let [now-ms (now sys)
        at (asked-at r)
        ended (when (#{:done :failed} (:state r)) (get-in r [:run :updated-at]))]
    (list
     (when at
       [:span "asked " [:time {:datetime (t/format-instant at)} (t/format-minute at)]
        " (" (parts/relative-time now-ms at) ")"])
     (when ended
       [:span "ended " [:time {:datetime (t/format-instant ended)} (t/format-minute ended)]]))))

(defn review-page
  "One review in full: which run of its pull request it is, and when; where
  it stands; then the view of one of its steps; then the remarks the operator
  writes for Owllie."
  [sys r {:keys [step]}]
  (let [runs (or (->> (review/by-pull-request sys)
                      (filter #(= (review/pull-request-key (:pull-request %))
                                  (review/pull-request-key (:pull-request r))))
                      first :reviews)
                 [r])
        details (filter map? (get-in r [:run :process :details]))
        branch (or (get-in r [:run :branch]) (get-in r [:launch :branch]))
        diff? (diff-tab? r step)
        shown (current-step r (when-not diff? step))
        run-id (:run-id r)
        diff-href (when (review/diff-text r) (review-href run-id diff-tab))
        ctx {:diff-href diff-href
             :judged (review/judgements sys run-id)
             :remarked (set (keep #(get-in % [:finding :fingerprint])
                                  (filter #(= :draft (:status %)) (review/remarks sys run-id))))
             :review? true
             :line-href (fn [path side n]
                          (remark-href run-id {"file" path "side" (name side) "line" (str n)}))}]
    [:div {:id "owldesk-review" :class "flex flex-col gap-4"}
     [:section {:class parts/card}
      [:div {:class "flex flex-col gap-2 px-4 py-4"}
       [:div {:class "flex flex-wrap items-center gap-2"}
        (parts/back-link "/owldesk")
        [:a {:href (pr-url (:pull-request r)) :target "_blank" :rel "noreferrer"
             :class (str parts/muted " hover:underline")} (pr-label (:pull-request r))]]
       [:h1 {:class "text-xl font-semibold"} (or (:title r) (pr-label (:pull-request r)))]
       [:div {:class "flex flex-wrap items-center gap-2 text-sm text-slate-500 dark:text-slate-400"}
        (state-chip r)
        (when-asked sys r)
        [:span {:class "font-mono text-xs"} run-id]
        (for [{:keys [label value]} details] [:span (str label " " value)])]
       [:div {:class "flex flex-wrap gap-2"}
        (when-let [url (get-in r [:launch :session-url])]
          [:a {:href url :target "_blank" :rel "noreferrer" :class parts/button} "Claude session ↗"])
        (when (and branch (get r :run))
          [:a {:href (results-branch-url sys branch) :target "_blank" :rel "noreferrer"
               :class parts/button} "Results branch ↗"])
        [:a {:href "#remarks" :class parts/button} "Remarks for Owllie"]
        (when-not (review/active? r)
          [:form {:method "post" :action (str "/owldesk/review/" run-id "/remove")
                  :onsubmit "return confirm('Delete this review and its results branch?')"}
           [:button {:type "submit" :class parts/button} "Remove"]])]]
      [:div {:class (str "flex flex-wrap items-center justify-between gap-3 px-4 py-3 " separated)}
       (run-picker r runs)
       (run-again-form sys r runs)]]
     (schema-warning r)
     (state-banner sys r)
     (when (and (:run r) (not= :done (:state r)))
       (progress-card sys r review-href))
     (when (seq (get-in r [:run :steps]))
       (list (tabs r (when-not diff? (:id shown)) review-href [diff? diff-href "Diff"])
             (if diff? (diff-view r ctx) (step-view r shown ctx))))
     (remarks-section sys r)]))

;; ------------------------------------------------------- writing a remark

(defn- textarea [name value]
  [:textarea {:name name :rows 5 :maxlength (:max-note-length review/tuning-limits)
              :class (str parts/input " w-full font-sans")}
   (str value)])

(defn- severity-select [name value]
  [:select {:name name :class parts/input}
   [:option {:value ""} "—"]
   (for [s review/severities]
     [:option {:value s :selected (= s value)} s])])

(defn remark-form-page
  "The form a remark is written in: on a finding, on lines of the diff, or on
  the review as a whole; or the form a draft is changed in. `values` are what
  the operator typed, kept when the remark is refused."
  [sys r {:keys [target remark values fault]}]
  (let [run-id (:run-id r)
        editing? (some? remark)
        target (if editing?
                 (cond
                   (:finding remark) {:fingerprint (get-in remark [:finding :fingerprint])}
                   (:lines remark) (:lines remark)
                   :else {:kind "general"})
                 target)
        finding (when (:fingerprint target) (get (review/findings r) (:fingerprint target)))
        lines (when-not (or (:fingerprint target) (= "general" (:kind target)))
                (let [line (if (int? (:line target)) (:line target) (parse-long (str (:line target))))
                      end (or (if (int? (:end-line target)) (:end-line target) (parse-long (str (:end-line target)))) line)]
                  (when line {:file (str (:file target))
                              :side (if (#{:old "old"} (:side target)) :old :new)
                              :line line :end-line end})))
        kinds (cond
                editing? [(:kind remark)]
                finding (vec (filter review/finding-remark-kinds review/remark-kinds))
                lines [:missed]
                :else [:general])
        kind (or (review/parse-remark-kind (get values "kind")) (:kind remark) (first kinds))
        value (fn [k from-remark] (if (contains? values k) (get values k) from-remark))
        action (if editing? (str "/owldesk/review/" run-id "/remarks/" (:id remark)) (str "/owldesk/review/" run-id "/remarks"))]
    [:div {:class "flex flex-col gap-4"}
     [:div {:class "flex flex-wrap items-center gap-2"}
      (parts/back-link (str (review-href run-id) "#remarks"))
      [:span {:class parts/muted} (or (:title r) (pr-label (:pull-request r)))]]
     (when fault (notice :refused (str "Refused: " (get remark-fault-text fault (parts/humanise fault)) ".")))
     [:section {:class parts/card}
      [:div {:class parts/card-heading}
       [:h1 {:class parts/heading-text}
        (cond editing? "Change a draft"
              finding "A remark on a finding"
              lines "A missed remark"
              :else "A general remark")]]
      [:div {:class "flex flex-col gap-3 px-4 py-3"}
       (cond
         finding (finding-card r finding {:review? true :judged {} :remarked #{}})
         (and editing? (seq (:excerpt remark))) (code/excerpt-view (:excerpt remark))
         lines (if-let [excerpt (seq (review/lines-excerpt r lines))]
                 (code/excerpt-view excerpt)
                 (notice :warn "Those lines are not lines of the review's diff.")))
       [:form {:method "post" :action action :class "flex flex-col gap-3"}
        (when finding [:input {:type "hidden" :name "fingerprint" :value (:fingerprint target)}])
        (when (and lines (not editing?))
          (list [:input {:type "hidden" :name "file" :value (:file lines)}]
                [:input {:type "hidden" :name "side" :value (name (:side lines))}]
                [:input {:type "hidden" :name "line" :value (str (:line lines))}]))
        (if (> (count kinds) 1)
          [:fieldset {:class "flex flex-col gap-2"}
           [:legend {:class "text-sm font-medium"} "What the remark says"]
           (for [k kinds]
             [:label {:class "flex items-start gap-2 text-sm"}
              [:input {:type "radio" :name "kind" :value (name k) :checked (= k kind) :class "mt-1 accent-sky-600"}]
              [:span [:span {:class "font-medium"} (kind-text k)] " — "
               [:span {:class parts/muted} (kind-hints k)]]])]
          (list [:input {:type "hidden" :name "kind" :value (name kind)}]
                [:p {:class parts/muted} (kind-hints kind)]))
        (when (and lines (not editing?))
          [:label {:class "flex flex-col gap-1"}
           [:span {:class "text-sm font-medium"} "Up to line"]
           [:input {:type "number" :name "end-line" :class (str parts/input " w-32")
                    :value (str (value "end-line" (:end-line lines)))
                    :min (:line lines)}]
           [:span {:class parts/faint} (str "From line " (:line lines) " of " (:file lines)
                                            (when (= :old (:side lines)) ", on the removed side") ".")]])
        (when (or (some #{:wrong-severity} kinds) (= :missed kind))
          [:label {:class "flex flex-col gap-1"}
           [:span {:class "text-sm font-medium"}
            (if (= :missed kind) "Severity it should have had (optional)" "Severity it should have (wrong severity only)")]
           (severity-select "proposed-severity" (value "proposed-severity" (:proposed-severity remark)))])
        (when (and (= :missed kind) (not editing?))
          [:label {:class "flex flex-col gap-1"}
           [:span {:class "text-sm font-medium"} "Rule that should have applied (optional)"]
           [:input {:type "text" :name "rule" :class (str parts/input " w-64 font-mono")
                    :placeholder "R-CONC-001, or empty for a rule to write"
                    :value (str (value "rule" ""))}]])
        [:label {:class "flex flex-col gap-1"}
         [:span {:class "text-sm font-medium"} "Note"]
         (textarea "note" (value "note" (:note remark)))
         [:span {:class parts/faint}
          "Your words about the code: what is wrong, or right, and why. Required for unclear, missed and general remarks."]]
        [:div {:class "flex flex-wrap items-center gap-3"}
         [:button {:type "submit" :class parts/primary-button} (if editing? "Save the draft" "Keep as a draft")]
         [:span {:class parts/faint} "Nothing is sent until you send the review's drafts."]]]]]]))

;; ----------------------------------------------------------- one tuning

(defn- tuning-header [sys tn]
  (let [review-run-id (:review-run-id tn)
        branch (or (get-in tn [:run :branch]) (get-in tn [:launch :branch]))]
    [:section {:class parts/card}
     [:div {:class "flex flex-col gap-2 px-4 py-4"}
      [:div {:class "flex flex-wrap items-center gap-2"}
       (parts/back-link (if review-run-id (str (review-href review-run-id) "#remarks") "/owldesk"))
       (when-let [pr (get-in tn [:launch :pull-request])]
         [:a {:href (pr-url pr) :target "_blank" :rel "noreferrer" :class (str parts/muted " hover:underline")}
          (str "remarks on " (pr-label pr))])]
      [:h1 {:class "text-xl font-semibold"} (or (:title tn) (str "Tuning " (:run-id tn)))]
      [:div {:class "flex flex-wrap items-center gap-2 text-sm text-slate-500 dark:text-slate-400"}
       (tuning-chip tn)
       [:span {:class "font-mono text-xs"} (:run-id tn)]
       (when-let [h (seq (get-in tn [:run :headline]))]
         [:span {:class "tabular-nums"}
          (interpose " · " (for [{:keys [label value]} h] (str value " " label)))])]
      [:div {:class "flex flex-wrap items-center gap-2"}
       (when-let [pr (:pull-request tn)]
         (list [:a {:href (:url pr) :target "_blank" :rel "noreferrer" :class parts/primary-button}
                "Pull request on Owllie ↗"]
               (pull-request-chips pr)))
       (when-let [url (get-in tn [:launch :session-url])]
         [:a {:href url :target "_blank" :rel "noreferrer" :class parts/button} "Claude session ↗"])
       (when (and branch (:run tn))
         [:a {:href (results-branch-url sys branch) :target "_blank" :rel "noreferrer" :class parts/button}
          "Results branch ↗"])
       (when-not (:active? tn)
         [:form {:method "post" :action (str "/owldesk/tuning/" (:run-id tn) "/remove")
                 :onsubmit "return confirm('Delete this tuning and its results branch? Its pull request stays.')"}
          [:button {:type "submit" :class parts/button} "Remove"]])]
      (when-let [pr (:pull-request tn)]
        (when (and (= :open (:state pr)) (:draft pr))
          [:p {:class parts/muted}
           "Opened as a draft: the session found a regression, a remark not met on the real diff, or a remark it did not apply. Its summary says which."]))]]))

(defn tuning-page
  "One tuning in full: where it stands, its pull request, then the view of
  one of its steps, read like a review's."
  [sys tn {:keys [step]}]
  (let [shown (current-step tn step)
        sent (when-let [rid (:review-run-id tn)]
               (filter #(= (:run-id tn) (:batch %)) (review/remarks sys rid)))]
    [:div {:id "owldesk-tuning" :class "flex flex-col gap-4"}
     (tuning-header sys tn)
     (state-banner sys tn)
     (when (and (:run tn) (not= :done (:state tn)))
       (progress-card sys tn tuning-href))
     (when (seq (get-in tn [:run :steps]))
       (list (tabs tn (:id shown) tuning-href)
             (step-view tn shown {})))
     (when (seq sent)
       [:section {:class parts/card}
        [:div {:class parts/card-heading}
         [:h2 {:class parts/heading-text} "The remarks it was sent"]
         [:span {:class parts/faint} (plural (count sent) "remark")]]
        [:ul (for [[i s] (map-indexed vector sent)]
               (remark-summary nil s [:span {:class "font-mono"} (str "r" (inc i))]))]])]))

;; ---------------------------------------------------------------- settings

(def ^:private fault-text
  {:invalid-results-repository "the results repository must be owner/name"
   :invalid-brain-ref "the brain ref is not a ref name"
   :retention-out-of-bounds "a retention is out of bounds"})

(def ^:private access-text
  {:unchecked [:neutral "not checked"]
   :ready [:green "ready"]
   :unreadable [:red "unreadable with the GitHub credential"]
   :unmarked [:amber (str "no " review/marker " marker")]})

(defn- credential-line [label present? hint]
  [:li {:class "flex flex-wrap items-center gap-2 text-sm"}
   (parts/dot (if present? :green :neutral))
   [:span {:class "font-mono text-xs"} label]
   [:span {:class parts/muted} (if present? "set" (str "not set — " hint))]])

(defn settings-section
  "The review settings, saved apart from the gitdesk configuration."
  [sys {:keys [outcome]}]
  (let [in-force (review/settings sys)
        status (review/results-access sys)
        [tone label] (access-text status)
        field (fn [{:keys [label name value suffix hint type]}]
                [:label {:class "flex flex-col gap-1"}
                 [:span {:class "text-sm font-medium"} label]
                 [:span {:class "flex items-center gap-2"}
                  [:input {:class parts/input :type (or type "text") :name name :value (str value)}]
                  (when suffix [:span {:class parts/muted} suffix])]
                 (when hint [:span {:class parts/faint} hint])])]
    [:div {:id "owldesk-settings" :class "flex flex-col gap-4"}
     (when outcome
       (if (:ok outcome)
         (notice :ok "Review settings saved. The results repository is checked again.")
         (notice :refused (str "Refused: " (fault-text (:fault outcome) (parts/humanise (:fault outcome)))
                               ". Nothing changed."))))
     [:section {:class parts/card}
      [:div {:class parts/card-heading}
       [:h2 {:class parts/heading-text} "Owllie reviews"]
       (parts/chip "saved apart" :sky)]
      [:form {:method "post" :action "/settings/owldesk"
              :data-on-submit "@post('/settings/owldesk', {contentType: 'form'})"
              :class "flex flex-col gap-4 p-4"}
       [:fieldset {:class "flex flex-col gap-2"}
        [:legend {:class "text-sm font-medium"} "Launcher"]
        [:div {:class "flex flex-wrap gap-2"}
         (for [[kind text] [[:scheduled-task "Scheduled task (API trigger)"]
                            [:claude-cli "Claude CLI on this machine"]]]
           [:label {:class "flex cursor-pointer items-center gap-2 rounded-lg border border-slate-300 px-3 py-1.5 text-sm dark:border-slate-700"}
            [:input {:type "radio" :name "launcher" :value (name kind) :class "accent-sky-600"
                     :checked (= kind (:launcher in-force))}]
            text
            (parts/chip (if (review/launcher-available? sys kind) "configured" "not configured")
                        (if (review/launcher-available? sys kind) :green :neutral))])]
        [:ul {:class "flex flex-col gap-1"}
         (credential-line "OWLLIE_FIRE_URL / OWLLIE_FIRE_TOKEN" (review/launcher-available? sys :scheduled-task)
                          "copy them from the scheduled task's API trigger")
         (credential-line "OWLLIE_TUNE_FIRE_URL / OWLLIE_TUNE_FIRE_TOKEN"
                          (review/tune-launcher-available? sys :scheduled-task)
                          "the owllie-tune scheduled task's API trigger, to send remarks to Owllie")
         (credential-line "OWLLIE_CLI_DIR" (review/launcher-available? sys :claude-cli)
                          "a clone of Owllie, with the Claude CLI signed in; it runs reviews and tunings")
         (credential-line "OWLLIE_RESULTS_TOKEN" (review/writer-available? sys)
                          "needed to delete results branches")]]
       [:div {:class "grid gap-4 md:grid-cols-2"}
        (field {:label "Results repository" :name "results-repository"
                :value (or (some-> (:results-repository in-force) config/repository-key) "")
                :hint (str "owner/name; its default branch must hold a " review/marker " file.")})
        (field {:label "Default brain ref" :name "brain-ref" :value (or (:brain-ref in-force) "")
                :hint "The Owllie branch, tag or commit whose rules are used. Empty: its default branch."})]
       [:div {:class "flex flex-wrap items-center gap-2 text-sm"}
        [:span "Results repository:"] (parts/chip label tone)
        (when-let [at (review/results-access-checked-at sys)]
          [:span {:class parts/faint} (str "checked " (t/format-instant at))])]
       [:div {:class "grid gap-4 md:grid-cols-3"}
        (field {:label "Keep the last reviews of a pull request" :name "keep-runs" :type "number"
                :value (:keep-runs in-force) :suffix "reviews"})
        (field {:label "Remove reviews of closed pull requests after" :name "closed-days" :type "number"
                :value (quot (:closed-retention-ms in-force) (t/days 1)) :suffix "days"})
        (field {:label "Remove failed reviews after" :name "failed-days" :type "number"
                :value (quot (:failed-retention-ms in-force) (t/days 1)) :suffix "days"})]
       [:div {:class "flex flex-wrap items-center gap-3"}
        [:button {:class parts/primary-button :type "submit"} "Save review settings"]
        [:button {:class parts/button :type "submit" :formaction "/settings/owldesk/check"
                  :data-on-click__prevent "@post('/settings/owldesk/check')"}
         "Check the results repository"]
        [:a {:href "/owldesk/cleanup" :class (str parts/muted " hover:underline")} "Clean up old results →"]]]]]))

(defn read-settings-proposal [params]
  (let [number (fn [k default] (or (parse-long (str (get params k ""))) default))]
    {:results-repository (str (get params "results-repository" ""))
     :brain-ref (str (get params "brain-ref" ""))
     :launcher (keyword (str (get params "launcher" "scheduled-task")))
     :keep-runs (number "keep-runs" 3)
     :closed-retention-ms (* (number "closed-days" 14) (t/days 1))
     :failed-retention-ms (* (number "failed-days" 3) (t/days 1))}))

;; ------------------------------------------------------------------ cleanup

(def ^:private reason-text
  {:beyond-kept "more recent reviews are kept"
   :pull-request-closed "pull request closed"
   :failed-long-ago "failed long ago"})

(defn cleanup-page [sys {:keys [outcome]}]
  (let [candidates (review/cleanup-candidates sys)
        with-branch (count (filter #(get-in % [:review :run]) candidates))]
    [:div {:id "owldesk-cleanup" :class "flex flex-col gap-4"}
     (parts/back-link "/settings")
     (when outcome
       (if (empty? (:failed outcome))
         (notice :ok (str (plural (:removed outcome) "review") " removed."))
         (notice :refused (str (plural (:removed outcome) "review") " removed, "
                               (count (:failed outcome)) " could not be: "
                               (str/join ", " (distinct (map (comp parts/humanise :fault) (:failed outcome))))))))
     [:section {:class parts/card}
      [:div {:class parts/card-heading}
       [:h2 {:class parts/heading-text} "Clean up old results"]
       [:span {:class parts/faint} (plural (count candidates) "review")]]
      (if (empty? candidates)
        (parts/empty-state "Nothing to clean up.")
        [:ul
         (for [{r :review reasons :reasons} candidates]
           [:li {:class (str "flex flex-wrap items-center gap-2 px-4 py-2 text-sm " separated)}
            [:a {:href (review-href (:run-id r)) :class "font-mono text-xs hover:underline"} (:run-id r)]
            [:span (pr-label (:pull-request r))]
            (state-chip r)
            [:span {:class parts/faint} (str/join ", " (map reason-text (sort reasons)))]
            (when-not (:run r) [:span {:class parts/faint} "(no branch)"])])])
      (when (seq candidates)
        [:form {:method "post" :action "/owldesk/cleanup" :class (str "flex flex-wrap items-center gap-3 px-4 py-3 " separated)
                :onsubmit "return confirm('Delete these reviews and their results branches?')"}
         (when (and (pos? with-branch) (not (review/writer-available? sys)))
           (notice :warn "OWLLIE_RESULTS_TOKEN is not set: reviews with a branch cannot be removed."))
         [:button {:type "submit" :class (str parts/primary-button " bg-rose-600 hover:bg-rose-500")}
          (str "Remove " (plural (count candidates) "review"))]
         [:span {:class parts/faint}
          (str (plural with-branch "branch") " deleted from the results repository. "
               "What you judged of their findings stays.")]])]]))

;; ------------------------------------------------------------------- badge

(defn badge-status
  "`[tone label]` for the shell's Owllie badge."
  [sys]
  (let [in-force (review/settings sys)]
    (cond
      (not (review/launcher-available? sys (:launcher in-force))) [:neutral "no owllie launcher"]
      (nil? (:results-repository in-force)) [:amber "owllie: no results repo"]
      (= :ready (review/results-access sys)) [:green "owllie ready"]
      (= :unchecked (review/results-access sys)) [:amber "owllie unchecked"]
      :else [:red "owllie results repo"])))
