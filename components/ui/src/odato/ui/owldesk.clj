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
   :pull-request-not-found "GitHub does not show that pull request."})

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

(defn- review-line [sys r]
  (let [now-ms (now sys)]
    [:li {:class "flex flex-wrap items-center gap-x-3 gap-y-1 py-1 text-sm"}
     [:a {:href (review-href (:run-id r)) :class "font-mono text-xs hover:underline"} (:run-id r)]
     (state-chip r)
     [:span {:class parts/muted} "asked " (parts/relative-time now-ms (:asked-at r))]
     (headline r)
     (when-let [failure (get-in r [:run :error :code])]
       [:span {:class parts/faint} failure])]))

(defn- pull-request-row [sys {:keys [pull-request reviews]}]
  (let [latest (first reviews)
        older (rest reviews)]
    [:li {:class (str "flex flex-col gap-1 px-4 py-3 " separated)}
     [:div {:class "flex flex-wrap items-baseline gap-x-2 gap-y-1"}
      [:a {:href (review-href (:run-id latest))
           :class "font-medium text-slate-900 hover:underline dark:text-slate-100"}
       (or (:title latest) (pr-label pull-request))]
      [:a {:href (pr-url pull-request) :target "_blank" :rel "noreferrer"
           :class (str parts/faint " hover:underline")} (pr-label pull-request)]
      [:span {:class parts/faint} (plural (count reviews) "review")]]
     [:ul (review-line sys latest)]
     (when (seq older)
       [:details {:class "text-sm"}
        [:summary {:class (str parts/muted " cursor-pointer")}
         (str "Earlier reviews (" (count older) ")")]
        [:ul {:class "pl-2"} (map #(review-line sys %) older)]])]))

(defn- reviews-card [sys]
  (let [groups (review/by-pull-request sys)
        by-repository (group-by #(config/repository-key (get-in % [:pull-request :repository]))
                                groups)
        {read-at :at polling-failure :failure} (review/last-read sys)]
    [:section {:class parts/card}
     [:div {:class parts/card-heading}
      [:h2 {:class parts/heading-text} "Reviews"]
      [:form {:method "post" :action "/owldesk/refresh"
              :data-on-submit "@post('/owldesk/refresh')"}
       [:button {:class parts/button :type "submit"} "Read the branches now"]]]
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

(defn- progress-card [sys r]
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
                 [:a {:href (review-href (:run-id r) (:id step)) :class "hover:underline"} (str (:title step))]
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
    [:div {:class "flex flex-wrap gap-2"}
     (button :relevant "Relevant")
     (button :false-positive "False positive")]))

(defn- quote-block [text]
  [:pre {:class (str "overflow-x-auto rounded-lg bg-slate-50 px-3 py-2 font-mono text-xs "
                     "dark:bg-slate-950")}
   (str text)])

(def ^:private finding-status-tones
  {"kept" :green "merged" :violet "dropped" :amber "rejected" :neutral})

(defn- finding-card [r finding judged]
  (let [{:keys [file line]} (when (map? (:location finding)) (:location finding))
        status (when (map? (:status finding)) (:status finding))]
    [:article {:class "flex flex-col gap-2 rounded-xl border border-slate-200 p-4 dark:border-slate-800"}
     [:div {:class "flex flex-wrap items-center gap-2"}
      (severity-chip (:severity finding))
      (when file [:code {:class "font-mono text-xs"} (str file (when line (str ":" line)))])
      (when (seq (:tags finding))
        [:span {:class parts/faint} (str/join ", " (map str (:tags finding)))])
      (when-let [value (:value status)]
        (parts/chip (str value) (finding-status-tones (str value) :neutral)))
      (when-let [reason (:reason status)] [:span {:class parts/faint} (str reason)])]
     (when (:quote finding) (quote-block (:quote finding)))
     (when (:body finding) (markdown/render (:body finding)))
     (when (and (map? (:fields finding)) (seq (:fields finding)))
       [:p {:class parts/faint}
        (interpose " · " (for [[k v] (:fields finding)] (str (name k) ": " v)))])
     (when (and (string? (:fingerprint finding))
                (contains? #{nil "kept" "merged"} (:value status)))
       (verdict-buttons r finding judged))]))

(defn- unknown-block [block]
  [:div {:class "flex flex-col gap-2"}
   (notice :warn (str "A block of type " (pr-str (:type block)) " this installation does not show:"))
   (quote-block (json/write-str block :escape-slash false))])

(defn- block-view
  "One block of a step, by its type. A type not known here is shown as such."
  [r judged block]
  (if-not (map? block)
    (unknown-block {:type nil :value block})
    (case (:type block)
      "markdown" (markdown/render (:text block))
      "code" (quote-block (:text block))
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
                    (for [item (:items block) :when (map? item)] (finding-card r item judged)))]
      (unknown-block block))))

(defn- tabs [r current-id]
  [:nav {:class "flex flex-wrap gap-1 border-b border-slate-200 dark:border-slate-800"}
   (for [step (get-in r [:run :steps])]
     (if (:result step)
       [:a {:href (review-href (:run-id r) (:id step))
            :class (str "flex items-center gap-1.5 whitespace-nowrap px-3 py-2 text-sm "
                        (if (= current-id (:id step))
                          "border-b-2 border-slate-900 font-semibold dark:border-slate-100"
                          "text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-100"))}
        (str (:title step))
        (when (#{:failed :skipped} (:state step)) (step-chip (:state step)))]
       [:span {:class "flex items-center gap-1.5 px-3 py-2 text-sm text-slate-400 dark:text-slate-600"}
        (str (:title step))]))])

(defn- step-view [sys r step]
  (let [result (:result step)
        judged (review/judgements sys (:run-id r))]
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
          (for [block (:blocks result)] (block-view r judged block)))]])))

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

(defn review-page
  "One review in full: where it stands, then the view of one of its steps."
  [sys r {:keys [step]}]
  (let [others (->> (review/by-pull-request sys)
                    (filter #(= (review/pull-request-key (:pull-request %))
                                (review/pull-request-key (:pull-request r))))
                    first :reviews)
        details (filter map? (get-in r [:run :process :details]))
        branch (or (get-in r [:run :branch]) (get-in r [:launch :branch]))
        shown (current-step r step)]
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
        [:span {:class "font-mono text-xs"} (:run-id r)]
        (for [{:keys [label value]} details] [:span (str label " " value)])]
       [:div {:class "flex flex-wrap gap-2"}
        (when-let [url (get-in r [:launch :session-url])]
          [:a {:href url :target "_blank" :rel "noreferrer" :class parts/button} "Claude session ↗"])
        (when (and branch (get r :run))
          [:a {:href (results-branch-url sys branch) :target "_blank" :rel "noreferrer"
               :class parts/button} "Results branch ↗"])
        (when-not (review/active? r)
          [:form {:method "post" :action (str "/owldesk/review/" (:run-id r) "/remove")
                  :onsubmit "return confirm('Delete this review and its results branch?')"}
           [:button {:type "submit" :class parts/button} "Remove"]])]]]
     (when (> (count others) 1)
       [:nav {:class "flex flex-wrap gap-2"}
        (for [o others]
          [:a {:href (review-href (:run-id o))
               :class (str parts/button (when (= (:run-id o) (:run-id r)) " ring-2 ring-sky-500"))}
           (:run-id o) " " (state-chip o)])])
     (schema-warning r)
     (state-banner sys r)
     (when (and (:run r) (not= :done (:state r)))
       (progress-card sys r))
     (when (seq (get-in r [:run :steps]))
       (list (tabs r (:id shown))
             (step-view sys r shown)))]))

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
         (credential-line "OWLLIE_CLI_DIR" (review/launcher-available? sys :claude-cli)
                          "a clone of Owllie, with the Claude CLI signed in")
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
