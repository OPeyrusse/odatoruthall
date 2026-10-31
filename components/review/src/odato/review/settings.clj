(ns odato.review.settings
  "The review settings, and whether the results repository they name can be
  used. Saved apart from the gitdesk configuration: neither waits on the
  other."
  (:require [clojure.string :as str]
            [odato.access.interface :as access]
            [odato.config.interface :as config]
            [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.github.interface :as github]
            [odato.owllie.interface :as owllie]
            [odato.time.interface :as t]))

(def defaults
  {:launcher :scheduled-task
   :keep-runs 3
   :closed-retention-ms (t/days 14)
   :failed-retention-ms (t/days 3)})

(def bounds
  {:min-kept 1 :max-kept 50
   :min-retention-ms (t/days 1) :max-retention-ms (t/days 365)})

(def launchers [:scheduled-task :claude-cli])

(def marker
  "The file Owllie requires at the root of a results repository."
  ".owllie-results")

(defn- durable [sys] (db/durable (:store sys)))
(defn- volatile [sys] (db/volatile* (:store sys)))
(defn- now [sys] (t/now-ms (:clock sys)))

;; ------------------------------------------------------------------ reading

(defn current [sys]
  (let [row (db/entity-map (db/db (durable sys)) :review-settings/scope :installation)]
    {:results-repository (config/parse-repository (:review-settings/results-repository row))
     :brain-ref (:review-settings/brain-ref row)
     :launcher (or (:review-settings/launcher row) (:launcher defaults))
     :keep-runs (or (:review-settings/keep-runs row) (:keep-runs defaults))
     :closed-retention-ms (or (:review-settings/closed-retention-ms row)
                              (:closed-retention-ms defaults))
     :failed-retention-ms (or (:review-settings/failed-retention-ms row)
                              (:failed-retention-ms defaults))
     :updated-at (:review-settings/updated-at row)}))

(def ^:private repository-re #"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9._-]{1,100}$")

(defn- blank->nil [s] (when-not (str/blank? (str s)) (str/trim (str s))))

(defn validate
  "The fault a proposal would be refused for, or nil."
  [{:keys [results-repository brain-ref keep-runs closed-retention-ms failed-retention-ms]}]
  (let [repository (blank->nil results-repository)
        ref (blank->nil brain-ref)
        within (fn [v lo hi] (and (integer? v) (<= lo v hi)))]
    (cond
      (and repository (not (re-matches repository-re repository))) :invalid-results-repository
      (and ref (not (owllie/ref-name? ref))) :invalid-brain-ref
      (not (within keep-runs (:min-kept bounds) (:max-kept bounds))) :retention-out-of-bounds
      (not (within closed-retention-ms (:min-retention-ms bounds) (:max-retention-ms bounds)))
      :retention-out-of-bounds
      (not (within failed-retention-ms (:min-retention-ms bounds) (:max-retention-ms bounds)))
      :retention-out-of-bounds
      :else nil)))

;; --------------------------------------------------------- results access

(defn access-status
  "`:unchecked`, `:ready`, `:unreadable` or `:unmarked`, for the repository the
  settings name now."
  [sys]
  (let [row (db/entity-map (db/db (volatile sys)) :results-access/scope :installation)
        repository (config/repository-key (:results-repository (current sys)))]
    (if (and repository (= repository (:results-access/repository row)))
      (or (:results-access/status row) :unchecked)
      :unchecked)))

(defn access-checked-at [sys]
  (:results-access/checked-at
   (db/entity-map (db/db (volatile sys)) :results-access/scope :installation)))

(defn observe-budget! [sys answer]
  (when-let [remaining (get-in answer [:budget :remaining])]
    (access/budget-observed! sys remaining (get-in answer [:budget :resets-at]))))

(defn check-results-repository!
  "ResultsRepositoryChecked: reads the repository with the operator's GitHub
  credential, and looks for the marker on its default branch."
  [sys]
  (let [repository (:results-repository (current sys))
        status (if-not (and repository (access/usable? sys :github))
                 :unchecked
                 (let [repo (github/repository (:github sys) repository)]
                   (observe-budget! sys repo)
                   (if (:failure repo)
                     :unreadable
                     (let [file (github/file-exists (:github sys)
                                                    (assoc repository :path marker))]
                       (observe-budget! sys file)
                       (cond (:failure file) :unreadable
                             (:exists file) :ready
                             :else :unmarked)))))]
    (db/put! (volatile sys) :results-access/scope :installation
             {:results-access/repository (config/repository-key repository)
              :results-access/status status
              :results-access/checked-at (now sys)})
    (events/publish! (:bus sys) {:type :review/results-checked :status status})
    status))

;; ------------------------------------------------------------------ writing

(defn save!
  "ReviewSettingsSaved / ReviewSettingsRefused. A saved repository is checked
  again by whoever calls `check-results-repository!` next."
  [sys proposal]
  (if-let [fault (validate proposal)]
    {:ok false :fault fault :settings (current sys)}
    (do (db/put! (durable sys) :review-settings/scope :installation
                 {:review-settings/results-repository (blank->nil (:results-repository proposal))
                  :review-settings/brain-ref (blank->nil (:brain-ref proposal))
                  :review-settings/launcher (if (some #{(:launcher proposal)} launchers)
                                              (:launcher proposal)
                                              (:launcher defaults))
                  :review-settings/keep-runs (long (:keep-runs proposal))
                  :review-settings/closed-retention-ms (long (:closed-retention-ms proposal))
                  :review-settings/failed-retention-ms (long (:failed-retention-ms proposal))
                  :review-settings/updated-at (now sys)})
        (db/put! (volatile sys) :results-access/scope :installation
                 {:results-access/status :unchecked :results-access/checked-at nil})
        (events/publish! (:bus sys) {:type :review/settings-saved})
        {:ok true :settings (current sys)})))
