(ns odato.ui.layout
  "The shell — the single entry the operator lands on, and the one place that
  says which applications this installation runs and whether it can reach what
  they depend on.

  gitdesk works with GitHub alone; worklog needs Jira as well, and says so
  through the Jira badge rather than by failing when the operator tries to log
  time."
  (:require [hiccup2.core :as h]
            [odato.access.interface :as access]
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

(def ^:private theme-script
  "Runs in the head, before anything is painted: a theme picked earlier in
  this tab is put back on <html>, so the page does not flash the system's
  first. The pick is the tab's own and lasts as long as it does — the
  system's theme is the default, and the switch only overrides it for a
  while. `odatoTheme` is what the switch calls: picking the theme in force
  again, or the middle stop, hands back to the system."
  (str "(function(){"
       "var k='odato-theme',r=document.documentElement;"
       "function apply(t){if(t){r.dataset.theme=t}else{delete r.dataset.theme}"
       "try{t?sessionStorage.setItem(k,t):sessionStorage.removeItem(k)}catch(e){}}"
       "try{var t=sessionStorage.getItem(k);if(t==='light'||t==='dark'){r.dataset.theme=t}}catch(e){}"
       "window.odatoTheme=function(t){apply(t&&t!==r.dataset.theme?t:null)};"
       "})();"))

(def ^:private icons
  {:light [:svg {:viewBox "0 0 24 24" :class "h-3.5 w-3.5" :fill "none"
                 :stroke "currentColor" :stroke-width "2" :stroke-linecap "round"}
           [:circle {:cx "12" :cy "12" :r "4"}]
           [:path {:d (str "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4"
                           "M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")}]]
   :system [:svg {:viewBox "0 0 24 24" :class "h-3.5 w-3.5" :fill "none"
                  :stroke "currentColor" :stroke-width "2"}
            [:circle {:cx "12" :cy "12" :r "8"}]
            [:path {:d "M12 4a8 8 0 0 1 0 16z" :fill "currentColor"}]]
   :dark [:svg {:viewBox "0 0 24 24" :class "h-3.5 w-3.5" :fill "none"
                :stroke "currentColor" :stroke-width "2" :stroke-linejoin "round"}
          [:path {:d "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"}]]})

(defn- theme-stop [stop label pick]
  [:button {:type "button"
            :class "theme-stop relative z-10 flex h-6 w-6 cursor-pointer items-center justify-center rounded-full"
            :data-stop (name stop)
            :title label
            :aria-label label
            :onclick (str "odatoTheme(" pick ")")}
   (icons stop)])

(defn- theme-switch
  "Light, the system's, dark. The knob starts in the middle."
  []
  [:div {:class (str "relative flex items-center rounded-full bg-slate-200 p-0.5 "
                     "dark:bg-slate-800")
         :role "group"
         :aria-label "theme"}
   [:span {:class (str "theme-knob absolute left-0.5 top-0.5 h-6 w-6 rounded-full "
                       "bg-white shadow-sm dark:bg-slate-500")
           :aria-hidden "true"}]
   (theme-stop :light "Light, for now" "'light'")
   (theme-stop :system "Follow the system" "null")
   (theme-stop :dark "Dark, for now" "'dark'")])

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
    [:script (h/raw theme-script)]
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
       (budget-badge sys)
       (theme-switch)]]
     [:main {:class "flex flex-1 flex-col gap-4"} content]
     [:footer {:class (str "pt-2 " parts/faint)}
      "One dashboard to rule them all."]]]])
