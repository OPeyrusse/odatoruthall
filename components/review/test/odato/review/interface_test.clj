(ns odato.review.interface-test
  "Reviews, as specs/owldesk/review.allium has them: the settings and the
  results repository check, launching, reading the branches back, judging
  findings and cleaning up."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odato.demo.reviews :as demo]
            [odato.github.interface :as github]
            [odato.owllie.interface :as owllie]
            [odato.review.interface :as review]
            [odato.testing :as testing]
            [odato.time.interface :as t]))

(def results {:owner "activeviam" :name "owllie-results"})

(def pr-412 {:owner "acme" :name "widget" :number 412 :author "ada"
             :title "WID-88 Cache the snapshot between renders"})

(def proposal
  {:results-repository "activeviam/owllie-results"
   :brain-ref ""
   :launcher :scheduled-task
   :keep-runs 3
   :closed-retention-ms (t/days 14)
   :failed-retention-ms (t/days 3)})

(defn- script [now branches]
  {:repositories {"activeviam/owllie-results" {:files #{".owllie-results"}}
                  "activeviam/unmarked" {}
                  "acme/widget" {}}
   :pull-request-details {"acme/widget#412" {:title (:title pr-412) :author "ada" :state :open}
                          "acme/widget#351" {:title "Old one" :author "linus" :state :merged
                                             :closed-at (- now (t/days 30))}}
   :branches {"activeviam/owllie-results" branches}})

(defn- system
  "A connected installation whose results repository is set and checked,
  with a launcher answering `answer`."
  ([] (system {}))
  ([{:keys [branches answer settings writer?] :or {answer {:session-url "https://claude.ai/code/session_1"
                                                           :session-id "session_1"}
                                                  writer? true}}]
   (let [now (t/parse-instant testing/default-instant)
         sys (testing/connected! {:github (script now branches)})
         launcher (owllie/mock-launcher (fn [_] answer))
         sys (assoc sys
                    :launchers {:scheduled-task launcher :claude-cli nil}
                    :results-writer (when writer? (:github sys)))]
     (review/ensure-defaults! sys)
     (review/save-settings! sys (merge proposal settings))
     (review/check-results-repository! sys)
     sys)))

(defn- now [sys] (t/now-ms (:clock sys)))

(defn- calls-of [sys op] (filter #(= op (:op %)) (github/calls (:github sys))))

;; ------------------------------------------------------------------ settings

(deftest settings-are-saved-apart-and-checked
  (let [sys (testing/connected! {:github (script 0 {})})]
    (review/ensure-defaults! sys)
    (testing "a fresh installation names no results repository"
      (is (nil? (:results-repository (review/settings sys))))
      (is (= :unchecked (review/results-access sys))))
    (testing "a saved repository must be checked before it is ready"
      (is (:ok (review/save-settings! sys proposal)))
      (is (= results (:results-repository (review/settings sys))))
      (is (= :unchecked (review/results-access sys)))
      (is (= :ready (review/check-results-repository! sys)))
      (is (= :ready (review/results-access sys))))
    (testing "saving another repository forgets the check"
      (review/save-settings! sys (assoc proposal :results-repository "activeviam/unmarked"))
      (is (= :unchecked (review/results-access sys)))
      (is (= :unmarked (review/check-results-repository! sys))))
    (testing "a repository GitHub does not show is unreadable"
      (review/save-settings! sys (assoc proposal :results-repository "activeviam/missing"))
      (is (= :unreadable (review/check-results-repository! sys))))
    (testing "refusals change nothing"
      (doseq [[fault change] [[:invalid-results-repository {:results-repository "not a repo"}]
                              [:invalid-brain-ref {:brain-ref "a..b"}]
                              [:retention-out-of-bounds {:keep-runs 0}]
                              [:retention-out-of-bounds {:failed-retention-ms (t/days 400)}]]]
        (is (= {:ok false :fault fault}
               (select-keys (review/save-settings! sys (merge proposal change)) [:ok :fault])))
        (is (= "missing" (get-in (review/settings sys) [:results-repository :name])))))))

;; ----------------------------------------------------------------- launching

(def url "https://github.com/acme/widget/pull/412")

(deftest a-review-is-handed-to-the-launcher
  (let [sys (system)
        outcome (review/launch! sys {:url url})
        launched (review/review sys (:run-id outcome))
        [request] (owllie/mock-calls (get-in sys [:launchers :scheduled-task]))]
    (is (:ok outcome))
    (testing "the request names the pull request, the results repository and the branch"
      (is (str/includes? request "PR_URL=https://github.com/acme/widget/pull/412"))
      (is (str/includes? request "RESULTS_REPO=activeviam/owllie-results"))
      (is (str/includes? request (str "RUN_BRANCH=review/acme/widget/412/" (:run-id outcome))))
      (is (not (str/includes? request "BRAIN_REF"))))
    (testing "the launch is kept with what the launcher answered"
      (is (= :fired (get-in launched [:launch :status])))
      (is (= "https://claude.ai/code/session_1" (get-in launched [:launch :session-url])))
      (is (= (:title pr-412) (:title launched)))
      (is (= :starting (:state launched))))
    (testing "a branch that never appears means the review never started"
      (t/advance! (:clock sys) (t/minutes 6))
      (is (= :never-started (:state (review/review sys (:run-id outcome))))))))

(deftest a-launcher-refusal-is-kept
  (let [sys (system {:answer {:failure "the scheduled task answered 401"}})
        {:keys [run-id]} (review/launch! sys {:url url})
        r (review/review sys run-id)]
    (is (= :never-started (:state r)))
    (is (= "the scheduled task answered 401" (get-in r [:launch :refusal])))))

(deftest what-prevents-a-review
  (testing "in the order the spec says"
    (is (= :invalid-pull-request-url (:refusal (review/launch! (system) {:url "acme/widget#412"}))))
    (is (= :invalid-brain-ref (:refusal (review/launch! (system) {:url url :brain-ref "x..y"}))))
    (is (= :no-results-repository
           (:refusal (review/launch! (system {:settings {:results-repository ""}}) {:url url}))))
    (is (= :results-repository-not-ready
           (:refusal (review/launch! (system {:settings {:results-repository "activeviam/unmarked"}})
                                     {:url url}))))
    (is (= :launcher-unavailable
           (:refusal (review/launch! (system {:settings {:launcher :claude-cli}}) {:url url}))))
    (is (= :pull-request-not-found
           (:refusal (review/launch! (system) {:url "https://github.com/acme/widget/pull/9"})))))
  (testing "one review of a pull request at a time"
    (let [sys (system)]
      (is (:ok (review/launch! sys {:url url})))
      (is (= :already-reviewing (:refusal (review/launch! sys {:url (str url "/files")}))))))
  (testing "no more than the scheduled task accepts in an hour, refused launches included"
    (let [sys (system {:answer {:failure "refused"}})]
      (dotimes [_ 30]
        (is (:ok (review/launch! sys {:url url})))
        (t/advance! (:clock sys) (t/minutes 1)))
      (is (= :fire-limit-reached (:refusal (review/launch! sys {:url url}))))
      (t/advance! (:clock sys) (t/minutes 31))
      (is (:ok (review/launch! sys {:url url})) "an hour later, there is room again"))))

;; ------------------------------------------------------------------- reading

(defn- seeded [now]
  (let [at (fn [ago] (- now ago))
        done-id (demo/run-id (at (t/hours 5)) "a412")
        failed-id (demo/run-id (at (t/hours 4)) "b412")
        stalled-id (demo/run-id (at (t/hours 2)) "c412")]
    {:ids {:done done-id :failed failed-id :stalled stalled-id}
     :branches {(demo/branch pr-412 done-id) (demo/done-run pr-412 done-id (at (t/hours 5)))
                (demo/branch pr-412 failed-id) (demo/failed-run pr-412 failed-id (at (t/hours 4)))
                (demo/branch pr-412 stalled-id) (demo/stalled-run pr-412 stalled-id (at (t/hours 2)))}}))

(deftest the-branches-say-where-each-run-stands
  (let [{:keys [ids branches]} (seeded (t/parse-instant testing/default-instant))
        sys (system {:branches branches})]
    (is (= :read (review/read-results! sys)))
    (is (= {(:done ids) :done (:failed ids) :failed (:stalled ids) :stalled}
           (into {} (map (juxt :run-id :state) (review/reviews sys)))))
    (testing "what a run's files say is there to show"
      (let [done (review/review sys (:done ids))]
        (is (= (:title pr-412) (:title done)))
        (is (= "synthesis" (get-in done [:run :summary-step])))
        (is (= 10 (count (get-in done [:run :steps]))) "the process the branch describes")
        (is (every? #{:done} (map :state (get-in done [:run :steps]))))
        (is (seq (review/findings done)))
        (is (= "diff_too_large" (get-in (review/review sys (:failed ids)) [:run :error :code])))
        (testing "a failed run's steps without a file were not run"
          (is (every? #{:not-run} (map :state (get-in (review/review sys (:failed ids)) [:run :steps])))))))
    (testing "a running run: steps done, the first phase still waiting is running, the rest pending"
      (let [steps (get-in (review/review sys (:stalled ids)) [:run :steps])
            by-phase (into {} (map (fn [[phase ss]] [phase (set (map :state ss))])
                                   (group-by :phase steps)))]
        (is (= {"prepare" #{:done} "phase1" #{:done} "phase2" #{:running} "synthesis" #{:pending}}
               by-phase))))
    (testing "the reviews of one pull request are grouped, the latest first"
      (is (= [[(:stalled ids) (:failed ids) (:done ids)]]
             (map #(map :run-id (:reviews %)) (review/by-pull-request sys)))))))

(deftest a-quiet-repository-costs-one-request
  (let [{:keys [ids branches]} (seeded (t/parse-instant testing/default-instant))
        sys (system {:branches branches})]
    (review/read-results! sys)
    (let [blobs (count (calls-of sys :blob))]
      (review/read-results! sys)
      (is (= blobs (count (calls-of sys :blob))) "nothing moved, nothing is read again")
      (testing "a branch that moves is read again, and only what changed"
        (github/mock-write-branch! (:github sys) results (demo/branch pr-412 (:stalled ids))
                                   {"owllie/status.json"
                                    (json/write-str {:state "running"
                                                     :updated_at (str (java.time.Instant/ofEpochMilli (now sys)))})})
        (review/read-results! sys)
        (is (= (inc blobs) (count (calls-of sys :blob))))
        (is (= :running (:state (review/review sys (:stalled ids))))))
      (testing "a branch deleted elsewhere takes its run with it"
        (github/delete-branch (:github sys) (assoc results :branch (demo/branch pr-412 (:failed ids))))
        (review/read-results! sys)
        (is (nil? (review/review sys (:failed ids))))))))

(deftest the-reader-knows-only-the-process-and-the-blocks
  (let [run-id (demo/run-id (- (t/parse-instant testing/default-instant) (t/hours 1)) "0a0a")
        process {:schema "owllie-run/2" :title "Something new" :summary_step "verdict"
                 :pull_request {:url "https://github.com/acme/widget/pull/412"}
                 :phases [{:id "one" :title "One"
                           :steps [{:id "notes" :title "Notes" :file "owllie/steps/notes.md"}
                                   {:id "odd" :title "Odd" :file "owllie/steps/odd.json"}
                                   {:id "late" :title "Late" :file "owllie/steps/late.json"}]}
                          {:id "two" :title "Two"
                           :steps [{:id "verdict" :title "Verdict" :file "owllie/steps/verdict.json"}]}]}
        sys (system {:branches {(demo/branch pr-412 run-id)
                                {"owllie/process.json" (json/write-str process)
                                 "owllie/status.json" (json/write-str {:state "running"
                                                                       :updated_at testing/default-instant})
                                 "owllie/steps/notes.md" "# Notes\n\nAnything."
                                 "owllie/steps/odd.json" "{not json"
                                 "owllie/steps/verdict.json"
                                 (json/write-str {:status "done" :blocks [{:type "hologram" :x 1}]})}}})]
    (review/read-results! sys)
    (let [r (review/review sys run-id)
          states (into {} (map (juxt :id :state) (get-in r [:run :steps])))]
      (is (= "Something new" (:title r)))
      (is (= {"notes" :done "odd" :running "late" :running "verdict" :done} states)
          "a step without a readable file, in the first phase still waiting, is running")
      (is (= "# Notes\n\nAnything." (get-in (first (get-in r [:run :steps])) [:result :markdown]))))
    (testing "once the run has ended, a missing file was not run and an unreadable one is said so"
      (github/mock-write-branch! (:github sys) results (demo/branch pr-412 run-id)
                                 {"owllie/status.json" (json/write-str {:state "done"
                                                                        :updated_at testing/default-instant})})
      (review/read-results! sys)
      (is (= {"notes" :done "odd" :unreadable "late" :not-run "verdict" :done}
             (into {} (map (juxt :id :state) (get-in (review/review sys run-id) [:run :steps]))))))))

(deftest a-launch-meets-its-branch
  (let [sys (system)
        {:keys [run-id]} (review/launch! sys {:url url})]
    (github/mock-write-branch! (:github sys) results (demo/branch pr-412 run-id)
                               (demo/done-run pr-412 run-id (now sys)))
    (review/read-results! sys)
    (let [r (review/review sys run-id)]
      (is (= :done (:state r)))
      (is (some? (:launch r)))
      (is (some? (:run r))))))

;; ------------------------------------------------------------------ findings

(deftest findings-are-judged-and-the-judgement-outlives-the-branch
  (let [{:keys [ids branches]} (seeded (t/parse-instant testing/default-instant))
        sys (system {:branches branches})
        _ (review/read-results! sys)
        done (review/review sys (:done ids))
        fingerprint (key (first (review/findings done)))]
    (is (:ok (review/judge! sys (:done ids) fingerprint :false-positive)))
    (is (= {fingerprint :false-positive} (review/judgements sys (:done ids))))
    (is (:ok (review/judge! sys (:done ids) fingerprint :relevant)))
    (is (= {fingerprint :relevant} (review/judgements sys (:done ids))))
    (is (= {:ok false :fault :no-such-finding} (review/judge! sys (:done ids) "000000000000" :relevant)))
    (testing "removing the review keeps the judgement and its copy of the finding"
      (is (:ok (review/remove! sys (:done ids))))
      (is (nil? (review/review sys (:done ids))))
      (let [[kept] (review/feedback sys)]
        (is (= :relevant (:feedback/verdict kept)))
        (is (string? (:feedback/quote kept)))))
    (testing "a judgement can be withdrawn"
      (review/withdraw-judgement! sys (:done ids) fingerprint)
      (is (empty? (review/feedback sys))))))

;; ------------------------------------------------------------------- cleanup

(deftest cleanup-candidates
  (let [now (t/parse-instant testing/default-instant)
        pr-351 {:owner "acme" :name "widget" :number 351 :author "linus" :title "Old one"}
        ids (for [h [10 9 8 7]] (demo/run-id (- now (t/hours h)) (str "aa" h "0")))
        old-failed (demo/run-id (- now (t/days 5)) "ff01")
        merged (demo/run-id (- now (t/days 40)) "ee01")
        branches (merge (into {} (for [id ids] [(demo/branch pr-412 id) (demo/done-run pr-412 id now)]))
                        {(demo/branch pr-412 old-failed) (demo/failed-run pr-412 old-failed now)
                         (demo/branch pr-351 merged) (demo/done-run pr-351 merged now)})
        sys (system {:branches branches})]
    (review/read-results! sys)
    (review/refresh-pull-request-states! sys)
    (let [candidates (into {} (map (juxt (comp :run-id :review) :reasons)) (review/cleanup-candidates sys))]
      (is (= {(first ids) #{:beyond-kept}
              old-failed #{:beyond-kept :failed-long-ago}
              merged #{:pull-request-closed}}
             candidates)))
    (testing "a review in flight is never a candidate, and counts among those kept"
      (let [{:keys [run-id]} (review/launch! sys {:url "https://github.com/acme/widget/pull/412"})]
        (is (not-any? #(= run-id (get-in % [:review :run-id])) (review/cleanup-candidates sys)))
        (is (= {:ok false :fault :review-in-flight} (review/remove! sys run-id)))
        (is (= #{(first ids) (second ids) old-failed merged}
               (set (map (comp :run-id :review) (review/cleanup-candidates sys)))))))
    (testing "cleaning up deletes the branches"
      (is (= 4 (:removed (review/clean-up! sys))))
      (is (not (contains? (set (keys (get-in @(:state (:github sys)) [:branches "activeviam/owllie-results"])))
                          (demo/branch pr-351 merged))))
      (is (= 3 (count (review/reviews sys)))))))

(deftest removing-a-branch-needs-a-writer
  (let [{:keys [ids branches]} (seeded (t/parse-instant testing/default-instant))
        sys (system {:branches branches :writer? false})]
    (review/read-results! sys)
    (is (= {:ok false :fault :no-results-writer} (review/remove! sys (:done ids))))
    (is (some? (review/review sys (:done ids))))))
