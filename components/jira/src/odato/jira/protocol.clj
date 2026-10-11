(ns odato.jira.protocol)

(defprotocol JiraClient
  (-whoami [this])
  (-fetch-issue [this issue-key])
  (-fetch-activity [this request])
  (-fetch-task-time [this request])
  (-write-worklog [this request])
  (-delete-worklog [this request]))
