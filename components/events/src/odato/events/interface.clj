(ns odato.events.interface
  "A bus for what just happened.

  The specification emits: `CollectionCompleted`, `SourceRefreshed`,
  `TimeReachedJira`, `ScanCompleted`. Nothing in the domain cares who is
  listening — the server does, because a listener is how a rendered page is
  patched in place while a collection runs."
  (:require [odato.events.core :as core]))

(defn bus [] (core/bus))

(defn publish!
  "Announces `{:type ... }`. Never throws at the caller: a listener that blows
  up is the listener's problem, not the collection's."
  [bus event]
  (core/publish! bus event))

(defn subscribe!
  "Registers `f` under `k`. Returns a zero-argument function that removes it."
  [bus k f]
  (core/subscribe! bus k f))

(defn unsubscribe! [bus k] (core/unsubscribe! bus k))

(defn recent
  "The last events the bus saw, oldest first. Tests read it; so does the
  activity log on the dashboard."
  [bus]
  (core/recent bus))
