(ns odato.server.handler-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odato.collector.interface :as collector]
            [odato.config.interface :as config]
            [odato.discovery.interface :as discovery]
            [odato.fixtures :as fix]
            [odato.server.handler :as handler]
            [odato.testing :as testing*]
            [odato.time.interface :as t]
            [odato.worklog.interface :as worklog]))

(def now (t/parse-instant "2026-09-15T10:30:00Z"))
(def today "2026-09-15")

(def github-script
  {:login "ada"
   :pull-requests
   {:repository-pull-requests
    {"acme/widget" [(fix/observation {:number 1 :author "ada" :title "Ship it"
                                      :updated-at now})]}
    :mentions [] :review-requests [] :recently-merged []}
   :activities
   {:github-commits [(fix/activity {:source :github-commits
                                    :happened-at (+ (t/parse-day today) (t/hours 9))
                                    :reference "sha-1" :title "ABC-1 make it go"
                                    :issue-keys #{"ABC-1"}})]}})

(def jira-script
  {:login "ada"
   :issues {"ABC-1" {:summary "Ship it" :status "In Progress"
                     :url "https://jira/ABC-1" :is-closed false}}
   :unknown #{"ABC-9"}})

(defn- installation []
  (testing*/configured! {:github github-script :jira jira-script}))

(defn- GET
  ([sys uri] (GET sys uri {}))
  ([sys uri headers]
   (handler/handle sys {:request-method :get :uri uri :headers headers})))

(defn- POST
  ([sys uri body] (POST sys uri body {"datastar-request" "true"}))
  ([sys uri body headers]
   (handler/handle sys {:request-method :post
                        :uri uri
                        :headers (merge {"content-type"
                                         "application/x-www-form-urlencoded"}
                                        headers)
                        :body body})))

(deftest the-shell-lands-on-gitdesk
  (let [sys (installation)
        response (GET sys "/")]
    (is (= 302 (:status response)))
    (is (= "/gitdesk" (get-in response [:headers "location"])))))

(deftest gitdesk-says-it-is-collecting-rather-than-empty
  (let [sys (installation)
        body (:body (GET sys "/gitdesk"))]
    (is (str/includes? body "never collected"))
    (is (str/includes? body "My work"))
    (testing "and shows what was collected once it has been"
      (collector/collect! sys :manual)
      (let [body (:body (GET sys "/gitdesk"))]
        (is (str/includes? body "Ship it"))
        (is (str/includes? body "acme/widget #1"))))))

(deftest a-refresh-is-a-plain-post
  (let [sys (installation)]
    (testing "a Datastar request is answered 204 — the event stream patches the page"
      (is (= 204 (:status (POST sys "/gitdesk/refresh" nil)))))
    (is (pos? (count (collector/pull-requests sys))))
    (testing "and a browser without it gets a redirect back"
      (let [response (POST sys "/gitdesk/refresh" nil {})]
        (is (= 303 (:status response)))
        (is (= "/gitdesk" (get-in response [:headers "location"])))))))

(deftest one-pull-request-in-detail
  (let [sys (installation)]
    (collector/collect! sys :manual)
    (let [response (GET sys "/gitdesk/pull-request/acme/widget/1")]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Ship it")))
    (is (= 404 (:status (GET sys "/gitdesk/pull-request/acme/widget/99"))))))

(deftest each-group-of-my-work-has-its-page
  (let [sys (installation)]
    (collector/collect! sys :manual)
    (testing "the dashboard count leads to it"
      (is (str/includes? (:body (GET sys "/gitdesk")) "href=\"/gitdesk/my/authored\"")))
    (let [response (GET sys "/gitdesk/my/authored")]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Ship it"))
      (is (str/includes? (:body response) "/sse/gitdesk/my/authored")))
    (testing "a group with nothing in it says so"
      (is (str/includes? (:body (GET sys "/gitdesk/my/to-review"))
                         "Nobody is waiting on your review.")))
    (is (= 404 (:status (GET sys "/gitdesk/my/starred"))))))

(deftest settings-are-saved-and-a-refusal-says-why
  (let [sys (installation)
        form (str "repositories=acme%2Fwidget&members=ada&cadence-minutes=30"
                  "&window-opens=5&window-closes=21&active-days=monday"
                  "&aging-days=15&comment-threshold=12&commit-threshold=10"
                  "&failing-commits=10&merged-days=3")]
    (POST sys "/settings" form)
    (let [in-force (config/current sys)]
      (is (= (t/minutes 30) (:refresh-cadence-ms in-force)))
      (is (= 12 (get-in in-force [:thresholds :comments])))
      (is (= #{:monday} (get-in in-force [:window :active-days])))
      (is (false? (:show-drafts in-force))))
    (testing "a save with nothing tracked is refused, and changes nothing"
      (let [response (POST sys "/settings" (str/replace form "repositories=acme%2Fwidget"
                                                        "repositories="))]
        (is (str/includes? (:body response) "no repositories"))
        (is (= 1 (count (:repositories (config/current sys)))))))))

(deftest the-timesheet-shows-five-days
  (let [sys (installation)
        body (:body (GET sys "/worklog"))]
    (is (str/includes? body "Last five working days"))
    (is (str/includes? body "2026-09-09"))
    (is (str/includes? body "2026-09-15"))))

(deftest a-day-is-named-scanned-filled-and-submitted
  (let [sys (installation)]
    (testing "naming a task"
      (POST sys (str "/worklog/" today "/tasks") "key=ABC-1")
      (is (= ["ABC-1"] (map :work-item-key
                            (:entries (worklog/day sys (t/parse-day today)))))))
    (testing "entering time"
      (let [entry (first (:entries (worklog/day sys (t/parse-day today))))]
        (POST sys (str "/worklog/entries/" (:id entry) "/time") "text=1h+30m")
        (is (= (t/minutes 90) (:total-logged (worklog/day sys (t/parse-day today)))))))
    (testing "scanning"
      (POST sys (str "/worklog/" today "/scan") nil)
      (is (:has-been-scanned (worklog/day sys (t/parse-day today))))
      (is (= 1 (count (discovery/lines-for sys (t/parse-day today) "ABC-1")))))
    (testing "submitting"
      (POST sys (str "/worklog/" today "/submit") nil)
      (is (:is-settled (worklog/day sys (t/parse-day today)))))
    (testing "and the day reads back as it was filled"
      (let [body (:body (GET sys (str "/worklog/" today)))]
        (is (str/includes? body "1h 30m"))
        (is (str/includes? body "in Jira"))))))

(deftest a-day-outside-the-window-is-not-a-page
  (let [sys (installation)]
    (is (= 404 (:status (GET sys "/worklog/2026-09-01"))))
    (is (= 404 (:status (GET sys "/worklog/not-a-day"))))))

(deftest an-orphan-is-attached-from-the-day-view
  (let [sys (testing*/configured!
             {:github (assoc github-script :activities
                             {:github-commits
                              [(fix/activity {:source :github-commits
                                              :happened-at (+ (t/parse-day today)
                                                              (t/hours 9))
                                              :reference "sha-x" :title "Tidy up"
                                              :issue-keys #{}})]})
              :jira jira-script})]
    (POST sys (str "/worklog/" today "/scan") nil)
    (let [orphan (first (discovery/orphans-on sys (t/parse-day today)))]
      (is (some? orphan))
      (POST sys (str "/worklog/lines/"
                     (java.net.URLEncoder/encode (str (:id orphan)) "UTF-8")
                     "/attach")
            "key=ABC-1")
      (is (empty? (discovery/orphans-on sys (t/parse-day today))))
      (is (= ["ABC-1"] (map :work-item-key
                            (:entries (worklog/day sys (t/parse-day today)))))))))

(deftest nothing-else-is-here
  (let [sys (installation)]
    (is (= 404 (:status (GET sys "/nowhere"))))
    (is (= 404 (:status (GET sys "/css/../../etc/passwd"))))))

(deftest the-assets-are-served-from-the-jar
  (let [sys (installation)]
    (is (= 200 (:status (GET sys "/css/app.css"))))
    (is (= 200 (:status (GET sys "/js/datastar.js"))))
    (is (= 200 (:status (GET sys "/favicon.svg"))))))
