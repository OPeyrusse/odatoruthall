# Plan — writing the Allium specifications

Two applications live in this repository and ship as one server:

- **gitdesk** — an in-flight pull request dashboard for a chosen set of
  GitHub repositories and a chosen set of team members, with counts and
  classifications. Modelled on the
  [reading-buffer/gitdesk](https://gitlab.com/kineolyan/reading-buffer/-/tree/main/src/gitdesk)
  SPA, but inverted: the **backend** holds the GitHub data in memory and the
  **configuration** is persisted in a database.
- **worklog** — managing the operator's own work and logging time to Jira
  through the worklog API: a sliding window of the last five working days, the
  tasks worked on each of them, and the time the operator puts against each
  task.

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
| `specs/worklog/work-item.allium` | `WorkItem` entity (a Jira issue the operator logs against), the lookup of a key and its failures | `access` |
| `specs/worklog/worklog.allium` | `WorkDay` and `WorklogEntry` — the day, the tasks on it, the time as typed, the entry lifecycle, submission to the Jira worklog API and its failure modes | `access`, `work-item` |
| `specs/worklog/discovery.allium` | The day scan: the GitHub and Jira sources, the queue, attribution of an activity to an issue key, quota waits, what a scan may and may not touch | `access`, `work-item`, `worklog` |
| `specs/worklog/timesheet.allium` | Surfaces: the five-day window with its totals, one day in detail with its loose ends, the persistence guarantee | all three |
| `specs/owldesk/review.allium` | Automated reviews run by Owllie: the review settings and the results repository check, launching a review, reading its results branch, the derived state of a review, judging findings, cleanup | `access`, `config` |
| `specs/owldesk/tuning.allium` | The operator's remarks on a review for Owllie: drafting, editing and sending them in one batch, the tuning run read from its own branch, its pull request on Owllie's rules and merging it | `access`, `config`, `review` |
| `specs/owldesk/owldesk.allium` | Surfaces: every review grouped by pull request, one review in full with its remarks section, one tuning in full, the review settings panel | `access`, `config`, `review`, `tuning` |

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
window, and the classification thresholds. One
configuration for the whole installation. Validation: non-empty repository
list, non-empty member list, cadence within bounds.

Configuration splits in two by *when it takes effect*, and the spec says so:
**collection settings** (repositories, members, cadence, collection window)
shape the next collection and nothing else; **presentation settings**
(thresholds) apply to the snapshot already held, so changing a
threshold reclassifies what is on screen without fetching anything.

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
counted but hidden until the list's own toggle shows them.

## 4. worklog — the substance to capture

**The window.** The last five working days, Monday to Friday, in UTC — today
included when today is a working day, otherwise the five working days that
precede it. It is derived from the clock on every read, never stored, so it
slides on its own. Every day in it can be filled, not only today: filling
yesterday, or last Thursday, is the ordinary case rather than a correction.

**The day.** One `WorkDay` per UTC date, holding one entry per task worked
that day, plus the day's total. A day with nothing on it shows zero rather
than being absent. A day also remembers when it was last scanned — a day
scanned and empty is a different thing from a day never scanned.

**The entry.** One task, one day, one duration, and the text that produced it.
The operator types "1h 30m"; the text is kept as typed and the duration it
means is kept beside it, so the timesheet reads back the way it was filled.
Text that says no length of time is refused and changes nothing.

**The lifecycle.** Time is drafted locally and reaches Jira only when the
operator submits the day: `drafted → submitted → acknowledged | rejected`, and
editing an acknowledged entry drafts it again while keeping the Jira worklog it
already has, so a re-submission updates that worklog instead of logging the
time twice. A rejection keeps the entered time and can be submitted again.
Removing an entry Jira holds withdraws the worklog from Jira first.

**Discovery.** The operator picks a day and asks for it to be scanned. The scan
reads the operator's own activity on that day — on GitHub, their commits, their
comments, the pull requests they opened and the ones they reviewed; on Jira,
the comments they wrote, the issues they created and the issues they closed —
one source at a time, paginating to exhaustion, with quota exhaustion a wait
rather than a failure, exactly as gitdesk collects. Every activity names the
Jira issues it belongs to; each named issue becomes a task on that day with no
time on it.

Activities belong to the *day*, not to the scan that found them: the day keeps
them, a second scan adds only what it has not already recorded, and they
survive a restart along with the rest of the worklog. Several activities
commonly name the same issue — two pull requests, a review, three comments —
and they stay separate, each with its own title and moment.

An activity naming no issue, or naming one Jira does not know, is kept as an
*orphan*, with its title, and the operator attaches it to a task from the day
view. Attaching goes through the same path as a key the scan read: the task
lands on the day if it was not there, and the activity joins the lines grouped
under it.

**Surfaces.** The five days side by side with their totals and their
breakdowns; one day in detail, read as one row per task — the ticket, its
single time field, and the lines that led to it grouped underneath, with the
orphans apart; the commands to name a task, enter time, remove an entry,
attach an orphan, submit a day and scan a day.

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
6. `worklog/work-item` → `worklog/worklog` → `worklog/discovery` →
   `worklog/timesheet`, then the worklog edge added to `shared/shell`.
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
- **UI: Datastar.** The interface is server-rendered HTML made dynamic with
  [Datastar](https://data-star.dev) — `data-*` attributes for signals and
  actions, and SSE from the server patching fragments in place. No SPA, no
  client-side store, no JSON API of its own: the server already owns the
  snapshot and the worklog, so it renders them and pushes the changes.
  It suits both applications: a collection cycle patches the gitdesk counts as
  its sources land, a day scan patches the timesheet as the tasks and the lines
  appear under their tickets, and typing "1h 30m" posts the field and gets the
  re-rendered day total back. Nothing of this reaches the spec — the spec says
  what is exposed and what commands exist, never how they travel.
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

These settle worklog:

- **Drafted here, submitted on the operator's word.** Nothing reaches Jira
  while the operator types; a day is submitted deliberately, and each entry
  says whether Jira has its time yet.
- **UTC days.** A day is a UTC calendar day, like the collection window, so
  the whole installation has one rule about time.
- **Five working days, today included.** Monday to Friday, sliding, derived
  from the clock. The window bounds what can be filled, so a day that has
  left it is settled.
- **Scanning is asked for, one day at a time.** Nothing scans on a schedule.
  The operator chooses the day from the timesheet and asks for it.
- **A scan only ever adds.** It never removes a task or an activity from a
  day, never records the same activity twice, and never touches entered,
  submitted or acknowledged time — which is what makes it safe to scan the
  same day twice.
- **Activities belong to the day and are grouped by ticket.** Several
  activities on one ticket are one row with one time field; the activities are
  the lines under it, carrying no time of their own, and are what the operator
  reads to decide how long the ticket took.
- **Orphans are kept, not dropped.** An activity naming no issue Jira knows
  keeps its title and can be attached to a task from the day view.
- **The worklog outlives the process.** Days, tasks, entered text, the lines a
  scan found and what Jira acknowledged survive a restart — unlike anything
  gitdesk collects. The lines a total was estimated from are still there
  tomorrow.
- **Worklog parameters are spec constants, not settings.** The number of days,
  the working days and the bounds on one entry live in the worklog slice's
  `config` block; the operator's `Settings` surface stays gitdesk's.

## 7bis. owldesk — decisions taken

- **The branch is the run.** Owllie writes each run to its own orphan branch
  `review/<owner>/<repo>/<number>/<run id>` of a results repository, following
  the layout in Owllie's `shared/run-layout.md`. The installation reads it and
  keeps only the launch (asked when, what the launcher answered) and the
  operator's judgement of findings, both durable.
- **The results repository is a setting**, checked (readable, carrying the
  `.owllie-results` marker) before any review is launched towards it.
- **Two launchers**, picked in the settings: a Claude scheduled task fired
  through its API trigger (`OWLLIE_FIRE_URL`, `OWLLIE_FIRE_TOKEN`), or the
  Claude CLI on the installation's machine (`OWLLIE_CLI_DIR`, a clone of
  Owllie). Both start a cloud session; nothing runs locally.
- **Cleanup is asked for**, previewed, and needs its own credential
  (`OWLLIE_RESULTS_TOKEN`) that may delete branches of the results repository;
  `GITHUB_TOKEN` stays read-only.
- **The reader is generic.** The branch says what the run is made of
  (`process.json`: phases in sequence, steps in parallel, each naming the file
  it writes when it ends) and where it stands (`status.json`). Each step is a
  list of generic blocks — markdown, code, stats, table, findings — or plain
  markdown. owldesk knows nothing else of Owllie, so the review can change
  without owldesk changing.
- **Timeouts are spec constants** (start timeout, stall threshold, reading
  intervals), to be tuned once real runs have been timed.

## 7ter. owldesk tuning — decisions taken

- **Remarks are drafts, gathered per review, sent in one batch.** Six kinds:
  false positive, relevant, wrong severity and unclear on a finding; missed on
  lines of the diff; general on the review. A sent remark never changes; to
  send it again, the operator copies it into a new draft.
- **One batch, one tuning run, one pull request on Owllie.** The request is
  Owllie's `OWLLIE_TUNE v1` (`shared/tune-layout.md` there). It carries the
  remarks and the commit the review read, never the code: the session reads
  the code from the reviewed repository, anonymizes and generalizes it, and
  pushes no reviewed code to Owllie's repository.
- **A tuning is read like a review**, from its own branch `tune/<run id>` of
  the results repository, with the same reader. The branch names the pull
  request; the pull request is then read until it is merged or closed.
- **Merging from owldesk** needs its own credential (`OWLLIE_BRAIN_TOKEN`),
  the only write to Owllie's repository; `GITHUB_TOKEN` stays read-only.
- **The code comes from the review's diff.** Owllie writes the diff it read
  as a `diff` block of the inputs step, and the commit it read in
  `process.json`. owldesk shows findings in context from that diff, lets the
  operator point a missed remark at its lines, and copies the excerpt into the
  remark so it outlives the branch.

## 8. Questions still open

None for gitdesk: every question this plan raised is answered in §7, so the
slices can be written without parking an `open question`.

One the worklog slice deliberately leaves for later, because it does not
change the shape of what is written:

- **A comment on a worklog.** Jira worklogs can carry a note; an entry here
  carries only a duration. Adding one is a field and a rule, not a new shape.

**Settled since.** *Time logged outside this installation* was parked here and
is now answered: a day scan reads the operator's own worklogs for that day, so
a day's total is what Jira holds plus what they have typed and not sent. The
two writers are reconciled by never mixing them — an entry holding nothing the
operator typed takes Jira's time, one holding their own keeps it and says
Jira's beside it, and a task whose time Jira holds under several worklogs is
read here and changed in Jira.
