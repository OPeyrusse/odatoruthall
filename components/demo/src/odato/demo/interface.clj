(ns odato.demo.interface
  "An installation with nothing to connect to.

  Running without a GitHub or a Jira token is a first-class way to use this:
  it is how the dashboard is looked at before anyone has decided to give it
  credentials, and how the mock clients are exercised outside the tests. The
  data below is scripted so that every classification the specification names
  has something under it — a stuck pull request, an aging one, a merge train,
  a day with lines that group under a ticket and one loose end."
  (:require [odato.demo.data :as data]))

(defn github-script
  "What the mock GitHub answers, relative to `now-ms`."
  [now-ms]
  (data/github-script now-ms))

(defn jira-script
  "What the mock Jira answers, relative to `now-ms`."
  [now-ms]
  (data/jira-script now-ms))

(def configuration
  "What a demo installation watches."
  data/configuration)
