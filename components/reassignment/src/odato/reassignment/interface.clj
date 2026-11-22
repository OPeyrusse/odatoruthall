(ns odato.reassignment.interface
  "Taking the operator off the open pull requests they have just ruled on, and
  handing a change request back to its author.

  Once a minute inside the collection window, a pass searches the open pull
  requests of the tracked repositories assigned to the operator, and edits the
  assignees of those whose latest verdict from the operator — their latest
  review that is not a comment — is newer than their latest assignment:

    approved           the operator is removed
    changes requested  the author is assigned, when a GitHub user, then the
                       operator removed

  The pass reads none of gitdesk's snapshot. It searches with the operator's
  client, `:github`, and writes with `:assigner`, the client holding the
  assignment credential; without it, or with the switch off, nothing is read
  or written."
  (:require [odato.reassignment.core :as core]))

(def pass-interval-ms core/pass-interval-ms)

(defn tick!
  "What the clock is for, a few times a minute at least. When the pass is due
  inside the collection window, it starts on the installation's executor (on
  the calling thread when there is none) and the next falls due a minute
  later; outside it, the next rolls forward to the window's opening. A pass
  still running when the next falls due is not doubled: the next starts once
  it has ended.

  Answers `:waiting`, `:passing`, `:outside-window` or `:started`."
  [sys]
  (core/tick! sys))

(defn pass!
  "One pass, here and now. Answers `{:writes [{:repository :number :url :op
  :login :failure?}]}`, `:op` being `:add` or `:remove`, in the order they
  were made; or `{:skipped reason}`, reason being `:switched-off`,
  `:no-assignment-credential`, `:github-unavailable`, `:nothing-tracked`,
  `:budget-exhausted`, or the failure that ended the search. The writes on a
  pull request stop at the first that fails."
  [sys]
  (core/pass! sys))

(defn assigner-available?
  "Whether the installation holds the assignment credential."
  [sys]
  (some? (:assigner sys)))
