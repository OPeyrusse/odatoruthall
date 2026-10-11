(ns odato.ui.interface
  "Every page and every fragment the server renders.

  The interface is server-rendered HTML made dynamic with Datastar: the server
  already owns the snapshot and the worklog, so it renders them and pushes the
  changes. Each dashboard has one fragment — `#gitdesk`, `#gitdesk-my-work`,
  `#worklog`, `#worklog-day` — which a collection cycle or a day scan patches in place as
  its sources land."
  (:require [hiccup2.core :as h]
            [odato.time.interface :as t]
            [odato.ui.gitdesk :as gitdesk]
            [odato.ui.layout :as layout]
            [odato.ui.settings :as settings]
            [odato.ui.worklog :as worklog]))

(defn render
  "Hiccup to a string."
  [hiccup]
  (str (h/html hiccup)))

(defn document
  "A whole page, doctype and all."
  [hiccup]
  (str "<!doctype html>" (render hiccup)))

;; --------------------------------------------------------------------- pages

(defn gitdesk-page [sys]
  (document (layout/page sys {:title "gitdesk" :section :gitdesk}
                         [:div {:data-on-load "@get('/sse/gitdesk')"}
                          (gitdesk/body sys)])))

(defn my-work-group
  "The group of the operator's own business a page slug names — `authored`,
  `to-review`, `assigned`, `addressed` — or nil."
  [slug]
  (gitdesk/my-work-group slug))

(defn my-work-page [sys group]
  (document (layout/page sys {:title (:label group) :section :gitdesk}
                         [:div {:data-on-load (str "@get('/sse/gitdesk/my/" (:slug group) "')")}
                          (gitdesk/my-work-page sys group)])))

(defn pull-request-page [sys pull-request]
  (document (layout/page sys {:title "pull request" :section :gitdesk}
                         (gitdesk/detail sys pull-request))))

(defn worklog-page [sys]
  (document (layout/page sys {:title "worklog" :section :worklog}
                         [:div {:data-on-load "@get('/sse/worklog')"}
                          (worklog/body sys)])))

(defn day-page [sys day]
  (document (layout/page sys {:title "worklog" :section :worklog}
                         [:div {:data-on-load
                                (str "@get('/sse/worklog/"
                                     (t/format-day (:starts-at day))
                                     "')")}
                          (worklog/day-page sys day)])))

(defn settings-page
  ([sys] (settings-page sys nil))
  ([sys outcome]
   (document (layout/page sys {:title "settings" :section :settings}
                          (settings/body sys {:outcome outcome})))))

(def ^:private not-found-class
  (str "rounded-xl border border-slate-200 bg-white px-4 py-10 text-center "
       "dark:border-slate-800 dark:bg-slate-900"))

(defn not-found-page [sys message]
  (document (layout/page sys {:title "not found" :section nil}
                         [:div {:class not-found-class} message])))

;; ----------------------------------------------------------------- fragments

(defn gitdesk-fragment [sys] (render (gitdesk/body sys)))
(defn my-work-fragment [sys group] (render (gitdesk/my-work-page sys group)))
(defn worklog-fragment [sys] (render (worklog/body sys)))
(defn day-fragment [sys day] (render (worklog/day-page sys day)))

;; ------------------------------------------------------------------- reading

(defn settings-proposal
  "Form parameters as a proposal the configuration component can judge."
  [params]
  (settings/read-proposal params))
