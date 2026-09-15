(ns odato.server.main
  "One process, both applications."
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [odato.server.handler :as handler]
            [odato.server.system :as system]
            [org.httpkit.server :as hk]))

(defonce ^:private running (atom nil))

(defn start!
  ([] (start! (system/settings)))
  ([settings]
   (let [sys (system/start! (system/create settings))
         stop-server (hk/run-server (handler/handler sys)
                                    {:port (:port settings)
                                     :thread 16
                                     :server-header "odatoruthall"
                                     :legacy-return-value? false})]
     (log/info (str "odatoruthall on http://localhost:" (:port settings)
                    (when (:demo settings) "  (demo data: no credential is used)")))
     (reset! running {:sys sys :server stop-server}))))

(defn stop! []
  (when-let [{:keys [sys server]} @running]
    (hk/server-stop! server {:timeout 2000})
    (system/stop! sys)
    (reset! running nil)))

(defn -main [& _]
  (let [settings (system/settings)]
    (start! settings)
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop!))
    @(promise)))
