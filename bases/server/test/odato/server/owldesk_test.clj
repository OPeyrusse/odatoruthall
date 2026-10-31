(ns odato.server.owldesk-test
  "owldesk through its routes: the list, launching, one review, judging,
  the review settings and the cleanup."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odato.demo.reviews :as demo]
            [odato.owllie.interface :as owllie]
            [odato.review.interface :as review]
            [odato.server.handler :as handler]
            [odato.testing :as testing]
            [odato.time.interface :as t]))

(def now (t/parse-instant testing/default-instant))
(def pr-412 {:owner "acme" :name "widget" :number 412 :author "ada" :title "Cache the snapshot"})
(def done-id (demo/run-id (- now (t/hours 3)) "a412"))

(defn- installation []
  (let [sys (testing/connected!
             {:github {:repositories {"activeviam/owllie-results" {:files #{".owllie-results"}}}
                       :pull-request-details {"acme/widget#412" {:title "Cache the snapshot"
                                                                 :author "ada" :state :open}}
                       :branches {"activeviam/owllie-results"
                                  {(demo/branch pr-412 done-id)
                                   (demo/done-run pr-412 done-id (- now (t/hours 3)))}}}})
        sys (assoc sys :launchers {:scheduled-task (owllie/mock-launcher
                                                    (fn [_] {:session-url "https://claude.ai/code/session_x"}))}
                   :results-writer (:github sys))]
    (review/ensure-defaults! sys)
    sys))

(defn- GET [sys uri] (handler/handle sys {:request-method :get :uri uri :headers {}}))

(defn- POST
  ([sys uri body] (POST sys uri body {"datastar-request" "true"}))
  ([sys uri body headers]
   (handler/handle sys {:request-method :post :uri uri
                        :headers (merge {"content-type" "application/x-www-form-urlencoded"} headers)
                        :body body})))

(def settings-form
  "results-repository=activeviam%2Fowllie-results&brain-ref=&launcher=scheduled-task&keep-runs=3&closed-days=14&failed-days=3")

(deftest the-review-settings-are-saved-and-checked
  (let [sys (installation)]
    (testing "without a results repository, the form says so"
      (is (str/includes? (:body (GET sys "/owldesk")) "No results repository is set")))
    (let [response (POST sys "/settings/owldesk" settings-form)]
      (is (= "text/event-stream" (get-in response [:headers "content-type"])))
      (is (str/includes? (:body response) "Review settings saved"))
      (is (= :ready (review/results-access sys))))
    (testing "a refused save says why, and a plain post gets the whole page"
      (let [response (POST sys "/settings/owldesk" "results-repository=nope&keep-runs=3&closed-days=14&failed-days=3" {})]
        (is (str/includes? (:body response) "the results repository must be owner/name"))
        (is (str/includes? (:body response) "Collection settings"))))))

(deftest a-review-is-launched-and-followed
  (let [sys (installation)]
    (POST sys "/settings/owldesk" settings-form)
    (testing "a refusal is shown in place"
      (is (str/includes? (:body (POST sys "/owldesk/launch" "url=acme%2Fwidget%23412"))
                         "not a link to a GitHub pull request")))
    (let [body (:body (POST sys "/owldesk/launch"
                            "url=https%3A%2F%2Fgithub.com%2Facme%2Fwidget%2Fpull%2F412"))
          run-id (second (re-find #"/owldesk/review/([^\"']+)" body))]
      (is (str/includes? body "Review asked for"))
      (is (str/includes? (:body (GET sys (str "/owldesk/review/" run-id))) "Waiting for its branch")))
    (testing "the branches read, the list and the review show what they hold"
      (review/read-results! sys)
      (let [list-body (:body (GET sys "/owldesk"))]
        (is (str/includes? list-body "Cache the snapshot"))
        (is (str/includes? list-body "1 blocker")))
      (let [page (:body (GET sys (str "/owldesk/review/" done-id)))]
        (is (str/includes? page "Verdict"))
        (is (str/includes? page "False positive")))
      (is (str/includes? (:body (GET sys (str "/owldesk/review/" done-id "/general-rules")))
                         "rejected"))
      (is (= 404 (:status (GET sys (str "/owldesk/review/" done-id "/nobody")))))
      (is (= 404 (:status (GET sys "/owldesk/review/20200101T0000Z-0000")))))))

(deftest findings-are-judged-through-a-post
  (let [sys (installation)]
    (POST sys "/settings/owldesk" settings-form)
    (review/read-results! sys)
    (let [fingerprint (-> (review/review sys done-id) :run :summary :findings first :fingerprint)]
      (is (= 204 (:status (POST sys (str "/owldesk/review/" done-id "/judge")
                                (str "fingerprint=" fingerprint "&verdict=false-positive")))))
      (is (= {fingerprint :false-positive} (review/judgements sys done-id)))
      (POST sys (str "/owldesk/review/" done-id "/judge") (str "fingerprint=" fingerprint "&verdict=none"))
      (is (empty? (review/judgements sys done-id))))))

(deftest a-review-is-removed-and-old-ones-cleaned-up
  (let [sys (installation)]
    (POST sys "/settings/owldesk" settings-form)
    (review/read-results! sys)
    (is (str/includes? (:body (GET sys "/owldesk/cleanup")) "Nothing to clean up"))
    (let [response (POST sys (str "/owldesk/review/" done-id "/remove") "" {})]
      (is (= 303 (:status response)))
      (is (nil? (review/review sys done-id))))))
