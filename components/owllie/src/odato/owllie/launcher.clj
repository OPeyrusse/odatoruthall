(ns odato.owllie.launcher
  "The ways a cloud session running Owllie is started.

  Both real launchers start a session in the cloud; nothing runs on this
  machine but the request. They answer `{:session-url ... :session-id ...}` or
  `{:failure \"what went wrong\"}`, and never throw."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [odato.http.interface :as http])
  (:import (java.util.concurrent TimeUnit)))

(defprotocol Launcher
  (-fire [this request-text] "Starts a session for one request."))

;; ------------------------------------------------------- the scheduled task

(def routine-beta
  "The beta the API trigger of a scheduled task ships under."
  "experimental-cc-routine-2026-04-01")

(defrecord ScheduledTask [api fire-url token]
  Launcher
  (-fire [_ request-text]
    (let [response (http/request api {:method :post :path fire-url
                                      :body {:text request-text}})
          body (:body response)]
      (cond
        (:error response) {:failure "the scheduled task could not be reached"}
        (http/ok? response) {:session-id (:claude_code_session_id body)
                             :session-url (:claude_code_session_url body)}
        :else {:failure (str "the scheduled task answered " (:status response)
                             (when-let [m (or (get-in body [:error :message]) (:message body))]
                               (str ": " m)))}))))

(defn scheduled-task
  "Fires a scheduled task through its API trigger: `:fire-url` and `:token` are
  the ones the task's API trigger shows."
  [{:keys [fire-url token timeout-ms]}]
  (->ScheduledTask (http/client {:timeout-ms (or timeout-ms 30000)
                                 :headers {"authorization" (str "Bearer " token)
                                           "anthropic-beta" routine-beta
                                           "anthropic-version" "2023-06-01"
                                           "user-agent" "odatoruthall"}})
                   fire-url token))

;; ---------------------------------------------------------------- the CLI

(def ^:private session-url-re #"https://claude\.ai/code/(session_[A-Za-z0-9]+)")

(defrecord ClaudeCli [command dir timeout-ms]
  Launcher
  (-fire [_ request-text]
    (try
      (let [prompt (str "Run the owllie-run skill with this request:\n" request-text)
            process (-> (ProcessBuilder. ^java.util.List (vec (concat command ["--cloud" prompt])))
                        (.directory (io/file dir))
                        (.redirectErrorStream true)
                        (.start))]
        (.close (.getOutputStream process))
        ;; The CLI prints the session as soon as it exists, then may keep
        ;; following its setup: once the link is there the session lives in
        ;; the cloud, and the local process is no longer needed.
        (let [reader (io/reader (.getInputStream process))
              found (future
                      (loop [seen []]
                        (if-let [line (.readLine ^java.io.BufferedReader reader)]
                          (if-let [[url id] (re-find session-url-re line)]
                            {:session-url url :session-id id}
                            (recur (conj (vec (take-last 5 seen)) line)))
                          {:failure (str "the Claude CLI printed no session"
                                         (when (seq seen) (str ": " (str/join " / " seen))))})))
              answer (deref found timeout-ms {:failure "the Claude CLI did not answer in time"})]
          (when (.isAlive process)
            (.destroy process)
            (.waitFor process 5 TimeUnit/SECONDS))
          answer))
      (catch Exception e
        (log/warn e "the Claude CLI could not be started")
        {:failure (str "the Claude CLI could not be started: " (.getMessage e))}))))

(defn claude-cli
  "Starts a cloud session with `claude --cloud`, from `:dir`, a clone of
  Owllie whose GitHub remote the session clones."
  [{:keys [dir command timeout-ms]}]
  (->ClaudeCli (or command ["claude"]) dir (or timeout-ms 180000)))

;; --------------------------------------------------------------- the mock

(defrecord MockLauncher [on-fire calls]
  Launcher
  (-fire [_ request-text]
    (swap! calls conj request-text)
    (on-fire request-text)))

(defn mock
  "A launcher that hands every request to `on-fire`, which answers like a real
  one."
  [on-fire]
  (->MockLauncher on-fire (atom [])))

(defn mock-calls [launcher]
  (when (instance? MockLauncher launcher) @(:calls launcher)))
