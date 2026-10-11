(ns odato.work-item.core
  (:require [clojure.string :as str]
            [odato.db.interface :as db]
            [odato.events.interface :as events]
            [odato.jira.interface :as jira]
            [odato.time.interface :as t]))

(def ^:private key-re #"^[A-Za-z][A-Za-z0-9]{0,9}-\d+$")

(defn valid-key? [s]
  (boolean (and s (re-matches key-re (str/trim (str s))))))

(defn- conn [sys] (db/durable (:store sys)))
(defn- now [sys] (t/now-ms (:clock sys)))

(defn- ->item [row]
  (when row
    {:key (:work-item/key row)
     :summary (:work-item/summary row)
     :status (:work-item/status row)
     :url (:work-item/url row)
     :closed? (boolean (:work-item/closed row))
     :first-seen-at (:work-item/first-seen-at row)
     :last-seen-at (:work-item/last-seen-at row)}))

(defn known [sys issue-key]
  (->item (db/entity-map (db/db (conn sys)) :work-item/key issue-key)))

(defn all [sys]
  (->> (db/all (db/db (conn sys)) :work-item/key) (map ->item) (sort-by :key) vec))

(defn lookup-state [sys issue-key]
  (:lookup/status (db/entity-map (db/db (conn sys)) :lookup/key issue-key)))

(defn- record! [sys issue-key facts]
  (let [existing (known sys issue-key)]
    (db/put! (conn sys) :work-item/key issue-key
             (cond-> {:work-item/summary (:summary facts)
                      :work-item/status (:status facts)
                      :work-item/url (:url facts)
                      :work-item/closed (boolean (:is-closed facts))
                      :work-item/last-seen-at (now sys)}
               (nil? existing) (assoc :work-item/first-seen-at (now sys))))
    (db/put! (conn sys) :lookup/key issue-key
             {:lookup/status :resolved :lookup/failure nil})
    (let [item (known sys issue-key)]
      (events/publish! (:bus sys) {:type :work-item/known :key issue-key :item item})
      {:known item})))

(defn- refuse! [sys issue-key failure]
  (db/put! (conn sys) :lookup/key issue-key
           {:lookup/status :failed
            :lookup/failure failure
            :lookup/requested-at (now sys)})
  (events/publish! (:bus sys) {:type :work-item/unavailable
                               :key issue-key :failure failure})
  {:failure failure})

(defn- ask! [sys issue-key]
  (db/put! (conn sys) :lookup/key issue-key
           {:lookup/status :requested :lookup/requested-at (now sys) :lookup/failure nil})
  (let [answer (try (jira/fetch-issue (:jira sys) issue-key)
                    (catch Exception _ {:failure :lookup-unavailable}))]
    (if-let [failure (:failure answer)]
      (refuse! sys issue-key failure)
      (record! sys issue-key (:facts answer)))))

(defn resolve! [sys issue-key]
  (cond
    (not (valid-key? issue-key)) (refuse! sys issue-key :unknown-issue)
    (known sys issue-key) (let [item (known sys issue-key)]
                            (events/publish! (:bus sys) {:type :work-item/known
                                                         :key issue-key :item item})
                            {:known item})
    :else (ask! sys issue-key)))

(defn refresh! [sys issue-key]
  (if (valid-key? issue-key)
    (ask! sys issue-key)
    (refuse! sys issue-key :unknown-issue)))
