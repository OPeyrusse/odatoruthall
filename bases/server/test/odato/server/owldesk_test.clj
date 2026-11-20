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
        (is (str/includes? list-body "2 runs") "the run just asked for, and the one its branch holds"))
      (let [page (:body (GET sys (str "/owldesk/review/" done-id)))]
        (is (str/includes? page "Verdict") "the summary step opens the page")
        (is (str/includes? page "1 blocker"))
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
        (is (str/includes? summary "Remarks for Owllie")))
    (testing "the diff has a tab of its own, file by file, each line offering a missed remark"
      (let [diff (:body (GET sys (str "/owldesk/review/" done-id "/diff")))
            inputs (:body (GET sys (str "/owldesk/review/" done-id "/inputs")))]
        (is (str/includes? summary (str "/owldesk/review/" done-id "/diff")) "every page offers the tab")
        (is (str/includes? diff "src/main/java/acme/widget/SnapshotCache.java"))
        (is (str/includes? diff "private Snapshot last;"))
        (is (str/includes? diff (str "/owldesk/review/" done-id "/remark?file=src%2Fmain%2Fjava%2Facme%2Fwidget%2FRenderer.java&amp;side=old&amp;line=11")))
        (is (not (str/includes? inputs "private Snapshot last;")) "the inputs step no longer holds it")
        (is (str/includes? inputs "shown in the Diff tab"))))
    (testing "code is text, never markup"
      (is (not (str/includes? (:body (GET sys (str "/owldesk/review/" done-id "/diff"))) "<String>")))
      (is (str/includes? (:body (GET sys (str "/owldesk/review/" done-id "/diff"))) "Map&lt;String, Snapshot&gt;"))))))

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

(deftest the-runs-of-a-pull-request-are-chosen-from-its-review
  (let [sys (installation)
        earlier-at (- now (t/days 1))
        earlier-id (demo/run-id earlier-at "e412")]
    (POST sys "/settings/owldesk" settings-form)
    (odato.github.interface/mock-write-branch! (:github sys) {:owner "activeviam" :name "owllie-results"}
                                               (demo/branch pr-412 earlier-id)
                                               (demo/failed-run pr-412 earlier-id earlier-at))
    (review/read-results! sys)
    (testing "the list has one line per pull request: its latest run, and how many it had"
      (let [body (:body (GET sys "/owldesk"))]
        (is (str/includes? body "2 runs"))
        (is (str/includes? body (str "/owldesk/review/" done-id)))
        (is (not (str/includes? body earlier-id)) "the earlier runs are chosen from the review page")))
    (testing "a review page says when its run was asked for, and offers every run of the pull request"
      (let [page (:body (GET sys (str "/owldesk/review/" earlier-id)))
            option (fn [id] (re-find (re-pattern (str "<option[^>]*value=\"" id "\"[^>]*>[^<]*")) page))]
        (is (str/includes? page (t/format-minute earlier-at)))
        (is (str/includes? (option earlier-id) "selected"))
        (is (str/includes? (option earlier-id) "failed"))
        (is (not (str/includes? (option done-id) "selected")))
        (is (str/includes? (option done-id) "(latest)"))
        (is (str/includes? (option done-id) "1 blocker") "with what each run found")))
    (testing "without scripts, the picker's form leads to the run chosen"
      (let [response (handler/handle sys {:request-method :get :uri "/owldesk/review"
                                          :query-string (str "run=" earlier-id) :headers {}})]
        (is (= 303 (:status response)))
        (is (= (str "/owldesk/review/" earlier-id) (get-in response [:headers "location"]))))
      (is (= 404 (:status (handler/handle sys {:request-method :get :uri "/owldesk/review"
                                               :query-string "run=nope" :headers {}})))))
    (testing "another run is asked for from a review page, and its page is shown"
      (let [response (POST sys (str "/owldesk/review/" earlier-id "/run-again") "brain-ref=" {})
            location (get-in response [:headers "location"])
            new-id (last (str/split (str location) #"/"))]
        (is (= 303 (:status response)))
        (is (= :starting (:state (review/review sys new-id))))
        (let [page (:body (GET sys location))]
          (is (str/includes? page "Waiting for its branch"))
          (is (str/includes? page "3 runs"))
          (is (re-find #"<button[^>]*disabled[^>]*>Run again" page)
              "no other run while this one is in flight"))
        (testing "and while it is in flight, another is refused with the reason"
          (let [refused (POST sys (str "/owldesk/review/" done-id "/run-again") "" {})]
            (is (= 200 (:status refused)))
            (is (str/includes? (:body refused) "already starting or running"))))))
    (is (= 404 (:status (POST sys "/owldesk/review/20200101T0000Z-0000/run-again" "" {}))))))
