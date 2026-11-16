(ns odato.demo.reviews
  "Reviews for an installation with nothing to connect to: results branches
  already pushed, in every state a review can be in, and a launcher that
  writes a run's branch step by step, the way a real Owllie session does.

  The files follow Owllie's shared/run-layout.md, `owllie-run/2`: a process
  written first, a status kept up to date, and one file per step, written
  when the step ends."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [odato.time.interface :as t])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def results-repository {:owner "acme" :name "owllie-results"})

(def phases
  [["prepare" "Preparation" [["inputs" "Inputs"]]]
   ["phase1" "Phase 1" [["intent-reader" "Intent reader"] ["general-rules" "General rules"]
                        ["edge-case-tests" "Edge-case tests"] ["impact" "Impact"] ["naming" "Naming"]]]
   ["phase2" "Phase 2" [["intent-comparator" "Intent comparator"] ["concurrency" "Concurrency"]
                        ["performance" "Performance"]]]
   ["synthesis" "Synthesis" [["synthesis" "Summary"]]]])

(def agents (vec (for [[_ _ steps] (subvec phases 1 3) [id] steps] id)))

(def ^:private agent-models
  {"intent-reader" "sonnet · high" "general-rules" "sonnet · medium"
   "edge-case-tests" "sonnet · high" "impact" "sonnet · high" "naming" "sonnet · medium"
   "intent-comparator" "sonnet · medium" "concurrency" "opus · high" "performance" "sonnet · medium"
   "synthesis" "sonnet · medium"})

(defn- iso [ms] (str (Instant/ofEpochMilli ms)))

(def ^:private minute-format
  (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmm'Z'") ZoneOffset/UTC))

(defn run-id [ms suffix] (str (.format minute-format (Instant/ofEpochMilli ms)) "-" suffix))

(defn branch [{:keys [owner name number]} run-id]
  (str "review/" owner "/" name "/" number "/" run-id))

(defn- write [x] (json/write-str x :escape-slash false))

(defn- sha1-12 [s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-1") (.getBytes (str s) "UTF-8"))]
    (subs (apply str (map #(format "%02x" %) digest)) 0 12)))

(defn- step-path [id ext] (str "owllie/steps/" id "." (or ext "json")))

;; ------------------------------------------------------------------ findings

(defn- finding
  [{:keys [rule severity confidence file line quote observation why suggestion status reason agents]
    :or {confidence "high" status "kept"}}]
  {:fingerprint (sha1-12 (str rule "\n" file "\n" quote))
   :severity severity
   :location {:file file :line line}
   :quote quote
   :body (str observation "\n\n" why "\n\n**Suggestion:** " suggestion)
   :tags [rule]
   :status {:value status :reason reason}
   :fields (cond-> {:confidence confidence} agents (assoc :agents agents))})

(def ^:private array-finding
  (finding {:rule "R-ARGS-002" :severity "major"
            :file "src/main/java/acme/widget/SnapshotCache.java" :line 41
            :quote "public void warm(String[] keys)"
            :observation "A Java array is passed as an argument."
            :why "Rule R-ARGS-002: prefer List or Set over arrays as arguments."
            :suggestion "Take a List<String> instead."}))

(def ^:private linked-finding
  (finding {:rule "R-COLL-001" :severity "major" :confidence "medium"
            :file "src/main/java/acme/widget/SnapshotCache.java" :line 18
            :quote "private final Map<String, Snapshot> byKey = new LinkedHashMap<>();"
            :observation "A LinkedHashMap is used with no stated need for the insertion order."
            :why "Rule R-COLL-001: no LinkedHashMap without an explicit order requirement."
            :suggestion "Use a HashMap, or say why the order matters."}))

(def ^:private race-finding
  (finding {:rule "R-CONC-001" :severity "blocker"
            :file "src/main/java/acme/widget/SnapshotCache.java" :line 52
            :quote "if (!byKey.containsKey(key)) { byKey.put(key, load(key)); }"
            :observation "Check-then-act on a map shared by every request of a singleton bean."
            :why "Two requests for the same key both load it, and the map is not thread-safe."
            :suggestion "Use a ConcurrentHashMap and computeIfAbsent."}))

(def ^:private intent-finding
  (finding {:rule "INTENT-FOUND-NOT-CLAIMED" :severity "minor" :confidence "medium"
            :file "src/main/java/acme/widget/Renderer.java" :line 12
            :quote "private static final Duration TTL = Duration.ofMinutes(5);"
            :observation "The cache entries expire after five minutes; the description does not say so."
            :why "A behaviour the description does not announce."
            :suggestion "Mention the expiry in the description."}))

(def ^:private agent-findings
  {"general-rules"
   [array-finding linked-finding
    (finding {:rule "R-ARGS-001" :severity "minor"
              :file "src/main/java/acme/widget/Renderer.java" :line 77
              :quote "render(Snapshot s, int width, int height, Theme theme, Locale locale, boolean dense)"
              :observation "Six parameters." :why "Rule R-ARGS-001."
              :suggestion "Group the layout in an argument object."
              :status "rejected" :reason "quote found neither in diff.patch nor in the cited file"})]
   "edge-case-tests"
   [(finding {:rule "R-EDGE-EMPTY" :severity "major"
              :file "src/main/java/acme/widget/SnapshotCache.java" :line 41
              :quote "public void warm(String[] keys)"
              :observation "No test warms the cache with no key."
              :why "Rule R-EDGE-EMPTY: an empty collection is the first edge case."
              :suggestion "Add a test for warm() with an empty list."
              :status "merged" :reason (str "merged into " (:fingerprint array-finding))})]
   "concurrency" [race-finding]
   "intent-comparator" [intent-finding]})

;; --------------------------------------------------------------------- files

(def head-sha
  "The commit every demo review read."
  "d07be31c0b8f6a4f1f1d6a3b2e9c8d7f6a5b4c3d")

(defn process-file [pr run-id]
  {:schema "owllie-run/2"
   :run_id run-id
   :title (:title pr)
   :pull_request {:url (str "https://github.com/" (:owner pr) "/" (:name pr) "/pull/" (:number pr))
                  :head_sha head-sha}
   :details [{:label "Head" :value "d07be31"} {:label "Brain" :value "main @ 73a41fc"}
             {:label "Diff" :value "412 lines in 6 files"}]
   :summary_step "synthesis"
   :phases (vec (for [[id title steps] phases]
                  {:id id :title title
                   :steps (vec (for [[step-id step-title ext] steps]
                                 {:id step-id :title step-title :file (step-path step-id ext)}))}))})

(defn status-file [state updated-ms error headline]
  {:state state :updated_at (iso updated-ms) :error error :headline (or headline [])})

(def ^:private headline
  [{:label "blocker" :value 1 :tone "red"} {:label "major" :value 2 :tone "amber"}
   {:label "minor" :value 1 :tone "neutral"} {:label "info" :value 0 :tone "neutral"}])

(def ^:private snapshot-cache
  "The new file the demo pull request adds: the findings point into it."
  ["package acme.widget;"
   ""
   "import java.time.Duration;"
   "import java.util.LinkedHashMap;"
   "import java.util.List;"
   "import java.util.Map;"
   "import org.springframework.stereotype.Component;"
   ""
   "/**"
   " * Keeps the last snapshot of each widget between two renders."
   " */"
   "@Component"
   "public class SnapshotCache {"
   ""
   "  private final SnapshotLoader loader;"
   "  private final Clock clock;"
   ""
   "  private final Map<String, Snapshot> byKey = new LinkedHashMap<>();"
   ""
   "  public SnapshotCache(SnapshotLoader loader, Clock clock) {"
   "    this.loader = loader;"
   "    this.clock = clock;"
   "  }"
   ""
   "  public Snapshot get(String key) {"
   "    Snapshot snapshot = byKey.get(key);"
   "    if (snapshot == null || snapshot.isOlderThan(clock.now(), Duration.ofMinutes(5))) {"
   "      snapshot = load(key);"
   "      byKey.put(key, snapshot);"
   "    }"
   "    return snapshot;"
   "  }"
   ""
   "  public int size() {"
   "    return byKey.size();"
   "  }"
   ""
   "  /**"
   "   * Loads every key ahead of the first render."
   "   */"
   "  public void warm(String[] keys) {"
   "    for (String key : keys) {"
   "      ensure(key);"
   "    }"
   "  }"
   ""
   "  public void clear() {"
   "    byKey.clear();"
   "  }"
   ""
   "  private void ensure(String key) {"
   "    if (!byKey.containsKey(key)) { byKey.put(key, load(key)); }"
   "  }"
   ""
   "  private Snapshot load(String key) {"
   "    return loader.load(key);"
   "  }"
   "}"])

(def diff
  "The diff the demo reviews read, as Owllie writes it in the inputs step."
  (str "diff --git a/src/main/java/acme/widget/Renderer.java b/src/main/java/acme/widget/Renderer.java\n"
       "index 3e0f2b1..d07be31 100644\n"
       "--- a/src/main/java/acme/widget/Renderer.java\n"
       "+++ b/src/main/java/acme/widget/Renderer.java\n"
       "@@ -8,6 +8,8 @@ import java.util.List;\n"
       " import java.util.Locale;\n"
       " \n"
       " public class Renderer {\n"
       "-  private Snapshot last;\n"
       "+\n"
       "+  private static final Duration TTL = Duration.ofMinutes(5);\n"
       "+  private final SnapshotCache cache;\n"
       "   private final Theme theme;\n"
       " \n"
       "diff --git a/src/main/java/acme/widget/SnapshotCache.java b/src/main/java/acme/widget/SnapshotCache.java\n"
       "new file mode 100644\n"
       "index 0000000..5d41402\n"
       "--- /dev/null\n"
       "+++ b/src/main/java/acme/widget/SnapshotCache.java\n"
       "@@ -0,0 +1," (count snapshot-cache) " @@\n"
       (apply str (for [line snapshot-cache] (str "+" line "\n")))))

(defn- inputs-md [pr]
  (str "**" (:title pr) "** by " (:author pr) "\n\n"
       "`main` @ `3e0f2b1` ← `feature` @ `d07be31` · 70 diff lines in 2 files\n\n"
       "### Description\n\nCaches the rendered snapshot between two renders.\n\n"
       "### Files\n\n```\n src/main/java/acme/widget/Renderer.java      |  5 ++-\n"
       " src/main/java/acme/widget/SnapshotCache.java | 58 +++++\n```\n"))

(defn- inputs-step [pr]
  {:status "done" :title "Inputs" :meta []
   :blocks [{:type "markdown" :text (inputs-md pr)}
            {:type "diff" :title "Diff" :text diff}]})

(defn- extras [agent]
  (case agent
    "impact" [{:type "table" :title "Modified symbols"
               :columns ["Symbol" "Change" "Callers" "Entry points" "Risk" "Why"]
               :rows [["acme.widget.SnapshotCache#warm(String[])" "contract" "1" "BootController#start"
                       "medium" "Public method called at startup."]]}]
    "intent-reader" [{:type "markdown"
                      :text "### I1 — Cache the snapshot\n\nRenders reuse the last snapshot instead of rebuilding it.\n\n*Likely why (medium certainty):* rendering is slow."}]
    "intent-comparator" [{:type "markdown" :text "**Status:** minor gaps"}]
    []))

(defn agent-step [agent]
  (let [findings (get agent-findings agent [])
        counts (frequencies (map (comp :value :status) findings))]
    {:status "done"
     :title (some (fn [[_ _ steps]] (some (fn [[id title]] (when (= id agent) title)) steps)) phases)
     :meta [{:label "Model" :value (agent-models agent)}
            {:label "Duration" :value "4 min 00 s · 1 attempt"}
            {:label "Findings" :value (or (not-empty (str/join " · " (for [s ["kept" "merged" "dropped" "rejected"]
                                                                          :when (counts s)]
                                                                      (str (counts s) " " s))))
                                          "none")}]
     :blocks (cond-> (extras agent)
               (seq findings) (conj {:type "findings" :title "Findings" :items findings})
               (empty? findings) (conj {:type "markdown" :text "Nothing to report."})
               (= agent "edge-case-tests") (conj {:type "markdown" :text "**Limitations**\n\n- Diff coverage not provided."}))}))

(defn synthesis-step []
  {:status "done" :title "Summary"
   :meta [{:label "Model" :value (agent-models "synthesis")} {:label "Duration" :value "1 min 12 s"}]
   :blocks [{:type "stats" :items headline}
            {:type "markdown" :text (str "### Verdict\n\nA check-then-act on a shared map can load the same snapshot "
                                         "twice under load. The new cache takes an array and keeps an insertion "
                                         "order nobody asked for.")}
            {:type "markdown" :text "**Intent check:** minor gaps"}
            {:type "findings" :title "Findings"
             :items [(assoc-in race-finding [:fields :agents] "concurrency")
                     (-> array-finding (assoc :tags ["R-ARGS-002" "R-EDGE-EMPTY"])
                         (assoc-in [:fields :agents] "general-rules, edge-case-tests"))
                     (assoc-in linked-finding [:fields :agents] "general-rules")
                     (assoc-in intent-finding [:fields :agents] "intent-comparator")]}
            {:type "table" :title "Impact" :columns ["Symbol" "Change" "Callers" "Entry points" "Risk"]
             :rows [["acme.widget.SnapshotCache#warm(String[])" "contract" "1" "BootController#start" "medium"]]}
            {:type "markdown" :text "### Uncovered edge cases\n\n- warm() with no key — `src/main/java/acme/widget/SnapshotCache.java:41`"}
            {:type "markdown" :text "### Limitations\n\n- Diff coverage not provided."}]})

(defn- base-files [pr run-id]
  {"owllie/process.json" (write (process-file pr run-id))
   (step-path "inputs" nil) (write (inputs-step pr))})

(defn done-run
  "The files of a review that went to the end."
  [pr run-id at]
  (merge (base-files pr run-id)
         {"owllie/status.json" (write (status-file "done" (+ at (t/minutes 11)) nil headline))
          (step-path "synthesis" nil) (write (synthesis-step))
          "owllie/report.md" "# Report\n"}
         (into {} (for [a agents] [(step-path a nil) (write (agent-step a))]))))

(defn failed-run [pr run-id at]
  {"owllie/process.json" (write (process-file pr run-id))
   "owllie/status.json" (write (status-file "failed" (+ at (t/minutes 2))
                                            {:code "diff_too_large"
                                             :message "the diff has 5210 lines, more than 3000; review it by subsets of files"}
                                            nil))})

(defn stalled-run [pr run-id at]
  (merge (base-files pr run-id)
         {"owllie/status.json" (write (status-file "running" (+ at (t/minutes 6)) nil nil))}
         (into {} (for [a (take 5 agents)] [(step-path a nil) (write (agent-step a))]))))

;; ----------------------------------------------------------------- the seed

(def pull-requests
  {"acme/widget#412" {:owner "acme" :name "widget" :number 412 :author "ada" :state :open
                      :title "WID-88 Cache the snapshot between renders"}
   "acme/widget#398" {:owner "acme" :name "widget" :number 398 :author "grace" :state :open
                      :title "WID-71 Move the collector onto virtual threads"}
   "acme/widget#351" {:owner "acme" :name "widget" :number 351 :author "linus" :state :open
                      :title "WID-40 Rework the settings surface"}
   "acme/gear#77" {:owner "acme" :name "gear" :number 77 :author "margaret" :state :merged
                   :title "GER-9 Split the quote builder"}})

(defn seed-branches
  "The results branches a demo installation starts with."
  [now]
  (let [pr #(get pull-requests %)
        at (fn [ago] (- now ago))
        runs [[(pr "acme/widget#412") (at (t/days 1)) "a412" done-run]
              [(pr "acme/widget#398") (at (t/hours 3)) "b398" failed-run]
              [(pr "acme/widget#351") (at (t/days 4)) "c351" done-run]
              [(pr "acme/widget#351") (at (t/hours 2)) "d351" stalled-run]
              [(pr "acme/gear#77") (at (t/days 20)) "e077" done-run]
              [(pr "acme/gear#77") (at (t/days 18)) "f077" done-run]]]
    (into {} (for [[p ms suffix files-of] runs
                   :let [id (run-id ms suffix)]]
               [(branch p id) (files-of p id ms)]))))

(def tuning-pull-request
  "The pull request a demo tuning opens on Owllie's rules."
  {:owner "acme" :name "owllie" :number 7})

(defn github-script
  "What the demo's mock GitHub adds for owldesk."
  [now]
  {:repositories {"acme/owllie-results" {:default-branch "main" :files #{".owllie-results"}}
                  "acme/widget" {:default-branch "main"}
                  "acme/gear" {:default-branch "main"}}
   :pull-request-details
   (into {(str (:owner tuning-pull-request) "/" (:name tuning-pull-request) "#" (:number tuning-pull-request))
          {:title "Tune R-COLL-001: maps written back in reading order" :author "owllie" :state :open
           :draft false :mergeable-state "clean"
           :url (str "https://github.com/" (:owner tuning-pull-request) "/" (:name tuning-pull-request)
                     "/pull/" (:number tuning-pull-request))}}
         (for [[k p] pull-requests]
              [k {:title (:title p) :author (:author p) :state (:state p)
                  :closed-at (when (not= :open (:state p)) (- now (t/days 19)))
                  :head-sha head-sha :url (str "https://github.com/" (:owner p) "/" (:name p)
                                               "/pull/" (:number p))}]))
   :branches {"acme/owllie-results" (seed-branches now)}
   :review-comments
   {"acme/widget#412"
    [{:id "9001" :author "grace" :path "src/main/java/acme/widget/SnapshotCache.java"
      :line 27 :original-line 27 :side "RIGHT" :commit-id head-sha :original-commit-id head-sha
      :body "The five minutes are a magic number: Renderer has the same TTL. Share one constant."
      :url "https://github.com/acme/widget/pull/412#discussion_r9001"}
     {:id "9002" :author "linus" :path "src/main/java/acme/widget/SnapshotCache.java"
      :line 48 :original-line 48 :side "RIGHT" :commit-id head-sha :original-commit-id head-sha
      :body "clear() races with get(): a render can put back what was just cleared."
      :url "https://github.com/acme/widget/pull/412#discussion_r9002"}
     {:id "9003" :author "ada" :path "src/main/java/acme/widget/SnapshotCache.java"
      :line 48 :original-line 48 :side "RIGHT" :commit-id head-sha :original-commit-id head-sha
      :in-reply-to "9002" :body "Good catch, fixing."
      :url "https://github.com/acme/widget/pull/412#discussion_r9003"}]}})

;; ------------------------------------------------------- the simulated run

(defn- request-value [text k]
  (second (re-find (re-pattern (str "(?m)^" k "=(.*)$")) text)))

(defn simulate
  "A launcher's `on-fire` for the demo: answers at once with a made-up
  session, then writes the run's branch step by step through `write!`
  (`(write! branch files)`), `step-ms` apart, reading the time from `now-fn`."
  [{:keys [write! now-fn step-ms]}]
  (fn [request-text]
    (let [branch-name (request-value request-text "RUN_BRANCH")
          [_ owner name number run-id] (re-matches #"review/([^/]+)/([^/]+)/(\d+)/(.+)" (str branch-name))
          pr (or (get pull-requests (str owner "/" name "#" number))
                 {:owner owner :name name :number (parse-long (str number)) :author "someone"
                  :title "A pull request"})
          status (fn [state h] {"owllie/status.json" (write (status-file state (now-fn) nil h))})
          step! (fn [files] (Thread/sleep (long step-ms)) (write! branch-name files))]
      (Thread/startVirtualThread
       (fn []
         (try
           (step! (merge {"owllie/process.json" (write (process-file pr run-id))} (status "running" nil)))
           (step! (merge {(step-path "inputs" nil) (write (inputs-step pr))} (status "running" nil)))
           (step! (merge (into {} (for [a (take 3 agents)] [(step-path a nil) (write (agent-step a))]))
                         (status "running" nil)))
           (step! (merge (into {} (for [a (drop 3 (take 5 agents))] [(step-path a nil) (write (agent-step a))]))
                         (status "running" nil)))
           (step! (merge (into {} (for [a (drop 5 agents)] [(step-path a nil) (write (agent-step a))]))
                         (status "running" nil)))
           (step! (merge {(step-path "synthesis" nil) (write (synthesis-step)) "owllie/report.md" "# Report\n"}
                         (status "done" headline)))
           (catch InterruptedException _ nil))))
      {:session-id (str "session_demo" (str/replace (str run-id) #"[^A-Za-z0-9]" ""))
       :session-url "https://claude.ai/code"})))

;; ------------------------------------------------------ the simulated tuning

(def ^:private tuning-phases
  [["prepare" "Preparation" [["inputs" "Inputs"]]]
   ["diagnose" "Diagnosis" [["diagnosis" "Misses"]]]
   ["draft" "Drafts" [["tune-general-rules" "General rules"]]]
   ["validate" "Validation" [["validation" "Cases"] ["replay" "Real diff"] ["leak-check" "Leak check"]]]
   ["publish" "Publication" [["pull-request" "Pull request"]]]])

(defn- tuning-process [run-id remarks]
  {:schema "owllie-run/2"
   :run_id run-id
   :title (str "Tune from " remarks " remark" (when (not= 1 remarks) "s"))
   :pull_request {:url "https://github.com/acme/widget/pull/412" :head_sha head-sha}
   :details [{:label "Brain" :value "main @ 73a41fc"}]
   :summary_step "pull-request"
   :phases (vec (for [[id title steps] tuning-phases]
                  {:id id :title title
                   :steps (vec (for [[step-id step-title] steps]
                                 {:id step-id :title step-title :file (step-path step-id nil)}))}))})

(def ^:private rule-change
  (str "diff --git a/rules/general-rules.md b/rules/general-rules.md\n"
       "--- a/rules/general-rules.md\n"
       "+++ b/rules/general-rules.md\n"
       "@@ -71,7 +71,8 @@\n"
       " - **instruction**: only allowed when it is explicit that the items must be returned in the same order as they were read.\n"
       "-- **exceptions**: LRU caches built on `LinkedHashMap(…, true)` with `removeEldestEntry`.\n"
       "+- **exceptions**: LRU caches built on `LinkedHashMap(…, true)` with `removeEldestEntry`; a map whose\n"
       "+  entries are written back in reading order by a method of the same class.\n"
       " - **bad**: `final Set<Long> definitionIds = new LinkedHashSet<>();`\n"))

(defn- tuning-step [title blocks]
  {:status "done" :title title :meta [] :blocks blocks})

(defn tuning-files
  "The files of a tuning, up to `stage` (1 to 5)."
  [run-id remarks stage]
  (let [pr-url (str "https://github.com/" (:owner tuning-pull-request) "/" (:name tuning-pull-request)
                    "/pull/" (:number tuning-pull-request))]
    (cond-> {"owllie/process.json" (write (tuning-process run-id remarks))}
      (>= stage 1)
      (assoc (step-path "inputs" nil)
             (write (tuning-step "Inputs" [{:type "markdown"
                                            :text (str "Review of acme/widget#412 at `d07be31`, "
                                                       remarks " remark(s).")}])))
      (>= stage 2)
      (assoc (step-path "diagnosis" nil)
             (write (tuning-step "Misses" [{:type "table" :columns ["Remark" "Cause" "Evidence"]
                                            :rows [["r2" "rule_too_narrow" "R-CONC-001 covers maps of a singleton only"]]}])))
      (>= stage 3)
      (assoc (step-path "tune-general-rules" nil)
             (write (tuning-step "General rules" [{:type "markdown" :text "**r1** applied: an exception for maps written back in reading order."}
                                                  {:type "diff" :title "rules/general-rules.md" :text rule-change}])))
      (>= stage 4)
      (assoc (step-path "validation" nil)
             (write (tuning-step "Cases" [{:type "table" :columns ["Case" "Before" "After" "Expected"]
                                           :rows [["general-rules/linked-map-written-back" "found" "not found" "not found"]
                                                  ["general-rules/linked-map-written-back-ok" "found" "found" "found"]]}]))
             (step-path "replay" nil)
             (write (tuning-step "Real diff" [{:type "table" :columns ["Remark" "Before" "After" "Met"]
                                               :rows [["r1" "3/3 found" "0/3 found" "yes"]]}]))
             (step-path "leak-check" nil)
             (write (tuning-step "Leak check" [{:type "markdown" :text "214 words checked, no leak."}])))
      (>= stage 5)
      (assoc (step-path "pull-request" nil)
             (write (tuning-step "Pull request"
                                 [{:type "markdown" :text (str "[Pull request](" pr-url ") opened, ready for review.")}
                                  {:type "table" :columns ["Remark" "Kind" "Rule" "Outcome" "Reason"]
                                   :rows [["r1" "false_positive" "R-COLL-001" "applied" ""]]}
                                  {:type "diff" :title "Change" :text rule-change}]))))))

(defn tuning-status [state updated-ms links]
  {:state state :updated_at (iso updated-ms) :error nil
   :headline (if (= "done" state)
               [{:label "applied" :value 1 :tone "green"} {:label "not applied" :value 0 :tone "neutral"}
                {:label "not met" :value 0 :tone "neutral"} {:label "regressions" :value 0 :tone "neutral"}]
               [])
   :links links})

(defn simulate-tuning
  "A tuning launcher's `on-fire` for the demo: answers at once with a made-up
  session, then writes the tuning's branch step by step through `write!`,
  `step-ms` apart, and ends with a pull request on Owllie."
  [{:keys [write! now-fn step-ms]}]
  (fn [request-text]
    (let [branch-name (request-value request-text "RUN_BRANCH")
          run-id (second (re-matches #"tune/(.+)" (str branch-name)))
          remarks (count (re-seq #"(?m)^REMARK=" request-text))
          pr-url (str "https://github.com/" (:owner tuning-pull-request) "/" (:name tuning-pull-request)
                      "/pull/" (:number tuning-pull-request))
          step! (fn [stage state links]
                  (Thread/sleep (long step-ms))
                  (write! branch-name
                          (assoc (tuning-files run-id remarks stage)
                                 "owllie/status.json" (write (tuning-status state (now-fn) links)))))]
      (Thread/startVirtualThread
       (fn []
         (try
           (doseq [stage (range 0 5)] (step! stage "running" []))
           (step! 5 "done" [{:label "Pull request" :url pr-url :kind "pull_request"}])
           (catch InterruptedException _ nil))))
      {:session-id (str "session_tune" (str/replace (str run-id) #"[^A-Za-z0-9]" ""))
       :session-url "https://claude.ai/code"})))
