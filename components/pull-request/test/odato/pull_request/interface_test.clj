(ns odato.pull-request.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [odato.config.interface :as config]
            [odato.pull-request.interface :as prs]
            [odato.testing]
            [odato.time.interface :as t]))

(def now (t/parse-instant "2026-09-15T12:00:00Z"))

(def in-force
  {:thresholds {:aging-ms (t/days 15) :comments 15 :commits 10
                :failing-commits 10 :merged-window-ms (t/days 3)}})

(defn- commits [n rollup]
  (vec (for [i (range n)]
         {:sha (str "sha" i)
          :committed-at (- now (* i (t/hours 1)))
          :check-rollup rollup})))

(defn- pull-request [facts-overrides]
  {:repository {:owner "acme" :name "widget"}
   :number 7
   :first-seen-at now
   :last-seen-at now
   :facts (prs/facts (merge {:title "Ship it"
                             :author "ada"
                             :state :open
                             :opened-at (- now (t/days 1))
                             :updated-at now
                             :commits (commits 1 :success)}
                            facts-overrides))})

(defn- view [overrides] (prs/view (pull-request overrides) in-force now))

(deftest green-is-the-newest-commits-rollup
  (is (= :success (:head-check (view {:commits (commits 3 :success)}))))
  (is (= :failure (:head-check (view {:commits [{:sha "a" :committed-at now
                                                 :check-rollup :failure}
                                                {:sha "b" :committed-at (- now 1)
                                                 :check-rollup :success}]}))))
  (is (nil? (:head-check (view {:commits []})))
      "a pull request with no commits has no rollup to report"))

(deftest only-the-last-review-of-each-reviewer-counts
  (let [v (view {:reviews [{:reviewer "grace" :verdict :changes-requested
                            :submitted-at (- now (t/hours 5)) :reviewed-sha "old"}
                           {:reviewer "grace" :verdict :approved
                            :submitted-at (- now (t/hours 1)) :reviewed-sha "sha0"}
                           {:reviewer "linus" :verdict :commented
                            :submitted-at (- now (t/hours 2)) :reviewed-sha "sha0"}]})]
    (is (= 1 (count (:decisive-reviews v))) "a comment is not a verdict")
    (is (:approved? v))
    (is (empty? (:change-requests v)))))

(deftest changes-requested-blocks-approval
  (let [v (view {:reviews [{:reviewer "grace" :verdict :approved
                            :submitted-at (- now (t/hours 2)) :reviewed-sha "sha0"}
                           {:reviewer "linus" :verdict :changes-requested
                            :submitted-at (- now (t/hours 1)) :reviewed-sha "sha0"}]})]
    (is (not (:approved? v)))
    (is (= 1 (count (:approvals v))))
    (is (= 1 (count (:change-requests v))))))

