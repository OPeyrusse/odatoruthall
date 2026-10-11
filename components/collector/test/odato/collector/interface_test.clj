(ns odato.collector.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [odato.access.interface :as access]
            [odato.collector.interface :as collector]
            [odato.config.interface :as config]
            [odato.fixtures :as fix]
            [odato.github.interface :as github]
            [odato.testing :as testing*]
            [odato.time.interface :as t]))

(def now (t/parse-instant "2026-09-15T10:30:00Z"))

(defn- script [& {:as overrides}]
  (merge {:login "ada"
          :pull-requests
          {:repository-pull-requests
           {"acme/widget" [(fix/observation {:number 1 :author "ada" :updated-at now})
                           (fix/observation {:number 2 :author "grace" :updated-at now})
                           (fix/observation {:number 3 :author "stranger"
                                             :updated-at now})]}
           :mentions [(fix/observation {:number 4 :author "grace" :updated-at now})]
           :review-requests []
           :recently-merged []}}
         overrides))

(defn- installation [& {:as github-script}]
  (testing*/configured! {:github (script github-script)}))

(deftest a-collection-fills-the-snapshot
  (let [sys (installation)]
    (is (not (:has-ever-collected (collector/snapshot sys))))
    (collector/collect! sys :manual)
    (let [pulls (collector/pull-requests sys)]
      (is (= #{1 2 4} (set (map :number pulls)))
          "and only what is of interest: nobody tracked wrote #3")
      (is (:has-ever-collected (collector/snapshot sys)))
      (is (testing*/saw? sys :collection/completed)))))

(deftest sources-take-their-turn-one-at-a-time
  (let [sys (installation :page-size 2)]
    (collector/collect! sys :manual)
    (let [calls (->> (github/calls (:github sys))
                     (filter #(= :pull-requests (:op %)))
                     (map :source))]
      (is (= [[:repository-pull-requests "acme/widget"]
              [:repository-pull-requests "acme/widget"]
              :mentions :review-requests :recently-merged]
             calls)
          "the repository paginates to exhaustion before the next source starts"))))

(deftest a-failure-never-destroys-what-was-collected
  (let [sys (installation)]
    (collector/collect! sys :manual)
    (is (= 3 (count (collector/pull-requests sys))))
    (let [good-at (:oldest-success-at (collector/snapshot sys))]
      (testing*/tick! sys (t/hours 1))
      ;; The repository source now refuses; everything else still answers.
      (let [sys (assoc sys :github (github/mock-client
                                    (script :failures
                                            {[:repository-pull-requests "acme/widget"]
                                             :service-error})))]
        (collector/collect! sys :manual)
        (is (= 3 (count (collector/pull-requests sys)))
            "the last known good state is still on screen")
        (let [snapshot (collector/snapshot sys)
              failed (first (:failed-sources snapshot))]
          (is (= 1 (count (:failed-sources snapshot))))
          (is (= :service-error (:last-failure-reason failed)))
          (is (= good-at (:last-success-at failed))
              "with the time the data was obtained unchanged")
          (is (pos? (:data-age failed)) "and how old it is"))))))

(deftest quota-exhaustion-is-a-wait-not-a-failure
  (let [sys (installation
             :page-size 1
             ;; The budget runs out after the first page and is already past
             ;; its reset, so the cycle waits and picks up at its cursor.
             :budget-sequence [{:remaining 5 :resets-at (- now (t/minutes 1))}
                               {:remaining 0 :resets-at (- now (t/minutes 1))}
                               {:remaining 5000 :resets-at (+ now (t/hours 1))}])]
    (collector/collect! sys :manual)
    (is (testing*/saw? sys :collection/waiting-on-quota))
    (is (not (testing*/saw? sys :collection/source-failed))
        "waiting is not failing")
    (is (= #{1 2 4} (set (map :number (collector/pull-requests sys))))
        "and everything was collected once the budget came back")))

(deftest a-refresh-while-one-is-running-does-not-start-a-second
  (let [sys (installation)
        first-cycle (collector/request-collection! sys :manual)]
    (is (:started first-cycle))
    (let [second-cycle (collector/request-collection! sys :manual)]
      (is (false? (:started second-cycle)))
      (is (= :already-collecting (:reason second-cycle))))
    (collector/run-cycle! sys (:cycle first-cycle))
    (is (:started (collector/request-collection! sys :manual))
        "and once it has finished, another may start")))

(deftest a-collection-needs-a-credential-and-something-to-track
  (testing "no credential"
    (let [sys (testing*/system)]
      (config/save! sys {:repositories #{{:owner "acme" :name "widget"}}
                         :members #{"ada"}
                         :refresh-cadence-ms (t/minutes 60)
                         :window {:opens-hour 5 :closes-hour 21
                                  :active-days #{:tuesday}}
                         :thresholds (:thresholds (config/current sys))
                         :show-drafts false})
      (is (= :credential-unavailable (:reason (collector/request-collection! sys :manual))))))
  (testing "nothing tracked"
    (let [sys (testing*/connected!)]
      (is (= :nothing-tracked (:reason (collector/request-collection! sys :manual)))))))

(deftest saving-the-configuration-never-collects
  (let [sys (installation)]
    (collector/collect! sys :manual)
    (is (= 3 (count (collector/pull-requests sys))))
    (config/save! sys (assoc (config/current sys) :repositories #{{:owner "acme"
                                                                  :name "gear"}}))
    (is (= 3 (count (collector/pull-requests sys)))
        "a repository removed from the list keeps its pull requests on screen")
    (testing "until the next collection, which is when it is dropped"
      (collector/collect! sys :manual)
      (is (= #{4} (set (map :number (collector/pull-requests sys))))
          "only the mention survives; the repository source and its data are gone")
      (is (not-any? #(= :repository-pull-requests (:kind %))
                    (filter #(= "repository-pull-requests|acme/widget" (:ident %))
                            (:sources (collector/snapshot sys))))))))

(deftest a-threshold-change-reclassifies-without-collecting
  (let [sys (installation :pull-requests
                          {:repository-pull-requests
                           {"acme/widget" [(fix/observation {:number 1 :author "ada"
                                                             :comment-count 10
                                                             :updated-at now})]}
                           :mentions [] :review-requests [] :recently-merged []})]
    (collector/collect! sys :manual)
    (is (not (:conflicting? (first (collector/pull-requests sys)))))
    (let [calls-before (count (github/calls (:github sys)))]
      (config/save! sys (assoc-in (config/current sys) [:thresholds :comments] 10))
      (is (:conflicting? (first (collector/pull-requests sys))))
      (is (= calls-before (count (github/calls (:github sys))))
          "nothing was fetched"))))

(deftest the-snapshot-dies-with-the-process
  (let [sys (installation)]
    (collector/collect! sys :manual)
    (is (pos? (count (collector/pull-requests sys))))
    (collector/server-started! sys)
    (is (= 3 (count (collector/pull-requests sys)))
        "emptied, then filled again by the collection that starts with the process")
    (is (testing*/saw? sys :installation/started))))

(deftest the-schedule-collects-only-inside-the-window
  (let [sys (installation)]
    (collector/server-started! sys)
    (is (= :waiting (collector/tick! sys)) "nothing is due yet")
    (testing "inside the window"
      (testing*/tick! sys (t/minutes 61))
      (is (= :collecting (collector/tick! sys)))
      (is (= (+ (testing*/now sys) (t/minutes 60)) (collector/schedule sys))))
    (testing "at night the schedule rolls forward to the next opening"
      (testing*/tick! sys (t/hours 12))            ; 2026-09-15 23:31 UTC
      (is (= :outside-window (collector/tick! sys)))
      (is (= (t/parse-instant "2026-09-16T05:00:00Z") (collector/schedule sys))))
    (testing "and a manual refresh is honoured out of the window"
      (is (:started (collector/request-collection! sys :manual))))))

(deftest a-cycle-that-has-started-runs-to-completion
  (let [sys (installation)
        started (collector/request-collection! sys :manual)]
    ;; The window closes underneath the cycle.
    (testing*/tick! sys (t/hours 14))
    (is (not (t/within-collection-window? (testing*/now sys)
                                          (:window (config/current sys)))))
    (collector/run-cycle! sys (:cycle started))
    (is (= 4 (count (:collected-sources (collector/snapshot sys))))
        "every source had its turn")))
