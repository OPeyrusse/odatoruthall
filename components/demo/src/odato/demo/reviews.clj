(ns odato.demo.reviews
  "Reviews for an installation with nothing to connect to: results branches
  already pushed, in every state a review can be in, and a launcher that
  writes a run's branch step by step, the way a real Owllie session does.

  The files follow Owllie's shared/run-layout.md."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [odato.time.interface :as t])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)))

(def results-repository {:owner "acme" :name "owllie-results"})

(def agents ["intent-reader" "general-rules" "edge-case-tests" "impact" "naming"
             "intent-comparator" "concurrency" "performance"])

(def ^:private agent-models
  {"intent-reader" ["sonnet" "high"] "general-rules" ["sonnet" "medium"]
   "edge-case-tests" ["sonnet" "high"] "impact" ["sonnet" "high"]
   "naming" ["sonnet" "medium"] "intent-comparator" ["sonnet" "medium"]
   "concurrency" ["opus" "high"] "performance" ["sonnet" "medium"]})

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

(defn- finding
  [{:keys [rule severity confidence file line quote observation why suggestion status reason into]
    :or {confidence "high" status "kept"}}]
  {:rule rule :severity severity :confidence confidence :file file :line line :quote quote
   :observation observation :why why :suggestion suggestion
   :fingerprint (sha1-12 (str rule "\n" file "\n" quote))
   :validation {:status status :reason reason :merged_into into}})

(def ^:private widget-412-findings
  {"general-rules"
   [(finding {:rule "R-ARGS-002" :severity "major"
              :file "src/main/java/acme/widget/SnapshotCache.java" :line 41
              :quote "public void warm(String[] keys)"
              :observation "A Java array is passed as an argument."
              :why "Rule R-ARGS-002: prefer List or Set over arrays as arguments."
              :suggestion "Take a List<String> instead."})
    (finding {:rule "R-COLL-001" :severity "major" :confidence "medium"
              :file "src/main/java/acme/widget/SnapshotCache.java" :line 18
              :quote "private final Map<String, Snapshot> byKey = new LinkedHashMap<>();"
              :observation "A LinkedHashMap is used with no stated need for the insertion order."
              :why "Rule R-COLL-001: no LinkedHashMap without an explicit order requirement."
              :suggestion "Use a HashMap, or say why the order matters."})
    (finding {:rule "R-ARGS-001" :severity "minor"
              :file "src/main/java/acme/widget/Renderer.java" :line 77
              :quote "render(Snapshot s, int width, int height, Theme theme, Locale locale, boolean dense)"
              :observation "Six parameters." :why "Rule R-ARGS-001." :suggestion "Group the layout in an argument object."
              :status "rejected" :reason "quote found neither in diff.patch nor in the cited file"})]
   "edge-case-tests"
   [(finding {:rule "R-EDGE-EMPTY" :severity "major"
              :file "src/main/java/acme/widget/SnapshotCache.java" :line 41
              :quote "public void warm(String[] keys)"
              :observation "No test warms the cache with no key."
              :why "Rule R-EDGE-EMPTY: an empty collection is the first edge case."
              :suggestion "Add a test for warm() with an empty list."
              :status "merged" :into nil})]
   "concurrency"
   [(finding {:rule "R-CONC-001" :severity "blocker"
              :file "src/main/java/acme/widget/SnapshotCache.java" :line 52
              :quote "if (!byKey.containsKey(key)) { byKey.put(key, load(key)); }"
              :observation "Check-then-act on a map shared by every request of a singleton bean."
              :why "Two requests for the same key both load it, and the map is not thread-safe."
              :suggestion "Use a ConcurrentHashMap and computeIfAbsent."})]
   "impact" []
   "naming" []
   "intent-reader" []
   "intent-comparator"
   [(finding {:rule "INTENT-FOUND-NOT-CLAIMED" :severity "minor" :confidence "medium"
              :file "src/main/java/acme/widget/Renderer.java" :line 12
              :quote "private static final Duration TTL = Duration.ofMinutes(5);"
              :observation "The cache entries expire after five minutes; the description does not say so."
              :why "A behaviour the description does not announce."
              :suggestion "Mention the expiry in the description."})]
   "performance" []})

(defn- merge-into [findings]
  ;; The edge-case finding sits on the same line as the array argument: it is
  ;; merged into it.
  (let [target (:fingerprint (first (get findings "general-rules")))]
    (update findings "edge-case-tests"
            (fn [fs] (mapv #(assoc-in % [:validation :merged_into] target) fs)))))

(defn- agent-file [agent findings at]
  (let [[model effort] (agent-models agent)]
    {:schema_version 1 :agent agent :model model :effort effort :status "done" :attempts 1
     :started_at (iso (- at (t/minutes 4))) :finished_at (iso at) :duration_s 240 :error nil
     :output (cond-> {:agent agent :findings findings :nothing_to_report (empty? findings)
                      :limitations (if (= agent "edge-case-tests") ["Diff coverage not provided."] [])}
               (= agent "impact")
               (assoc :symbols [{:symbol "acme.widget.SnapshotCache#warm(String[])"
                                 :kind "contract" :callers [{:file "src/main/java/acme/widget/Boot.java" :line 22
                                                             :quote "cache.warm(keys);"}]
                                 :callers_truncated false
                                 :paths_to_entry_points [["BootController#start" "SnapshotCache#warm"]]
                                 :risk "medium" :justification "Public method called at startup."}]))}))

(defn- summary [findings]
  (let [kept (for [[agent fs] findings f fs :when (= "kept" (get-in f [:validation :status]))]
               (assoc f :agent agent))
        order (zipmap ["blocker" "major" "minor" "info"] (range))
        shown (->> kept (sort-by (comp order :severity)) vec)]
    {:schema_version 1
     :verdict (str "A check-then-act on a shared map can load the same snapshot twice under load. "
                   "The new cache takes an array and keeps an insertion order nobody asked for.")
     :intent {:status "minor_gaps"
              :gaps (vec (for [f shown :when (str/starts-with? (:rule f) "INTENT")] (:fingerprint f)))}
     :findings (vec (for [f shown]
                      (-> f
                          (dissoc :validation :agent :rule)
                          (assoc :rules (cond-> [(:rule f)]
                                          (= "R-ARGS-002" (:rule f)) (conj "R-EDGE-EMPTY"))
                                 :agents (cond-> [(:agent f)]
                                           (= "R-ARGS-002" (:rule f)) (conj "edge-case-tests"))))))
     :omitted {:blocker 0 :major 0 :minor 0 :info 0}
     :counts (frequencies (map :severity shown))
     :impact [{:symbol "acme.widget.SnapshotCache#warm(String[])" :kind "contract" :callers 1
               :callers_truncated false :entry_points ["BootController#start"] :risk "medium"}]
     :tests [{:file "src/main/java/acme/widget/SnapshotCache.java" :line 41
              :case "warm() with no key" :fingerprint nil}]
     :limitations ["Diff coverage not provided."]}))

(defn- meta-file [pr run-id at]
  {:schema_version 1 :run_id run-id :created_at (iso at)
   :request {:pr_url (str "https://github.com/" (:owner pr) "/" (:name pr) "/pull/" (:number pr))
             :results_repo "acme/owllie-results" :run_branch (branch pr run-id) :brain_ref nil}
   :brain {:ref "main" :sha "73a41fc0e2b1d9c4a8f6e5d3c2b1a0f9e8d7c6b5"}
   :pr {:owner (:owner pr) :repo (:name pr) :number (:number pr) :title (:title pr)
        :author (:author pr) :base_ref "main" :base_sha "3e0f2b1" :head_ref "feature" :head_sha "d07be31"}
   :diff {:files 6 :lines 412}})

(defn- progress-file [status started updated agent-states error]
  {:schema_version 1 :status status :started_at (iso started) :updated_at (iso updated)
   :finished_at (when (#{"done" "failed"} status) (iso updated))
   :agents (into {} (for [a agents]
                      [a (let [s (get agent-states a "pending")]
                           {:status s :findings (when (= s "done") 0) :attempts (if (= s "pending") 0 1)
                            :started_at (when (not= s "pending") (iso started))
                            :finished_at (when (#{"done" "failed" "skipped"} s) (iso updated))})]))
   :error error})

(defn done-run
  "The files of a review that went to the end."
  [pr run-id at]
  (let [findings (merge-into widget-412-findings)]
    (merge {"owllie/meta.json" (write (meta-file pr run-id at))
            "owllie/progress.json" (write (progress-file "done" at (+ at (t/minutes 11))
                                                         (zipmap agents (repeat "done")) nil))
            "owllie/summary.json" (write (summary findings))
            "owllie/report.md" "# Report\n"}
           (into {} (for [a agents]
                      [(str "owllie/agents/" a ".json")
                       (write (agent-file a (get findings a []) (+ at (t/minutes 8))))])))))

(defn failed-run [pr run-id at]
  {"owllie/meta.json" (write (assoc (meta-file pr run-id at) :diff {:files 48 :lines 5210}))
   "owllie/progress.json" (write (progress-file "failed" at (+ at (t/minutes 2)) {}
                                                {:code "diff_too_large"
                                                 :message "the diff has 5210 lines, more than 3000; review it by subsets of files"}))})

(defn stalled-run [pr run-id at]
  {"owllie/meta.json" (write (meta-file pr run-id at))
   "owllie/progress.json" (write (progress-file "phase2" at (+ at (t/minutes 6))
                                                (merge (zipmap (take 5 agents) (repeat "done"))
                                                       {"concurrency" "running" "performance" "running"
                                                        "intent-comparator" "running"})
                                                nil))
   "owllie/agents/general-rules.json"
   (write (agent-file "general-rules" (get widget-412-findings "general-rules") (+ at (t/minutes 4))))})

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
          started (now-fn)
          step! (fn [files] (Thread/sleep (long step-ms)) (write! branch-name files))]
      (Thread/startVirtualThread
       (fn []
         (try
           (step! {"owllie/meta.json" (write (meta-file pr run-id started))
                   "owllie/progress.json" (write (progress-file "preparing" started (now-fn) {} nil))})
           (step! {"owllie/progress.json"
                   (write (progress-file "phase1" started (now-fn)
                                         (zipmap (take 5 agents) (repeat "running")) nil))})
           (let [files (done-run pr run-id started)
                 phase1 (select-keys files (for [a (take 5 agents)] (str "owllie/agents/" a ".json")))]
             (step! (assoc phase1 "owllie/progress.json"
                           (write (progress-file "phase2" started (now-fn)
                                                 (merge (zipmap (take 5 agents) (repeat "done"))
                                                        (zipmap (drop 5 agents) (repeat "running")))
                                                 nil))))
             (step! (assoc (apply dissoc files "owllie/summary.json" (keys phase1))
                           "owllie/progress.json"
                           (write (progress-file "synthesis" started (now-fn)
                                                 (zipmap agents (repeat "done")) nil))))
             (step! (assoc (select-keys files ["owllie/summary.json"])
                           "owllie/progress.json"
                           (write (progress-file "done" started (now-fn)
                                                 (zipmap agents (repeat "done")) nil)))))
           (catch InterruptedException _ nil))))
      {:session-id (str "session_demo" (str/replace (str run-id) #"[^A-Za-z0-9]" ""))
       :session-url "https://claude.ai/code"})))