(deftest addressed-means-back-in-the-reviewers-court
  (testing "commits have landed since the reviewer looked"
    (let [v (view {:commits [{:sha "new" :committed-at now :check-rollup :success}]
                   :reviews [{:reviewer "grace" :verdict :changes-requested
                              :submitted-at (- now (t/hours 3)) :reviewed-sha "old"}]})]
      (is (= #{"grace"} (:awaiting-rereview v)))
      (is (prs/addressed-for? v "grace"))))
  (testing "nothing has landed since"
    (let [v (view {:commits [{:sha "same" :committed-at (- now (t/hours 5))
                              :check-rollup :success}]
                   :reviews [{:reviewer "grace" :verdict :changes-requested
                              :submitted-at (- now (t/hours 3)) :reviewed-sha "same"}]})]
      (is (empty? (:awaiting-rereview v))))))

(deftest classifications-read-the-thresholds-in-force
  (testing "aging"
    (is (not (:aging? (view {:opened-at (- now (t/days 14))}))))
    (is (:aging? (view {:opened-at (- now (t/days 15))})))
    (is (not (:aging? (view {:state :merged :merged-at now
                             :opened-at (- now (t/days 40))})))
        "a merged pull request is not aging"))
  (testing "conflicting"
    (is (not (:conflicting? (view {:comment-count 14}))))
    (is (:conflicting? (view {:comment-count 15}))))
  (testing "difficult needs the commits and the approval"
    (let [approved [{:reviewer "grace" :verdict :approved
                     :submitted-at (- now (t/hours 1)) :reviewed-sha "sha0"}]]
      (is (not (:difficult? (view {:commits (commits 10 :success)})))
          "commits alone are not difficulty")
      (is (not (:difficult? (view {:commits (commits 9 :success) :reviews approved})))
          "nor is an approval alone")
      (is (:difficult? (view {:commits (commits 10 :success) :reviews approved})))))
  (testing "stuck: approved, and the last ten commits fail their checks"
    (let [approved [{:reviewer "grace" :verdict :approved
                     :submitted-at (- now (t/hours 1)) :reviewed-sha "sha0"}]]
      (is (:stuck? (view {:commits (commits 10 :failure) :reviews approved})))
      (is (not (:stuck? (view {:commits (commits 10 :failure)})))
          "an unapproved pull request going nowhere is not stuck")
      (is (not (:stuck? (view {:commits (into (commits 9 :failure)
                                              [{:sha "ok" :committed-at (- now (t/days 1))
                                                :check-rollup :success}])
                               :reviews approved})))
          "one green commit in the window is enough")
      (is (not (:stuck? (view {:commits (commits 9 :failure) :reviews approved})))
          "and there have to be ten of them")))
  (testing "merging"
    (let [approved [{:reviewer "grace" :verdict :approved
                     :submitted-at (- now (t/hours 1)) :reviewed-sha "sha0"}]
          merging {:mergeable true :auto-merge-enabled true :reviews approved}]
      (is (:merging? (view merging)))
      (is (not (:merging? (view (assoc merging :is-draft true)))))
      (is (not (:merging? (view (assoc merging :mergeable false)))))
      (is (not (:merging? (view (assoc merging :auto-merge-enabled false)))))
      (is (not (:merging? (view (dissoc merging :reviews)))))))
  (testing "merged fades with the window"
    (is (:recently-merged? (view {:state :merged :merged-at (- now (t/days 2))})))
    (is (not (:recently-merged? (view {:state :merged :merged-at (- now (t/days 4))}))))))

(deftest a-threshold-change-reclassifies-what-is-on-screen
  (let [pr (pull-request {:comment-count 10})]
    (is (not (:conflicting? (prs/view pr in-force now))))
    (is (:conflicting? (prs/view pr (assoc-in in-force [:thresholds :comments] 10) now))
        "no collection needed")))

(deftest of-interest-is-the-operator-or-a-tracked-member
  (let [tracked {:members #{"grace"} :operator "ada"}]
    (is (prs/of-interest? (prs/facts {:author "ada"}) tracked))
    (is (prs/of-interest? (prs/facts {:author "grace"}) tracked))
    (is (prs/of-interest? (prs/facts {:author "linus" :assignees #{"ada"}}) tracked))
    (is (prs/of-interest? (prs/facts {:author "linus" :assignees #{"grace"}}) tracked))
    (is (not (prs/of-interest? (prs/facts {:author "linus" :assignees #{"torvalds"}})
                               tracked)))))

(deftest lists-read-most-recent-first
  (let [old (assoc-in (pull-request {}) [:facts :updated-at] (- now (t/days 2)))
        new (assoc-in (pull-request {}) [:facts :updated-at] now)]
    (is (= [now (- now (t/days 2))]
           (map #(get-in % [:facts :updated-at])
                (prs/by-recent-activity [(prs/view old in-force now)
                                         (prs/view new in-force now)]))))))

(deftest the-defaults-are-the-reference-values
  (is (= (:thresholds in-force) (:thresholds (config/current (odato.testing/system))))))
