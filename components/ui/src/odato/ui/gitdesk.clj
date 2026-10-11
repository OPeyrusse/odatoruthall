(ns odato.ui.gitdesk
  "What gitdesk shows the operator: four groups of counts, the lists behind
  them, and one pull request in detail.

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

(def ^:private row-class
  (str "flex flex-col gap-1 px-4 py-3 transition hover:bg-slate-50 "
       "dark:hover:bg-slate-800/60"))

(defn- pr-row [now-ms pull-request]
  [:li {:class row-class}
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

(defn- pr-list [title now-ms pull-requests empty-message]
  [:section {:class parts/card}
   [:div {:class parts/card-heading}
    [:h2 {:class parts/heading-text} title]
    [:span {:class parts/muted} (str (count pull-requests))]]
   (if (seq pull-requests)
     [:ul {:class "divide-y divide-slate-200 dark:divide-slate-800"}
      (map #(pr-row now-ms %) pull-requests)]
     (parts/empty-state empty-message))])

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
  "The lists the dashboard shows: most recent activity first, each pull request
  once however many groups claim it, and drafts only when the operator has
  asked for them."
  [& groups]
  (->> (apply concat groups)
       (reduce (fn [acc pull-request] (assoc acc (:ident pull-request) pull-request)) {})
       vals
       (filter :listable?)
       prs/by-recent-activity
       vec))

;; --------------------------------------------------------- collection status

(defn- source-label [source]
  (if (= :repository-pull-requests (:kind source))
    (:repository source)
    (parts/humanise (:kind source))))

(defn- collection-status [sys now-ms]
  (let [snapshot (collector/snapshot sys)
        active (first (collector/active-cycles sys))]
    [:section {:class parts/card :id "collection-status"}
     [:div {:class parts/card-heading}
      [:h2 {:class parts/heading-text} "Collection"]
      [:div {:class "flex items-center gap-2"}
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
          "Nothing is tracked yet — add a repository and a member in settings.")))]))

;; -------------------------------------------------------------- team work

(def ^:private table-head
  "text-left text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400")

(defn- team-work [in-force pull-requests]
  [:section {:class parts/card}
   [:div {:class parts/card-heading}
    [:h2 {:class parts/heading-text} "Team work"]
    [:span {:class parts/muted} (str (count (:members in-force)) " tracked")]]
   (if (seq (:members in-force))
     [:table {:class "w-full text-sm"}
      [:thead
       [:tr {:class table-head}
        [:th {:class "px-4 py-2 font-medium"} "Member"]
        [:th {:class "px-4 py-2 text-right font-medium"} "Authored"]
        [:th {:class "px-4 py-2 text-right font-medium"} "Drafted"]
        [:th {:class "px-4 py-2 text-right font-medium"} "Assigned"]]]
      [:tbody {:class "divide-y divide-slate-200 dark:divide-slate-800"}
       (for [member (sort (:members in-force))]
         [:tr
          [:td {:class "px-4 py-2 font-medium"} member]
          [:td {:class "px-4 py-2 text-right tabular-nums"}
           (count (filter #(prs/authored-by? % member) pull-requests))]
          [:td {:class "px-4 py-2 text-right tabular-nums"}
           (count (filter #(prs/drafted-by? % member) pull-requests))]
          [:td {:class "px-4 py-2 text-right tabular-nums"}
           (count (filter #(prs/assigned-to? % member) pull-requests))]])]]
     (parts/empty-state "No members are tracked yet."))])

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
    [:h2 {:class "text-2xl font-semibold tracking-tight"} "My work"]
    [:div {:class "grid grid-cols-2 gap-2 sm:grid-cols-4"}
     (for [{:keys [group label] :as g} my-work-groups]
       [:a {:href (my-work-href g) :class summary-item
            :title (str "The pull requests " label)}
        [:span {:class summary-label}
         label
         [:span {:class "text-emerald-500" :aria-hidden "true"} "▶"]]
        [:span {:class summary-count} (str (count (get mine group)))]])]]])

(defn my-work-page
  "One group of the operator's own business, in full: every pull request in
  it, most recent activity first, drafts only when the operator asked for
  them."
  [sys {:keys [group title empty]}]
  (let [now-ms (t/now-ms (:clock sys))
        operator (access/operator-login sys)
        members (get (my-work operator (collector/pull-requests sys)) group)
        shown (listed members)
        hidden (- (count members) (count shown))]
    [:div {:id "gitdesk-my-work" :class "flex flex-col gap-4"}
     [:section {:class parts/card}
      [:div {:class "flex flex-col gap-1 px-4 py-4"}
       [:a {:href "/gitdesk" :class (str parts/muted " w-fit hover:underline")}
        "← gitdesk"]
       [:h1 {:class "text-2xl font-semibold tracking-tight"} title]
       [:p {:class parts/muted}
        (str (count members) " pull request" (when (not= 1 (count members)) "s"))
        (when (pos? hidden)
          [:span {:class "italic"}
           (str " (" hidden " draft" (when (< 1 hidden) "s")
                " hidden — show them in settings)")])]]
      (if (seq shown)
        [:ul {:class "divide-y divide-slate-200 border-t border-slate-200 dark:divide-slate-800 dark:border-slate-800"}
         (map #(pr-row now-ms %) shown)]
        [:div {:class "border-t border-slate-200 dark:border-slate-800"}
         (parts/empty-state
          (cond
            (not operator) "No valid GitHub credential, so there is no \"me\" yet."
            (pos? hidden) "Only drafts here, and drafts are hidden."
            :else empty))])]]))

;; ------------------------------------------------------------------ the body

(defn body
  "The fragment SSE patches in place while a collection runs."
  [sys]
  (let [now-ms (t/now-ms (:clock sys))
        operator (access/operator-login sys)
        in-force (config/current sys)
        pull-requests (collector/pull-requests sys)
        mine (my-work operator pull-requests)
        watched (monitoring pull-requests)
        train (merge-train pull-requests)]
    [:div {:id "gitdesk" :class "flex flex-col gap-4"}
     (collection-status sys now-ms)

     (my-work-summary mine)

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
      (pr-list "My work" now-ms
               (listed (:authored mine) (:to-review mine)
                       (:assigned mine) (:addressed mine))
               (if operator
                 "Nothing of yours is in flight."
                 "No valid GitHub credential, so there is no \"me\" yet."))
      (pr-list "Monitoring" now-ms
               (listed (:stuck watched) (:aging watched)
                       (:conflicting watched) (:difficult watched))
               "Nothing worth a second look.")
      (pr-list "Merge train" now-ms
               (listed (:merging train) (:just-merged train))
               "Nothing on its way in.")
      (team-work in-force pull-requests)]

     (when-not (:show-drafts in-force)
       [:p {:class (str "px-1 " parts/faint)}
        "Drafts are counted above and hidden from the lists. Show them in settings."])]))

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
