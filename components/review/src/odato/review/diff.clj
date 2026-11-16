(ns odato.review.diff
  "The diff a review read, as Owllie writes it in the `diff` block of its
  inputs step: files, hunks and numbered lines. It is what a finding is shown
  in context from, what a missed remark points into, and where a reviewer's
  comment is placed (specs/owldesk/tuning.allium).

  A line is `{:kind :add|:del|:ctx :old n|nil :new n|nil :text}`: an added
  line has only a new number, a removed one only an old number, a kept one
  both. Lines are named by their side — `:new` for added and kept lines,
  numbered in the new file, `:old` for removed and kept lines, numbered in the
  old file."
  (:require [clojure.string :as str]))

(def ^:private hunk-re #"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$")

(defn- strip-prefix [path]
  (cond
    (nil? path) nil
    (= "/dev/null" path) nil
    (re-find #"^[ab]/" path) (subs path 2)
    :else path))

(defn- header-path
  "The new path a `diff --git a/x b/y` line names."
  [line]
  (let [rest (subs line (count "diff --git "))
        i (str/last-index-of rest " b/")]
    (when i (subs rest (+ i 3)))))

(defn- finish-file [files file]
  (if file
    (conj files (update file :hunks #(vec (remove (comp empty? :lines) %))))
    files))

(defn parse
  "A unified diff (`git diff` output) as `[{:file :old-file :hunks [{:header
  :lines [...]}]}]`, in the order of the diff. `:file` is the path after the
  change, or the path before it for a deleted file. Anything a hunk does not
  hold — modes, renames without content, binary files — is skipped."
  [text]
  (loop [lines (str/split-lines (str text))
         files []
         file nil
         old-n 0
         new-n 0]
    (if-let [line (first lines)]
      (cond
        (str/starts-with? line "diff --git ")
        (recur (rest lines) (finish-file files file)
               {:file (header-path line) :old-file nil :hunks []} 0 0)

        (and file (str/starts-with? line "--- ") (empty? (:hunks file)))
        (recur (rest lines) files
               (assoc file :old-file (strip-prefix (subs line 4))) old-n new-n)

        (and file (str/starts-with? line "+++ ") (empty? (:hunks file)))
        (let [path (strip-prefix (subs line 4))]
          (recur (rest lines) files
                 (assoc file :file (or path (:old-file file) (:file file))) old-n new-n))

        (and file (re-matches hunk-re line))
        (let [[_ old-start _ new-start _ heading] (re-matches hunk-re line)]
          (recur (rest lines) files
                 (update file :hunks conj {:header (str/trim (str heading)) :lines []})
                 (parse-long old-start) (parse-long new-start)))

        (and file (seq (:hunks file)) (#{\+ \- \space} (first line)))
        (let [kind ({\+ :add \- :del \space :ctx} (first line))
              entry {:kind kind
                     :old (when (#{:del :ctx} kind) old-n)
                     :new (when (#{:add :ctx} kind) new-n)
                     :text (subs line 1)}
              hunk-index (dec (count (:hunks file)))]
          (recur (rest lines) files
                 (update-in file [:hunks hunk-index :lines] conj entry)
                 (if (#{:del :ctx} kind) (inc old-n) old-n)
                 (if (#{:add :ctx} kind) (inc new-n) new-n)))

        ;; An empty line inside a hunk is a kept empty line whose space a
        ;; tool trimmed.
        (and file (seq (:hunks file)) (= "" line))
        (let [hunk-index (dec (count (:hunks file)))]
          (recur (rest lines) files
                 (update-in file [:hunks hunk-index :lines] conj
                            {:kind :ctx :old old-n :new new-n :text ""})
                 (inc old-n) (inc new-n)))

        :else (recur (rest lines) files file old-n new-n))
      (vec (finish-file files file)))))

(defn file-of [files path] (first (filter #(= path (:file %)) files)))

(defn- number-on [side line]
  (if (= :old side) (:old line) (:new line)))

(defn- side-of [side] (if (#{:old "old"} side) :old :new))

(defn- located
  "Every line of a file with its place: `[hunk-index line-index line]`."
  [file]
  (for [[h hunk] (map-indexed vector (:hunks file))
        [i line] (map-indexed vector (:lines hunk))]
    [h i line]))

(defn- place [file side n]
  (first (filter (fn [[_ _ line]] (= n (number-on side line))) (located file))))

(defn line-at
  "The line numbered `n` on `side` of `path`, or nil."
  [files path side n]
  (when-let [file (file-of files path)]
    (nth (place file (side-of side) n) 2 nil)))

(def max-lines
  "How many lines one remark may point at."
  50)

(defn lines-in?
  "Whether `{:file :side :line :end-line}` are lines of the diff: both ends on
  that side of that file, in order, and not too many."
  [files {:keys [file side line end-line]}]
  (let [end (or end-line line)]
    (boolean
     (and (int? line) (int? end) (<= line end) (< (- end line) max-lines)
          (line-at files file side line)
          (line-at files file side end)))))

(defn excerpt
  "The lines around `{:file :side :line :end-line}`: those lines, marked, and
  `context` lines on each side, within the hunks they are in. Nil when the
  lines are not in the diff."
  [files {:keys [file side line end-line]} context]
  (when-let [f (file-of files file)]
    (let [side (side-of side)
          end (or end-line line)
          [h-start i-start] (place f side line)
          [h-end i-end] (place f side end)]
      (when (and h-start h-end (or (< h-start h-end) (and (= h-start h-end) (<= i-start i-end))))
        (let [in-range? (fn [[h i]] (and (or (> h h-start) (and (= h h-start) (>= i i-start)))
                                         (or (< h h-end) (and (= h h-end) (<= i i-end)))))
              wanted? (fn [[h i]] (or (and (= h h-start) (>= i (- i-start context)) (< i i-start))
                                      (and (= h h-end) (> i i-end) (<= i (+ i-end context)))
                                      (in-range? [h i])))]
          (vec (for [[h i l] (located f)
                     :when (wanted? [h i])]
                 (assoc l :marked (boolean (in-range? [h i]))))))))))

(defn locate-text
  "The number, on `side` of `path`, of the line whose text is `text` (spaces
  around it ignored), when exactly one line has it; nil otherwise."
  [files path side text]
  (when-let [f (file-of files path)]
    (let [side (side-of side)
          wanted (str/trim (str text))
          hits (for [[_ _ l] (located f)
                     :when (and (number-on side l) (= wanted (str/trim (:text l))))]
                 (number-on side l))]
      (when (= 1 (count hits)) (first hits)))))
