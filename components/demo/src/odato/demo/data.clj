(ns odato.demo.data
  (:require [odato.pull-request.interface :as prs]
            [odato.time.interface :as t]))

(def configuration
  {:repositories #{{:owner "acme" :name "widget"} {:owner "acme" :name "gear"}}
   :members #{"ada" "grace" "linus" "margaret"}})

(defn- commits [n rollup at]
  (vec (for [i (range n)]
         {:sha (format "%08x" (+ 1000 i (long (/ at 100000))))
          :committed-at (- at (* i (t/hours 3)))
          :check-rollup rollup})))

(defn- observation [owner name number facts]
  {:repository {:owner owner :name name}
   :number number
   :facts (prs/facts (merge {:url (str "https://github.com/" owner "/" name
                                       "/pull/" number)}
                            facts))})

(defn github-script [now]
  (let [approved (fn [who at sha] {:reviewer who :verdict :approved
                                   :submitted-at at :reviewed-sha sha})
        changes (fn [who at sha] {:reviewer who :verdict :changes-requested
                                  :submitted-at at :reviewed-sha sha})]
    {:login "ada"
     :page-size 25
     :budget {:remaining 4712 :resets-at (+ now (t/minutes 43))}
     :pull-requests
     {:repository-pull-requests
      {"acme/widget"
       [(observation "acme" "widget" 412
                     {:title "WID-88 Cache the snapshot between renders"
                      :author "ada" :assignees #{"ada"}
                      :requested-reviewers #{"grace"}
                      :labels #{"performance"}
                      :opened-at (- now (t/days 2)) :updated-at (- now (t/hours 3))
                      :comment-count 4
                      :commits (commits 3 :success (- now (t/hours 3)))})
        ;; Stuck: approved, and the last ten commits fail their checks.
        (observation "acme" "widget" 398
                     {:title "WID-71 Move the collector onto virtual threads"
                      :author "grace" :assignees #{"grace"}
                      :opened-at (- now (t/days 9)) :updated-at (- now (t/hours 8))
                      :comment-count 7
                      :commits (commits 12 :failure (- now (t/hours 8)))
                      :reviews [(approved "ada" (- now (t/days 1)) "old")]})
        ;; Aging and conflicting.
        (observation "acme" "widget" 351
                     {:title "WID-40 Rework the settings surface"
                      :author "linus" :assignees #{"linus" "ada"}
                      :opened-at (- now (t/days 22)) :updated-at (- now (t/days 2))
                      :comment-count 19
                      :commits (commits 4 :success (- now (t/days 2)))
                      :reviews [(changes "ada" (- now (t/days 3)) "stale")]})
        ;; Merging: not a draft, mergeable, auto-merge armed, every review approving.
        (observation "acme" "widget" 421
                     {:title "WID-92 Bound the collection window in UTC"
                      :author "margaret" :assignees #{"margaret"}
                      :mergeable true :auto-merge-enabled true
                      :opened-at (- now (t/days 1)) :updated-at (- now (t/minutes 40))
                      :comment-count 2
                      :commits (commits 2 :success (- now (t/minutes 40)))
                      :reviews [(approved "ada" (- now (t/minutes 55)) "head")]})
        ;; A draft: counted everywhere, listed only when the operator asks.
        (observation "acme" "widget" 425
                     {:title "WID-95 Sketch the Turso persistence seam"
                      :author "ada" :is-draft true
                      :opened-at (- now (t/hours 20)) :updated-at (- now (t/hours 6))
                      :commits (commits 1 :pending (- now (t/hours 6)))})]
       "acme/gear"
       [;; Difficult: plenty of commits, and approved.
        (observation "acme" "gear" 77
                     {:title "GER-12 Replace the fetch queue"
                      :author "grace" :assignees #{"grace" "ada"}
                      :opened-at (- now (t/days 11)) :updated-at (- now (t/hours 30))
                      :comment-count 9
                      :commits (commits 14 :success (- now (t/hours 30)))
                      :reviews [(approved "linus" (- now (t/hours 31)) "head")]})]}

      :mentions
      [(observation "acme" "gear" 81
                    {:title "GER-15 Name the orphans in the day view"
                     :author "margaret" :assignees #{"ada"}
                     :opened-at (- now (t/days 4)) :updated-at (- now (t/hours 12))
                     :comment-count 3
                     :commits (commits 5 :pending (- now (t/hours 12)))})]

      :review-requests
      [(observation "acme" "widget" 418
                    {:title "WID-90 Say which sources failed to refresh"
                     :author "linus" :requested-reviewers #{"ada"}
                     :assignees #{"linus"}
                     :opened-at (- now (t/days 3)) :updated-at (- now (t/hours 5))
                     :comment-count 1
                     :commits (commits 2 :success (- now (t/hours 5)))})]

      :recently-merged
      [(observation "acme" "widget" 407
                    {:title "WID-83 Keep the cursor across a quota wait"
                     :author "ada" :state :merged
                     :merged-at (- now (t/days 1))
                     :opened-at (- now (t/days 5)) :updated-at (- now (t/days 1))
                     :comment-count 6
                     :commits (commits 4 :success (- now (t/days 1)))
                     :reviews [(approved "grace" (- now (t/days 1)) "head")]})]}

     :activities
     ;; The last working day, not today: the timesheet shows five of those, and
     ;; a weekend would otherwise leave the demo with a day nothing can scan.
     (let [day (last (t/working-days-ending-at now 5))
           at (fn [h] (+ day (t/hours h)))]
       {:github-commits
        [{:source :github-commits :happened-at (at 9)
          :reference "c0ffee01" :title "WID-88 cache the rendered snapshot"
          :issue-keys #{"WID-88"}}
         {:source :github-commits :happened-at (at 11)
          :reference "c0ffee02" :title "WID-88 drop the second render"
          :issue-keys #{"WID-88"}}]
        :github-comments
        [{:source :github-comments :happened-at (at 14)
          :reference "https://github.com/acme/gear/pull/81#issuecomment-1"
          :title "GER-15 On naming the orphans" :issue-keys #{"GER-15"}}]
        :github-pull-requests
        [{:source :github-pull-requests :happened-at (at 10)
          :reference "https://github.com/acme/widget/pull/412"
          :title "WID-88 Cache the snapshot between renders" :issue-keys #{"WID-88"}}]
        :github-reviews
        [{:source :github-reviews :happened-at (at 16)
          :reference "https://github.com/acme/widget/pull/418"
          :title "Reviewed: say which sources failed to refresh" :issue-keys #{}}]})}))

(defn jira-script [now]
  (let [day (last (t/working-days-ending-at now 5))]
    {:login "ada"
     :issues {"WID-88" {:summary "Cache the snapshot between renders"
                        :status "In Progress" :url "https://jira.example/browse/WID-88"
                        :is-closed false}
              "WID-90" {:summary "Say which sources failed to refresh"
                        :status "In Review" :url "https://jira.example/browse/WID-90"
                        :is-closed false}
              "GER-15" {:summary "Name the orphans in the day view"
                        :status "To Do" :url "https://jira.example/browse/GER-15"
                        :is-closed false}
              "WID-83" {:summary "Keep the cursor across a quota wait"
                        :status "Done" :url "https://jira.example/browse/WID-83"
                        :is-closed true}}
     ;; Time already logged in Jira, read back onto the tasks the day carries.
     ;; The worklog on WID-83 is somebody else's, so it is not read back.
     :time-logged
     [{:id 76812 :key "WID-88" :at (+ day (t/hours 11)) :logged-ms (t/hours 1)}
      {:id 76813 :key "WID-90" :at (+ day (t/hours 16)) :logged-ms (t/minutes 30)}
      {:id 76814 :key "WID-83" :at (+ day (t/hours 17)) :logged-ms (t/hours 2)
       :author "bob"}]
     :activities
     {:jira-comments
      [{:source :jira-comments :happened-at (+ day (t/hours 15))
        :reference "https://jira.example/browse/WID-90?focusedCommentId=1"
        :title "Comment on WID-90 — Say which sources failed to refresh"
        :issue-keys #{"WID-90"}}]
      :jira-issues-created []
      :jira-issues-closed
      [{:source :jira-issues-closed :happened-at (+ day (t/hours 17))
        :reference "https://jira.example/browse/WID-83"
        :title "WID-83 Keep the cursor across a quota wait"
        :issue-keys #{"WID-83"}}]}}))
