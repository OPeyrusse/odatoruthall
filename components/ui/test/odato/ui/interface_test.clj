(ns odato.ui.interface-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odato.collector.interface :as collector]
            [odato.config.interface :as config]
            [odato.fixtures :as fix]
            [odato.testing :as testing*]
            [odato.time.interface :as t]
            [odato.ui.interface :as ui]
            [odato.worklog.interface :as worklog]))

(def now (t/parse-instant "2026-09-15T10:30:00Z"))

(defn- installation []
  (testing*/configured!
   {:github {:login "ada"
             :pull-requests
             {:repository-pull-requests
              {"acme/widget"
               [(fix/observation {:number 1 :author "ada" :title "Ship it"
                                  :updated-at now :opened-at (- now (t/days 20))
                                  :comment-count 20})
                (fix/observation {:number 2 :author "ada" :title "A sketch"
                                  :is-draft true :updated-at now})]}
              :mentions [] :review-requests [] :recently-merged []}}
    :jira {:login "ada"
           :issues {"ABC-1" {:summary "Ship it" :status "In Progress"
                             :url "https://jira/ABC-1" :is-closed false}}}}))

(deftest every-page-is-a-document
  (let [sys (installation)]
    (doseq [[what body] {"gitdesk" (ui/gitdesk-page sys)
                         "worklog" (ui/worklog-page sys)
                         "settings" (ui/settings-page sys)}]
      (testing what
        (is (str/starts-with? body "<!doctype html>"))
        (is (str/includes? body "/css/app.css"))
        (is (str/includes? body "/js/datastar.js"))
        (is (not (str/includes? body "<>"))
            "hiccup fragments would render as a literal tag")
        (is (not (str/includes? body "clojure.lang"))
            "nothing leaks a printed object")))))

(deftest a-fragment-carries-the-id-it-is-patched-onto
  (let [sys (installation)]
    (is (str/includes? (ui/gitdesk-fragment sys) "id=\"gitdesk\""))
    (is (str/includes? (ui/worklog-fragment sys) "id=\"worklog\""))
    (testing "and is one line, so it is one SSE data field"
      (is (not (str/includes? (ui/gitdesk-fragment sys) "\n"))))))

(deftest the-badges-say-what-is-reachable
  (let [sys (installation)
        body (ui/gitdesk-page sys)]
    (is (str/includes? body "ada"))
    (testing "and say when nothing was offered"
      (let [fresh (testing*/system)]
        (is (str/includes? (ui/gitdesk-page fresh) "no github token"))
        (is (str/includes? (ui/gitdesk-page fresh) "no jira token"))))))

(deftest drafts-are-counted-and-hidden
  (let [sys (installation)]
    (collector/collect! sys :manual)
    (let [body (ui/gitdesk-fragment sys)]
      (is (str/includes? body "Ship it"))
      (is (not (str/includes? body "A sketch")) "the draft is not listed")
      (is (str/includes? body "Drafts are counted above and hidden")))
    (testing "until the operator asks for them"
      (config/save! sys (assoc (config/current sys) :show-drafts true))
      (is (str/includes? (ui/gitdesk-fragment sys) "A sketch")))))

(deftest the-settings-form-reads-back-what-is-in-force
  (let [sys (installation)
        body (ui/settings-page sys)]
    (is (str/includes? body "acme/widget"))
    (is (str/includes? body "Collection settings"))
    (is (str/includes? body "take effect at the next collection"))
    (is (str/includes? body "apply to the snapshot in hand"))))

(deftest a-form-is-read-back-as-a-proposal
  (let [proposal (ui/settings-proposal {"repositories" "acme/widget\nacme/gear"
                                        "members" "ada grace"
                                        "cadence-minutes" "45"
                                        "window-opens" "6" "window-closes" "20"
                                        "active-days" ["monday" "friday"]
                                        "aging-days" "10" "comment-threshold" "12"
                                        "commit-threshold" "8" "failing-commits" "6"
                                        "merged-days" "2"
                                        "show-drafts" "true"})]
    (is (= #{{:owner "acme" :name "widget"} {:owner "acme" :name "gear"}}
           (:repositories proposal)))
    (is (= #{"ada" "grace"} (:members proposal)))
    (is (= (t/minutes 45) (:refresh-cadence-ms proposal)))
    (is (= #{:monday :friday} (get-in proposal [:window :active-days])))
    (is (= (t/days 10) (get-in proposal [:thresholds :aging-ms])))
    (is (true? (:show-drafts proposal)))
    (is (nil? (config/validate proposal)))))

(deftest the-timesheet-says-what-it-shows
  (let [sys (installation)
        body (ui/worklog-page sys)]
    (is (str/includes? body "Last five working days"))
    (is (str/includes? body "2026-09-09"))
    (is (str/includes? body "Nothing on this day yet."))
    (testing "and a day reads back as it was filled"
      (let [day-starts-at (t/parse-day "2026-09-15")]
        (worklog/window sys)
        (worklog/name-task! sys day-starts-at "ABC-1")
        (let [entry (first (:entries (worklog/day sys day-starts-at)))]
          (worklog/enter-time! sys (:id entry) "1h 30m"))
        (let [body (ui/day-fragment sys (worklog/day sys day-starts-at))]
          (is (str/includes? body "ABC-1"))
          (is (str/includes? body "1h 30m"))
          (is (str/includes? body "Loose ends")))))))
