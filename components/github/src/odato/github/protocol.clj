(ns odato.github.protocol)

(defprotocol GithubClient
  (-whoami [this])
  (-fetch-pull-requests [this request])
  (-fetch-activity [this request]))
