(ns odato.server.owldesk-test
  "owldesk through its routes: the list, launching, one review, judging,
  the review settings and the cleanup."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odato.demo.reviews :as demo]
            [odato.github.interface]
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
                   :tune-launchers {:scheduled-task (owllie/mock-launcher
                                                     (fn [_] {:session-url "https://claude.ai/code/session_t"}))}
                   :results-writer (:github sys))]
    (review/ensure-defaults! sys)
    sys))

(defn- GET [sys uri]
  (let [[path query] (str/split uri #"\?" 2)]
    (handler/handle sys {:request-method :get :uri path :query-string query :headers {}})))

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
        (is (str/includes? page "Verdict") "the summary step opens the page")
        (is (str/includes? page "False positive")))
      (is (str/includes? (:body (GET sys (str "/owldesk/review/" done-id "/general-rules")))
                         "rejected"))
      (is (str/includes? (:body (GET sys (str "/owldesk/review/" done-id "/inputs")))
                         "Description") "a markdown block is shown as markdown")
      (is (= 404 (:status (GET sys (str "/owldesk/review/" done-id "/nobody")))))
      (is (= 404 (:status (GET sys "/owldesk/review/20200101T0000Z-0000")))))))

(deftest findings-are-judged-through-a-post
  (let [sys (installation)]
    (POST sys "/settings/owldesk" settings-form)
    (review/read-results! sys)
    (let [fingerprint (key (first (review/findings (review/review sys done-id))))]
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

(deftest a-running-review-shows-its-process
  (let [sys (installation)
        stalled-id (demo/run-id (- now (t/hours 1)) "c412")]
    (POST sys "/settings/owldesk" settings-form)
    (odato.github.interface/mock-write-branch! (:github sys) {:owner "activeviam" :name "owllie-results"}
                                               (demo/branch pr-412 stalled-id)
                                               (demo/stalled-run pr-412 stalled-id (- now (t/minutes 10))))
    (review/read-results! sys)
    (let [page (:body (GET sys (str "/owldesk/review/" stalled-id)))]
      (is (str/includes? page "Progress"))
      (is (str/includes? page "Phase 2"))
      (is (str/includes? page "pending")))))

;; ------------------------------------------------------------- remarks

(defn- location [response] (get-in response [:headers "location"]))

(defn- form [& kvs]
  (str/join "&" (for [[k v] (partition 2 kvs)]
                  (str k "=" (java.net.URLEncoder/encode (str v) "UTF-8")))))

(deftest the-code-is-shown-as-the-review-read-it
  (let [sys (installation)]
    (POST sys "/settings/owldesk" settings-form)
    (review/read-results! sys)
    (let [summary (:body (GET sys (str "/owldesk/review/" done-id)))]
      (testing "a finding shows the lines around it, and offers a remark"
        (is (str/includes? summary "byKey.containsKey(key)"))
        (is (str/includes? summary "Remark for Owllie"))
        (is (str/includes? summary "Remarks for Owllie"))))
    (testing "the diff is shown file by file, each line offering a missed remark"
      (let [inputs (:body (GET sys (str "/owldesk/review/" done-id "/inputs")))]
        (is (str/includes? inputs "src/main/java/acme/widget/SnapshotCache.java"))
        (is (str/includes? inputs "private Snapshot last;"))
        (is (str/includes? inputs (str "/owldesk/review/" done-id "/remark?file=src%2Fmain%2Fjava%2Facme%2Fwidget%2FRenderer.java&amp;side=old&amp;line=11")))))
    (testing "code is text, never markup"
      (is (not (str/includes? (:body (GET sys (str "/owldesk/review/" done-id "/inputs"))) "<String>")))
      (is (str/includes? (:body (GET sys (str "/owldesk/review/" done-id "/inputs"))) "Map&lt;String, Snapshot&gt;")))))

(deftest remarks-are-written-gathered-and-sent
  (let [sys (installation)
        base (str "/owldesk/review/" done-id)]
    (POST sys "/settings/owldesk" settings-form)
    (review/read-results! sys)
    (let [fingerprint (some (fn [[fp f]] (when (= 52 (get-in f [:location :line])) fp))
                            (review/findings (review/review sys done-id)))]
      (testing "the form of a remark on a finding"
        (let [page (:body (GET sys (str base "/remark?fingerprint=" fingerprint)))]
          (is (str/includes? page "A remark on a finding"))
          (is (str/includes? page "False positive"))
          (is (str/includes? page "Wrong severity"))))
      (testing "a refused remark keeps what was typed and says why"
        (let [response (POST sys (str base "/remarks")
                             (form "fingerprint" fingerprint "kind" "wrong-severity" "proposed-severity" "blocker"
                                   "note" "Typed with care") {})]
          (is (= 200 (:status response)))
          (is (str/includes? (:body response) "propose a severity other than the finding"))
          (is (str/includes? (:body response) "Typed with care"))
          (is (empty? (review/remarks sys done-id)))))
      (testing "a remark is kept as a draft and listed"
        (let [response (POST sys (str base "/remarks")
                             (form "fingerprint" fingerprint "kind" "false-positive" "note" "Request scoped.") {})]
          (is (= 303 (:status response)))
          (is (= (str base "#remarks") (location response))))
        (let [response (POST sys (str base "/remarks")
                             (form "file" "src/main/java/acme/widget/SnapshotCache.java" "side" "new" "line" "27"
                                   "end-line" "29" "kind" "missed" "note" "Magic number.") {})]
          (is (= 303 (:status response))))
        (let [page (:body (GET sys base))]
          (is (str/includes? page "2 drafts"))
          (is (str/includes? page "Magic number."))
          (is (str/includes? page "Send 2 drafts to Owllie"))))
      (testing "a draft is changed on its own page"
        (let [missed (second (review/remarks sys done-id))
              path (str base "/remarks/" (:id missed))]
          (is (str/includes? (:body (GET sys path)) "Change a draft"))
          (is (= 303 (:status (POST sys path (form "note" "A magic number, twice.") {}))))
          (is (= "A magic number, twice." (:note (review/remark sys (:id missed)))))))
      (testing "sending answers in place, and the tuning can be followed"
        (let [response (POST sys (str base "/send") "")
              tune-id (:batch (first (review/remarks sys done-id)))]
          (is (= "text/event-stream" (get-in response [:headers "content-type"])))
          (is (str/includes? (:body response) "Remarks sent to Owllie"))
          (is (some? tune-id))
          (is (str/includes? (:body (GET sys (str "/owldesk/tuning/" tune-id))) "Waiting for its branch"))
          (is (str/includes? (:body (GET sys base)) "Batches sent"))))
      (is (= 404 (:status (GET sys "/owldesk/tuning/20200101T0000Z-0000")))))))
