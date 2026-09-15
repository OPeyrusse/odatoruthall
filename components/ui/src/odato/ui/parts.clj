(ns odato.ui.parts
  "The pieces both dashboards are built from: stat tiles, chips, badges and the
  empty states. Tailwind classes live here rather than being spread through the
  views, so the two applications look like one installation."
  (:require [clojure.string :as str]
            [odato.time.interface :as t]))

(def card
  "rounded-xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900")

(def card-heading
  "px-4 py-3 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between gap-3")

(def heading-text
  "text-sm font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400")

(def muted "text-sm text-slate-500 dark:text-slate-400")

(def faint "text-xs text-slate-400 dark:text-slate-600")

(def button
  (str "inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 "
       "text-sm font-medium text-slate-700 shadow-sm transition hover:bg-slate-50 "
       "disabled:opacity-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200 "
       "dark:hover:bg-slate-700 cursor-pointer whitespace-nowrap"))

(def primary-button
  (str "inline-flex items-center gap-1.5 rounded-lg bg-sky-600 px-3 py-1.5 text-sm "
       "font-medium text-white shadow-sm transition hover:bg-sky-500 "
       "disabled:opacity-50 cursor-pointer whitespace-nowrap"))

(def input
  (str "w-full rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm "
       "text-slate-900 shadow-sm outline-none focus:border-sky-500 focus:ring-2 "
       "focus:ring-sky-500/30 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100"))

(def badge-shell
  (str "inline-flex items-center gap-1.5 rounded-full border border-slate-200 px-2.5 py-1 "
       "text-xs font-medium text-slate-600 dark:border-slate-700 dark:text-slate-300"))

(def ^:private tone-classes
  {:neutral "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300"
   :green "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300"
   :amber "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300"
   :red "bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-300"
   :sky "bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-300"
   :violet "bg-violet-100 text-violet-800 dark:bg-violet-950 dark:text-violet-300"})

(defn chip
  ([label] (chip label :neutral))
  ([label tone]
   [:span {:class (str "inline-flex items-center rounded-full px-2 py-0.5 text-xs "
                       "font-medium whitespace-nowrap " (tone-classes tone))}
    label]))

(defn dot
  "A coloured dot, for the connection badges."
  [tone]
  [:span {:class (str "inline-block h-2 w-2 rounded-full "
                      (case tone
                        :green "bg-emerald-500"
                        :amber "bg-amber-500"
                        :red "bg-rose-500"
                        "bg-slate-400"))}])

(def ^:private stat-label
  (str "mt-0.5 text-xs font-medium uppercase tracking-wide "
       "text-slate-500 dark:text-slate-400"))

(defn stat
  "One count in a group, with the label under it. A count is always shown, even
  when it is zero — a dashboard that hides its zeros makes the operator guess."
  [{:keys [label count tone]}]
  [:div {:class (str "rounded-lg px-3 py-2.5 " (tone-classes (or tone :neutral)))}
   [:div {:class "text-2xl font-semibold tabular-nums"} (str count)]
   [:div {:class stat-label} label]])

(defn stat-group [title stats]
  [:section {:class card}
   [:div {:class card-heading} [:h2 {:class heading-text} title]]
   [:div {:class "grid grid-cols-2 gap-2 p-3 sm:grid-cols-4"}
    (map stat stats)]])

(defn empty-state [message]
  [:div {:class "px-4 py-10 text-center text-sm text-slate-500 dark:text-slate-400"}
   message])

(defn check-chip [rollup]
  (case rollup
    :success (chip "checks pass" :green)
    :failure (chip "checks fail" :red)
    :pending (chip "checks running" :amber)
    :none (chip "no checks" :neutral)
    nil))

(defn relative-time
  "How long ago, in words, with the exact instant on hover."
  [now-ms ms]
  (when ms
    (let [said (t/humanize-age (- (long now-ms) (long ms)))]
      [:span {:title (t/format-instant ms) :class "whitespace-nowrap"}
       (if (= "just now" said) said (str said " ago"))])))

(defn humanise
  "`:credential-rejected` reads as `credential rejected`."
  [k]
  (when k (str/replace (name k) #"-" " ")))
