(ns odato.access.core
  (:require [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.time.interface :as t]))

(def connection-statuses #{:absent :unverified :valid :rejected})
(def access-failures #{:credential-rejected :service-unreachable :service-error})

(defn- conn [sys] (db/volatile* (:store sys)))
(defn- now [sys] (t/now-ms (:clock sys)))
(defn- emit! [sys event] (events/publish! (:bus sys) event))

;; ------------------------------------------------------------------ reading

(defn connection [sys service]
  (db/entity-map (db/db (conn sys)) :connection/service service))

(defn connections [sys]
  (db/all (db/db (conn sys)) :connection/service))

(defn usable? [sys service]
  (= :valid (:connection/status (connection sys service))))

(defn operator-login [sys]
  (let [c (connection sys :github)]
    (when (= :valid (:connection/status c)) (:connection/account-login c))))

(defn budget [sys]
  (db/entity-map (db/db (conn sys)) :budget/id :github))

(defn budget-available? [sys]
  (not= :exhausted (:budget/status (budget sys))))

;; ------------------------------------------------------------------ writing

(defn ensure-defaults! [sys]
  (let [d (db/db (conn sys))]
    (db/transact!
     (conn sys)
     (cond-> []
       (nil? (db/lookup d :connection/service :github))
       (conj {:connection/service :github :connection/status :absent})
       (nil? (db/lookup d :connection/service :jira))
       (conj {:connection/service :jira :connection/status :absent})
       (nil? (db/lookup d :budget/id :github))
       (conj {:budget/id :github :budget/status :available :budget/remaining 0}))))
  sys)

(defn present-credential!
  "CredentialPresented: absent becomes unverified, and a check is requested."
  [sys service]
  (let [c (connection sys service)]
    (when (= :absent (:connection/status c))
      (db/transact! (conn sys) [{:connection/service service
                                 :connection/status :unverified}])
      (emit! sys {:type :connection/check-requested :service service}))
    (connection sys service)))

(defn verified!
  "ConnectionVerified: a credential that was unverified or rejected is valid,
  and names its owner."
  [sys service login]
  (let [c (connection sys service)]
    (if (contains? #{:unverified :rejected} (:connection/status c))
      (do (db/put! (conn sys) :connection/service service
                   {:connection/status :valid
                    :connection/account-login login
                    :connection/last-checked-at (now sys)
                    :connection/last-refusal nil})
          (emit! sys {:type :connection/established :service service :login login}))
      ;; Already valid: the check cost a round trip and told us nothing new.
      (db/transact! (conn sys) [{:connection/service service
                                 :connection/last-checked-at (now sys)}]))
    (connection sys service)))

(defn refused!
  "ConnectionRefused: a credential that was unverified or valid is rejected."
  [sys service failure]
  (let [c (connection sys service)]
    (when (contains? #{:unverified :valid} (:connection/status c))
      (db/put! (conn sys) :connection/service service
               {:connection/status :rejected
                :connection/account-login nil
                :connection/last-checked-at (now sys)
                :connection/last-refusal failure})
      (emit! sys {:type :access/lost :service service :failure failure}))
    (connection sys service)))

(defn check-connection! [sys service client-whoami]
  (present-credential! sys service)
  (let [answer (try (client-whoami)
                    (catch Exception _ {:failure :service-unreachable}))]
    (if-let [failure (:failure answer)]
      (refused! sys service failure)
      (verified! sys service (:login answer)))))

;; ------------------------------------------------------------------- budget

(defn budget-observed!
  "GithubBudgetRunsOut / GithubBudgetHasRoom. Running out is a wait, not a
  failure: what changes is only that the installation stops asking."
  [sys remaining resets-at]
  (let [before (:budget/status (budget sys))
        after (if (<= (long (or remaining 0)) 0) :exhausted :available)]
    (db/put! (conn sys) :budget/id :github
             {:budget/status after
              :budget/remaining (long (or remaining 0))
              :budget/resets-at resets-at
              :budget/observed-at (now sys)})
    (when (and (= after :exhausted) (not= before :exhausted))
      (emit! sys {:type :budget/exhausted :resets-at resets-at}))
    (budget sys)))

(defn replenish-due!
  "GithubBudgetReplenishes. The remaining count stays at its last observed
  value until GitHub's next answer corrects it."
  [sys]
  (let [b (budget sys)]
    (when (and (= :exhausted (:budget/status b))
               (:budget/resets-at b)
               (<= (long (:budget/resets-at b)) (now sys)))
      (db/transact! (conn sys) [{:budget/id :github :budget/status :available}])
      (emit! sys {:type :budget/replenished})
      true)))

(defn await-budget! [sys]
  (loop [waited 0]
    (replenish-due! sys)
    (cond
      (budget-available? sys) true
      ;; A budget with no reset time in sight is still a wait, checked often
      ;; enough that the operator's refresh is not held up for long.
      (> waited (t/hours 2)) false
      :else (let [poll (long (get sys :poll-ms 250))]
              (if (try (Thread/sleep poll) true (catch InterruptedException _ false))
                (recur (+ waited poll))
                false)))))
