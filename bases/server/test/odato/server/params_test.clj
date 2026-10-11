(ns odato.server.params-test
  (:require [clojure.test :refer [deftest is testing]]
            [odato.server.params :as params]))

(deftest a-plain-form-post
  (let [p (params/of {:headers {"content-type" "application/x-www-form-urlencoded"}
                      :body "key=ABC-1&text=1h+30m"})]
    (is (= "ABC-1" (params/one p "key")))
    (is (= "1h 30m" (params/one p "text")))))

(deftest a-parameter-given-more-than-once-reads-back-as-a-vector
  (let [p (params/of {:headers {"content-type" "application/x-www-form-urlencoded"}
                      :body "active-days=monday&active-days=friday"})]
    (is (= ["monday" "friday"] (get p "active-days")))
    (is (= "monday" (params/one p "active-days")))))

(deftest the-multipart-body-datastar-sends
  (let [boundary "----abc123"
        body (str "--" boundary "\r\n"
                  "Content-Disposition: form-data; name=\"key\"\r\n\r\n"
                  "ABC-1\r\n"
                  "--" boundary "\r\n"
                  "Content-Disposition: form-data; name=\"text\"\r\n\r\n"
                  "1h 30m\r\n"
                  "--" boundary "--\r\n")
        p (params/of {:headers {"content-type"
                                (str "multipart/form-data; boundary=" boundary)}
                      :body body})]
    (is (= "ABC-1" (params/one p "key")))
    (is (= "1h 30m" (params/one p "text")))))

(deftest the-query-string-counts-too
  (let [p (params/of {:query-string "day=2026-09-15&who=ada%20lovelace" :headers {}})]
    (is (= "2026-09-15" (params/one p "day")))
    (is (= "ada lovelace" (params/one p "who")))))

(deftest nothing-at-all-is-nothing
  (is (= {} (params/of {:headers {} :body nil}))))
