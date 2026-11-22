(ns odato.github.client-test
  "What the real client asks GitHub, and which Jira issues a GitHub activity
  names, as it reads them.

  Only the title names an issue: the pull request's title, or a commit's first
  line. A description or a commit body often cites older tickets to explain
  the change, and citing a ticket is not working on it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
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

(defn- searched
  "The search query the client sends for one page of `kind`."
  [kind repositories]
  (let [seen (promise)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (deliver seen (json/read-str (slurp (.getRequestBody exchange))
                                                     :key-fn keyword))
                        (let [bytes (.getBytes (json/write-str {:data {:search {:nodes []}}})
                                               "UTF-8")]
                          (.sendResponseHeaders exchange 200 (count bytes))
                          (doto (.getResponseBody exchange) (.write bytes) (.close))))))
    (.start server)
    (try
      (github/fetch-pull-requests
       (github/client {:base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
                       :token "t" :timeout-ms 5000})
       {:kind kind :login "ada" :repositories repositories
        :merged-window-ms (t/days 3) :now-ms today})
      (get-in (deref seen 5000 nil) [:variables :q])
      (finally (.stop server 0)))))

(deftest every-search-is-held-to-the-tracked-repositories
  (let [tracked #{{:owner "acme" :name "widget"} {:owner "acme" :name "gadget"}}]
    (doseq [[kind start] {:mentions "is:pr is:open mentions:ada"
                          :review-requests "is:pr is:open review-requested:ada"
                          :recently-merged "is:pr is:merged merged:>="}]
      (testing (name kind)
        (let [q (searched kind tracked)]
          (is (str/starts-with? q start))
          (is (str/ends-with? q " repo:acme/gadget repo:acme/widget")))))))

(defn- answering
  "Runs `f` with a client against a local GitHub answering `body` with
  `status` to every request, and answers what `f` returned with the requests
  it received, as `{:method :path :body}`."
  [status body f]
  (let [received (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (swap! received conj
                               {:method (.getRequestMethod exchange)
                                :path (.getPath (.getRequestURI exchange))
                                :body (json/read-str (slurp (.getRequestBody exchange))
                                                     :key-fn keyword)})
                        (let [bytes (.getBytes (json/write-str body) "UTF-8")]
                          (.add (.getResponseHeaders exchange)
                                "content-type" "application/json")
                          (.sendResponseHeaders exchange status (count bytes))
                          (doto (.getResponseBody exchange) (.write bytes) (.close))))))
    (.start server)
    (try
      (let [answer (f (github/client {:base-url (str "http://127.0.0.1:"
                                                     (.getPort (.getAddress server)))
                                      :token "t" :timeout-ms 5000}))]
        {:answer answer :received @received})
      (finally (.stop server 0)))))

(def ^:private assigned-page
  {:data {:rateLimit {:remaining 4990 :resetAt "2026-09-15T11:00:00Z"}
          :search
          {:pageInfo {:hasNextPage true :endCursor "Y3Vyc29yOjUw"}
           :nodes [{:number 12 :title "Cache the snapshot"
                    :url "https://github.com/acme/widget/pull/12"
                    :repository {:name "widget" :owner {:login "acme"}}
                    :author {:__typename "User" :login "grace"}
                    :reviews {:nodes [{:author {:login "ada"} :state "COMMENTED"
                                       :submittedAt "2026-09-15T09:00:00Z"}
                                      {:author {:login "ada"} :state "PENDING"
                                       :submittedAt nil}
                                      {:author {:login "ada"} :state "CHANGES_REQUESTED"
                                       :submittedAt "2026-09-15T10:00:00Z"}]}
                    :timelineItems {:nodes [{:createdAt "2026-09-14T08:00:00Z"
                                             :assignee {:login "ada"}}
                                            {:createdAt "2026-09-14T08:05:00Z"
                                             :assignee {}}]}}
                   {:number 13 :title "Bump the parser"
                    :url "https://github.com/acme/widget/pull/13"
                    :repository {:name "widget" :owner {:login "acme"}}
                    :author {:__typename "Bot" :login "dependabot"}
                    :reviews {:nodes []}
                    :timelineItems {:nodes []}}
                   {:number 14 :title "An orphan"
                    :url "https://github.com/acme/widget/pull/14"
                    :repository {:name "widget" :owner {:login "acme"}}
                    :author nil
                    :reviews {:nodes []}
                    :timelineItems {:nodes []}}]}}})

(deftest the-assigned-search-is-held-to-the-tracked-repositories
  (let [{:keys [received]}
        (answering 200 assigned-page
                   #(github/assigned-pull-requests
                     % {:login "ada" :cursor "Y3Vyc29yOjA="
                        :repositories #{{:owner "acme" :name "widget"}
                                        {:owner "acme" :name "gadget"}}}))
        variables (:variables (:body (first received)))]
    (is (= "is:pr is:open assignee:ada repo:acme/gadget repo:acme/widget" (:q variables)))
    (is (= "ada" (:login variables)) "whose reviews are read")
    (is (= "Y3Vyc29yOjA=" (:cursor variables)))))

(deftest an-assigned-pull-request-reads-its-author-the-operator-s-reviews-and-the-assignments
  (let [{:keys [answer]}
        (answering 200 assigned-page
                   #(github/assigned-pull-requests
                     % {:login "ada" :repositories #{{:owner "acme" :name "widget"}}}))
        [by-grace by-a-bot orphan] (:pull-requests answer)]
    (is (= {:repository {:owner "acme" :name "widget"}
            :number 12
            :title "Cache the snapshot"
            :url "https://github.com/acme/widget/pull/12"
            :author "grace"
            :author-is-user true
            :reviews [{:reviewer "ada" :verdict :commented
                       :submitted-at (t/parse-instant "2026-09-15T09:00:00Z")}
                      {:reviewer "ada" :verdict :changes-requested
                       :submitted-at (t/parse-instant "2026-09-15T10:00:00Z")}]
            :assignments [{:assignee "ada"
                           :assigned-at (t/parse-instant "2026-09-14T08:00:00Z")}]}
           by-grace)
        "a pending review is not a review yet, and an assignee that is not a user is no one")
    (is (= ["dependabot" false] ((juxt :author :author-is-user) by-a-bot)))
    (is (= [nil false] ((juxt :author :author-is-user) orphan)) "a deleted account")
    (is (= "Y3Vyc29yOjUw" (:next-cursor answer)))
    (is (= 4990 (get-in answer [:budget :remaining])))))

(deftest an-assignee-is-written-on-the-pull-request-s-issue
  (let [pr {:owner "acme" :name "widget" :number 12 :login "grace"}]
    (testing "added"
      (let [{:keys [answer received]} (answering 201 {} #(github/add-assignee % pr))]
        (is (:written answer))
        (is (= [{:method "POST" :path "/repos/acme/widget/issues/12/assignees"
                 :body {:assignees ["grace"]}}]
               received))))
    (testing "removed"
      (let [{:keys [answer received]} (answering 200 {} #(github/remove-assignee % pr))]
        (is (:written answer))
        (is (= [{:method "DELETE" :path "/repos/acme/widget/issues/12/assignees"
                 :body {:assignees ["grace"]}}]
               received))))
    (testing "refused"
      (is (= :credential-rejected
             (:failure (:answer (answering 401 {:message "Bad credentials"}
                                           #(github/add-assignee % pr)))))))))
