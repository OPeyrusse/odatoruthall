(ns odato.server.sse
  "Server-sent events, in the shape Datastar reads them.

  There is no JSON API: the server already owns the snapshot and the worklog,
  so it renders them and pushes the rendered fragment. A collection cycle
  patches the gitdesk counts as its sources land; a day scan patches the
  timesheet as the tasks and the lines appear under their tickets."
  (:require [clojure.string :as str]
            [odato.events.interface :as events]
            [org.httpkit.server :as hk]))

(def ^:private headers
  {"content-type" "text/event-stream"
   "cache-control" "no-cache"
   "connection" "keep-alive"
   "x-accel-buffering" "no"})

(defn merge-fragments
  "One `datastar-merge-fragments` event. The fragment is morphed onto the
  element carrying its id, so what the operator is typing into survives the
  patch."
  [html]
  (str "event: datastar-merge-fragments\n"
       "data: mergeMode morph\n"
       ;; A fragment with newlines in it would be several `data:` lines that
       ;; Datastar rejoins; keeping it on one is simpler and is what hiccup
       ;; produces anyway.
       "data: fragments " (str/replace (str html) #"\r?\n" " ") "\n\n"))

(defn stream
  "Opens an event stream that re-renders `fragment-fn` whenever the bus sees an
  event `relevant?` accepts. The first patch goes out immediately, so a page
  that reconnects is up to date without waiting for something to happen."
  [sys request {:keys [relevant? fragment-fn]}]
  (hk/as-channel
   request
   {:on-open
    (fn [ch]
      (hk/send! ch {:status 200 :headers headers} false)
      (let [key (keyword "sse" (str (random-uuid)))
            patch! (fn []
                     (try (hk/send! ch (merge-fragments (fragment-fn sys)) false)
                          (catch Throwable _ (hk/close ch))))]
        (patch!)
        (events/subscribe! (:bus sys) key
                           (fn [event] (when (relevant? event) (patch!))))
        (hk/on-close ch (fn [_] (events/unsubscribe! (:bus sys) key)))))}))

(defn gitdesk-event?
  "What changes a gitdesk surface: a collection, the credentials behind it, the
  budget it spends, and a configuration change that reclassifies the snapshot."
  [{:keys [type]}]
  (contains? #{"collection" "config" "connection" "budget" "installation" "access"}
             (namespace type)))

(defn worklog-event?
  "What changes the timesheet: a scan, a task, entered time, and what Jira made
  of it."
  [{:keys [type]}]
  (contains? #{"worklog" "scan" "work-item" "connection" "access"}
             (namespace type)))
