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
            [odato.time.interface :as t])
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
   :github {:token (env "GITHUB_TOKEN")
            :base-url (env "GITHUB_API_URL" "https://api.github.com")}
   :jira {:base-url (env "JIRA_BASE_URL")
          :email (env "JIRA_EMAIL")
          :token (env "JIRA_TOKEN")}})

(defn- clients [settings now-ms]
  (if (:demo settings)
    {:github (github/mock-client (demo/github-script now-ms))
     :jira (jira/mock-client (demo/jira-script now-ms))}
    {:github (github/client (:github settings))
     :jira (jira/client (:jira settings))}))

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
    (config/save! sys (merge (config/current sys) demo/configuration))))

(defn create
  "Builds the installation without starting anything that ticks."
  ([] (create (settings)))
  ([settings]
   (let [clock (t/system-clock)
         now (t/now-ms clock)
         sys (merge {:store (db/open (when (:db-file settings)
                                       {:file (:db-file settings)}))
                     :clock clock
                     :bus (events/bus)
                     :settings settings
                     :executor (Executors/newVirtualThreadPerTaskExecutor)
                     :scheduler (Executors/newSingleThreadScheduledExecutor)}
                    (clients settings now))]
     (access/ensure-defaults! sys)
     (config/ensure-defaults! sys)
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
    sys))

(defn stop! [sys]
  (some-> ^java.util.concurrent.ExecutorService (:scheduler sys) .shutdownNow)
  (some-> ^java.util.concurrent.ExecutorService (:executor sys) .shutdown)
  (db/flush! (:store sys))
  (db/close! (:store sys))
  sys)
