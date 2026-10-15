(ns odato.config.interface
  "What the operator tells the installation to watch, how often to look, and
  where the lines between \"fine\" and \"worth noticing\" fall.

  One configuration for the whole installation, and it outlives the process.

  It divides in two by *when it takes effect*:

    collection settings    tracked repositories, tracked members, cadence, the
                           collection window. They shape the next collection
                           and nothing else.
    presentation settings  the classification thresholds. They apply to the
                           snapshot already in hand, so changing one
                           reclassifies what is on screen without fetching
                           anything.

  Saving never starts a collection."
  (:require [odato.config.core :as core]))

;; The values a fresh installation starts with, and the bounds a saved
;; configuration is held to. They are those of the dashboard this application
;; is modelled on.
(def defaults core/defaults)

(defn ensure-defaults! [sys] (core/ensure-defaults! sys))

(defn current
  "The configuration in force."
  [sys]
  (core/current sys))

(defn collectable?
  "Whether there is anything to collect: at least one repository and at least
  one member."
  [sys]
  (core/collectable? sys))

(defn save!
  "OperatorSavesConfiguration. Answers `{:ok true :config ...}`, or
  `{:ok false :fault ...}` — and a refused save changes nothing: the
  configuration in force is the one that was already in force.

  A save that changed a collection setting announces
  `:config/collection-settings-changed`; one that changed a presentation
  setting announces `:config/presentation-settings-changed`. Neither starts a
  collection."
  [sys proposal]
  (core/save! sys proposal))

(defn validate
  "The fault a proposal would be refused for, or nil."
  [proposal]
  (core/validate proposal))

;; ------------------------------------------------------------- repositories

(defn parse-repository
  "`\"owner/name\"` as `{:owner ... :name ...}`, or nil."
  [s]
  (core/parse-repository s))

(defn repository-key
  "`{:owner ... :name ...}` back as `\"owner/name\"`."
  [repository]
  (core/repository-key repository))

(defn parse-repositories [lines] (core/parse-repositories lines))
(defn parse-members [lines] (core/parse-members lines))
(defn parse-days [days] (core/parse-days days))

(def day-order core/day-order)
