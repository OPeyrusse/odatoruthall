(ns odato.reassignment.interface-test
  "The pass against the mock GitHub: which pull requests it acts on, what it
  writes and in which order, and when it runs at all."
  (:require [clojure.test :refer [deftest is testing]]
            [odato.access.interface :as access]
            [odato.config.interface :as config]
            [odato.github.interface :as github]
            [odato.reassignment.interface :as reassignment]
            [odato.testing :as testing*]
            [odato.time.interface :as t])
  (:import (java.util.concurrent CountDownLatch Executors TimeUnit)))

(def widget {:owner "acme" :name "widget"})

(defn- at [s] (t/parse-instant s))

(defn- pull-request [number & {:as facts}]
  (merge {:repository widget
          :number number
          :title (str "Pull request " number)
          :url (str "https://github.com/acme/widget/pull/" number)
          :assignees #{"ada"}
          :author "grace"
          :author-is-user true
          :reviews []
          :assignments [{:assignee "ada" :assigned-at (at "2026-09-14T08:00:00Z")}]}
         facts))

(defn- review [verdict submitted-at]
  {:reviewer "ada" :verdict verdict :submitted-at (at submitted-at)})

(defn- installation
  "Connected, watching acme/widget, switched on, the mock GitHub holding the
  assignment credential too, unless `opts` says otherwise."
  [{:keys [switched assigner] :or {switched true assigner true} :as opts}]
  (let [sys (testing*/configured! opts)
        sys (cond-> sys assigner (assoc :assigner (:github sys)))]
    (config/switch-reassignment! sys switched)
    sys))

(defn- pass [pull-requests & {:as opts}]
  (let [sys (installation (assoc-in opts [:github :assigned-pull-requests] pull-requests))]
    {:sys sys :answer (reassignment/pass! sys)}))

(defn- written [answer]
  (mapv (juxt :number :op :login) (:writes answer)))

