(ns odato.server.handler
  "The routes both applications hang off.

  Every command is a plain HTTP POST that a form can make on its own. Datastar
  makes the same POST without a page load and the server answers 204, because
  the open event stream is what patches the page. Without JavaScript the same
  POST gets a redirect back to where it came from. The dashboard works either
  way, which is what makes the server the one that owns the state."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [odato.access.interface :as access]
            [odato.collector.interface :as collector]
            [odato.config.interface :as config]
            [odato.discovery.interface :as discovery]
            [odato.pull-request.interface :as prs]
            [odato.server.params :as params]
            [odato.server.sse :as sse]
            [odato.server.static :as static]
            [odato.time.interface :as t]
            [odato.ui.interface :as ui]
            [odato.worklog.interface :as worklog]))

;; ----------------------------------------------------------------- answers

(defn- html [body] {:status 200
                    :headers {"content-type" "text/html; charset=utf-8"}
                    :body body})

(defn- datastar-request?
  "Datastar names itself on every request it makes, which is how the server
  knows whether an open event stream will do the patching."
  [request]
  (some? (get-in request [:headers "datastar-request"])))

(defn- done
  "204 when an event stream is listening, a redirect back when one is not."
  [request back]
  (if (datastar-request? request)
    {:status 204 :headers {} :body ""}
    {:status 303 :headers {"location" back} :body ""}))

(defn- not-found [sys message]
  {:status 404
   :headers {"content-type" "text/html; charset=utf-8"}
   :body (ui/not-found-page sys message)})

;; ------------------------------------------------------------------ gitdesk

