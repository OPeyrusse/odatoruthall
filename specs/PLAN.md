# Plan — writing the Allium specifications

Two applications live in this repository and ship as one server:

- **gitdesk** — an in-flight pull request dashboard for a chosen set of
  GitHub repositories and a chosen set of team members, with counts and
  classifications. Modelled on the
  [reading-buffer/gitdesk](https://gitlab.com/kineolyan/reading-buffer/-/tree/main/src/gitdesk)
  SPA, but inverted: the **backend** holds the GitHub data in memory and the
  **configuration** is persisted in a database.
- **worklog** — managing the operator's own work and logging time to Jira
  through the worklog API. Its behaviour is specified in a second pass; this
  plan reserves its slot and its seams.

The specification is the primary artefact. This document says *what to write,
in what order, and where the seams are* — it is not itself a specification and
carries no normative weight.

## 1. What belongs in the spec, and what does not

Allium describes observable behaviour in domain terms. So:

**In the spec.** The refresh cycle and what a viewer sees while it runs; the
PR classifications (aging, stuck, conflicting, difficult, merging, merged) and
the thresholds that drive them; who counts as "of interest"; what the
configuration is, how it is validated and that it survives a restart; that the
collected GitHub data does *not* survive a restart; behaviour when a token is
missing, rejected, or the rate limit is exhausted; the worklog lifecycle and
what it means for an entry to be submitted to Jira.

**Out of the spec**, recorded in §6 instead: Polylith component layout, the
choice of database, HTTP route shapes, the Docker image, how the tokens reach
the process. `GITHUB_TOKEN` and `JIRA_TOKEN` appear in the spec only as
external entities whose *presence and validity* change observable behaviour —
never as environment variables.

## 2. Slice layout

One `.allium` file per bounded area, wired with `use`. Files are listed in
dependency order — producers before consumers.

| File | Owns | Uses |
|---|---|---|
| `specs/shared/access.allium` | The single `Operator` actor, `GithubAccess` / `JiraAccess` external entities, connection-check rules, credential states (absent / unverified / valid / rejected), rate-limit budget | — |
| `specs/shared/config.allium` | `Configuration` entity (tracked repositories, tracked members, refresh cadence, thresholds, display preferences), validation rules, persistence guarantee across restarts, the settings surface | `access` |
| `specs/shared/shell.allium` | The single entry surface both applications hang off: navigation between the two dashboards, connection badges, `related` edges | `config`, `access`, both app surfaces |
| `specs/gitdesk/pull-request.allium` | `PullRequest` entity and variants — author, assignees, reviews, commits, check rollup, labels, draft, mergeable, auto-merge, comment count — plus every derived classification | `config` |
| `specs/gitdesk/collection.allium` | The refresh cycle: the fetch queue, per-source collection (repository PRs, mentions, review requests, recently merged), cursor pagination, per-source success/failure, snapshot freshness, scheduled refresh, rate-limit back-off | `access`, `config`, `pull-request` |
| `specs/gitdesk/dashboard.allium` | Surfaces and counts: *My work* (authored / to review / assigned / addressed), *Monitoring* (stuck / aging / conflicting / difficult), *Merge train* (merging / just merged), *Team work* per tracked member, the list views with their ordering and the draft toggle | `pull-request`, `collection` |
| `specs/worklog/*.allium` | Specified in the second pass — expected shape in §4 | `access`, `config` |

Rationale for the split: `pull-request` is pure classification and is the file
tests will hammer hardest; `collection` is where every failure mode and
temporal rule lives; `dashboard` is boundary-only. Keeping them apart means a
threshold change touches one file.

## 3. gitdesk — the substance to capture

Harvested from the reference implementation; each item becomes a rule, a
derived field or a config parameter.

**Configuration** (persisted; the inversion from the SPA, where it sat in
local storage): one global list of tracked repositories as `owner/name`, one
general list of tracked members (logins) — members are not scoped per
repository — the refresh cadence (**60 minutes by default**), the collection
window, the show-drafts preference, and the classification thresholds. One
configuration for the whole installation. Validation: non-empty repository
list, non-empty member list, cadence within bounds.

Configuration splits in two by *when it takes effect*, and the spec says so:
**collection settings** (repositories, members, cadence, collection window)
shape the next collection and nothing else; **presentation settings** (thresholds,
show-drafts) apply to the snapshot already held, so changing a threshold
reclassifies what is on screen without fetching anything.

**Collection** (in memory; lost on restart, rebuilt by the first refresh).
There is exactly **one snapshot**, owned by the server and shared by every
view of it:

- Four sources — open PRs per tracked repository, PRs mentioning the operator,
  PRs with a review requested from the operator, PRs merged within a recent
  window.
- Sources are fetched through a queue, one at a time, each paginating to
  exhaustion before the next starts.
- A PR is *of interest* when its author or one of its assignees is the
  operator or a tracked member.
- **Refresh is the server's own business**: it runs on the configured cadence
  whether or not anyone is watching, and the operator can also trigger one on
  demand. A manual refresh while one is already running does not start a
  second.
- **The server collects at startup**, so the snapshot is filled without
  waiting for the first cadence tick. Until the first source returns, the
  dashboard says it is collecting rather than showing an empty state.
- **Collection window.** The scheduled refresh only starts between **05:00
  and 21:00 UTC**, Monday to Friday — nothing at night, nothing at the
  weekend. All times are UTC, so the behaviour does not depend on where the
  container runs. The bounds and the active days are config parameters with
  those values as defaults. A manual refresh is always honoured, inside the
  window or out — the window suppresses the schedule, not the operator.
- **A collection in progress runs to completion**, even when the window
  closes underneath it; the window governs when a cycle *starts*.
- **Saving the configuration never triggers a collection.** The operator
  refreshes manually when they want the change reflected, so a repository
  removed from the list keeps its PRs on screen until the next collection.
- **A failure never destroys what was collected.** Each source keeps its last
  successful result and the time it was obtained; a failed collection leaves
  that data in place and records the failure. The dashboard therefore always
  shows the last known good state, alongside how old it is and which sources
  failed to refresh.
- **Quota exhaustion suspends, it does not fail.** Each response reports the
  remaining API budget and when it replenishes; when the budget runs out the
  cycle waits for the reset and resumes from where it stopped, keeping
  everything collected so far. The dashboard says the collection is waiting on
  quota rather than reporting an error.

**Classification** (the heart of the spec):

- *green*: the newest commit's check rollup is success / failure / pending.
- *approved*: the last review per author, keeping only approvals and
  change requests.
- *aging*: open longer than the aging threshold (15 days in the reference).
- *conflicting*: at least the comment threshold (15).
- *difficult*: at least the commit threshold (10) and approved.
- *stuck*: approved, and the last N commits fail their checks (10).
- *merging*: not a draft, mergeable, auto-merge enabled, every review
  approving.
- *merged*: merged inside the recent window (3 days).

Every threshold becomes a `config` parameter with the reference value as its
default.

**Surfaces**: the four stat groups above, the per-member counts (authored /
drafted / assigned), and the list views with their orderings. Drafts are
counted but hidden unless the preference is on.

## 4. worklog — the reserved slot

Awaiting your second comment. Expected to need: a work item entity mirroring
the Jira issue the operator works on, a `WorklogEntry` with a lifecycle
(drafted → submitted → acknowledged / rejected), rules for the submission to
the Jira worklog API and its failure modes, a day/week view with totals, and
a surface for entering and correcting time. The seams to `shared/access`
(Jira credential states) and `shared/config` are already in place, so the
slice can be written without disturbing gitdesk.

## 5. Method and sequence

Spec-first: there is no code to distil from, so each slice is built with
`/elicit`, checked, then assembled.

1. `shared/access` + `shared/config` — the vocabulary everything else
   references, including the single-operator identity and the refresh
   cadence.
2. `gitdesk/pull-request` — entity and classifications, thresholds to config.
3. `gitdesk/collection` — the cycle, failures, freshness, budget.
4. `gitdesk/dashboard` — surfaces and counts.
5. `shared/shell` — the two applications under one navigation.
6. `worklog/*` — after the second comment.
7. Assembly: `allium analyse specs` over the whole set, reconcile the seams
   (unknown names, missing producers, deadlocks) with `/tend`, then `/witness`.

After every slice: `./scripts/check-specs.sh` (the editor hook already runs
`allium check` on each write; CI gates the whole directory). The checker is
`cargo install allium-cli --version 3.6.1`, matching the pinned CI version —
it is not installed in this environment yet.

Tests come later, from `/propagate` over the assembled set, once the seams are
clean. Implementation follows the tests.

## 6. Architecture notes (deliberately outside the spec)

Recorded here so the decisions are not lost, and so nothing leaks into the
`.allium` files:

- **Clojure + Polylith.** One workspace; roughly one component per spec slice
  (`config-store`, `github-client`, `pr-domain`, `pr-collector`,
  `jira-client`, `worklog-domain`), one `http-server` base serving both
  applications, and a single `server` project so the two ship together and
  share a navigation shell.
- **State.** GitHub data in a process-level store, rebuilt by the refresh
  cycle; configuration in a database (engine still to pick — an embedded one
  keeps the single-container deployment honest).
- **Secrets.** `GITHUB_TOKEN` and `JIRA_TOKEN` from the environment, never
  persisted with the configuration.
- **Packaging.** One Docker image running the single server locally.

## 7. Decisions taken

These are settled and are written as behaviour, not as open questions:

- **One operator.** The installation serves a single user: one GitHub token,
  one Jira token, one configuration, one snapshot. "Me" is always the token
  owner, so no rule needs to distinguish a viewer from the operator.
- **Server-owned refresh.** The server refreshes on the cadence held in its
  configuration — 60 minutes by default — independently of anyone watching,
  and exposes a manual refresh the operator can trigger at any time.
- **Failures preserve data.** A failed collection keeps the previously
  collected data and reports the failure; the dashboard shows the last known
  good state and its age.
- **Collection at startup, then on a schedule bounded by a window.** The
  server collects as soon as it starts and shows a "collecting" state until
  the first result lands. Scheduled refreshes start only between 05:00 and
  21:00 UTC on weekdays; a cycle already running when the window closes
  finishes. Manual refresh is always available.
- **Quota exhaustion is a wait, not a failure.** The cycle pauses until the
  GitHub budget replenishes and then continues.
- **One global repository list, one general member list** — members are not
  scoped per repository.
- **Configuration changes do not refresh.** Collection settings take effect
  at the next collection, which the operator triggers; presentation settings
  (thresholds, drafts) apply immediately to the snapshot in hand.

## 8. Questions still open

None for gitdesk: every question this plan raised is answered in §7, so the
slices can be written without parking an `open question`. New ones will
surface while eliciting — that is what the section is for — and worklog
brings its own once its behaviour is described.
