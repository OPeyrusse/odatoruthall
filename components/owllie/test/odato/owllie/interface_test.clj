(ns odato.owllie.interface-test
  "How a review request is written. The branch names must be the ones Owllie
  computes itself (its scripts/owllie_results.py), or it refuses the run."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odato.owllie.interface :as owllie]
            [odato.time.interface :as t])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (java.net InetSocketAddress)))

(deftest pull-request-links
  (is (= {:repository {:owner "acme" :name "ledger-service"} :number 412}
         (owllie/parse-pull-request-url "https://github.com/acme/ledger-service/pull/412")))
  (testing "a tab of the pull request, a query or spaces around it"
    (is (= 412 (:number (owllie/parse-pull-request-url
                         " https://github.com/acme/ledger-service/pull/412/files?w=1 ")))))
  (testing "anything else"
    (doseq [s ["" "acme/ledger-service#412" "https://github.com/acme/ledger-service/issues/412"
               "https://gitlab.com/acme/x/pull/1" "https://github.com/acme/x;rm/pull/1"
               "https://github.com/acme/../pull/1"]]
      (is (nil? (owllie/parse-pull-request-url s)) s))))

(deftest branch-names-are-owllie's
  (let [pr (fn [owner name number] {:repository {:owner owner :name name} :number number})]
    (is (= "review/acme/ledger-service/412/20261031T1012Z-7f3a"
           (owllie/branch-name (pr "acme" "ledger-service" 412) "20261031T1012Z-7f3a")))
    (is (= "review/acme/_github/7/20261031T1012Z-abcd"
           (owllie/branch-name (pr "Acme" ".github" 7) "20261031T1012Z-abcd")))
    (is (= "review/a/x__y_lock/1/20261031T1012Z-abcd"
           (owllie/branch-name (pr "a" "x..y.lock" 1) "20261031T1012Z-abcd")))
    (is (= {:owner "acme" :name "ledger-service" :number 412 :run-id "20261031T1012Z-7f3a"}
           (owllie/parse-branch-name "review/acme/ledger-service/412/20261031T1012Z-7f3a")))
    (is (nil? (owllie/parse-branch-name "review/acme/ledger-service/412")))))