(defn- searches [sys]
  (count (filter #(= :assigned-pull-requests (:op %)) (github/calls (:github sys)))))

;; --------------------------------------------------------------- the ruling

(deftest an-approval-takes-the-operator-off
  (let [{:keys [sys answer]} (pass [(pull-request 12 :reviews [(review :approved "2026-09-15T10:00:00Z")])])]
    (is (= [[12 :remove "ada"]] (written answer)) "and nothing else")
    (is (= [] (written (reassignment/pass! sys)))
        "and the next pass no longer finds it assigned to the operator")))

(deftest a-change-request-hands-it-back-to-its-author
  (let [{:keys [answer]} (pass [(pull-request 12 :reviews [(review :changes-requested "2026-09-15T10:00:00Z")])])]
    (is (= [[12 :add "grace"] [12 :remove "ada"]] (written answer))
        "the author first, then the operator")))

(deftest an-author-who-is-not-a-github-user-gets-no-assignment
  (let [{:keys [answer]} (pass [(pull-request 12 :author "dependabot" :author-is-user false
                                              :reviews [(review :changes-requested "2026-09-15T10:00:00Z")])
                                (pull-request 13 :author nil :author-is-user false
                                              :reviews [(review :changes-requested "2026-09-15T10:00:00Z")])])]
    (is (= [[12 :remove "ada"] [13 :remove "ada"]] (written answer)))))

(deftest the-verdict-is-the-latest-review-that-is-not-a-comment
  (let [{:keys [answer]}
        (pass [(pull-request 12 :reviews [(review :approved "2026-09-15T09:00:00Z")
                                          (review :commented "2026-09-15T10:00:00Z")])
               (pull-request 13 :reviews [(review :changes-requested "2026-09-15T09:00:00Z")
                                          (review :approved "2026-09-15T10:00:00Z")])
               (pull-request 14 :reviews [(review :approved "2026-09-15T09:00:00Z")
                                          (review :dismissed "2026-09-15T10:00:00Z")])
               (pull-request 15 :reviews [(review :commented "2026-09-15T10:00:00Z")])
               (pull-request 16 :reviews [{:reviewer "grace" :verdict :approved
                                           :submitted-at (at "2026-09-15T10:00:00Z")}])])]
    (is (= [[12 :remove "ada"] [13 :remove "ada"]] (written answer))
        "a comment is skipped over; a dismissed verdict, a comment alone or someone else's review acts on nothing")))

(deftest an-assignment-newer-than-the-verdict-is-respected
  (let [{:keys [answer]}
        (pass [(pull-request 12 :reviews [(review :approved "2026-09-15T09:00:00Z")]
                             :assignments [{:assignee "ada" :assigned-at (at "2026-09-14T08:00:00Z")}
                                           {:assignee "ada" :assigned-at (at "2026-09-15T09:30:00Z")}])
               (pull-request 13 :reviews [(review :approved "2026-09-15T09:00:00Z")]
                             :assignments [{:assignee "ada" :assigned-at (at "2026-09-14T08:00:00Z")}
                                           {:assignee "grace" :assigned-at (at "2026-09-15T09:30:00Z")}])
               (pull-request 14 :reviews [(review :approved "2026-09-15T09:00:00Z")]
                             :assignments [])])]
    (is (= [[13 :remove "ada"] [14 :remove "ada"]] (written answer))
        "the operator re-assigned after the review stays; someone else's assignment is not theirs")))

(deftest every-page-is-searched
  (let [{:keys [sys answer]}
        (pass (for [n [12 13 14]] (pull-request n :reviews [(review :approved "2026-09-15T10:00:00Z")]))
              :github {:page-size 2})]
    (is (= [12 13 14] (map first (written answer))))
    (is (= 2 (searches sys)))))

;; ----------------------------------------------------------- partial failure

(deftest an-author-write-failing-leaves-the-operator-assigned
  (let [changes [(pull-request 12 :reviews [(review :changes-requested "2026-09-15T10:00:00Z")])]
        {:keys [sys answer]} (pass changes :github {:assignee-failures {:add-assignee :service-error}})]
    (is (= [{:number 12 :op :add :login "grace" :failure :service-error}]
           (mapv #(select-keys % [:number :op :login :failure]) (:writes answer)))
        "the operator is not removed")
    (is (= [[12 :add "grace"]] (written (reassignment/pass! sys)))
        "so the next pass tries again")))

(deftest a-removal-failing-is-retried-whole-by-the-next-pass
  (let [changes [(pull-request 12 :reviews [(review :changes-requested "2026-09-15T10:00:00Z")])]
        {:keys [sys answer]} (pass changes :github {:assignee-failures {:remove-assignee :service-error}})]
    (is (= [nil :service-error] (map :failure (:writes answer))))
    (is (= [[12 :add "grace"] [12 :remove "ada"]] (written (reassignment/pass! sys))))))

;; ------------------------------------------------------------ what is listed

(defn- listed [sys]
  (mapv (fn [{:keys [number writes]}] [number (mapv (juxt :op :login :failure) writes)])
        (reassignment/latest sys)))

(deftest the-latest-reassignments-are-listed-newest-first
  (let [{:keys [sys]} (pass [(pull-request 12 :reviews [(review :approved "2026-09-15T10:00:00Z")])
                             (pull-request 13 :reviews [(review :changes-requested "2026-09-15T10:00:00Z")])])]
    (is (= [[13 [[:add "grace" nil] [:remove "ada" nil]]]
            [12 [[:remove "ada" nil]]]]
           (listed sys))
        "one line per pull request, its writes in the order they were made")
    (is (= {:repository widget :number 12 :title "Pull request 12"
            :url "https://github.com/acme/widget/pull/12" :at (testing*/now sys)}
           (dissoc (last (reassignment/latest sys)) :writes)))
    (testing "a pass that writes nothing leaves the list as it was"
      (testing*/tick! sys (t/minutes 1))
      (is (= [] (written (reassignment/pass! sys))))
      (is (= [13 12] (map first (listed sys)))))))

(deftest a-failed-write-is-listed-and-its-retry-replaces-it
  (let [changes [(pull-request 12 :reviews [(review :changes-requested "2026-09-15T10:00:00Z")])
                 (pull-request 13 :reviews [(review :approved "2026-09-15T10:00:00Z")])]
        {:keys [sys]} (pass changes :github {:assignee-failures {:add-assignee :service-error}})]
    (is (= [[13 [[:remove "ada" nil]]]
            [12 [[:add "grace" :service-error]]]]
           (listed sys)))
    (testing*/tick! sys (t/minutes 1))
    (reassignment/pass! sys)
    (is (= [[12 [[:add "grace" :service-error]]]
            [13 [[:remove "ada" nil]]]]
           (listed sys))
        "a write failing every minute keeps one line, and leaves the others on the list")
    (is (= (testing*/now sys) (:at (first (reassignment/latest sys)))))))

(deftest only-the-latest-pull-requests-are-listed
  (let [many (for [n (range 1 26)] (pull-request n :reviews [(review :approved "2026-09-15T10:00:00Z")]))
        {:keys [sys]} (pass many)]
    (is (= reassignment/reassignments-listed (count (reassignment/latest sys))))
    (is (= (range 25 5 -1) (map first (listed sys))) "the newest")))

;; ---------------------------------------------------------- when it runs

(def approved [(pull-request 12 :reviews [(review :approved "2026-09-15T10:00:00Z")])])

(deftest nothing-is-read-or-written-unless-switched-on-with-the-credential
  (doseq [[why opts reason] [["switched off" {:switched false} :switched-off]
                             ["without the assignment credential" {:assigner false} :no-assignment-credential]]]
    (testing why
      (let [{:keys [sys answer]} (apply pass approved (mapcat identity opts))]
        (is (= {:skipped reason} answer))
        (is (zero? (searches sys)))))))

(deftest nothing-is-searched-without-github-or-anything-tracked
  (testing "without a usable GitHub credential"
    (let [sys (installation {})]
      (access/refused! sys :github :credential-rejected)
      (is (= {:skipped :github-unavailable} (reassignment/pass! sys)))
      (is (zero? (searches sys)))))
  (testing "with no repository tracked"
    (let [sys (assoc (testing*/connected! {}) :assigner :a-client)]
      (config/switch-reassignment! sys true)
      (is (= {:skipped :nothing-tracked} (reassignment/pass! sys)))
      (is (zero? (searches sys))))))

(deftest a-spent-budget-or-a-failed-search-skips-the-pass
  (testing "a budget already spent asks nothing"
    (let [sys (installation {:github {:assigned-pull-requests approved}})]
      (access/budget-observed! sys 0 (+ (testing*/now sys) (t/minutes 10)))
      (is (= {:skipped :budget-exhausted} (reassignment/pass! sys)))
      (is (zero? (searches sys)))))
  (testing "a failed search writes nothing"
    (let [{:keys [answer]} (pass approved :github {:failures {:assigned-pull-requests :service-error}})]
      (is (= {:skipped :service-error} answer))))
  (testing "the budget the search reports is the installation's"
    (let [{:keys [sys]} (pass approved :github {:budget {:remaining 0 :resets-at 1}})]
      (is (= 1 (searches sys)))
      (is (not (access/budget-available? sys))))))

(deftest a-pass-is-due-every-minute-inside-the-collection-window
  (let [sys (installation {:github {:assigned-pull-requests approved}})]
    (is (= :started (reassignment/tick! sys)))
    (is (= 1 (searches sys)))
    (testing "and not again within the minute"
      (t/advance! (:clock sys) (dec (t/minutes 1)))
      (is (= :waiting (reassignment/tick! sys))))
    (testing "but once it has come round"
      (t/advance! (:clock sys) 1)
      (is (= :started (reassignment/tick! sys)))
      (is (= 2 (searches sys))))))

(deftest outside-the-window-the-pass-waits-for-its-opening
  (let [sys (installation {:at "2026-09-15T21:30:00Z" :github {:assigned-pull-requests approved}})]
    (is (= :outside-window (reassignment/tick! sys)))
    (is (zero? (searches sys)))
    (t/set-now! (:clock sys) (- (at "2026-09-16T05:00:00Z") 1))
    (is (= :waiting (reassignment/tick! sys)) "nothing until the window opens")
    (t/set-now! (:clock sys) (at "2026-09-16T05:00:00Z"))
    (is (= :started (reassignment/tick! sys)))
    (is (= 1 (searches sys)))))

(deftest a-pass-still-running-is-not-doubled
  (let [executor (Executors/newSingleThreadExecutor)
        held (CountDownLatch. 1)
        sys (assoc (installation {:github {:assigned-pull-requests approved}}) :executor executor)]
    (try
      (.submit executor ^Runnable #(.await held))
      (is (= :started (reassignment/tick! sys)) "the pass queues behind the held task")
      (testing*/tick! sys (t/minutes 1))
      (is (= :passing (reassignment/tick! sys)) "a minute later, it is still running")
      (.countDown held)
      (.submit executor ^Runnable (fn []))
      (.shutdown executor)
      (.awaitTermination executor 5 TimeUnit/SECONDS)
      (is (= 1 (searches sys)))
      (is (= :started (reassignment/tick! (dissoc sys :executor)))
          "the next starts once it has ended")
      (is (= 2 (searches sys)))
      (finally (.shutdownNow executor)))))
