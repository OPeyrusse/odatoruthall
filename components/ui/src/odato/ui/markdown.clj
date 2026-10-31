(ns odato.ui.markdown
  "Markdown to hiccup, for what a review's steps say.

  Only what a review writes is understood: headings, paragraphs, lists,
  fenced code, inline code, bold, italic and links. Everything else is text.
  The input is never trusted as HTML — it quotes pull request descriptions and
  code — so nothing here produces raw markup: hiccup escapes every string, and
  a link is kept only when it points at http or https."
  (:require [clojure.string :as str]))

(def ^:private inline-re
  #"`([^`]+)`|\*\*([^*]+)\*\*|\*([^*\s][^*]*)\*|\[([^\]]+)\]\(([^)\s]+)\)")

(defn- inline
  "One line of text, with its inline marks."
  [text]
  (let [m (re-matcher inline-re (str text))]
    (loop [at 0 out []]
      (if (.find m)
        (let [before (subs text at (.start m))
              [_ code bold italic label href] (re-groups m)
              piece (cond
                      code [:code {:class "rounded bg-slate-100 px-1 font-mono text-[0.85em] dark:bg-slate-800"} code]
                      bold [:strong bold]
                      italic [:em italic]
                      (re-matches #"https?://\S+" href)
                      [:a {:href href :target "_blank" :rel "noreferrer" :class "underline"} label]
                      :else label)]
          (recur (.end m) (cond-> out (seq before) (conj before) true (conj piece))))
        ;; A list, not a vector: hiccup reads a vector as an element.
        (let [rest (subs text at)]
          (seq (cond-> out (seq rest) (conj rest))))))))

(def ^:private heading-classes
  {1 "text-lg font-semibold" 2 "text-base font-semibold" 3 "text-sm font-semibold"})

(defn- list-item [line]
  (when-let [[_ _bullet number text] (re-matches #"\s*(?:([-*+])|(\d+)[.)])\s+(.*)" line)]
    {:ordered? (some? number) :text text}))

(defn- blocks
  "The lines of a document, cut into blocks."
  [lines]
  (loop [lines lines out []]
    (if-let [line (first lines)]
      (cond
        (str/blank? line) (recur (rest lines) out)

        (str/starts-with? (str/triml line) "```")
        (let [[body after] (split-with #(not (str/starts-with? (str/triml %) "```")) (rest lines))]
          (recur (rest after) (conj out {:type :code :text (str/join "\n" body)})))

        (re-matches #"#{1,6}\s+.*" line)
        (let [[_ hashes text] (re-matches #"(#{1,6})\s+(.*)" line)]
          (recur (rest lines) (conj out {:type :heading :level (count hashes) :text text})))

        (list-item line)
        (let [[items after] (split-with list-item lines)]
          (recur after (conj out {:type :list :ordered? (:ordered? (list-item line))
                                  :items (map (comp :text list-item) items)})))

        :else
        (let [[para after] (split-with #(not (or (str/blank? %) (list-item %)
                                                 (re-matches #"#{1,6}\s+.*" %)
                                                 (str/starts-with? (str/triml %) "```")))
                                       lines)]
          (recur after (conj out {:type :paragraph :lines para}))))
      out)))

(defn render
  "A markdown document as hiccup."
  [text]
  [:div {:class "flex flex-col gap-2 text-sm leading-relaxed"}
   (for [{:keys [type] :as b} (blocks (str/split-lines (str text)))]
     (case type
       :heading [:p {:class (heading-classes (min 3 (:level b)))} (inline (:text b))]
       :code [:pre {:class (str "overflow-x-auto rounded-lg bg-slate-50 px-3 py-2 font-mono text-xs "
                                "dark:bg-slate-950")}
              (:text b)]
       :list [(if (:ordered? b) :ol :ul)
              {:class (str (if (:ordered? b) "list-decimal" "list-disc") " pl-5")}
              (for [item (:items b)] [:li (inline item)])]
       :paragraph [:p (interpose [:br] (map inline (:lines b)))]))])
