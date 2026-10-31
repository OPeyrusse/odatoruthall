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
  [["prepare" "Preparation" [["inputs" "Inputs" "md"]]]
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

(defn process-file [pr run-id]
  {:schema "owllie-run/2"
   :run_id run-id
   :title (:title pr)
   :pull_request {:url (str "https://github.com/" (:owner pr) "/" (:name pr) "/pull/" (:number pr))}
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

(defn- inputs-md [pr]
  (str "**" (:title pr) "** by " (:author pr) "\n\n"
       "`main` @ `3e0f2b1` ← `feature` @ `d07be31` · 412 diff lines in 6 files\n\n"
       "### Description\n\nCaches the rendered snapshot between two renders.\n\n"
       "### Files\n\n```\n src/main/java/acme/widget/SnapshotCache.java | 64 +++++\n```\n"))

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
   (step-path "inputs" "md") (inputs-md pr)})

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

(defn github-script
  "What the demo's mock GitHub adds for owldesk."
  [now]
  {:repositories {"acme/owllie-results" {:default-branch "main" :files #{".owllie-results"}}
                  "acme/widget" {:default-branch "main"}
                  "acme/gear" {:default-branch "main"}}
   :pull-request-details
   (into {} (for [[k p] pull-requests]
              [k {:title (:title p) :author (:author p) :state (:state p)
                  :closed-at (when (not= :open (:state p)) (- now (t/days 19)))
                  :head-sha "d07be31" :url (str "https://github.com/" (:owner p) "/" (:name p)
                                                "/pull/" (:number p))}]))
   :branches {"acme/owllie-results" (seed-branches now)}})

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
           (step! (merge {(step-path "inputs" "md") (inputs-md pr)} (status "running" nil)))
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