(defn- pull-request-by [sys owner name number]
  (let [ident (prs/ident {:owner owner :name name} (parse-long (str number)))]
    (first (filter #(= ident (:ident %)) (collector/pull-requests sys)))))

;; ------------------------------------------------------------------ worklog

(defn- day-or-nil [sys day-string]
  (when-let [starts-at (t/parse-day day-string)]
    (when (worklog/within-window? sys starts-at)
      ;; Reading the window is what makes the five days exist.
      (worklog/window sys)
      (worklog/day sys starts-at))))

;; ------------------------------------------------------------------- routing

(defn- path-segments [uri]
  (vec (remove str/blank? (str/split (str uri) #"/"))))

(defn handle
  [sys {:keys [request-method uri] :as request}]
  (let [segments (path-segments uri)
        params (delay (params/of request))
        param (fn [k] (params/one @params k))]
    (case [request-method segments]

      ;; --------------------------------------------------------- the shell
      [:get []] {:status 302 :headers {"location" "/gitdesk"} :body ""}

      ;; --------------------------------------------------------- gitdesk
      [:get ["gitdesk"]] (html (ui/gitdesk-page sys))

      [:post ["gitdesk" "refresh"]]
      (do (collector/start! sys :manual) (done request "/gitdesk"))

      [:get ["sse" "gitdesk"]]
      (sse/stream sys request {:relevant? sse/gitdesk-event?
                               :fragment-fn ui/gitdesk-fragment})

      ;; --------------------------------------------------------- worklog
      [:get ["worklog"]] (html (ui/worklog-page sys))

      [:get ["sse" "worklog"]]
      (sse/stream sys request {:relevant? sse/worklog-event?
                               :fragment-fn ui/worklog-fragment})

      ;; --------------------------------------------------------- settings
      [:get ["settings"]] (html (ui/settings-page sys))

      [:post ["settings"]]
      (let [outcome (config/save! sys (ui/settings-proposal @params))]
        (if (datastar-request? request)
          {:status 200
           :headers {"content-type" "text/html; charset=utf-8"}
           :body (ui/settings-page sys outcome)}
          {:status 200
           :headers {"content-type" "text/html; charset=utf-8"}
           :body (ui/settings-page sys outcome)}))

      ;; Anything with a variable in it.
      (cond
        ;; /gitdesk/my/:group
        (and (= :get request-method) (= 3 (count segments))
             (= ["gitdesk" "my"] (subvec segments 0 2)))
        (if-let [group (ui/my-work-group (nth segments 2))]
          (html (ui/my-work-page sys group))
          (not-found sys "No such group of your work."))

        ;; /sse/gitdesk/my/:group
        (and (= :get request-method) (= 4 (count segments))
             (= ["sse" "gitdesk" "my"] (subvec segments 0 3)))
        (if-let [group (ui/my-work-group (nth segments 3))]
          (sse/stream sys request {:relevant? sse/gitdesk-event?
                                   :fragment-fn #(ui/my-work-fragment % group)})
          (not-found sys "No such group of your work."))

        ;; /gitdesk/pull-request/:owner/:name/:number
        (and (= :get request-method)
             (= 5 (count segments))
             (= ["gitdesk" "pull-request"] (subvec segments 0 2)))
        (let [[_ _ owner name number] segments]
          (if-let [pull-request (pull-request-by sys owner name number)]
            (html (ui/pull-request-page sys pull-request))
            (not-found sys "No such pull request in the snapshot.")))

        ;; /sse/worklog/:day
        (and (= :get request-method)
             (= 3 (count segments))
             (= ["sse" "worklog"] (subvec segments 0 2)))
        (if-let [starts-at (t/parse-day (nth segments 2))]
          (sse/stream sys request
                      {:relevant? sse/worklog-event?
                       :fragment-fn (fn [sys]
                                      (ui/day-fragment sys (worklog/day sys starts-at)))})
          (not-found sys "No such day."))

        ;; /worklog/:day
        (and (= :get request-method) (= 2 (count segments))
             (= "worklog" (first segments)))
        (if-let [day (day-or-nil sys (second segments))]
          (html (ui/day-page sys day))
          (not-found sys "That day is not one the timesheet shows."))

        ;; /worklog/:day/scan
        (and (= :post request-method) (= 3 (count segments))
             (= "worklog" (first segments)) (= "scan" (nth segments 2)))
        (let [starts-at (t/parse-day (second segments))]
          (discovery/start! sys starts-at)
          (done request (str "/worklog/" (second segments))))

        ;; /worklog/:day/submit
        (and (= :post request-method) (= 3 (count segments))
             (= "worklog" (first segments)) (= "submit" (nth segments 2)))
        (let [starts-at (t/parse-day (second segments))]
          (worklog/submit-day! sys starts-at)
          (done request (str "/worklog/" (second segments))))

        ;; /worklog/:day/tasks
        (and (= :post request-method) (= 3 (count segments))
             (= "worklog" (first segments)) (= "tasks" (nth segments 2)))
        (let [starts-at (t/parse-day (second segments))]
          (worklog/name-task! sys starts-at (str/trim (str (param "key"))))
          (done request (str "/worklog/" (second segments))))

        ;; /worklog/entries/:id/time
        (and (= :post request-method) (= 4 (count segments))
             (= ["worklog" "entries"] (subvec segments 0 2))
             (= "time" (nth segments 3)))
        (let [entry (worklog/entry sys (nth segments 2))]
          (worklog/enter-time! sys (nth segments 2) (str (param "text")))
          (done request (str "/worklog/" (t/format-day (:day-starts-at entry)))))

        ;; /worklog/entries/:id/remove
        (and (= :post request-method) (= 4 (count segments))
             (= ["worklog" "entries"] (subvec segments 0 2))
             (= "remove" (nth segments 3)))
        (let [entry (worklog/entry sys (nth segments 2))
              back (if entry
                     (str "/worklog/" (t/format-day (:day-starts-at entry)))
                     "/worklog")]
          (worklog/remove-entry! sys (nth segments 2))
          (done request back))

        ;; /worklog/lines/:id/attach
        (and (= :post request-method) (= 4 (count segments))
             (= ["worklog" "lines"] (subvec segments 0 2))
             (= "attach" (nth segments 3)))
        (let [activity-id (java.net.URLDecoder/decode (nth segments 2) "UTF-8")
              line (discovery/activity sys activity-id)
              back (if line
                     (str "/worklog/" (t/format-day (:day-starts-at line)))
                     "/worklog")]
          (discovery/attach! sys activity-id (str/trim (str (param "key"))))
          (done request back))

        ;; Anything under /css, /js or the favicon.
        (= :get request-method)
        (or (static/resource uri) (not-found sys "Nothing here."))

        :else (not-found sys "Nothing here.")))))

(defn handler
  "The whole server, as one function of a request."
  [sys]
  (fn [request]
    (try (handle sys request)
         (catch Throwable e
           (log/error e "the request failed" (:uri request))
           {:status 500
            :headers {"content-type" "text/html; charset=utf-8"}
            :body (ui/not-found-page sys "Something went wrong reading that.")}))))
