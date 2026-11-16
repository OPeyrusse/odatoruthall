(ns odato.review.tuning-test
  "Tuning, as specs/owldesk/tuning.allium has it: remarks drafted, changed and
  deleted review by review, sent to Owllie in one batch, the tuning read back
  from its branch with its pull request, and the reviewers' comments offered
  as candidate misses."
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
(def pr-412 {:owner "acme" :name "widget" :number 412 :author "margaret" :title "Cache the snapshot"})
(def now (t/parse-instant testing/default-instant))
(def run-id (demo/run-id (- now (t/hours 3)) "a412"))
(def cache "src/main/java/acme/widget/SnapshotCache.java")
(def renderer "src/main/java/acme/widget/Renderer.java")

(defn- comment* [id author line & {:as extra}]
  (merge {:id id :author author :path cache :line line :original-line line :side "RIGHT"
          :commit-id demo/head-sha :original-commit-id demo/head-sha
          :body (str "comment " id) :url (str "https://github.com/acme/widget/pull/412#discussion_r" id)}
         extra))

(def comments
  [(comment* "1" "grace" 27)
   (comment* "2" "linus" 50)                                        ; within 3 lines of a finding (52)
   (comment* "3" "ada" 35)                                          ; the operator's
   (comment* "4" "linus" 27 :in-reply-to "1")                       ; a reply
   (comment* "5" "margaret" 30)                                     ; the author's
   (comment* "6" "grace" 99 :line nil :commit-id "other" :original-commit-id "older"
             :diff-hunk "@@ -0,0 +1,58 @@\n+    byKey.clear();")    ; written on another commit
   (comment* "7" "grace" 3 :commit-id "other" :original-commit-id "older"
             :diff-hunk "@@ -0,0 +1,58 @@\n+}")])                  ; a text found many times

(defn- system
  ([] (system {}))
  ([{:keys [answer tune? no-head?] :or {answer {:session-url "https://claude.ai/code/session_t"
                                                :session-id "session_t"}
                                        tune? true}}]
   (let [files (cond-> (demo/done-run pr-412 run-id (- now (t/hours 3)))
                 no-head? (assoc "owllie/process.json"
                                 (json/write-str (update (demo/process-file pr-412 run-id)
                                                         :pull_request dissoc :head_sha))))
         sys (testing/connected!
              {:github {:repositories {"activeviam/owllie-results" {:files #{".owllie-results"}}
                                       "acme/widget" {}}
                        :pull-request-details {"acme/widget#412" {:title (:title pr-412) :author "margaret"
                                                                  :state :open}
                                               "acme/owllie#7" {:title "Tune" :author "owllie" :state :open
                                                                :draft false :mergeable-state "clean"}}
                        :review-comments {"acme/widget#412" comments}
                        :branches {"activeviam/owllie-results" {(demo/branch pr-412 run-id) files}}}})
         tuner (owllie/mock-launcher (fn [_] answer))
         sys (assoc sys
                    :launchers {:scheduled-task (owllie/mock-launcher (fn [_] answer))}
                    :tune-launchers {:scheduled-task (when tune? tuner)}
                    :results-writer (:github sys))]
     (review/ensure-defaults! sys)
     (review/save-settings! sys {:results-repository "activeviam/owllie-results" :brain-ref "main"
                                 :launcher :scheduled-task :keep-runs 3
                                 :closed-retention-ms (t/days 14) :failed-retention-ms (t/days 3)})
     (review/check-results-repository! sys)
     (review/read-results! sys)
     sys)))

(defn- the-review [sys] (review/review sys run-id))

(defn- finding-at [sys line]
  (some (fn [[fp f]] (when (= line (get-in f [:location :line])) fp)) (review/findings (the-review sys))))

;; ------------------------------------------------------------- drafting

(deftest remarks-on-findings-lines-and-the-review
  (let [sys (system)
        race (finding-at sys 52)]
    (testing "a false positive on a finding keeps its copy and its code, and marks the finding"
      (let [{:keys [ok remark-id]} (review/draft-remark! sys run-id {:kind "false-positive" :fingerprint race
                                                                      :note "The bean is request scoped."})
            remark (review/remark sys remark-id)]
        (is ok)
        (is (= :draft (:status remark)))
        (is (= "R-CONC-001" (:rule remark)))
        (is (= race (get-in remark [:finding :fingerprint])))
        (is (= ["concurrency"] (get-in remark [:finding :agents])))
        (is (= {:file cache :side :new :line 52 :end-line 52} (:lines remark)))
        (is (= 52 (:new (first (filter :marked (:excerpt remark))))))
        (is (str/starts-with? (:quote remark) "if (!byKey.containsKey(key))"))
        (is (= :false-positive (get (review/judgements sys run-id) race)))))
    (testing "a missed remark on removed lines, with the severity it should have had"
      (let [{:keys [ok remark-id]} (review/draft-remark! sys run-id
                                                         {:kind "missed" :lines {:file renderer :side "old" :line "11"}
                                                          :note "The field was read by the debug overlay."
                                                          :proposed-severity "major" :rule " R-API-001 "})
            remark (review/remark sys remark-id)]
        (is ok)
        (is (= {:file renderer :side :old :line 11 :end-line 11} (:lines remark)))
        (is (= "  private Snapshot last;" (:quote remark)))
        (is (= "major" (:proposed-severity remark)))
        (is (= "R-API-001" (:rule remark)))
        (is (nil? (:finding remark)))))
    (testing "a general remark has no code"
      (let [{:keys [remark-id]} (review/draft-remark! sys run-id {:kind "general" :note "Too verbose."})
            remark (review/remark sys remark-id)]
        (is (nil? (:lines remark)))
        (is (nil? (:excerpt remark)))))
    (is (= [:false-positive :missed :general] (map :kind (review/remarks sys run-id))))))

(deftest a-refused-remark-records-nothing
  (let [sys (system)
        race (finding-at sys 52)
        refused (fn [proposal] (:fault (review/draft-remark! sys run-id proposal)))]
    (is (= :finding-not-found (refused {:kind "relevant" :fingerprint "000000000000"})))
    (is (= :lines-not-in-diff (refused {:kind "missed" :lines {:file renderer :side "new" :line 77} :note "x"})))
    (is (= :lines-not-in-diff (refused {:kind "missed" :note "x"})))
    (is (= :note-required (refused {:kind "unclear" :fingerprint race :note "  "})))
    (is (= :note-required (refused {:kind "general" :note ""})))
    (is (= :note-too-long (refused {:kind "general" :note (apply str (repeat 2001 "x"))})))
    (is (= :severity-not-changed (refused {:kind "wrong-severity" :fingerprint race :proposed-severity "blocker"})))
    (is (= :severity-not-changed (refused {:kind "wrong-severity" :fingerprint race})))
    (is (= :no-results (:fault (review/draft-remark! sys "20260101T0000Z-dead" {:kind "general" :note "x"}))))
    (is (empty? (review/remarks sys run-id)))
    (is (empty? (review/judgements sys run-id)))))

(deftest drafts-change-until-they-are-sent
  (let [sys (system)
        race (finding-at sys 52)
        {:keys [remark-id]} (review/draft-remark! sys run-id {:kind "wrong-severity" :fingerprint race
                                                              :proposed-severity "major"})]
    (is (:ok (review/edit-remark! sys remark-id {:note "Only one request per key." :proposed-severity "minor"})))
    (is (= ["Only one request per key." "minor"]
           ((juxt :note :proposed-severity) (review/remark sys remark-id))))
    (is (= :severity-not-changed (:fault (review/edit-remark! sys remark-id {:proposed-severity "blocker"}))))
    (testing "a deleted draft goes; the verdict of another stays"
      (let [{other :remark-id} (review/draft-remark! sys run-id {:kind "relevant" :fingerprint race})]
        (is (:ok (review/delete-remark! sys other)))
        (is (nil? (review/remark sys other)))
        (is (= :relevant (get (review/judgements sys run-id) race)))))
    (testing "a sent remark no longer changes"
      (is (:ok (review/send-remarks! sys run-id)))
      (is (= :not-a-draft (:fault (review/edit-remark! sys remark-id {:note "x" :proposed-severity "minor"}))))
      (is (= :not-a-draft (:fault (review/delete-remark! sys remark-id)))))))

;; -------------------------------------------------------------- sending

(deftest a-batch-is-one-request-without-code
  (let [sys (system)
        race (finding-at sys 52)]
    (review/draft-remark! sys run-id {:kind "false-positive" :fingerprint race :note "Request scoped."})
    (review/draft-remark! sys run-id {:kind "missed" :lines {:file cache :side "new" :line "27" :end-line "29"}
                                      :note "Magic number.\nShare the TTL."})
    (review/draft-remark! sys run-id {:kind "general" :note "Too verbose."})
    (let [{:keys [ok] tune-id :run-id} (review/send-remarks! sys run-id)
          text (first (owllie/mock-calls (get-in sys [:tune-launchers :scheduled-task])))
          lines (str/split-lines text)
          remark (fn [i] (json/read-str (subs (nth lines (+ 7 i)) (count "REMARK="))))]
      (is ok)
      (is (= ["OWLLIE_TUNE v1"
              "PR_URL=https://github.com/acme/widget/pull/412"
              (str "HEAD_SHA=" demo/head-sha)
              (str "REVIEW_RUN=" run-id)
              "RESULTS_REPO=activeviam/owllie-results"
              (str "RUN_BRANCH=tune/" tune-id)
              "BRAIN_BASE=main"]
             (take 7 lines)))
      (is (= 10 (count lines)))
      (testing "remarks in Owllie's field names, numbered in the order written"
        (is (= {"id" "r1" "kind" "false_positive" "agents" ["concurrency"] "rule" "R-CONC-001"
                "note" "Request scoped." "fingerprint" race "severity" "blocker"
                "file" cache "side" "new" "line" 52 "end_line" 52}
               (dissoc (remark 0) "observation" "quote")))
        (is (string? (get (remark 0) "observation")))
        (is (= {"id" "r2" "kind" "missed" "agents" [] "rule" nil "note" "Magic number.\nShare the TTL."
                "file" cache "side" "new" "line" 27 "end_line" 29
                "quote" "    if (snapshot == null || snapshot.isOlderThan(clock.now(), Duration.ofMinutes(5))) {"}
               (remark 1)))
        (is (= {"id" "r3" "kind" "general" "agents" [] "rule" nil "note" "Too verbose."} (remark 2))))
      (testing "the excerpt never travels"
        (is (not (str/includes? text "excerpt"))))
      (testing "the remarks are sent, tied to their batch"
        (is (every? #(and (= :sent (:status %)) (= tune-id (:batch %))) (review/remarks sys run-id))))
      (testing "the tuning starts, and another batch waits"
        (let [tn (review/tuning sys tune-id)]
          (is (= :starting (:state tn)))
          (is (= run-id (:review-run-id tn)))
          (is (= "https://claude.ai/code/session_t" (get-in tn [:launch :session-url]))))
        (review/draft-remark! sys run-id {:kind "general" :note "Again."})
        (is (= :tuning-in-flight (:refusal (review/send-remarks! sys run-id))))))))

(deftest what-prevents-a-batch
  (is (= :no-draft-remarks (:refusal (review/send-remarks! (system) run-id))))
  (let [sys (system {:tune? false})]
    (review/draft-remark! sys run-id {:kind "general" :note "x"})
    (is (= :launcher-unavailable (:refusal (review/send-remarks! sys run-id)))))
  (let [sys (system {:no-head? true})]
    (review/draft-remark! sys run-id {:kind "general" :note "x"})
    (is (= :head-unknown (:refusal (review/send-remarks! sys run-id)))))
  (let [sys (system)]
    (dotimes [i 21] (review/draft-remark! sys run-id {:kind "general" :note (str "n" i)}))
    (is (= :too-many-remarks (:refusal (review/send-remarks! sys run-id))))
    (is (every? #(= :draft (:status %)) (review/remarks sys run-id)) "a refused batch leaves its drafts")))

;; -------------------------------------------------------- following one

(deftest a-tuning-is-read-from-its-branch-and-its-pull-request
  (let [sys (system)]
    (review/draft-remark! sys run-id {:kind "general" :note "Too verbose."})
    (let [tune-id (:run-id (review/send-remarks! sys run-id))
          branch (owllie/tune-branch-name tune-id)
          pr-url "https://github.com/acme/owllie/pull/7"
          write! (fn [stage state links]
                   (github/mock-write-branch! (:github sys) results branch
                                              (assoc (demo/tuning-files tune-id 1 stage)
                                                     "owllie/status.json"
                                                     (json/write-str (demo/tuning-status state now links)))))]
      (write! 2 "running" [])
      (review/read-results! sys)
      (let [tn (review/tuning sys tune-id)]
        (is (= :running (:state tn)))
        (is (= ["inputs" "diagnosis" "tune-general-rules" "validation" "replay" "leak-check" "pull-request"]
               (map :id (get-in tn [:run :steps]))))
        (is (nil? (:pull-request tn))))
      (write! 5 "done" [{:label "Pull request" :url pr-url :kind "pull_request"}])
      (review/read-results! sys)
      (is (= pr-url (get-in (review/tuning sys tune-id) [:pull-request :url])))
      (testing "the pull request is read while it is open"
        (review/refresh-tuning-pull-requests! sys)
        (let [pr (:pull-request (review/tuning sys tune-id))]
          (is (= [:open false "clean"] ((juxt :state :draft :mergeable-state) pr)))))
      (testing "a tuning branch is not a review"
        (is (= [run-id] (map :run-id (review/reviews sys)))))
      (testing "removing it deletes its branch and keeps its remarks"
        (is (:ok (review/remove-tuning! sys tune-id)))
        (is (nil? (github/mock-branch-files (:github sys) results branch)))
        (is (nil? (review/tuning sys tune-id)))
        (is (= 1 (count (review/remarks sys run-id))))))))

(deftest remarks-of-a-batch-given-up-can-be-sent-again
  (let [sys (system {:answer {:failure "the scheduled task answered 401"}})]
    (review/draft-remark! sys run-id {:kind "general" :note "Too verbose."})
    (let [tune-id (:run-id (review/send-remarks! sys run-id))
          [sent] (review/remarks sys run-id)]
      (is (= :never-started (:state (review/tuning sys tune-id))))
      (let [{:keys [ok remark-id]} (review/redraft-remark! sys (:id sent))]
        (is ok)
        (is (= [:sent :draft] (map :status (review/remarks sys run-id))))
        (is (= "Too verbose." (:note (review/remark sys remark-id))))))))

;; -------------------------------------------- reviewers' comments

(deftest reviewers-comments-are-offered-as-misses
  (let [sys (system)
        r (the-review sys)]
    (review/read-comments! sys r)
    (let [offered (review/miss-candidates sys r)]
      (is (= ["3" "1" "6"] (map :id offered))
          "the operator's first; not a reply, not the author's, not near a finding, not unplaceable")
      (is (:by-operator (first offered)))
      (is (= {:file cache :side :new :line 48 :end-line 48} (:lines (last offered)))
          "a comment on another commit is placed by its text"))
    (testing "a comment turned into a draft, or dismissed, is no longer offered"
      (let [{:keys [ok remark-id]} (review/draft-from-comment! sys run-id "1")
            remark (review/remark sys remark-id)]
        (is ok)
        (is (= [:missed "comment 1" "1"] ((juxt :kind :note :from-comment) remark)))
        (is (= {:file cache :side :new :line 27 :end-line 27} (:lines remark))))
      (review/dismiss-comment! sys run-id "6")
      (is (= ["3"] (map :id (review/miss-candidates sys (the-review sys))))))))
