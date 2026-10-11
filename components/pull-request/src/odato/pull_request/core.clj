(ns odato.pull-request.core
  (:require [odato.time.interface :as t]))

(defn ident [{:keys [owner name]} number]
  (str owner "/" name "#" number))

(defn facts
  "The facts a pull request carries, with every field present and nothing
  assumed: a pull request with no commits has no rollup to report."
  [m]
  {:title (:title m)
   :url (:url m)
   :branch (:branch m)
   :author (:author m)
   :assignees (set (:assignees m))
   :requested-reviewers (set (:requested-reviewers m))
   :labels (set (:labels m))
   :is-draft (boolean (:is-draft m))
   :state (or (:state m) :open)
   :mergeable (boolean (:mergeable m))
   :auto-merge-enabled (boolean (:auto-merge-enabled m))
   :comment-count (long (or (:comment-count m) 0))
   :opened-at (:opened-at m)
   :updated-at (:updated-at m)
   :merged-at (:merged-at m)
   :commit-count (long (or (:commit-count m) (count (:commits m)) 0))
   :commits (vec (:commits m))        ; newest first
   :reviews (vec (:reviews m))})      ; oldest first

;; -------------------------------------------------------------- the reviews

(defn- latest-review-per-reviewer
  "Only the last review of each reviewer counts, and only if it approved or
  requested changes: a comment is not a verdict."
  [reviews]
  (->> reviews
       (sort-by #(or (:submitted-at %) 0))
       (reduce (fn [acc r] (assoc acc (:reviewer r) r)) {})
       vals
       (filter #(contains? #{:approved :changes-requested} (:verdict %)))
       (sort-by #(or (:submitted-at %) 0))
       vec))

(defn- awaiting-rereview
  "Reviewers who asked for changes and have had them answered since: the author
  has pushed commits the reviewer has not looked at, so the pull request is
  back in the reviewer's court."
  [decisive commits]
  (let [head (first commits)]
    (if-not head
      #{}
      (into #{}
            (comp (filter #(= :changes-requested (:verdict %)))
                  (filter (fn [r]
                            (and (not= (:sha head) (:reviewed-sha r))
                                 (> (long (or (:committed-at head) 0))
                                    (long (or (:submitted-at r) 0))))))
                  (map :reviewer))
            decisive))))

;; ----------------------------------------------------------- classification

(defn view [{:keys [repository number facts first-seen-at last-seen-at]} config now-ms]
  (let [{:keys [thresholds show-drafts]} config
        commits (:commits facts)
        head (first commits)
        decisive (latest-review-per-reviewer (:reviews facts))
        approvals (filterv #(= :approved (:verdict %)) decisive)
        change-requests (filterv #(= :changes-requested (:verdict %)) decisive)
        approved? (and (pos? (count decisive)) (zero? (count change-requests)))
        open? (= :open (:state facts))
        commit-count (:commit-count facts)
        failing-window (long (:failing-commits thresholds))
        newest (take failing-window commits)
        age (- (long now-ms) (long (or (:opened-at facts) now-ms)))]
    {:repository repository
     :number number
     :ident (ident repository number)
     :facts facts
     :first-seen-at first-seen-at
     :last-seen-at last-seen-at
     :head-commit head
     :head-check (:check-rollup head)
     :decisive-reviews decisive
     :approvals approvals
     :change-requests change-requests
     :approved? approved?
     :awaiting-rereview (awaiting-rereview decisive commits)
     :open? open?
     :age age

     :aging? (and open? (>= age (long (:aging-ms thresholds))))

     :conflicting? (and open?
                        (>= (:comment-count facts) (long (:comments thresholds))))

     :difficult? (and open?
                      (>= commit-count (long (:commits thresholds)))
                      approved?)

     :stuck? (and open?
                  approved?
                  (>= commit-count failing-window)
                  (= failing-window (count newest))
                  (every? #(= :failure (:check-rollup %)) newest))

     :merging? (and open?
                    (not (:is-draft facts))
                    (:mergeable facts)
                    (:auto-merge-enabled facts)
                    approved?)

     :recently-merged? (and (= :merged (:state facts))
                            (some? (:merged-at facts))
                            (<= (- (long now-ms) (long (:merged-at facts)))
                                (long (:merged-window-ms thresholds))))

     ;; Drafts are always counted, and only listed when the operator asks.
     :listable? (or (not (:is-draft facts)) (boolean show-drafts))}))

(defn of-interest? [facts members operator]
  (let [members (set members)
        assignees (set (:assignees facts))]
    (boolean (or (contains? members (:author facts))
                 (and operator (= operator (:author facts)))
                 (some members assignees)
                 (and operator (contains? assignees operator))))))

;; ----------------------------------------------------------- slices by person

(defn authored-by? [view login] (= login (get-in view [:facts :author])))
(defn assigned-to? [view login] (contains? (get-in view [:facts :assignees]) login))
(defn review-requested-from? [view login]
  (contains? (get-in view [:facts :requested-reviewers]) login))
(defn addressed-for? [view login] (contains? (:awaiting-rereview view) login))
(defn involves? [view login]
  (or (authored-by? view login) (assigned-to? view login)))
(defn drafted-by? [view login]
  (and (authored-by? view login) (get-in view [:facts :is-draft])))

(defn by-recent-activity [views]
  (vec (sort-by #(- (long (or (get-in % [:facts :updated-at]) 0))) views)))
