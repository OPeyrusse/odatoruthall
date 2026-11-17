(ns odato.ui.interface
  "Every page and every fragment the server renders.

  The interface is server-rendered HTML made dynamic with Datastar: the server
  already owns the snapshot and the worklog, so it renders them and pushes the
  changes. Each dashboard has one fragment — `#gitdesk`, `#gitdesk-my-work`,
  `#gitdesk-member`, `#worklog`, `#worklog-day`, `#owldesk`, `#owldesk-review` — which a collection cycle or a day scan patches in place as
  its sources land."
  (:require [hiccup2.core :as h]
            [odato.time.interface :as t]
            [odato.ui.gitdesk :as gitdesk]
            [odato.ui.layout :as layout]
            [odato.ui.owldesk :as owldesk]
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

(defn tracked-member
  "The login, when it names a tracked member; nil otherwise."
  [sys login]
  (gitdesk/tracked-member sys login))

(defn member-page [sys member]
  (document (layout/page sys {:title (str "@" member) :section :gitdesk}
                         [:div {:data-on-load (str "@get('/sse/gitdesk/member/" member "')")}
                          (gitdesk/member-page sys member)])))

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
  ([sys outcome] (settings-page sys outcome nil))
  ([sys outcome review-outcome]
   (document (layout/page sys {:title "settings" :section :settings}
                          [:div {:class "flex flex-col gap-4"}
                           (settings/body sys {:outcome outcome})
                           (owldesk/settings-section sys {:outcome review-outcome})]))))

(defn owldesk-page
  ([sys] (owldesk-page sys nil))
  ([sys outcome]
   (document (layout/page sys {:title "owldesk" :section :owldesk}
                          [:div {:data-on-load "@get('/sse/owldesk')" :class "flex flex-col gap-4"}
                           (owldesk/launch-outcome outcome)
                           (owldesk/body sys {:outcome outcome})]))))

(defn review-page
  "One review. `outcomes` holds what asking for another run of its pull
  request answered (`:launch`) and what sending its remarks answered
  (`:remarks`), when there is one to say; both sit outside `#owldesk-review`,
  so the page patching itself leaves them on screen."
  ([sys review step] (review-page sys review step nil))
  ([sys review step {:keys [launch remarks]}]
   (document (layout/page sys {:title (str "review " (:run-id review)) :section :owldesk}
                          [:div {:data-on-load (str "@get('/sse/owldesk/review/" (:run-id review)
                                                    (when step (str "/" step)) "')")
                                 :class "flex flex-col gap-4"}
                           (owldesk/launch-outcome launch)
                           (owldesk/remarks-outcome remarks)
                           (owldesk/review-page sys review {:step step})]))))

(defn remark-form-page
  "The page a remark is written or changed on: no event stream patches it,
  so nothing the operator types is lost."
  [sys review opts]
  (document (layout/page sys {:title (str "remark on " (:run-id review)) :section :owldesk}
                         (owldesk/remark-form-page sys review opts))))

(defn tuning-page [sys tuning step]
  (document (layout/page sys {:title (str "tuning " (:run-id tuning)) :section :owldesk}
                         [:div {:data-on-load (str "@get('/sse/owldesk/tuning/" (:run-id tuning)
                                                   (when step (str "/" step)) "')")}
                          (owldesk/tuning-page sys tuning {:step step})])))

(defn tuning-step?
  "Whether `step` names a step of the tuning's process."
  [tuning step]
  (contains? (owldesk/step-ids tuning) step))

(defn review-step?
  "Whether `step` names a step of the review's process."
  [review step]
  (contains? (owldesk/step-ids review) step))

(defn cleanup-page
  ([sys] (cleanup-page sys nil))
  ([sys outcome]
   (document (layout/page sys {:title "cleanup" :section :settings}
                          (owldesk/cleanup-page sys {:outcome outcome})))))

(def ^:private not-found-class
  (str "rounded-xl border border-slate-200 bg-white px-4 py-10 text-center "
       "dark:border-slate-800 dark:bg-slate-900"))

(defn not-found-page [sys message]
  (document (layout/page sys {:title "not found" :section nil}
                         [:div {:class not-found-class} message])))

;; ----------------------------------------------------------------- fragments

(defn gitdesk-fragment [sys] (render (gitdesk/body sys)))
(defn my-work-fragment [sys group] (render (gitdesk/my-work-page sys group)))
(defn member-fragment [sys member] (render (gitdesk/member-page sys member)))
(defn worklog-fragment [sys] (render (worklog/body sys)))
(defn day-fragment [sys day] (render (worklog/day-page sys day)))
(defn owldesk-fragment [sys] (render (owldesk/body sys {})))
(defn launch-outcome-fragment [outcome] (render (owldesk/launch-outcome outcome)))
(defn review-fragment [sys review step] (render (owldesk/review-page sys review {:step step})))
(defn remarks-outcome-fragment [outcome] (render (owldesk/remarks-outcome outcome)))
(defn tuning-fragment [sys tuning step] (render (owldesk/tuning-page sys tuning {:step step})))
(defn review-settings-fragment [sys outcome]
  (render (owldesk/settings-section sys {:outcome outcome})))

;; ------------------------------------------------------------------- reading

(defn settings-proposal
  "Form parameters as a proposal the configuration component can judge."
  [params]
  (settings/read-proposal params))

(defn review-settings-proposal
  "Form parameters as a proposal the review settings can judge."
  [params]
  (owldesk/read-settings-proposal params))
