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
     :budget-sequence [{:remaining 1 ...} {:remaining 0 ...}]}

  `:budget-sequence` is consumed one entry per answer and its last entry
  repeats, which is how a test makes the budget run out halfway through a
  source and watches the cycle wait rather than fail."
  (:require [odato.github.protocol :as protocol]))

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
          {:activities items :next-cursor next-cursor :budget budget})))))

(defn client [script]
  (->MockGithub script (atom {:calls [] :budget-index -1})))

(defn calls [c]
  (when (instance? MockGithub c) (:calls @(:state c))))
