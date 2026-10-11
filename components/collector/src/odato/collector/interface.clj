(ns odato.collector.interface
  "How the installation fills its snapshot from GitHub, and what the operator
  sees while it does.

  There is exactly one snapshot, it belongs to the installation rather than to
  a viewer, and it does not survive a restart. Four sources feed it — the open
  pull requests of each tracked repository, the ones mentioning the operator,
  the ones awaiting the operator's review, and the ones merged recently. They
  are collected one at a time, each paginating to exhaustion before the next
  starts.

  Three promises hold throughout:

    * a failure never destroys what was collected — each source keeps its last
      successful result and the time it was obtained;
    * quota exhaustion is a wait, not a failure — the cycle stops asking until
      the budget replenishes, then resumes at the cursor it stopped on;
    * a cycle that has started runs to completion, even if the collection
      window closes underneath it."
  (:require [odato.collector.core :as core]
            [odato.collector.cycle :as cycle*]
            [odato.collector.schedule :as schedule]))

(defn server-started!
  "ServerStarted. Nothing collected crosses a restart: the snapshot is emptied,
  the schedule is set a cadence ahead, and a startup collection is asked for so
  the snapshot fills without waiting for the first tick."
  [sys]
  (cycle*/server-started! sys))

(defn request-collection!
  "CollectionRequested. Answers `{:started true :cycle <id>}`, or
  `{:started false :reason :credential-unavailable|:nothing-tracked|:already-collecting}`.

  A manual refresh while a cycle is already running does not start a second
  one; the running cycle carries on."
  [sys kind]
  (cycle*/request-collection! sys kind))

(defn run-cycle!
  "Runs a cycle to completion on the calling thread. The server calls it on a
  virtual thread; a test calls it directly."
  [sys cycle-id]
  (cycle*/run-cycle! sys cycle-id))

(defn collect!
  "Requests a collection and runs it here and now."
  [sys kind]
  (cycle*/collect! sys kind))

(defn start!
  "Requests a collection and runs it on a virtual thread, so the caller — an
  HTTP request, the scheduler, a credential that has just become valid — is not
  held for the length of it."
  [sys kind]
  (cycle*/start! sys kind))

(defn tick!
  "What the clock is for. Rolls the schedule forward when the cadence comes
  round — collecting inside the collection window, and only moving the next run
  when outside it — and lets an exhausted budget replenish."
  [sys]
  (schedule/tick! sys))

(defn schedule
  "When the next scheduled collection is due."
  [sys]
  (schedule/current sys))

;; ------------------------------------------------------------------ reading

(defn snapshot
  "The snapshot as it stands: its sources, how old it is, and what failed."
  [sys]
  (core/snapshot sys))

(defn sources [sys] (core/sources sys))

(defn active-cycles
  "The cycles running, with what they are waiting on."
  [sys]
  (core/active-cycles sys))

(defn collecting?
  "Whether a cycle is running."
  [sys]
  (core/collecting? sys))

(defn pull-requests
  "Every collected pull request, classified against the configuration in force
  and the clock as it stands. This is what every dashboard count reads."
  [sys]
  (core/pull-request-views sys))
