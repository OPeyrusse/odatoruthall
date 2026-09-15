(ns odato.collector.schedule
  "When the next collection is due, and whether it may start.

  Scheduled refreshes start only inside the collection window — between 05:00
  and 21:00 UTC on weekdays by default. Outside it, the schedule rolls forward
  to the next opening without collecting. The operator's own refresh is never
  suppressed this way: the window suppresses the schedule, not the operator."
  (:require [odato.access.interface :as access]
            [odato.collector.core :as core]
            [odato.collector.cycle :as cycle*]
            [odato.config.interface :as config]
            [odato.db.interface :as db]
            [odato.time.interface :as t]))

(defn current [sys]
  (:schedule/next-run-at
   (db/entity-map (db/db (core/conn sys)) :schedule/id :collection)))

(defn- set-next! [sys at]
  (db/put! (core/conn sys) :schedule/id :collection {:schedule/next-run-at at}))

(defn tick!
  "One turn of the clock. Answers what it did, which is what the tests read."
  [sys]
  (access/replenish-due! sys)
  (let [in-force (config/current sys)
        now (core/now sys)
        due (current sys)]
    (cond
      (nil? due) (do (set-next! sys (+ now (:refresh-cadence-ms in-force))) :scheduled)

      (> (long due) now) :waiting

      (t/within-collection-window? now (:window in-force))
      (do (set-next! sys (+ now (:refresh-cadence-ms in-force)))
          (cycle*/start! sys :scheduled)
          :collecting)

      :else
      (do (set-next! sys (t/next-window-opening now (:window in-force)))
          :outside-window))))
