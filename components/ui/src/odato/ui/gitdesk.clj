(ns odato.ui.gitdesk
  "What gitdesk shows the operator: the operator's own counts, the tracked
  members side by side, the lists behind them, and one pull request in
  detail.

  Every group counts from the one snapshot the installation holds, reading the
  classifications as they stand. Nothing here fetches anything: a count that
  changes because a threshold changed is the same snapshot, classified again."
  (:require [clojure.string :as str]
            [odato.access.interface :as access]
            [odato.collector.interface :as collector]
            [odato.config.interface :as config]
            [odato.pull-request.interface :as prs]
            [odato.time.interface :as t]
            [odato.ui.parts :as parts]))

(defn- pr-href [pull-request]
  (str "/gitdesk/pull-request/" (get-in pull-request [:repository :owner])
       "/" (get-in pull-request [:repository :name]) "/" (:number pull-request)))

(defn- repository-label [pull-request]
  (str (get-in pull-request [:repository :owner]) "/"
       (get-in pull-request [:repository :name]) " #" (:number pull-request)))

(defn- classification-chips [pull-request]
  (list
   (when (:stuck? pull-request) (parts/chip "stuck" :red))
   (when (:aging? pull-request) (parts/chip "aging" :amber))
   (when (:conflicting? pull-request) (parts/chip "conflicting" :amber))
   (when (:difficult? pull-request) (parts/chip "difficult" :violet))
   (when (:merging? pull-request) (parts/chip "merging" :green))
   (when (:recently-merged? pull-request) (parts/chip "merged" :sky))
   (when (get-in pull-request [:facts :is-draft]) (parts/chip "draft" :neutral))))

(def ^:private separated "border-t border-slate-200 dark:border-slate-800")

(def ^:private row-class
  (str "flex flex-col gap-1 px-4 py-3 transition hover:bg-slate-50 "
       "dark:hover:bg-slate-800/60 " separated))

(defn- draft? [pull-request]
  (boolean (get-in pull-request [:facts :is-draft])))

(defn- plural [n word]
  (str n " " word (when (not= 1 n) "s")))

;; ------------------------------------------------------------------ drafts
;;
;; Drafts are always counted, and listed only once the operator asks for them.
;; Each list carries its own toggle: a Datastar signal, off whenever the page
;; is opened. It lives in the page, so flipping it fetches nothing, and it is
;; declared only if missing, so a collection patching the list in place leaves
;; it as the operator set it.

(defn- drafts-signal
  "The attribute declaring a list's toggle, off."
  [signal]
  {:data-signals__ifmissing (str "{" signal ": false}")})

(defn- drafts-toggle
  "The button showing or hiding a list's drafts, when it has any."
  [signal pull-requests]
  (let [n (count (filter draft? pull-requests))]
    (when (pos? n)
      [:button {:type "button" :class parts/button
                :data-on-click (str "$" signal " = !$" signal)
                :data-text (str "$" signal " ? 'Hide drafts' : 'Show " (plural n "draft") "'")}
       (str "Show " (plural n "draft"))])))

(defn- pr-row [now-ms signal pull-request]
  [:li (cond-> {:class row-class}
         ;; Hidden before Datastar runs too, so a draft is never listed unasked.
         (draft? pull-request) (assoc :style "display: none"
                                      :data-show (str "$" signal)))
   [:div {:class "flex flex-wrap items-baseline gap-x-2 gap-y-1"}
    [:a {:href (pr-href pull-request)
         :class "font-medium text-slate-900 hover:underline dark:text-slate-100"}
     (get-in pull-request [:facts :title])]
    [:span {:class parts/faint} (repository-label pull-request)]]
   [:div {:class "flex flex-wrap items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400"}
    [:span (str "by " (get-in pull-request [:facts :author]))]
    [:span "·"]
    (parts/relative-time now-ms (get-in pull-request [:facts :updated-at]))
    (parts/check-chip (:head-check pull-request))
    (when (:approved? pull-request) (parts/chip "approved" :green))
    (when (seq (:change-requests pull-request)) (parts/chip "changes requested" :amber))
    (classification-chips pull-request)]])