(deftest run-ids-sort-in-the-order-reviews-were-asked
  (let [at (t/parse-instant "2026-10-31T10:12:44Z")
        id (owllie/new-run-id at)]
    (is (re-matches #"20261031T1012Z-[0-9a-f]{4}" id))
    (is (= (t/parse-instant "2026-10-31T10:12:00Z") (owllie/run-id-instant id)))
    (is (neg? (compare id (owllie/new-run-id (+ at (t/minutes 1))))))))

(deftest the-request-block
  (let [block (owllie/request-block {:pull-request {:repository {:owner "acme" :name "x"} :number 3}
                                     :results-repository {:owner "activeviam" :name "owllie-results"}
                                     :branch "review/acme/x/3/20261031T1012Z-abcd"
                                     :brain-ref nil})]
    (is (= ["OWLLIE_RUN v1"
            "PR_URL=https://github.com/acme/x/pull/3"
            "RESULTS_REPO=activeviam/owllie-results"
            "RUN_BRANCH=review/acme/x/3/20261031T1012Z-abcd"]
           (str/split-lines block)))
    (is (str/includes? (owllie/request-block {:pull-request {:repository {:owner "a" :name "b"} :number 1}
                                              :results-repository {:owner "c" :name "d"}
                                              :branch "x" :brain-ref "rules/try"})
                       "BRAIN_REF=rules/try"))))

(deftest the-tune-request-block
  (let [block (owllie/tune-request-block
               {:pull-request {:repository {:owner "acme" :name "x"} :number 3}
                :head-sha "d07be31c0b8f6a4f1f1d6a3b2e9c8d7f6a5b4c3d"
                :review-run-id "20261031T1012Z-abcd"
                :results-repository {:owner "activeviam" :name "owllie-results"}
                :branch "tune/20261116T0930Z-1c2d"
                :brain-ref nil
                :remarks [{:id "r1" :kind "missed" :agents [] :rule nil
                           :file "src/A.java" :side "new" :line 3 :end_line 4
                           :quote "if (m.get(k) == null) {" :note "Check-then-act.\nUse computeIfAbsent."}]})
        lines (str/split-lines block)]
    (is (= ["OWLLIE_TUNE v1"
            "PR_URL=https://github.com/acme/x/pull/3"
            "HEAD_SHA=d07be31c0b8f6a4f1f1d6a3b2e9c8d7f6a5b4c3d"
            "REVIEW_RUN=20261031T1012Z-abcd"
            "RESULTS_REPO=activeviam/owllie-results"
            "RUN_BRANCH=tune/20261116T0930Z-1c2d"]
           (take 6 lines)))
    (testing "one remark per line, as JSON: a note's line breaks stay inside it"
      (is (= 7 (count lines)))
      (let [remark (json/read-str (subs (last lines) (count "REMARK=")))]
        (is (= "Check-then-act.\nUse computeIfAbsent." (get remark "note")))
        (is (contains? remark "rule"))
        (is (nil? (get remark "rule")))
        (is (= "src/A.java" (get remark "file")))))
    (testing "the brain ref becomes the base of the pull request"
      (is (str/includes? (owllie/tune-request-block {:pull-request {:repository {:owner "a" :name "b"} :number 1}
                                                     :results-repository {:owner "c" :name "d"}
                                                     :branch "tune/x" :brain-ref "rules/try" :remarks []})
                         "BRAIN_BASE=rules/try\n"))))
  (testing "tuning branches"
    (is (= "tune/20261116T0930Z-1c2d" (owllie/tune-branch-name "20261116T0930Z-1c2d")))
    (is (= {:run-id "20261116T0930Z-1c2d"} (owllie/parse-tune-branch-name "tune/20261116T0930Z-1c2d")))
    (is (nil? (owllie/parse-tune-branch-name "tune/nope")))
    (is (nil? (owllie/parse-tune-branch-name "review/acme/x/3/20261116T0930Z-1c2d"))))
  (testing "the CLI is asked for the skill the request names"
    (is (= "owllie-tune" (owllie/skill-for "OWLLIE_TUNE v1\nPR_URL=x")))
    (is (= "owllie-run" (owllie/skill-for "OWLLIE_RUN v1\nPR_URL=x")))))

(deftest ref-names
  (is (owllie/ref-name? "main"))
  (is (owllie/ref-name? "rules/try-2"))
  (doseq [s ["" "main..x" "-x" "a//b" "a/.b" "x.lock" "x/" "a b"]]
    (is (not (owllie/ref-name? s)) s)))

(defn- serving [status body f]
  (let [seen (atom nil)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (reset! seen {:path (str (.getRequestURI exchange))
                                      :method (.getRequestMethod exchange)
                                      :headers (into {} (for [[k v] (.getRequestHeaders exchange)]
                                                          [(str/lower-case k) (first v)]))
                                      :body (slurp (.getRequestBody exchange))})
                        (let [bytes (.getBytes (json/write-str body) "UTF-8")]
                          (.add (.getResponseHeaders exchange) "content-type" "application/json")
                          (.sendResponseHeaders exchange status (count bytes))
                          (doto (.getResponseBody exchange) (.write bytes) (.close))))))
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1/claude_code/routines/trig_1/fire")
            seen)
         (finally (.stop server 0)))))

(deftest a-scheduled-task-is-fired-through-its-api-trigger
  (serving 200 {:type "routine_fire"
                :claude_code_session_id "session_01H"
                :claude_code_session_url "https://claude.ai/code/session_01H"}
           (fn [url seen]
             (is (= {:session-id "session_01H" :session-url "https://claude.ai/code/session_01H"}
                    (owllie/fire! (owllie/scheduled-task {:fire-url url :token "sk-ant-oat01-x"})
                                  "OWLLIE_RUN v1\n")))
             (is (= "POST" (:method @seen)))
             (is (= "Bearer sk-ant-oat01-x" (get-in @seen [:headers "authorization"])))
             (is (= "experimental-cc-routine-2026-04-01" (get-in @seen [:headers "anthropic-beta"])))
             (is (= {"text" "OWLLIE_RUN v1\n"} (json/read-str (:body @seen))))))
  (testing "a refusal is said, not thrown"
    (serving 401 {:error {:message "invalid token"}}
             (fn [url _]
               (is (= {:failure "the scheduled task answered 401: invalid token"}
                      (owllie/fire! (owllie/scheduled-task {:fire-url url :token "bad"}) "x")))))))

(deftest the-cli-launcher-reads-the-session-it-printed
  (let [dir (str (System/getProperty "java.io.tmpdir"))
        echo (owllie/claude-cli {:dir dir :command ["sh" "-c" "echo Starting; echo 'View: https://claude.ai/code/session_01Dx?from=cli'; sleep 30" "claude"]
                                 :timeout-ms 10000})
        silent (owllie/claude-cli {:dir dir :command ["sh" "-c" "echo nope" "claude"] :timeout-ms 10000})]
    (is (= {:session-url "https://claude.ai/code/session_01Dx" :session-id "session_01Dx"}
           (owllie/fire! echo "OWLLIE_RUN v1\n")))
    (is (str/starts-with? (:failure (owllie/fire! silent "x")) "the Claude CLI printed no session"))))
