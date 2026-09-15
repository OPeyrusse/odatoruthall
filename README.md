# odatoruthall

One dashboard to rule them all.

Two applications, one server, one operator:

- **gitdesk** — an in-flight pull request dashboard for a chosen set of GitHub
  repositories and a chosen set of team members, with counts and
  classifications. The backend holds the GitHub data in memory; the
  configuration outlives the process.
- **worklog** — the operator's own work: a sliding window of the last five
  working days, the tasks worked on each of them, the lines that led to each
  task, and the time logged against it through the Jira worklog API.

`specs/` is the primary artefact. The `.allium` files say what the installation
does, in domain terms; this code implements them. Read the spec before changing
behaviour, and change it in the same commit when behaviour changes.

## Running it

**With no credential at all.** The installation ships mock GitHub and Jira
services, and scripted data that puts something under every classification the
specification names:

```sh
npm install && npm run assets      # once: the stylesheet and the Datastar bundle
ODATO_DEMO=1 clojure -M:dev:run    # http://localhost:7777
```

**Against the real services.** Copy `.env.example` to `.env` and fill it in:

```sh
GITHUB_TOKEN=…            # its owner is the operator: "me" is the token's owner
JIRA_BASE_URL=https://your-site.atlassian.net
JIRA_EMAIL=… JIRA_TOKEN=…
ODATO_DB_FILE=./data/odatoruthall.edn   # where the worklog is kept
clojure -M:dev:run
```

A credential that was never offered leaves its badge saying so rather than
making the dashboard fail: gitdesk works with GitHub alone, and worklog says
through the Jira badge that it cannot log a minute without Jira.

**In Docker.**

```sh
docker compose up --build              # with .env
ODATO_DEMO=1 docker compose up --build # with the scripted data
```

## Building

```sh
./scripts/build-jar.sh     # projects/server/target/odatoruthall.jar
./scripts/smoke-jar.sh     # starts it on the scripted data and checks both apps answer
clojure -M:dev:test        # every brick's tests
./scripts/check-specs.sh   # the Allium gate (needs `cargo install allium-cli`)
npm run assets             # rebuild the stylesheet and the Datastar bundle
```

Both built assets are committed, so the jar and the image build without Node.
Rebuild them when a view uses a Tailwind class it has not used before.

## The workspace

Polylith, laid out one component per specification slice. It is plain
`tools.deps` — the `poly` tool is a convenience, not a requirement.

| Brick | Owns | Spec slice |
|---|---|---|
| `components/time` | UTC days, the collection window, the timesheet window, durations typed as text | — |
| `components/db` | the two DataScript databases and the line between them | — |
| `components/events` | the bus the surfaces are patched from | — |
| `components/http` | a JSON client over `java.net.http` | — |
| `components/access` | credentials, their four states, the GitHub budget | `shared/access` |
| `components/config` | what is watched, and when a change takes effect | `shared/config` |
| `components/github` | the GitHub seam: a real client and a scripted mock | — |
| `components/jira` | the Jira seam: a real client and a scripted mock | — |
| `components/demo` | the scripted data an installation with no credential runs on | — |
| `components/pull-request` | the facts, and every classification read off them | `gitdesk/pull-request` |
| `components/collector` | the collection cycle: queue, pages, quota waits, window | `gitdesk/collection` |
| `components/work-item` | a key becomes a Jira issue, or is reported as one Jira refused | `worklog/work-item` |
| `components/worklog` | days, entries, and what it means for time to reach Jira | `worklog/worklog` |
| `components/discovery` | the day scan, attribution, and the orphans it keeps | `worklog/discovery` |
| `components/ui` | every page and fragment | `gitdesk/dashboard`, `worklog/timesheet`, `shared/shell` |
| `bases/server` | the routes, the event stream, the process | — |
| `projects/server` | the one deployable artefact | — |

## How it is built, and why

**Two databases, not one.** The specification draws the line itself: the
configuration and the worklog outlive the process, and everything gitdesk
collects dies with it and is rebuilt by the collection that starts with the
process. So `components/db` opens a `:durable` connection and a `:volatile`
one. Both are DataScript, in memory. Give the store a file and the durable half
is written there after every transaction and read back at startup — which is
where a persistent engine goes when one arrives. Turso is that engine, and it
is not wired yet.

**Virtual threads.** A collection cycle and a day scan each run on one virtual
thread and block on every page they read. That is what lets the code read the
way the specification does — one source at a time, paginating to exhaustion,
and a quota wait that is simply a wait — without a thread pool sized against
the number of repositories being watched.

**Datastar, no JSON API.** The server already owns the snapshot and the
worklog, so it renders them and pushes the changes: each dashboard has one
fragment that an open event stream patches in place as sources land. Every
command is a plain form post that works without JavaScript; with it, Datastar
makes the same post and the server answers 204 because the stream will do the
patching.

**Mock services are first-class.** `odato.github.mock` and `odato.jira.mock`
answer from a script: pages, per-source failures, a budget that can be made to
run out, and worklogs that are remembered so a day submitted twice can be shown
to log the time once. Every test runs against them, and so does `ODATO_DEMO=1`.

**Java 26.** The image and CI run it. Nothing in the code needs more than 21.

## What is not done

- **Turso.** The persistent database is the file seam in `components/db` and
  nothing more. Swapping it is one namespace.
- **Time logged outside this installation** is not read back, so a day's total
  is what this installation knows about rather than what Jira holds — the open
  question the specification parks in `worklog/worklog`.
- **A comment on a worklog.** An entry carries a duration and no note.
