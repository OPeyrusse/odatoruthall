(ns odato.ui.code
  "Code as a review read it: its diff, file by file, and the few lines around
  a finding or a remark (specs/owldesk/owldesk.allium,
  CodeIsShownAsTheRunSawIt).

  Everything is drawn on the server, as text: hiccup escapes every string, so
  the code is shown, never interpreted. Lines are told apart by their tint and
  their sign, and numbered on both sides; a diff drawn for a review page lets
  each line start a missed remark."
  (:require [clojure.string :as str]))

(def ^:private line-tones
  {:add "bg-emerald-50 dark:bg-emerald-950/40"
   :del "bg-rose-50 dark:bg-rose-950/40"
   :ctx ""})

(def ^:private marked-tone
  "bg-amber-100 dark:bg-amber-900/50")

(def ^:private sign {:add "+" :del "-" :ctx " "})

(def ^:private number-class
  (str "w-10 select-none border-r border-slate-200 px-1.5 text-right align-top tabular-nums "
       "text-slate-400 dark:border-slate-800 dark:text-slate-600"))

(defn- number-cell
  "A line's number on one side; a link that starts a remark on that line when
  the view offers it."
  [n side line-href]
  [:td {:class number-class}
   (cond
     (nil? n) ""
     line-href [:a {:href (line-href side n) :class "hover:text-sky-600 hover:underline"
                    :title (str "Remark on this line (" (name side) " side)")}
                (str n)]
     :else (str n))])

(defn- line-row [path {:keys [kind old new text marked]} line-href]
  (let [href (when line-href (partial line-href path))]
    [:tr {:class (if marked marked-tone (line-tones kind))
          :id (when line-href (str "L-" (if new (str "n" new) (str "o" old)) "-" (hash path)))}
     (number-cell old :old (when (#{:del} kind) href))
     (number-cell new :new (when (#{:add :ctx} kind) href))
     [:td {:class "w-4 select-none px-1 text-slate-400"} (sign kind)]
     [:td {:class "whitespace-pre px-2"} (str text)]]))

(def ^:private table-class
  "w-full border-collapse font-mono text-xs leading-5")

(defn- stats [file]
  (let [lines (mapcat :lines (:hunks file))]
    [(count (filter #(= :add (:kind %)) lines)) (count (filter #(= :del (:kind %)) lines))]))

(defn diff-view
  "A parsed diff (odato.review.diff), file by file. With `:line-href`
  (`(fn [path side n])`), each line number links to a remark on that line."
  [files {:keys [line-href]}]
  (if (empty? files)
    [:p {:class "text-sm text-slate-500"} "The diff holds no line."]
    [:div {:class "flex flex-col gap-3"}
     (for [file files
           :let [[added removed] (stats file)]]
       [:details {:open true
                  :class "overflow-hidden rounded-lg border border-slate-200 dark:border-slate-800"}
        [:summary {:class (str "flex cursor-pointer flex-wrap items-center gap-2 bg-slate-50 px-3 py-1.5 "
                               "font-mono text-xs dark:bg-slate-900")}
         [:span {:class "font-semibold"} (str (:file file))]
         (when (and (:old-file file) (not= (:old-file file) (:file file)))
           [:span {:class "text-slate-400"} (str "from " (:old-file file))])
         [:span {:class "text-emerald-700 dark:text-emerald-400"} (str "+" added)]
         [:span {:class "text-rose-700 dark:text-rose-400"} (str "-" removed)]]
        [:div {:class "overflow-x-auto"}
         [:table {:class table-class}
          [:tbody
           (for [hunk (:hunks file)]
             (list
              [:tr {:class "bg-sky-50 text-slate-500 dark:bg-sky-950/40 dark:text-slate-400"}
               [:td {:colspan 4 :class "px-2"}
                (str "@@" (when-not (str/blank? (:header hunk)) (str " " (:header hunk))))]]
              (for [line (:lines hunk)] (line-row (:file file) line line-href))))]]]])]))

(defn excerpt-view
  "The lines around a finding or a remark, its own marked."
  [lines]
  (when (seq lines)
    [:div {:class "overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800"}
     [:table {:class table-class}
      [:tbody (for [line lines] (line-row nil line nil))]]]))
