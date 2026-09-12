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
| `specs/shared/access.allium` | `Operator` actor, `GithubAccess` / `JiraAccess` external entities, connection-check rules, credential states (absent / unverified / valid / rejected), rate-limit budget | — |
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
local storage): tracked repositories as `owner/name`, tracked members
(logins), refresh cadence, show-drafts preference, and the classification
thresholds. Validation: non-empty repository list, non-empty member list,
cadence within bounds.

**Collection** (in memory; lost on restart, rebuilt by the first refresh):

- Four sources — open PRs per tracked repository, PRs mentioning the operator,
  PRs with a review requested from the operator, PRs merged within a recent
  window.
- Sources are fetched through a queue, one at a time, each paginating to
  exhaustion before the next starts.
- A PR is *of interest* when its author or one of its assignees is the
  operator or a tracked member.
- Every source carries its own state (loading / ok / failed); a failed source
  must not empty the dashboard.
- Refresh runs on the configured cadence and on demand.
- Each response reports the remaining API budget; exhaustion is a specified
  behaviour, not an accident.

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
   references. Settle the identity question (§7) here.
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

## 7. Questions to settle (they become `open question` entries)

1. **Identity.** A token from the environment means one operator. Is "me"
   always the token owner, and is the configuration global rather than
   per-user? If several people open the dashboard, do they share one snapshot
   and one configuration?
2. **Refresh ownership.** Does the backend refresh on its own cadence
   regardless of viewers, or only while someone is watching?
3. **Cold start.** On restart, before the first refresh completes — empty
   dashboard, or a "collecting" state? Does the server refresh eagerly at
   startup?
4. **Rate limit.** When the GitHub budget runs low, does the cycle skip,
   slow down, or refuse, and what does the viewer see?
5. **Partial failure.** One repository fails while the others succeed: keep
   the previous data for that repository, or show it as unavailable?
6. **Members vs. repositories.** Are they one list each, or per-repository
   member lists?
7. **Configuration history.** Does a configuration change trigger an
   immediate refresh, and does removing a repository drop its PRs at once?
