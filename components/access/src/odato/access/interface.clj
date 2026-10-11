(ns odato.access.interface
  "Who operates this installation, which services it reaches, and the state of
  the credentials that reach them.

  One installation, one operator: one GitHub credential, one Jira credential.
  \"Me\" is always the owner of the valid GitHub credential.

  A credential's presence and validity are observable, and they change what the
  operator sees — the badges in the shell, whether a collection may start,
  whether a day may be scanned. How a credential arrives is not this
  component's business; that it was accepted, refused or never offered is."
  (:require [odato.access.core :as core]))

;; The states a credential can be in: absent, unverified, valid, rejected.
(def connection-statuses core/connection-statuses)

;; Why a service said no: credential-rejected, service-unreachable, service-error.
(def access-failures core/access-failures)

(defn ensure-defaults!
  "A fresh installation holds two connections, both absent, and a GitHub budget
  nobody has observed yet."
  [sys]
  (core/ensure-defaults! sys))

(defn connection
  "The connection for `:github` or `:jira`, as it stands."
  [sys service]
  (core/connection sys service))

(defn connections [sys] (core/connections sys))

(defn usable?
  "Whether this service can be reached with the credential in hand."
  [sys service]
  (core/usable? sys service))

(defn operator-login
  "The operator: the owner of the valid GitHub credential, or nil while there
  is not one."
  [sys]
  (core/operator-login sys))

(defn present-credential!
  "A credential has been offered. Absent becomes unverified, and the connection
  asks to be checked."
  [sys service]
  (core/present-credential! sys service))

(defn verified!
  "The service accepted the credential and named its owner."
  [sys service login]
  (core/verified! sys service login))

(defn refused!
  "The service refused the credential, or did not answer."
  [sys service failure]
  (core/refused! sys service failure))

(defn check-connection!
  "Presents the credential and exercises it against `client`, which answers
  `{:login ...}` or `{:failure ...}`. This is the whole connection check."
  [sys service client-whoami]
  (core/check-connection! sys service client-whoami))

;; ------------------------------------------------------------- GitHub budget

(defn budget [sys] (core/budget sys))

(defn budget-available? [sys] (core/budget-available? sys))

(defn budget-observed!
  "GitHub reports the remaining budget and its reset time on every answer.
  Running out suspends collection; it does not fail it."
  [sys remaining resets-at]
  (core/budget-observed! sys remaining resets-at))

(defn replenish-due!
  "Called by whatever watches the clock: an exhausted budget whose reset time
  has passed is available again, and the installation may start asking."
  [sys]
  (core/replenish-due! sys))

(defn await-budget!
  "Blocks the calling (virtual) thread until the budget is available again.
  Returns true once it is, false if the wait was interrupted."
  [sys]
  (core/await-budget! sys))