(defn- pr-rows
  "A list's pull requests, drafts hidden until its toggle shows them, then what
  to say when nothing else is in it. Every row carries its own top border, so
  the hidden ones leave no stray line behind."
  [now-ms signal pull-requests empty-message]
  (let [only-drafts? (and (seq pull-requests) (every? draft? pull-requests))]
    (list
     (when (seq pull-requests)
       [:ul (map #(pr-row now-ms signal %) pull-requests)])
     (when-not (some (complement draft?) pull-requests)
       [:div (cond-> {:class separated}
               only-drafts? (assoc :data-show (str "!$" signal)))
        (parts/empty-state (if only-drafts? "Only drafts here." empty-message))]))))

(defn- pr-list [title signal now-ms pull-requests empty-message]
  [:section (merge {:class parts/card} (drafts-signal signal))
   [:div {:class "px-4 py-3 flex items-center justify-between gap-3"}
    [:h2 {:class parts/heading-text} title]
    [:div {:class "flex items-center gap-2"}
     (drafts-toggle signal pull-requests)
     [:span {:class parts/muted} (str (count pull-requests))]]]
   (pr-rows now-ms signal pull-requests empty-message)])

;; ------------------------------------------------------------- the surfaces

(defn- my-work
  "The operator's own business: what they wrote, what waits for them, what was
  handed to them, and what has come back to them."
  [operator pull-requests]
  (let [open (filterv :open? pull-requests)]
    {:authored (filterv #(prs/authored-by? % operator) open)
     :to-review (filterv #(prs/review-requested-from? % operator) open)
     :assigned (filterv #(prs/assigned-to? % operator) open)
     :addressed (filterv #(prs/addressed-for? % operator) open)}))

(def my-work-groups
  "The four groups of the operator's own business, in the order the dashboard
  shows them. The slug names the page dedicated to each one."
  [{:group :authored :slug "authored" :label "authored" :title "Authored"
    :empty "Nothing you wrote is in flight."}
   {:group :to-review :slug "to-review" :label "to review" :title "To review"
    :empty "Nobody is waiting on your review."}
   {:group :assigned :slug "assigned" :label "assigned" :title "Assigned"
    :empty "Nothing is assigned to you."}
   {:group :addressed :slug "addressed" :label "addressed" :title "Addressed"
    :empty "Nothing has come back to you."}])

(defn my-work-group
  "The group a page slug names, or nil."
  [slug]
  (first (filter #(= slug (:slug %)) my-work-groups)))

(defn- my-work-href [{:keys [slug]}]
  (str "/gitdesk/my/" slug))

(defn- monitoring
  "The pull requests worth a second look: open too long, argued over, reworked
  too often, or approved and going nowhere."
  [pull-requests]
  {:stuck (filterv :stuck? pull-requests)
   :aging (filterv :aging? pull-requests)
   :conflicting (filterv :conflicting? pull-requests)
   :difficult (filterv :difficult? pull-requests)})

(defn- merge-train
  "What is on its way in, and what has just landed."
  [pull-requests]
  {:merging (filterv :merging? pull-requests)
   :just-merged (filterv :recently-merged? pull-requests)})

(defn- listed
  "The lists the dashboard shows: most recent activity first, and each pull
  request once however many groups claim it."
  [& groups]
  (->> (apply concat groups)
       (reduce (fn [acc pull-request] (assoc acc (:ident pull-request) pull-request)) {})
       vals
       prs/by-recent-activity
       vec))

;; --------------------------------------------------------- collection status

(defn- source-label [source]
  (if (= :repository-pull-requests (:kind source))
    (:repository source)
    (parts/humanise (:kind source))))

(def ^:private collection-heading
  "px-4 py-3 flex items-center justify-between gap-3")

(def ^:private collection-details
  ;; Rows animate between 0fr and 1fr. The state being entered sets the
  ;; duration: opening on hover takes 1s, closing on leave takes 350ms.
  (str "grid grid-rows-[0fr] transition-[grid-template-rows] duration-350 ease-in "
       "group-hover:grid-rows-[1fr] group-hover:duration-1000 group-hover:ease-out "
       "group-has-focus-visible:grid-rows-[1fr] motion-reduce:transition-none"))

(defn- collection-status
  "The band saying where the collection stands. Only its heading shows; the
  sources open under it while the pointer is over the band, and close when
  it leaves. A failed source is still named in the heading."
  [sys now-ms]
  (let [snapshot (collector/snapshot sys)
        active (first (collector/active-cycles sys))
        failed (count (filter #(= :failed (:last-outcome %)) (:sources snapshot)))]
    [:section {:class (str parts/card " group") :id "collection-status"}
     [:div {:class collection-heading}
      [:h2 {:class parts/heading-text} "Collection"]
      [:div {:class "flex items-center gap-2"}
       (when (pos? failed)
         (parts/chip (str failed " failed") :red))
       (cond
         (and active (:waiting-on-quota active))
         (parts/chip "waiting on quota" :amber)

         active
         (parts/chip (str "collecting (" (- (:total active) (:queued active))
                          "/" (:total active) ")")
                     :sky)

         (:has-ever-collected snapshot)
         [:span {:class parts/muted}
          (list "collected "
                (parts/relative-time now-ms (:oldest-success-at snapshot)))]

         :else (parts/chip "never collected" :neutral))
       [:button {:class parts/button
                 :data-on-click "@post('/gitdesk/refresh')"}
        "Refresh"]]]
     [:div {:class collection-details}
      [:div {:class "min-h-0 overflow-hidden"}
       [:div {:class "border-t border-slate-200 dark:border-slate-800"}
        (if (seq (:sources snapshot))
          [:ul {:class "divide-y divide-slate-200 text-sm dark:divide-slate-800"}
           (for [source (:sources snapshot)]
             [:li {:class "flex flex-wrap items-center justify-between gap-2 px-4 py-2"}
              [:span {:class "font-medium"} (source-label source)]
              [:span {:class "flex items-center gap-2"}
               (case (:last-outcome source)
                 :succeeded (parts/chip "refreshed" :green)
                 :failed (parts/chip (str "failed: "
                                          (parts/humanise (:last-failure-reason source)))
                                     :red)
                 (parts/chip "never collected" :neutral))
               (when (:last-success-at source)
                 [:span {:class parts/muted}
                  (parts/relative-time now-ms (:last-success-at source))])]])]
          (parts/empty-state
           (if (config/collectable? sys)
             "Nothing has been collected yet."
             "Nothing is tracked yet — add a repository and a member in settings.")))]]]]))

;; ------------------------------------------------------------ collections

(defn- collection-card
  "A card listing one collection of pull requests: its heading, how many it
  holds and how many of them are drafts, with the toggle showing those, then
  the pull requests, most recent activity first."
  [now-ms signal heading pull-requests empty-message]
  (let [pull-requests (listed pull-requests)
        drafts (count (filter draft? pull-requests))]
    [:section (merge {:class parts/card} (drafts-signal signal))
     [:div {:class "flex flex-col gap-1 px-4 py-4"}
      heading
      [:div {:class "flex flex-wrap items-center justify-between gap-2"}
       [:p {:class parts/muted}
        (plural (count pull-requests) "pull request")
        (when (pos? drafts) (str " · " (plural drafts "draft")))]
       (drafts-toggle signal pull-requests)]]
     (pr-rows now-ms signal pull-requests empty-message)]))

(def ^:private back-link
  [:a {:href "/gitdesk" :class (str parts/muted " w-fit hover:underline")} "← gitdesk"])

(def ^:private page-title "text-2xl font-semibold tracking-tight")

(def ^:private arrow
  [:span {:class "text-emerald-500" :aria-hidden "true"} "▶"])

;; ---------------------------------------------------------------- my work

(def ^:private summary-item
  (str "group flex flex-col items-center gap-1 rounded-lg px-3 py-3 transition "
       "hover:bg-slate-100 dark:hover:bg-slate-800/60"))

(def ^:private summary-label
  (str "flex items-center gap-1.5 text-xs font-medium uppercase tracking-widest "
       "text-slate-500 dark:text-slate-400"))

(def ^:private summary-count
  (str "text-4xl font-semibold tabular-nums text-slate-900 group-hover:text-sky-600 "
       "dark:text-slate-100 dark:group-hover:text-sky-400"))

(defn- my-work-summary
  "The four counts of the operator's own business. Each one leads to the page
  that lists its pull requests."
  [mine]
  [:section {:class parts/card :id "my-work"}
   [:div {:class "flex flex-col gap-2 px-4 pt-4 pb-3"}
    [:h2 {:class page-title} "My work"]
    [:div {:class "grid grid-cols-2 gap-2 sm:grid-cols-4"}
     (for [{:keys [group label] :as g} my-work-groups]
       [:a {:href (my-work-href g) :class summary-item
            :title (str "The pull requests " label)}
        [:span {:class summary-label} label arrow]
        [:span {:class summary-count} (str (count (get mine group)))]])]]])

(defn my-work-page
  "One group of the operator's own business, in full: every pull request in
  it, most recent activity first, drafts once the operator shows them."
  [sys {:keys [group title empty]}]
  (let [now-ms (t/now-ms (:clock sys))
        operator (access/operator-login sys)]
    [:div {:id "gitdesk-my-work" :class "flex flex-col gap-4"}
     (collection-card now-ms "draftsListed"
                      (list back-link [:h1 {:class page-title} title])
                      (get (my-work operator (collector/pull-requests sys)) group)
                      (if operator
                        empty
                        "No valid GitHub credential, so there is no \"me\" yet."))]))

;; -------------------------------------------------------------- team work

(defn tracked-member
  "The login, when it names a tracked member; nil otherwise."
  [sys login]
  (get (:members (config/current sys)) login))

(defn- member-href [member]
  (str "/gitdesk/member/" member))

(defn- member-work
  "What a member has in flight: what they wrote, and what was handed to them."
  [member pull-requests]
  (let [open (filterv :open? pull-requests)]
    {:authored (filterv #(prs/authored-by? % member) open)
     :assigned (filterv #(prs/assigned-to? % member) open)}))

(def ^:private segment-tones
  "The colours of the former gitdesk, kept so the chart reads as it did."
  {:in-review "bg-[#fdd77c]"
   :drafted "bg-[#a17202]"
   :assigned "bg-[#a0d5ff]"})

(defn- segment [kind n scale]
  (when (pos? n)
    [:div {:class (str "h-full " (segment-tones kind))
           :style (str "width:" (scale n) "%")
           :title (case kind
                    :in-review (str n " in review")
                    :drafted (plural n "draft")
                    :assigned (str n " assigned"))}]))

(defn- member-bar
  "One member's bar. Every bar shares the same axis: what they wrote runs to
  its left, drafts closest to it, and what was handed to them to its right, so
  the members read against each other at a glance."
  [{:keys [authored drafted assigned]} max-authored scale]
  [:div {:class "flex h-6 w-full"}
   [:div {:style (str "width:" (scale (- max-authored authored)) "%")}]
   (segment :in-review (- authored drafted) scale)
   (segment :drafted drafted scale)
   (segment :assigned assigned scale)])

(defn- legend-item [kind label]
  [:span {:class "flex items-center gap-1.5"}
   [:span {:class (str "inline-block h-2.5 w-2.5 rounded-sm " (segment-tones kind))}]
   label])

(defn- team-work
  "The tracked members side by side: what each has in flight, authored against
  assigned. Each member leads to the page listing those pull requests."
  [in-force pull-requests]
  (let [stats (for [member (sort (:members in-force))
                    :let [{:keys [authored assigned]} (member-work member pull-requests)]]
                {:member member
                 :authored (count authored)
                 :drafted (count (filter #(get-in % [:facts :is-draft]) authored))
                 :assigned (count assigned)})
        max-authored (reduce max 0 (map :authored stats))
        max-size (+ max-authored (reduce max 0 (map :assigned stats)))
        scale #(* 100.0 (/ % (max 1 max-size)))]
    [:section {:class parts/card :id "team-work"}
     [:div {:class "flex flex-col gap-3 px-4 pt-4 pb-4"}
      [:div {:class "flex flex-wrap items-baseline justify-between gap-2"}
       [:div {:class "flex items-baseline gap-2"}
        [:h2 {:class page-title} "Team work"]
        [:span {:class parts/muted} "(authored / assigned)"]]
       [:div {:class (str "flex flex-wrap items-center gap-3 " parts/faint)}
        (legend-item :in-review "in review")
        (legend-item :drafted "drafts")
        (legend-item :assigned "assigned")]]
      (if (seq stats)
        [:ul {:class "flex flex-col gap-2"}
         (for [{:keys [member authored assigned] :as stat} stats]
           [:li {:class "grid items-center gap-x-4 gap-y-1 sm:grid-cols-[minmax(0,1fr)_3fr]"}
            [:a {:href (member-href member)
                 :class "flex w-fit items-center gap-1.5 text-sm hover:underline"}
             [:span {:class "font-medium"} (str "@" member)]
             [:span {:class parts/muted} (str "(" authored " / " assigned ")")]
             arrow]
            (member-bar stat max-authored scale)])]
        (parts/empty-state "No members are tracked yet."))]]))

(defn member-page
  "One tracked member's pull requests in flight: what they wrote, then what
  was handed to them."
  [sys member]
  (let [now-ms (t/now-ms (:clock sys))
        {:keys [authored assigned]} (member-work member (collector/pull-requests sys))]
    [:div {:id "gitdesk-member" :class "flex flex-col gap-4"}
     [:section {:class parts/card}
      [:div {:class "flex flex-col gap-1 px-4 py-4"}
       back-link
       [:h1 {:class page-title} (str "@" member)]]]
     (collection-card now-ms "draftsAuthored" [:h2 {:class page-title} "Authored"] authored
                      (str "Nothing by @" member " is in flight."))
     (collection-card now-ms "draftsAssigned" [:h2 {:class page-title} "Assigned"] assigned
                      (str "Nothing is assigned to @" member "."))]))

;; ------------------------------------------------------------------ the body

(defn body
  "The fragment SSE patches in place while a collection runs."
  [sys]
  (let [now-ms (t/now-ms (:clock sys))
        operator (access/operator-login sys)
        in-force (config/current sys)
        pull-requests (collector/pull-requests sys)
        watched (monitoring pull-requests)
        train (merge-train pull-requests)]
    [:div {:id "gitdesk" :class "flex flex-col gap-4"}
     (collection-status sys now-ms)

     (my-work-summary (my-work operator pull-requests))

     (team-work in-force pull-requests)

     (parts/stat-group
      "Monitoring"
      [{:label "stuck" :count (count (:stuck watched)) :tone :red}
       {:label "aging" :count (count (:aging watched)) :tone :amber}
       {:label "conflicting" :count (count (:conflicting watched)) :tone :amber}
       {:label "difficult" :count (count (:difficult watched)) :tone :violet}])

     (parts/stat-group
      "Merge train"
      [{:label "merging" :count (count (:merging train)) :tone :green}
       {:label "just merged" :count (count (:just-merged train)) :tone :sky}])

     [:div {:class "grid gap-4 lg:grid-cols-2"}
      (pr-list "Monitoring" "draftsMonitoring" now-ms
               (listed (:stuck watched) (:aging watched)
                       (:conflicting watched) (:difficult watched))
               "Nothing worth a second look.")
      (pr-list "Merge train" "draftsMergeTrain" now-ms
               (listed (:merging train) (:just-merged train))
               "Nothing on its way in.")]]))

;; ------------------------------------------------------- one pull request

(defn- people-card [pull-request facts]
  [:section {:class parts/card}
   [:div {:class parts/card-heading} [:h2 {:class parts/heading-text} "People"]]
   [:dl {:class "space-y-2 px-4 py-3 text-sm"}
    [:div [:dt {:class parts/muted} "Assignees"]
     [:dd (or (not-empty (str/join ", " (sort (:assignees facts)))) "—")]]
    [:div [:dt {:class parts/muted} "Review requested from"]
     [:dd (or (not-empty (str/join ", " (sort (:requested-reviewers facts)))) "—")]]
    [:div [:dt {:class parts/muted} "Back in their court"]
     [:dd (or (not-empty (str/join ", " (sort (:awaiting-rereview pull-request)))) "—")]]
    [:div [:dt {:class parts/muted} "Labels"]
     [:dd (or (not-empty (str/join ", " (sort (:labels facts)))) "—")]]]])

(defn- state-card [pull-request facts]
  [:section {:class parts/card}
   [:div {:class parts/card-heading} [:h2 {:class parts/heading-text} "State"]]
   [:dl {:class "space-y-2 px-4 py-3 text-sm"}
    [:div [:dt {:class parts/muted} "Comments"] [:dd (:comment-count facts)]]
    [:div [:dt {:class parts/muted} "Commits"] [:dd (:commit-count facts)]]
    [:div [:dt {:class parts/muted} "Mergeable"]
     [:dd (if (:mergeable facts) "yes" "no")]]
    [:div [:dt {:class parts/muted} "Auto-merge"]
     [:dd (if (:auto-merge-enabled facts) "armed" "off")]]
    [:div [:dt {:class parts/muted} "Changes requested"]
     [:dd (count (:change-requests pull-request))]]]])

(defn detail
  "The snapshot's view of the pull request, as of the last collection that saw
  it — not a fresh read of GitHub."
  [sys pull-request]
  (let [now-ms (t/now-ms (:clock sys))
        facts (:facts pull-request)]
    [:div {:class "flex flex-col gap-4"}
     [:section {:class parts/card}
      [:div {:class "flex flex-col gap-2 px-4 py-4"}
       [:div {:class "flex flex-wrap items-center gap-2"}
        [:a {:href "/gitdesk" :class (str parts/muted " hover:underline")} "← gitdesk"]
        [:span {:class parts/muted} (repository-label pull-request)]]
       [:h1 {:class "text-xl font-semibold"} (:title facts)]
       [:div {:class "flex flex-wrap items-center gap-1.5"}
        (parts/chip (str "by " (:author facts)) :neutral)
        (parts/check-chip (:head-check pull-request))
        (when (:approved? pull-request) (parts/chip "approved" :green))
        (classification-chips pull-request)]
       [:div {:class "flex flex-wrap gap-4 text-sm text-slate-500 dark:text-slate-400"}
        [:span (list "opened " (parts/relative-time now-ms (:opened-at facts)))]
        [:span (list "updated " (parts/relative-time now-ms (:updated-at facts)))]
        (when (:merged-at facts)
          [:span (list "merged " (parts/relative-time now-ms (:merged-at facts)))])
        [:span (list "last collected "
                     (parts/relative-time now-ms (:last-seen-at pull-request)))]]
       [:a {:href (:url facts) :class (str parts/primary-button " w-fit")
            :target "_blank" :rel "noreferrer"}
        "Open on GitHub"]]]
     [:div {:class "grid gap-4 md:grid-cols-2"}
      (people-card pull-request facts)
      (state-card pull-request facts)]]))
