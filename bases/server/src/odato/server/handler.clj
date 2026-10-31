(ns odato.server.handler
  "The routes the three applications hang off.

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
            [odato.review.interface :as review]
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

(defn- html-or-fragment
  "A command whose answer Datastar morphs in place — one merge event, which is
  what it reads in the answer to a post — or that a plain form post reads as a
  whole page."
  [request fragment page]
  (if (datastar-request? request)
    {:status 200
     :headers {"content-type" "text/event-stream" "cache-control" "no-cache"}
     :body (sse/merge-fragments (fragment))}
    {:status 200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body (page)}))

(defn- verdict [s]
  (case (str s) "relevant" :relevant "false-positive" :false-positive nil))

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

      ;; --------------------------------------------------------- owldesk
      [:get ["owldesk"]]
      (do (review/read-soon! sys) (html (ui/owldesk-page sys)))

      [:get ["sse" "owldesk"]]
      (sse/stream sys request {:relevant? sse/owldesk-event?
                               :fragment-fn ui/owldesk-fragment})

      [:post ["owldesk" "launch"]]
      (let [outcome (review/launch! sys {:url (param "url") :brain-ref (param "brain-ref")})]
        (html-or-fragment request
                          #(ui/launch-outcome-fragment outcome)
                          #(ui/owldesk-page sys outcome)))

      [:post ["owldesk" "refresh"]]
      (do (future (review/read-results! sys)) (done request "/owldesk"))

      [:get ["owldesk" "cleanup"]]
      (do (review/refresh-pull-request-states! sys) (html (ui/cleanup-page sys)))

      [:post ["owldesk" "cleanup"]]
      (html (ui/cleanup-page sys (review/clean-up! sys)))

      ;; --------------------------------------------------------- settings
      [:get ["settings"]] (html (ui/settings-page sys))

      [:post ["settings" "owldesk"]]
      (let [outcome (review/save-settings! sys (ui/review-settings-proposal @params))]
        (when (:ok outcome) (review/check-results-repository! sys))
        (html-or-fragment request
                          #(ui/review-settings-fragment sys outcome)
                          #(ui/settings-page sys nil outcome)))

      [:post ["settings" "owldesk" "check"]]
      (do (review/check-results-repository! sys)
          (html-or-fragment request
                            #(ui/review-settings-fragment sys nil)
                            #(ui/settings-page sys)))

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

        ;; /gitdesk/member/:login
        (and (= :get request-method) (= 3 (count segments))
             (= ["gitdesk" "member"] (subvec segments 0 2)))
        (if-let [member (ui/tracked-member sys (nth segments 2))]
          (html (ui/member-page sys member))
          (not-found sys "No such tracked member."))

        ;; /sse/gitdesk/member/:login
        (and (= :get request-method) (= 4 (count segments))
             (= ["sse" "gitdesk" "member"] (subvec segments 0 3)))
        (if-let [member (ui/tracked-member sys (nth segments 3))]
          (sse/stream sys request {:relevant? sse/gitdesk-event?
                                   :fragment-fn #(ui/member-fragment % member)})
          (not-found sys "No such tracked member."))

        ;; /gitdesk/pull-request/:owner/:name/:number
        (and (= :get request-method)
             (= 5 (count segments))
             (= ["gitdesk" "pull-request"] (subvec segments 0 2)))
        (let [[_ _ owner name number] segments]
          (if-let [pull-request (pull-request-by sys owner name number)]
            (html (ui/pull-request-page sys pull-request))
            (not-found sys "No such pull request in the snapshot.")))

        ;; /owldesk/review/:run-id and /owldesk/review/:run-id/:step
        (and (= :get request-method) (<= 3 (count segments) 4)
             (= ["owldesk" "review"] (subvec segments 0 2)))
        (let [step (get segments 3)]
          (review/read-soon! sys)
          (if-let [r (review/review sys (nth segments 2))]
            (if (or (nil? step) (ui/review-step? r step))
              (html (ui/review-page sys r step))
              (not-found sys "No such step in this review."))
            (not-found sys "No such review. Its branch may not be read yet, or it was removed.")))

        ;; /sse/owldesk/review/:run-id[/:step]
        (and (= :get request-method) (<= 4 (count segments) 5)
             (= ["sse" "owldesk" "review"] (subvec segments 0 3)))
        (let [run-id (nth segments 3)
              agent (get segments 4)]
          (sse/stream sys request
                      {:relevant? sse/owldesk-event?
                       :fragment-fn (fn [sys]
                                      (if-let [r (review/review sys run-id)]
                                        (ui/review-fragment sys r agent)
                                        "<div id=\"owldesk-review\">This review is gone.</div>"))}))

        ;; /owldesk/review/:run-id/judge
        (and (= :post request-method) (= 4 (count segments))
             (= ["owldesk" "review"] (subvec segments 0 2)) (= "judge" (nth segments 3)))
        (let [run-id (nth segments 2)
              fingerprint (str (param "fingerprint"))]
          (if-let [v (verdict (param "verdict"))]
            (review/judge! sys run-id fingerprint v)
            (review/withdraw-judgement! sys run-id fingerprint))
          (done request (str "/owldesk/review/" run-id)))

        ;; /owldesk/review/:run-id/remove
        (and (= :post request-method) (= 4 (count segments))
             (= ["owldesk" "review"] (subvec segments 0 2)) (= "remove" (nth segments 3)))
        (let [outcome (review/remove! sys (nth segments 2))]
          (if (:ok outcome)
            {:status 303 :headers {"location" "/owldesk"} :body ""}
            (not-found sys (str "The review could not be removed: "
                                (str/replace (name (:fault outcome)) "-" " ") "."))))

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
