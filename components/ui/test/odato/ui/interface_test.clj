(ns odato.ui.interface-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odato.collector.interface :as collector]
            [odato.config.interface :as config]
            [odato.discovery.interface :as discovery]
            [odato.fixtures :as fix]
            [odato.testing :as testing*]
            [odato.time.interface :as t]
            [odato.ui.interface :as ui]
            [odato.work-item.interface]
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
                             :url "https://jira/ABC-1" :is-closed false}
                    "ABC-2" {:summary "Fix it" :status "To Do"
                             :url "https://jira/ABC-2" :is-closed false}}}}))

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
      (let [day-starts-at (:starts-at (last (worklog/window sys)))]
        (worklog/window sys)
        (worklog/name-task! sys day-starts-at "ABC-1")
        (let [entry (first (:entries (worklog/day sys day-starts-at)))]
          (worklog/enter-time! sys (:id entry) "1h 30m"))
        (let [body (ui/day-fragment sys (worklog/day sys day-starts-at))]
          (is (str/includes? body "ABC-1"))
          (is (str/includes? body "1h 30m"))
          (is (str/includes? body "Loose ends")))))))

(deftest the-day-says-what-jira-holds
  (let [sys (installation)
        day-starts-at (:starts-at (last (worklog/window sys)))
        entry-id (atom nil)]
    (odato.work-item.interface/resolve! sys "ABC-1")
    (worklog/add-task! sys day-starts-at "ABC-1")
    (reset! entry-id (:id (first (:entries (worklog/day sys day-starts-at)))))
    (testing "a task whose time came from Jira reads as held by Jira"
      (worklog/jira-time-on-task! sys day-starts-at "ABC-1"
                                  {:logged-ms (t/hours 1) :worklogs 1 :worklog "76812"})
      (let [body (ui/day-fragment sys (worklog/day sys day-starts-at))]
        (is (str/includes? body "1h"))
        (is (str/includes? body "in Jira"))
        (is (not (str/includes? body "Jira holds"))
            "and says nothing more, because there is nothing to reconcile")))
    (testing "a task holding time Jira does not have says both"
      (worklog/enter-time! sys @entry-id "3h")
      (worklog/jira-time-on-task! sys day-starts-at "ABC-1"
                                  {:logged-ms (t/hours 1) :worklogs 1 :worklog "76812"})
      (let [body (ui/day-fragment sys (worklog/day sys day-starts-at))]
        (is (str/includes? body "Jira holds 1h on this task today"))
        (is (str/includes? body "Submitting the day sends 3h instead"))))
    (testing "and a task Jira holds under several worklogs cannot be edited here"
      (worklog/jira-time-on-task! sys day-starts-at "ABC-1"
                                  {:logged-ms (t/minutes 90) :worklogs 2 :worklog nil})
      (let [body (ui/day-fragment sys (worklog/day sys day-starts-at))]
        (is (str/includes? body "under 2 worklogs"))
        (is (str/includes? body "one field cannot edit them"))
        (is (str/includes? body "disabled=\"disabled\""))))
    (testing "and one whose time is Jira's says how many worklogs make it up"
      (odato.work-item.interface/resolve! sys "ABC-2")
      (worklog/add-task! sys day-starts-at "ABC-2")
      (worklog/jira-time-on-task! sys day-starts-at "ABC-2"
                                  {:logged-ms (t/hours 2) :worklogs 2 :worklog nil})
      (is (str/includes? (ui/day-fragment sys (worklog/day sys day-starts-at))
                         "in Jira · 2 worklogs")))))

(def ^:private greyed
  "The end of the class a list of tickets carries while its day is scanned."
  "text-slate-700 dark:text-slate-400\"")

(deftest a-day-being-scanned-says-so
  (let [sys (installation)
        [first-day second-day] (map :starts-at (take-last 2 (worklog/window sys)))
        _ (worklog/name-task! sys second-day "ABC-1")
        first-scan (:scan (discovery/request-scan! sys first-day))
        second-scan (:scan (discovery/request-scan! sys second-day))]
    (testing "the timesheet lists every scan in flight"
      (let [body (ui/worklog-fragment sys)]
        (is (str/includes? body "Scanning"))
        (is (str/includes? body (t/day-label first-day)))
        (is (str/includes? body (str "href=\"/worklog/" (t/format-day second-day) "\"")))
        (is (str/includes? body greyed) "and greys the days being scanned")))
    (testing "the day being scanned says so, glows, and cannot be asked for again"
      (let [body (ui/day-fragment sys (worklog/day sys second-day))]
        (is (str/includes? body "scan-glow"))
        (is (str/includes? body "scanning 0/7"))
        (is (str/includes? body "This day is being scanned"))
        (is (str/includes? body greyed))))
    (testing "and stops saying so once the scan ends"
      (discovery/run-scan! sys second-scan)
      (let [body (ui/day-fragment sys (worklog/day sys second-day))]
        (is (not (str/includes? body "scan-glow")))
        (is (not (str/includes? body "This day is being scanned")))
        (is (not (str/includes? body greyed))))
      (is (str/includes? (ui/worklog-fragment sys) "Scanning")
          "while the other day's scan is still listed")
      (discovery/run-scan! sys first-scan)
      (is (not (str/includes? (ui/worklog-fragment sys) "Scanning"))))))
