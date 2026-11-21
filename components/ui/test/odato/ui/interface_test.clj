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
                                  :is-draft true :updated-at now
                                  :opened-at (- now (t/days 20))})]}
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

(defn- shown-by
  "The signal the row titled `title` waits on to be shown, or nil when it is
  listed outright."
  [body title]
  (let [row (re-find (re-pattern (str "<li[^>]*>(?:(?!</li>).)*" title)) body)]
    (second (re-find #"data-show=\"\$(\w+)\"" (str row)))))

(deftest drafts-are-counted-and-hidden-behind-a-toggle
  (let [sys (installation)]
    (collector/collect! sys :manual)
    (let [body (ui/gitdesk-fragment sys)]
      (is (not (str/includes? body "Drafts are counted above")) "no setting to go and change"))
    (let [body (ui/member-fragment sys "ada")]
      (is (nil? (shown-by body "Ship it")))
      (is (= "draftsAuthored" (shown-by body "A sketch")) "the draft waits on the toggle")
      (is (str/includes? body "{draftsAuthored: false}") "which is off")
      (is (str/includes? body "$draftsAuthored = !$draftsAuthored"))
      (is (str/includes? body "Show 1 draft"))
      (is (not (str/includes? body "draftsAssigned = !")) "no toggle without drafts"))))

(deftest a-group-of-my-work-counts-its-drafts-and-hides-them
  (let [sys (installation)
        authored (ui/my-work-group "authored")]
    (collector/collect! sys :manual)
    (let [body (ui/my-work-fragment sys authored)]
      (is (str/includes? body "id=\"gitdesk-my-work\""))
      (is (str/includes? body "2 pull requests · 1 draft"))
      (is (nil? (shown-by body "Ship it")))
      (is (= "draftsListed" (shown-by body "A sketch")) "the draft is hidden")
      (is (str/includes? body "display: none") "even before the toggle runs")
      (is (str/includes? body "Show 1 draft")))
    (is (nil? (ui/my-work-group "starred")))))

(deftest the-aging-count-leads-to-the-page-listing-what-it-counts
  (let [sys (installation)]
    (collector/collect! sys :manual)
    (let [dashboard (ui/gitdesk-fragment sys)]
      (is (str/includes? dashboard "href=\"/gitdesk/aging\""))
      (is (not (str/includes? dashboard "Monitoring")))
      (is (not (str/includes? dashboard "Merge train"))))
    (let [body (ui/aging-fragment sys)]
      (is (str/includes? body "id=\"gitdesk-aging\""))
      (is (str/includes? body "2 pull requests · 1 draft"))
      (is (nil? (shown-by body "Ship it")))
      (is (= "draftsAging" (shown-by body "A sketch")) "the draft is hidden"))))

(deftest nothing-outside-the-tracked-repositories-is-shown
  ;; Whatever source brought it, a pull request of an untracked repository
  ;; shows nowhere: not in the operator's work, not in the aging, not in the
  ;; team.
  (let [elsewhere (fn [m] (fix/observation (merge {:owner "other" :name "thing"
                                                   :updated-at now
                                                   :opened-at (- now (t/days 20))}
                                                  m)))
        sys (testing*/configured!
             {:github {:login "ada"
                       ;; Asked as configured, GitHub answers in its own case.
                       :pull-requests
                       {:repository-pull-requests
                        {"ACME/Widget"
                         [(fix/observation {:number 1 :author "grace" :title "Tracked and old"
                                            :updated-at now :opened-at (- now (t/days 20))})]}
                        :mentions [(elsewhere {:number 7 :author "grace" :title "Mentioned elsewhere"
                                               :assignees #{"grace"}})]
                        :review-requests [(elsewhere {:number 8 :author "ada" :title "Mine elsewhere"
                                                      :requested-reviewers #{"ada"}})]
                        :recently-merged []}}
              :configuration {:repositories #{{:owner "ACME" :name "Widget"}}}})]
    (collector/collect! sys :manual)
    (testing "the aging lists the tracked repositories only, whatever their case"
      (let [body (ui/aging-fragment sys)]
        (is (str/includes? body "Tracked and old"))
        (is (not (str/includes? body "Mentioned elsewhere")))
        (is (not (str/includes? body "Mine elsewhere")))))
    (testing "the team counts the tracked repositories only"
      (is (= "(1 / 0)" (second (re-find #"@grace</span>.*?(\(\d+ / \d+\))"
                                         (ui/gitdesk-fragment sys))))
          "authored / assigned")
      (let [body (ui/member-fragment sys "grace")]
        (is (str/includes? body "Tracked and old"))
        (is (not (str/includes? body "Mentioned elsewhere")))
        (is (str/includes? body "Nothing is assigned to @grace."))))
    (testing "nor does the operator's own work"
      (doseq [slug ["authored" "to-review"]]
        (is (not (str/includes? (ui/my-work-fragment sys (ui/my-work-group slug))
                                "Mine elsewhere"))
            slug)))))

(deftest a-listed-pull-request-links-to-its-page-and-to-github
  (let [sys (installation)]
    (collector/collect! sys :manual)
    (let [body (ui/my-work-fragment sys (ui/my-work-group "authored"))]
      (is (str/includes? body "href=\"/gitdesk/pull-request/acme/widget/1\""))
      (is (re-find #"href=\"https://github.com/acme/widget/pull/1\"[^>]*>acme/widget #1</a>"
                   body)))))

(deftest the-team-chart-counts-what-each-member-has-in-flight
  (let [sys (installation)]
    (collector/collect! sys :manual)
    (let [body (ui/gitdesk-fragment sys)]
      (is (str/includes? body "Team work"))
      (is (str/includes? body "@ada"))
      (is (str/includes? body "(2 / 0)") "the draft counts as authored")
      (is (str/includes? body "1 in review"))
      (is (str/includes? body "1 draft\""))
      (is (str/includes? body "@grace"))
      (is (str/includes? body "(0 / 0)")))
    (testing "and a member's page lists what they wrote, drafts hidden"
      (let [body (ui/member-fragment sys "ada")]
        (is (str/includes? body "id=\"gitdesk-member\""))
        (is (str/includes? body "Ship it"))
          (is (= "draftsAuthored" (shown-by body "A sketch")))
        (is (str/includes? body "Nothing is assigned to @ada."))))
    (is (nil? (ui/tracked-member sys "mallory")))))

(deftest a-back-link-returns-to-the-page-it-was-reached-from
  (let [sys (installation)]
    (collector/collect! sys :manual)
    (doseq [[what body fallback]
            [["a group of my work" (ui/my-work-fragment sys (ui/my-work-group "authored"))
              "/gitdesk"]
             ["a pull request" (ui/pull-request-page sys (first (collector/pull-requests sys)))
              "/gitdesk"]]]
      (testing what
        (is (str/includes? body "history.back()") "goes back in the history")
        (is (str/includes? body (str "href=\"" fallback "\"")) "and leads somewhere without one")
        (is (str/includes? body "← Back"))))))

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
                                        "merged-days" "2"})]
    (is (= #{{:owner "acme" :name "widget"} {:owner "acme" :name "gear"}}
           (:repositories proposal)))
    (is (= #{"ada" "grace"} (:members proposal)))
    (is (= (t/minutes 45) (:refresh-cadence-ms proposal)))
    (is (= #{:monday :friday} (get-in proposal [:window :active-days])))
    (is (= (t/days 10) (get-in proposal [:thresholds :aging-ms])))
    (is (nil? (config/validate proposal)))))

(deftest the-timesheet-says-what-it-shows
  (let [sys (installation)
        body (ui/worklog-page sys)]
    (is (str/includes? body "Last five working days"))
    (is (str/includes? body "2026-09-09"))
    (is (str/includes? body "Nothing on these days yet."))
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

(defn- occurrences [body text]
  (count (re-seq (re-pattern (java.util.regex.Pattern/quote text)) body)))

(deftest the-timesheet-reads-one-row-per-ticket
  (let [sys (installation)
        [monday tuesday wednesday] (map :starts-at (worklog/window sys))]
    (odato.work-item.interface/resolve! sys "ABC-2")
    (worklog/name-task! sys monday "ABC-1")
    (worklog/name-task! sys wednesday "ABC-1")
    (worklog/task-found-on-day! sys tuesday "ABC-2")
    (let [entry (first (:entries (worklog/day sys wednesday)))]
      (worklog/enter-time! sys (:id entry) "2h"))
    (let [body (ui/worklog-fragment sys)]
      (testing "a ticket worked on two days is one row, its title said once"
        (is (= 1 (occurrences body "Ship it")))
        (is (str/includes? body "2h")))
      (testing "the ticket the operator named reads before the one a scan found"
        (is (< (str/index-of body "Ship it") (str/index-of body "Fix it"))))
      (testing "and each day keeps its commands under its heading"
        (is (= 5 (occurrences body ">Scan</button>")))
        (is (= 5 (occurrences body "Submit to Jira")))))))

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

(def ^:private greyed-in-card
  "A ticket's time on the timesheet, while its day is scanned."
  "font-medium tabular-nums hover:underline text-slate-600 dark:text-slate-400")

(def ^:private greyed-on-day
  "A ticket's key on its day, while the day is scanned."
  "font-semibold text-slate-600 dark:text-slate-400")

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
        (is (str/includes? body greyed-in-card) "and greys the days being scanned")))
    (testing "the day being scanned says so, glows, and cannot be asked for again"
      (let [body (ui/day-fragment sys (worklog/day sys second-day))]
        (is (str/includes? body "busy-glow"))
        (is (str/includes? body "scanning 0/7"))
        (is (= 2 (count (re-seq #"disabled=\"disabled\" title=\"This day is being scanned\"" body)))
            "neither scanned again nor submitted")
        (is (str/includes? body greyed-on-day))
        (is (str/includes? body "text-slate-600 shadow-sm") "and so is the time")))
    (testing "and stops saying so once the scan ends"
      (discovery/run-scan! sys second-scan)
      (let [body (ui/day-fragment sys (worklog/day sys second-day))]
        (is (not (str/includes? body "busy-glow")))
        (is (not (str/includes? body "This day is being scanned")))
        (is (not (str/includes? body greyed-on-day))))
      (is (str/includes? (ui/worklog-fragment sys) "Scanning")
          "while the other day's scan is still listed")
      (discovery/run-scan! sys first-scan)
      (is (not (str/includes? (ui/worklog-fragment sys) "Scanning"))))))

(deftest a-day-being-submitted-glows-too
  (let [sys (installation)
        day (:starts-at (last (worklog/window sys)))]
    (worklog/name-task! sys day "ABC-1")
    (worklog/occupy-day! sys day :submission)
    (testing "the day on its way to Jira says so and glows like a scan"
      (let [body (ui/day-fragment sys (worklog/day sys day))]
        (is (str/includes? body "busy-glow"))
        (is (str/includes? body "submitting to Jira"))
        (is (not (str/includes? body greyed-on-day))
            "its tickets keep their colour: the submission adds nothing to them")))
    (testing "and stops once Jira has answered"
      (worklog/release-day! sys day :submission)
      (is (not (str/includes? (ui/day-fragment sys (worklog/day sys day))
                              "busy-glow"))))))

(deftest every-page-carries-the-theme-switch
  (let [body (ui/worklog-page (installation))]
    (is (str/includes? body "window.odatoTheme")
        "the pick is put back in the head, before anything is painted")
    (doseq [stop ["light" "system" "dark"]]
      (is (str/includes? body (str "data-stop=\"" stop "\""))))))
