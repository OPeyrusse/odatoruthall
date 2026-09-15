(ns odato.ui.layout
  "The shell — the single entry the operator lands on, and the one place that
  says which applications this installation runs and whether it can reach what
  they depend on.

  gitdesk works with GitHub alone; worklog needs Jira as well, and says so
  through the Jira badge rather than by failing when the operator tries to log
  time."
  (:require [odato.access.interface :as access]
            [odato.time.interface :as t]
            [odato.ui.parts :as parts]))

(defn- badge [{:keys [service status login]}]
  (let [[tone label] (case status
                       ;; Both badges naming the same person is not the same
                       ;; as one badge: each says which service it speaks for.
                       :valid [:green (str (name service) " "
                                           (or login "connected"))]
                       :rejected [:red (str (name service) " rejected")]
                       :unverified [:amber (str (name service) " unverified")]
                       [:neutral (str "no " (name service) " token")])]
    [:span {:class parts/badge-shell
            :title (str (name service) ": " (name (or status :absent)))}
     (parts/dot tone)
     label]))

(defn- budget-badge [sys]
  (let [b (access/budget sys)
        known? (some? (:budget/observed-at b))
        exhausted? (= :exhausted (:budget/status b))]
    (when known?
      [:span {:class parts/badge-shell
              :title (if exhausted?
                       (str "GitHub budget spent; back at "
                            (t/format-instant (:budget/resets-at b)))
                       "GitHub API budget left before collection has to wait")}
       (parts/dot (cond exhausted? :red
                        (< (long (or (:budget/remaining b) 0)) 500) :amber
                        :else :green))
       (str (:budget/remaining b) " api")])))

(def ^:private nav-current
  "bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900")

(def ^:private nav-other
  "text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800")

(defn- nav-link [{:keys [href label current?]}]
  [:a {:href href
       :class (str "rounded-lg px-3 py-1.5 text-sm font-medium transition "
                   (if current? nav-current nav-other))}
   label])

(def ^:private body-class
  (str "h-full bg-slate-50 text-slate-900 antialiased "
       "dark:bg-slate-950 dark:text-slate-100"))

(defn page
  "One page of the installation: the navigation, the badges, and the content.

  The two applications hang off this shell, and the badges say what each can
  reach — which is why they are here and not inside either dashboard."
  [sys {:keys [title section]} content]
  [:html {:lang "en" :class "h-full"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title (str title " · odatoruthall")]
    [:link {:rel "stylesheet" :href "/css/app.css"}]
    [:link {:rel "icon" :href "/favicon.svg" :type "image/svg+xml"}]
    [:script {:type "module" :src "/js/datastar.js"}]]
   [:body {:class body-class}
    [:div {:class "mx-auto flex min-h-full max-w-7xl flex-col gap-4 px-4 py-4 sm:px-6"}
     [:header {:class "flex flex-wrap items-center justify-between gap-3"}
      [:div {:class "flex items-center gap-3"}
       [:a {:href "/" :class "text-base font-semibold tracking-tight"} "odatoruthall"]
       [:nav {:class "flex items-center gap-1"}
        (nav-link {:href "/gitdesk" :label "gitdesk" :current? (= section :gitdesk)})
        (nav-link {:href "/worklog" :label "worklog" :current? (= section :worklog)})
        (nav-link {:href "/settings" :label "settings" :current? (= section :settings)})]]
      [:div {:class "flex flex-wrap items-center gap-2"}
       (badge {:service :github
               :status (:connection/status (access/connection sys :github))
               :login (access/operator-login sys)})
       (badge {:service :jira
               :status (:connection/status (access/connection sys :jira))
               :login (:connection/account-login (access/connection sys :jira))})
       (budget-badge sys)]]
     [:main {:class "flex flex-1 flex-col gap-4"} content]
     [:footer {:class (str "pt-2 " parts/faint)}
      "One dashboard to rule them all."]]]])
