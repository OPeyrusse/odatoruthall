(ns odato.server.system
  "One installation, wired and running.

  Everything that has to be decided once lives here: where the credentials come
  from, whether the durable half of the store has a file behind it, and the two
  executors. Nothing below this namespace reads an environment variable —
  `GITHUB_TOKEN` and `JIRA_TOKEN` are external entities whose presence and
  validity change what the operator sees, and that is all the domain knows
  about them."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [odato.access.interface :as access]
            [odato.collector.interface :as collector]
            [odato.config.interface :as config]
            [odato.db.interface :as db]
            [odato.demo.interface :as demo]
            [odato.events.interface :as events]
            [odato.github.interface :as github]
            [odato.jira.interface :as jira]
            [odato.owllie.interface :as owllie]
            [odato.reassignment.interface :as reassignment]
            [odato.review.interface :as review]
            [odato.time.interface :as t]
            [odato.turso.interface :as turso])
  (:import (java.util.concurrent Executors TimeUnit)))

(defn- env
  ([k] (env k nil))
  ([k default]
   (let [v (System/getenv k)]
     (if (str/blank? v) default v))))

(defn- truthy? [s]
  (contains? #{"1" "true" "yes" "on"} (str/lower-case (str s))))

(defn- demo? [] (truthy? (env "ODATO_DEMO" "false")))

(defn settings
  "What the process was told, read once."
  []
  {:port (parse-long (env "PORT" "7777"))
   :demo (demo?)
   :db-file (env "ODATO_DB_FILE")
   :turso {:url (env "TURSO_URL")
           :token (env "TURSO_TOKEN")
           :table-name (env "TURSO_TABLE")}
   :github {:token (env "GITHUB_TOKEN")
            :assign-token (env "GITHUB_ASSIGN_TOKEN")
            :base-url (env "GITHUB_API_URL" "https://api.github.com")}
   :jira {:base-url (env "JIRA_BASE_URL")
          :email (env "JIRA_EMAIL")
          :token (env "JIRA_TOKEN")}
   :owllie {:fire-url (env "OWLLIE_FIRE_URL")
            :fire-token (env "OWLLIE_FIRE_TOKEN")
            :cli-dir (env "OWLLIE_CLI_DIR")
            :tune-fire-url (env "OWLLIE_TUNE_FIRE_URL")
            :tune-fire-token (env "OWLLIE_TUNE_FIRE_TOKEN")
            :claude-command (env "OWLLIE_CLAUDE_COMMAND" "claude")
            :results-token (env "OWLLIE_RESULTS_TOKEN")}})

(defn- store
  "Where the durable half lives, in the order the operator asked for.

  A Turso that is configured and cannot be reached stops the process rather
  than quietly falling back to a file: the worklog is the operator's own work,
  and writing it somewhere they did not ask for is worse than not starting."
  [{:keys [turso db-file]}]
  (cond
    (turso/configured? turso)
    (do (log/info "worklog: Turso," (:table-name (turso/options turso)) "table")
        (db/open {:storage (turso/storage turso)}))

    db-file
    (do (log/info "worklog:" db-file)
        (db/open {:file db-file}))

    :else
    (do (log/warn "worklog: in memory — it will go with the process."
                  "Set TURSO_URL and TURSO_TOKEN, or ODATO_DB_FILE.")
        (db/open {}))))

(defn- review-clients
  "The launchers that can start an Owllie session, and the client that may
  delete results branches: each is there only when its credential is."
  [settings]
  (let [{:keys [fire-url fire-token tune-fire-url tune-fire-token cli-dir claude-command
                results-token]} (:owllie settings)
        cli (when cli-dir
              (owllie/claude-cli {:dir cli-dir :command (str/split claude-command #"\s+")}))]
    {:launchers {:scheduled-task (when (and fire-url fire-token)
                                   (owllie/scheduled-task {:fire-url fire-url :token fire-token}))
                 :claude-cli cli}
     ;; A tuning is its own scheduled task; the CLI asks for the skill the
     ;; request names, so one clone serves both.
     :tune-launchers {:scheduled-task (when (and tune-fire-url tune-fire-token)
                                        (owllie/scheduled-task {:fire-url tune-fire-url
                                                                :token tune-fire-token}))
                      :claude-cli cli}
     :results-writer (when results-token
                       (github/client {:token results-token
                                       :base-url (:base-url (:github settings))}))}))

(defn- clients [settings now-ms]
  (if (:demo settings)
    (let [gh (github/mock-client (merge (demo/github-script now-ms)
                                        (demo/review-github-script now-ms)))
          simulated (owllie/mock-launcher
                     (demo/simulated-review
                      {:write! (fn [branch files]
                                 (github/mock-write-branch! gh demo/results-repository branch files))
                       :now-fn #(System/currentTimeMillis)
                       :step-ms 4000}))
          tuning (owllie/mock-launcher
                  (demo/simulated-tuning
                   {:write! (fn [branch files]
                              (github/mock-write-branch! gh demo/results-repository branch files))
                    :now-fn #(System/currentTimeMillis)
                    :step-ms 4000}))]
      {:github gh
       :jira (jira/mock-client (demo/jira-script now-ms))
       :launchers {:scheduled-task simulated :claude-cli simulated}
       :tune-launchers {:scheduled-task tuning :claude-cli tuning}
       :results-writer gh
       :assigner gh})
    (let [{:keys [token assign-token base-url]} (:github settings)]
      (merge {:github (github/client {:token token :base-url base-url})
              :jira (jira/client (:jira settings))
              :assigner (when assign-token
                          (github/client {:token assign-token :base-url base-url}))}
             (review-clients settings)))))

(defn- present-credentials!
  "A credential that was never offered stays absent, and the badge says so
  rather than the dashboard failing."
  [sys settings]
  (when (or (:demo settings) (:token (:github settings)))
    (access/check-connection! sys :github #(github/whoami (:github sys))))
  (when (or (:demo settings)
            (and (:token (:jira settings)) (:base-url (:jira settings))))
    (access/check-connection! sys :jira #(jira/whoami (:jira sys)))))

(defn- seed-demo-configuration! [sys]
  (when-not (config/collectable? sys)
    (config/save! sys (merge (config/current sys) demo/configuration)))
  (when-not (:results-repository (review/settings sys))
    (review/save-settings! sys (merge (review/settings sys)
                                      {:results-repository (str (:owner demo/results-repository) "/"
                                                                (:name demo/results-repository))}))))

(defn create
  "Builds the installation without starting anything that ticks."
  ([] (create (settings)))
  ([settings]
   (let [clock (t/system-clock)
         now (t/now-ms clock)
         sys (merge {:store (store settings)
                     :clock clock
                     :bus (events/bus)
                     :settings settings
                     :executor (Executors/newVirtualThreadPerTaskExecutor)
                     :scheduler (Executors/newSingleThreadScheduledExecutor)}
                    (clients settings now))]
     (access/ensure-defaults! sys)
     (config/ensure-defaults! sys)
     (review/ensure-defaults! sys)
     sys)))

(defn start!
  "Starts the installation: the credentials are exercised, the snapshot is
  emptied and filled again by the collection that starts with the process, and
  the clock begins to tick."
  [sys]
  (let [settings (:settings sys)]
    (present-credentials! sys settings)
    (when (:demo settings) (seed-demo-configuration! sys))
    (collector/server-started! sys)
    ;; The installation collects as soon as it can: at startup if the
    ;; credential is already good, and otherwise the moment it becomes good.
    (events/subscribe! (:bus sys) ::collect-when-access-arrives
                       (fn [{:keys [type service]}]
                         (when (and (= :connection/established type)
                                    (= :github service))
                           (collector/start! sys :startup))))
    ;; One tick a minute is enough for a cadence measured in minutes, and it is
    ;; also what lets an exhausted budget come back.
    (.scheduleAtFixedRate ^java.util.concurrent.ScheduledExecutorService (:scheduler sys)
                          ^Runnable (fn []
                                      (try (collector/tick! sys)
                                           (catch Throwable e
                                             (log/warn e "the scheduler tick failed"))))
                          60 60 TimeUnit/SECONDS)
    ;; The results branches of the reviews are read on their own pace —
    ;; every minute while a review is in flight, every ten otherwise — and
    ;; the results repository is checked once its credential is good.
    (events/subscribe! (:bus sys) ::check-results-when-access-arrives
                       (fn [{:keys [type service]}]
                         (when (and (= :connection/established type) (= :github service))
                           (.submit ^java.util.concurrent.ExecutorService (:executor sys)
                                    ^Runnable (fn []
                                                (try (review/check-results-repository! sys)
                                                     (catch Throwable e
                                                       (log/warn e "the results repository could not be checked"))))))))
    (when (access/usable? sys :github)
      (.submit ^java.util.concurrent.ExecutorService (:executor sys)
               ^Runnable (fn [] (try (review/check-results-repository! sys)
                                     (catch Throwable e
                                       (log/warn e "the results repository could not be checked"))))))
    (.scheduleAtFixedRate ^java.util.concurrent.ScheduledExecutorService (:scheduler sys)
                          ^Runnable (fn []
                                      (try (review/tick! sys)
                                           (catch Throwable e
                                             (log/warn e "the review tick failed"))))
                          5 15 TimeUnit/SECONDS)
    ;; The reassignment pass falls due every minute, on its own schedule;
    ;; ticking at the minute itself would let it slip to every other one.
    (.scheduleAtFixedRate ^java.util.concurrent.ScheduledExecutorService (:scheduler sys)
                          ^Runnable (fn []
                                      (try (reassignment/tick! sys)
                                           (catch Throwable e
                                             (log/warn e "the reassignment tick failed"))))
                          10 15 TimeUnit/SECONDS)
    ;; A storage keeps every node it has ever been handed until somebody says
    ;; otherwise: the tree is rewritten as it grows, and the old nodes are
    ;; still there. Once a day is plenty for one operator's worklog, and a file
    ;; store has none to collect.
    (when (= :storage (db/backend (:store sys)))
      (.scheduleAtFixedRate ^java.util.concurrent.ScheduledExecutorService (:scheduler sys)
                            ^Runnable (fn []
                                        (try (db/collect-garbage! (:store sys))
                                             (catch Throwable e
                                               (log/warn e "the storage could not be swept"))))
                            24 24 TimeUnit/HOURS))
    sys))

(defn stop! [sys]
  (some-> ^java.util.concurrent.ExecutorService (:scheduler sys) .shutdownNow)
  (some-> ^java.util.concurrent.ExecutorService (:executor sys) .shutdown)
  (db/flush! (:store sys))
  (db/close! (:store sys))
  sys)
