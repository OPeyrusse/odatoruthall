(ns odato.github.client-test
  "Which Jira issues a GitHub activity names, as the real client reads them.

  Only the title names an issue: the pull request's title, or a commit's first
  line. A description or a commit body often cites older tickets to explain
  the change, and citing a ticket is not working on it."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [odato.github.interface :as github]
            [odato.time.interface :as t])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (java.net InetSocketAddress)))

(def today (t/parse-day "2026-09-15"))

(defn- serving
  "Runs `f` with a client against a local GitHub answering `body` to every
  request."
  [body f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [bytes (.getBytes (json/write-str body) "UTF-8")]
                          (.add (.getResponseHeaders exchange)
                                "content-type" "application/json")
                          (.sendResponseHeaders exchange 200 (count bytes))
                          (doto (.getResponseBody exchange) (.write bytes) (.close))))))
    (.start server)
    (try
      (f (github/client {:base-url (str "http://127.0.0.1:"
                                        (.getPort (.getAddress server)))
                         :token "t" :timeout-ms 5000}))
      (finally (.stop server 0)))))

(defn- keys-of [client source]
  (map :issue-keys (:activities (github/fetch-activity
                                 client {:source source :day-starts-at today
                                         :login "ada"}))))

(deftest a-pull-request-names-the-tickets-in-its-title
  (serving {:items [{:title "WID-88 Cache the snapshot between renders"
                     :body "Follows WID-12, which introduced the cache."
                     :html_url "https://github.com/acme/WID-77/pull/412"
                     :created_at "2026-09-15T10:00:00Z"
                     :updated_at "2026-09-15T10:00:00Z"}]}
           (fn [client]
             (doseq [source [:github-pull-requests :github-reviews :github-comments]]
               (testing (name source)
                 (is (= [#{"WID-88"}] (keys-of client source))
                     "the ticket cited in the description is not worked on"))))))

(deftest a-commit-names-the-tickets-in-its-first-line
  (serving {:items [{:sha "abc"
                     :commit {:message "WID-88 Cache the snapshot\n\nReverts what WID-12 did."
                              :author {:date "2026-09-15T10:00:00Z"}}
                     :repository {:full_name "acme/WID-77"}}]}
           (fn [client]
             (is (= [#{"WID-88"}] (keys-of client :github-commits))))))

(deftest a-title-naming-no-ticket-is-an-orphan
  (serving {:items [{:title "Say which sources failed to refresh"
                     :body "Part of WID-90."
                     :html_url "https://github.com/acme/widget/pull/418"
                     :updated_at "2026-09-15T10:00:00Z"}]}
           (fn [client]
             (is (= [#{}] (keys-of client :github-reviews))))))
