(ns odato.github.protocol)

(defprotocol GithubClient
  (-whoami [this])
  (-fetch-pull-requests [this request])
  (-fetch-activity [this request]))

(defprotocol GithubRepositories
  "What owldesk asks of a repository: whether it can be read, one pull
  request, and the branches and files of the results repository."
  (-repository [this request])
  (-file-exists [this request])
  (-pull-request [this request])
  (-branches [this request])
  (-tree [this request])
  (-blob [this request])
  (-delete-branch [this request]))
