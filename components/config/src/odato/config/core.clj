(ns odato.config.core
  (:require [clojure.string :as str]
            [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.time.interface :as t]))

(def defaults
  {:refresh-cadence-ms (t/minutes 60)
   :min-refresh-cadence-ms (t/minutes 5)
   :max-refresh-cadence-ms (t/hours 24)
   :window-opens-hour 5
   :window-closes-hour 21
   :window-active-days #{:monday :tuesday :wednesday :thursday :friday}
   :aging-ms (t/days 15)
   :comment-threshold 15
   :commit-threshold 10
   :failing-commits 10
   :merged-window-ms (t/days 3)})

(def day-order [:monday :tuesday :wednesday :thursday :friday :saturday :sunday])

(defn- conn [sys] (db/durable (:store sys)))
(defn- now [sys] (t/now-ms (:clock sys)))

;; ------------------------------------------------------------- repositories

(defn parse-repository [s]
  (when s
    (let [[owner name & more] (str/split (str/trim (str s)) #"/")]
      (when (and owner name (empty? more)
                 (not (str/blank? owner)) (not (str/blank? name)))
        {:owner owner :name name}))))

(defn repository-key [{:keys [owner name]}]
  (when (and owner name) (str owner "/" name)))

(defn- split-lines* [x]
  (cond
    (nil? x) []
    (string? x) (remove str/blank? (map str/trim (str/split x #"[\s,]+")))
    (coll? x) (remove str/blank? (map (comp str/trim str) x))
    :else []))

(defn parse-repositories [lines]
  (into #{} (keep parse-repository) (split-lines* lines)))

(defn parse-members [lines]
  (into #{} (split-lines* lines)))

(defn parse-days [days]
  (let [wanted (set (map #(keyword (str/lower-case (name %))) (split-lines* days)))]
    (into #{} (filter wanted) day-order)))

;; ---------------------------------------------------------------- reading

(defn current [sys]
  (let [row (db/entity-map (db/db (conn sys)) :config/scope :installation)]
    {:scope :installation
     :repositories (into #{} (keep parse-repository) (:config/repositories row))
     :members (set (:config/members row))
     :refresh-cadence-ms (or (:config/refresh-cadence-ms row)
                             (:refresh-cadence-ms defaults))
     :window {:opens-hour (or (:config/window-opens-hour row)
                              (:window-opens-hour defaults))
              :closes-hour (or (:config/window-closes-hour row)
                               (:window-closes-hour defaults))
              :active-days (or (not-empty (set (:config/window-active-days row)))
                               (:window-active-days defaults))}
     :thresholds {:aging-ms (or (:config/aging-ms row) (:aging-ms defaults))
                  :comments (or (:config/comment-threshold row)
                                (:comment-threshold defaults))
                  :commits (or (:config/commit-threshold row)
                               (:commit-threshold defaults))
                  :failing-commits (or (:config/failing-commits row)
                                       (:failing-commits defaults))
                  :merged-window-ms (or (:config/merged-window-ms row)
                                        (:merged-window-ms defaults))}
     :reassignment-enabled (boolean (:config/reassignment-enabled row))
     :updated-at (:config/updated-at row)}))

(defn collectable? [sys]
  (let [c (current sys)]
    (and (pos? (count (:repositories c))) (pos? (count (:members c))))))

(defn ensure-defaults! [sys]
  (when-not (db/lookup (db/db (conn sys)) :config/scope :installation)
    (db/transact! (conn sys)
                  [{:config/scope :installation
                    :config/refresh-cadence-ms (:refresh-cadence-ms defaults)
                    :config/window-opens-hour (:window-opens-hour defaults)
                    :config/window-closes-hour (:window-closes-hour defaults)
                    :config/window-active-days (vec (:window-active-days defaults))
                    :config/aging-ms (:aging-ms defaults)
                    :config/comment-threshold (:comment-threshold defaults)
                    :config/commit-threshold (:commit-threshold defaults)
                    :config/failing-commits (:failing-commits defaults)
                    :config/merged-window-ms (:merged-window-ms defaults)}]))
  sys)

;; ---------------------------------------------------------------- writing

(defn validate
  "The fault this proposal would be refused for, in the order the
  specification asks them."
  [{:keys [repositories members refresh-cadence-ms window]}]
  (let [{:keys [opens-hour closes-hour active-days]} window]
    (cond
      (empty? repositories) :no-repositories
      (empty? members) :no-members
      (>= (long opens-hour) (long closes-hour)) :window-inverted
      (empty? active-days) :no-active-days
      (< (long refresh-cadence-ms) (long (:min-refresh-cadence-ms defaults)))
      :cadence-out-of-bounds
      (> (long refresh-cadence-ms) (long (:max-refresh-cadence-ms defaults)))
      :cadence-out-of-bounds
      :else nil)))

(defn save! [sys proposal]
  (if-let [fault (validate proposal)]
    {:ok false :fault fault :config (current sys)}
    (let [before (current sys)
          {:keys [repositories members refresh-cadence-ms window thresholds]} proposal
          collection-changed? (or (not= repositories (:repositories before))
                                  (not= members (:members before))
                                  (not= refresh-cadence-ms (:refresh-cadence-ms before))
                                  (not= window (:window before)))
          presentation-changed? (not= thresholds (:thresholds before))]
      (db/put!
       (conn sys) :config/scope :installation
       {:config/repositories (mapv repository-key repositories)
        :config/members (vec members)
        :config/refresh-cadence-ms (long refresh-cadence-ms)
        :config/window-opens-hour (long (:opens-hour window))
        :config/window-closes-hour (long (:closes-hour window))
        :config/window-active-days (vec (:active-days window))
        :config/aging-ms (long (:aging-ms thresholds))
        :config/comment-threshold (long (:comments thresholds))
        :config/commit-threshold (long (:commits thresholds))
        :config/failing-commits (long (:failing-commits thresholds))
        :config/merged-window-ms (long (:merged-window-ms thresholds))
        :config/updated-at (now sys)})
      (let [after (current sys)]
        (when collection-changed?
          (events/publish! (:bus sys) {:type :config/collection-settings-changed
                                       :config after}))
        (when presentation-changed?
          (events/publish! (:bus sys) {:type :config/presentation-settings-changed
                                       :config after}))
        {:ok true :config after}))))

(defn switch-reassignment! [sys enabled?]
  (db/put! (conn sys) :config/scope :installation
           {:config/reassignment-enabled (boolean enabled?)})
  (current sys))
