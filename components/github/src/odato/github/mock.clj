(ns odato.github.mock
  "A GitHub that answers from a script.

  It exists for two reasons the plan asks for: running the whole installation
  without a GitHub token, and testing the collection cycle — its pagination,
  its per-source failures and its quota waits — without a network.

  The script:

    {:login    \"ada\"
     :whoami   {:failure :credential-rejected}   ; optional, overrides the above
     :page-size 2                                ; how many results per page
     :pull-requests
       {:repository-pull-requests {\"acme/widget\" [observation ...]}
        :mentions         [observation ...]
        :review-requests  [observation ...]
        :recently-merged  [observation ...]}
     :activities
       {:github-commits [activity ...] :github-comments [...] ...}
     :failures {:mentions :service-error}        ; this source always fails
     :fail-after {:mentions 1}                   ; ... or fails on page n
     :budget   {:remaining 4999 :resets-at 123}  ; a fixed budget
     :budget-sequence [{:remaining 1 ...} {:remaining 0 ...}]
     :repositories {\"acme/owllie-results\" {:default-branch \"main\"
                                           :files #{\".owllie-results\"}}}
     :pull-request-details {\"acme/widget#12\" {:title ... :author ... :state :open
                                              :closed-at nil :head-sha ...}}
     :branches {\"acme/owllie-results\" {\"review/...\" {\"owllie/meta.json\" \"{...}\"}}}
     :review-comments {\"acme/widget#12\" [{:id :author :path :line :side ...}]}
     :delete-failure :credential-rejected}       ; deleting a branch fails

  A repository missing from `:repositories` is not found. The branches are
  held in the client's own state, so `write-branch!` can move one under a
  reader's eyes, the way a running review does.

  `:budget-sequence` is consumed one entry per answer and its last entry
  repeats, which is how a test makes the budget run out halfway through a
  source and watches the cycle wait rather than fail."
  (:require [clojure.string :as str]
            [odato.github.protocol :as protocol])
  (:import (java.security MessageDigest)))

(def ^:private big-budget {:remaining 4999 :resets-at nil})

(defn- source-key [{:keys [kind repository]}]
  (if (= kind :repository-pull-requests)
    [:repository-pull-requests (str (:owner repository) "/" (:name repository))]
    kind))

(defn- results-for [script request]
  (let [k (source-key request)]
    (if (vector? k)
      (get-in script [:pull-requests (first k) (second k)] [])
      (get-in script [:pull-requests k] []))))

(defn- next-budget! [state script]
  (let [seq* (:budget-sequence script)]
    (if (seq seq*)
      (let [i (:budget-index (swap! state update :budget-index (fnil inc -1)))]
        (nth seq* (min i (dec (count seq*)))))
      (or (:budget script) big-budget))))

(defn- page
  "Cuts `results` at the cursor, and says where the next page starts."
  [script results cursor]
  (let [size (or (:page-size script) 1000)
        from (if cursor (parse-long cursor) 0)
        taken (vec (take size (drop from results)))
        next-from (+ from (count taken))]
    {:items taken
     :next-cursor (when (< next-from (count results)) (str next-from))}))

(defn- failure-for [script state key page-number]
  (or (get-in script [:failures key])
      (when-let [n (get-in script [:fail-after key])]
        (when (>= page-number (long n)) (get-in script [:fail-after-reason key]
                                               :service-error)))))

;; ------------------------------------------------------------ repositories

(defn- sha1 [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-1") (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))

(defn- repo-key [{:keys [owner name]}] (str owner "/" name))

(defn- branch-sha [files]
  (sha1 (str/join "\n" (for [[path content] (sort files)] (str path "\u0000" (sha1 content))))))

(defn- answer [state script op request f]
  (swap! state update :calls conj {:op op :request request})
  (merge (f) {:budget (next-budget! state script)}))

(defn- find-branch [state request]
  (let [branches (get-in @state [:branches (repo-key request)])]
    (some (fn [[branch files]] (when (= (:sha request) (branch-sha files)) files)) branches)))

(defrecord MockGithub [script state]
  protocol/GithubClient
  (-whoami [_]
    (swap! state update :calls conj {:op :whoami})
    (or (:whoami script)
        {:login (or (:login script) "operator")
         :budget (next-budget! state script)}))

  (-fetch-pull-requests [_ request]
    (let [key (source-key request)
          cursor (:cursor request)
          page-number (if cursor (inc (quot (parse-long cursor)
                                            (max 1 (or (:page-size script) 1000))))
                          1)
          budget (next-budget! state script)]
      (swap! state update :calls conj {:op :pull-requests :source key :cursor cursor})
      (if-let [failure (failure-for script state key page-number)]
        {:failure failure :budget budget}
        (let [{:keys [items next-cursor]} (page script (results-for script request) cursor)]
          {:observations items :next-cursor next-cursor :budget budget}))))

  (-fetch-activity [_ {:keys [source cursor] :as request}]
    (let [budget (next-budget! state script)]
      (swap! state update :calls conj {:op :activity :source source :cursor cursor
                                       :day (:day-starts-at request)})
      (if-let [failure (get-in script [:failures source])]
        {:failure failure :budget budget}
        (let [{:keys [items next-cursor]}
              (page script (get-in script [:activities source] []) cursor)]
          {:activities items :next-cursor next-cursor :budget budget}))))

  protocol/GithubRepositories
  (-repository [_ request]
    (answer state script :repository request
            #(if-let [r (get-in script [:repositories (repo-key request)])]
               {:repository {:default-branch (:default-branch r "main") :private true}}
               {:failure :not-found})))

  (-file-exists [_ request]
    (answer state script :file-exists request
            #(if-let [r (get-in script [:repositories (repo-key request)])]
               {:exists (contains? (set (:files r)) (:path request))}
               {:failure :not-found})))

  (-pull-request [_ request]
    (answer state script :pull-request request
            #(if-let [pr (get-in script [:pull-request-details
                                         (str (repo-key request) "#" (:number request))])]
               {:pull-request pr}
               {:failure :not-found})))

  (-branches [_ request]
    (answer state script :branches request
            #(if (get-in script [:repositories (repo-key request)])
               {:branches (vec (for [[branch files] (sort (get-in @state [:branches (repo-key request)]))
                                     :when (str/starts-with? branch (str (:prefix request)))]
                                 {:name branch :sha (branch-sha files)}))}
               {:failure :not-found})))

  (-tree [_ request]
    (answer state script :tree request
            #(if-let [files (find-branch state request)]
               {:entries (vec (for [[path content] (sort files)]
                                {:path path :sha (sha1 content)}))}
               {:failure :not-found})))

  (-blob [_ request]
    (answer state script :blob request
            #(if-let [content (some (fn [[_ branches]]
                                      (some (fn [[_ files]]
                                              (some (fn [[_ c]] (when (= (:sha request) (sha1 c)) c))
                                                    files))
                                            branches))
                                    (:branches @state))]
               {:content content}
               {:failure :not-found})))

  (-review-comments [_ request]
    (answer state script :review-comments request
            #(if (get-in script [:pull-request-details (str (repo-key request) "#" (:number request))])
               {:comments (vec (get-in script [:review-comments
                                               (str (repo-key request) "#" (:number request))]))}
               {:failure :not-found})))

  (-delete-branch [_ request]
    (answer state script :delete-branch request
            #(if-let [failure (:delete-failure script)]
               {:failure failure}
               (do (swap! state update-in [:branches (repo-key request)] dissoc (:branch request))
                   {:deleted true})))))

(defn client [script]
  (->MockGithub script (atom {:calls [] :budget-index -1
                              :branches (or (:branches script) {})})))

(defn write-branch!
  "Adds or replaces files on a branch of a mock repository, creating it if
  needed."
  [c repository branch files]
  (swap! (:state c) update-in [:branches (repo-key repository) branch] merge files)
  c)

(defn branch-files
  "What a branch of a mock repository holds, or nil."
  [c repository branch]
  (get-in @(:state c) [:branches (repo-key repository) branch]))

(defn calls [c]
  (when (instance? MockGithub c) (:calls @(:state c))))
