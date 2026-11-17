# odatoruthall

One dashboard to rule them all.

Three applications, one server, one operator:

- **gitdesk** — an in-flight pull request dashboard for a chosen set of GitHub
  repositories and a chosen set of team members, with counts and
  classifications. The backend holds the GitHub data in memory; the
  configuration outlives the process.
- **worklog** — the operator's own work: a sliding window of the last five
  working days, the tasks worked on each of them, the lines that led to each
  task, and the time logged against it through the Jira worklog API.
- **owldesk** — automated code reviews of pull requests, run by
  [Owllie](https://github.com/OPeyrusse/owllie) in a Claude cloud session:
  paste a pull request link, follow the run while it goes, then read its
  summary and each review agent's findings, and mark them relevant or false
  positives. A pull request can be run again from its review page, which
  picks any of its runs and opens on the latest. Each run writes to its own
  branch of a results repository; the installation reads it back from there.

`specs/` is the primary artefact. The `.allium` files say what the installation
does, in domain terms; this code implements them. Read the spec before changing
behaviour, and change it in the same commit when behaviour changes.

## Credentials

The process reads everything it is told from the environment, and nothing else:
`GITHUB_TOKEN` and `JIRA_TOKEN` are never written down beside the
configuration, never persisted, and never reach a page. Their *presence and
validity* are all the domain knows about them — which is why a missing token
leaves a badge saying so instead of breaking a dashboard.

| Variable | What it is | Default |
|---|---|---|
| `GITHUB_TOKEN` | The operator's GitHub token. Its owner is the operator: "me" is always the token's owner. | — |
| `GITHUB_API_URL` | Move it for GitHub Enterprise. | `https://api.github.com` |
| `JIRA_BASE_URL` | The Jira Cloud site, e.g. `https://acme.atlassian.net`. | — |
| `JIRA_EMAIL` | The account the token belongs to. | — |
| `JIRA_TOKEN` | A Jira API token. | — |
| `TURSO_URL` | A Turso database, as the `libsql://…` URL Turso hands out. Set with `TURSO_TOKEN`, it is where the worklog lives. | unset |
| `TURSO_TOKEN` | The Turso database token. | unset |
| `TURSO_TABLE` | The table the store keeps its nodes in. | `odatoruthall` |
| `ODATO_DB_FILE` | Where the worklog is kept when Turso is not configured. Unset too, and the whole installation is in memory: the configuration and the worklog go with the process. | unset |
| `OWLLIE_FIRE_URL` | owldesk: the Fire URL of the Claude scheduled task running Owllie (its API trigger). | unset |
| `OWLLIE_FIRE_TOKEN` | owldesk: the token of that API trigger. | unset |
| `OWLLIE_TUNE_FIRE_URL` | owldesk: the Fire URL of the Claude scheduled task running Owllie's tuning (`owllie-tune`), to which the remarks on a review are sent. | unset |
| `OWLLIE_TUNE_FIRE_TOKEN` | owldesk: the token of that API trigger. | unset |
| `OWLLIE_CLI_DIR` | owldesk, instead of the scheduled tasks: a clone of Owllie from which `claude --cloud` starts the session, for reviews and tunings alike. The Claude CLI must be installed and signed in (`claude auth login`). | unset |
| `OWLLIE_CLAUDE_COMMAND` | The command that runs the Claude CLI. | `claude` |
| `OWLLIE_RESULTS_TOKEN` | owldesk: a fine-grained token with *Contents* write on the results repository only, to delete old results branches. Without it, cleanup is off and `GITHUB_TOKEN` stays read-only. | unset |
| `ODATO_DEMO` | `1` runs against scripted data: no credential is used and no request leaves the process. | `false` |
| `PORT` | The port the server listens on. | `7777` |

**Where the worklog lives.** The installation looks in that order — Turso,
then the file, then nowhere — and says which one it chose in its first lines of
log. A Turso that is configured and cannot be reached stops the process rather
than quietly falling back to the file: writing the operator's own work somewhere
they did not ask for is worse than not starting.

**Getting them.** A GitHub [personal access token](https://github.com/settings/tokens)
needs read access to the repositories being watched — `repo` on a classic
token, or *Contents*, *Pull requests*, *Metadata* (read) and *Issues* (read, for
the day scan) on a fine-grained one. A Jira token comes from
[id.atlassian.com](https://id.atlassian.com/manage-profile/security/api-tokens)
and is paired with the email of the account that made it.

**Passing them.** Copy the template and fill it in — `.env` is git-ignored:

```sh
cp .env.example .env
$EDITOR .env
```

`./scripts/run.sh` and `docker compose` both read that file. Nothing else does:
plain `clojure -M:dev:run` and plain `docker run` read the environment they are
given, so with those you export the variables yourself.

## owldesk

Setting up the Owllie side — the results repository with its
`.owllie-results` marker, the scheduled task and its API trigger — is
described in Owllie's README. Here:

1. Give the launcher to the process: `OWLLIE_FIRE_URL` and `OWLLIE_FIRE_TOKEN`
   (or `OWLLIE_CLI_DIR`). `GITHUB_TOKEN` must be able to read the results
   repository.
2. In **settings › Owllie reviews**, set the results repository (e.g.
   `activeviam/owllie-results`) and save: it is checked at once, and the
   badge in the header turns to *owllie ready*.
3. Paste a pull request link in **owldesk**, or use *Review with Owllie* on a
   pull request in gitdesk. To review it again — after a push, or with
   another brain ref — use *Run again* on its review page; the list shows one
   line per pull request with its latest run, and the review page's *Run*
   picker moves between the runs.

The results branches are read every minute while a review is in flight and
every ten minutes otherwise; a quiet results repository costs two GitHub
requests per reading (review branches, tuning branches). What you mark on
findings is kept in the installation's store, and survives the branches being
cleaned up.

### Tuning Owllie from your reviews

Each review has a **Remarks for Owllie** section: remarks on a finding (false
positive, relevant, wrong severity, unclear), on lines of the diff the review
should have flagged (a line number in the *Inputs* step starts one), or on the
review as a whole. Reviewers' comments on the pull request that fall where the
review found nothing are offered as candidate misses. Remarks stay drafts —
changed or deleted on their own page — until you send them, all at once: Owllie
anonymizes the code, writes evaluation cases and rule changes, and opens one
pull request on its own repository, which owldesk follows and links to. You
read and merge it on GitHub.

This needs Owllie's second scheduled task, `owllie-tune` (see Owllie's
`shared/tune-layout.md`), given to the process as `OWLLIE_TUNE_FIRE_URL` and
`OWLLIE_TUNE_FIRE_TOKEN`; with `OWLLIE_CLI_DIR`, the same clone serves both.

## Running it

**From the workspace, against the real services.**

```sh
npm install && npm run assets   # once: the stylesheet and the Datastar bundle
./scripts/run.sh                # loads ./.env, then starts on http://localhost:7777
```

Without `.env`, export them yourself:

```sh
GITHUB_TOKEN=ghp_… JIRA_BASE_URL=https://acme.atlassian.net \
  JIRA_EMAIL=you@acme.com JIRA_TOKEN=… ODATO_DB_FILE=./data/odatoruthall.edn \
  clojure -M:dev:run
```

**With no credential at all.** The installation ships mock GitHub and Jira
services and scripted data that puts something under every classification the
specification names:

```sh
ODATO_DEMO=1 ./scripts/run.sh   # http://localhost:7777
```

gitdesk works with GitHub alone; worklog needs Jira as well and says so through
the Jira badge rather than by failing when the operator tries to log time. So a
`GITHUB_TOKEN` on its own is a perfectly good installation with half the shell
greyed out.

## Building the jar

```sh
./scripts/build-jar.sh          # projects/server/target/odatoruthall.jar
./scripts/smoke-jar.sh          # starts it on the scripted data, checks both apps answer
```

Running what it built:

```sh
ODATO_DEMO=1 java -jar projects/server/target/odatoruthall.jar

GITHUB_TOKEN=ghp_… JIRA_BASE_URL=https://acme.atlassian.net \
  JIRA_EMAIL=you@acme.com JIRA_TOKEN=… ODATO_DB_FILE=./data/odatoruthall.edn \
  java -jar projects/server/target/odatoruthall.jar
```

`ODATO_VERSION` names the build; without it the version is counted from the git
revisions.

## Building the image

One image, one process, both applications: Node builds the two assets, a JDK 26
builds the jar, a JRE runs it.

```sh
docker build -t odatoruthall:latest .

# with the scripted data — nothing to configure, nothing leaves the container
docker run --rm -p 7777:7777 -e ODATO_DEMO=1 odatoruthall:latest

# against the real services, with the worklog kept in a named volume
docker run --rm -p 7777:7777 \
  -e GITHUB_TOKEN -e JIRA_BASE_URL -e JIRA_EMAIL -e JIRA_TOKEN \
  -v odato-worklog:/data \
  odatoruthall:latest

# or hand it the whole file — but override ODATO_DB_FILE, because the path in
# .env is the one that suits a workspace run, not a container
docker run --rm -p 7777:7777 --env-file .env \
  -e ODATO_DB_FILE=/data/odatoruthall.edn -v odato-worklog:/data \
  odatoruthall:latest
```

The image already sets `ODATO_DB_FILE=/data/odatoruthall.edn`, so `/data` is the
only volume there is: the worklog is the only thing that outlives the process.
Everything gitdesk collects is rebuilt by the collection that starts with it.
`--build-arg JDK_VERSION=25` lowers the JDK if 26 is not published for your
platform yet.

## compose.yaml

`compose.yaml` is the same image with everything around it already decided, so
that running the installation for real is one command instead of the long
`docker run` above. It reads `.env` on its own, maps the port, declares the `worklog` named volume
that keeps `/data` across restarts and rebuilds, and pins `ODATO_DB_FILE` to the
path inside the container, so the `.env` that suits a workspace run also suits
this one.

```sh
docker compose up --build              # reads ./.env
ODATO_DEMO=1 docker compose up --build # the scripted data instead
docker compose logs -f                 # what the collection cycle is doing
docker compose down                    # stop; the worklog volume survives
docker compose down -v                 # stop and throw the worklog away
```

It is a convenience, not a requirement: `docker build` and `docker run` above do
the same thing by hand, and neither the jar nor the image needs compose to run.

## Nix and home-manager

The repository is a flake: it builds the jar from the sources and exports a
home-manager module running the server as a systemd user service. Running as
the operator, the server reaches the programs installed in their profile — the
Claude CLI of owldesk among them.

```nix
# flake.nix of the home-manager configuration
inputs.odatoruthall = {
  url = "github:OPeyrusse/odatoruthall";
  inputs.nixpkgs.follows = "nixpkgs";
};

# home-manager modules
modules = [
  odatoruthall.homeManagerModules.default
  {
    services.odatoruthall = {
      enable = true;
      environment = {
        JIRA_BASE_URL = "https://acme.atlassian.net";
        JIRA_EMAIL = "me@acme.com";
      };
      # The default: secrets stay out of the Nix store
      environmentFiles = [ "-%h/.config/odatoruthall/env" ];
    };
  }
];
```

The variables of [Credentials](#credentials) that are not secrets are options of
`services.odatoruthall.environment`. They are written in the Nix store, and
systemd specifiers are expanded in them (`%h`, `%S`, …).

The secrets — `GITHUB_TOKEN`, `JIRA_TOKEN`, `TURSO_TOKEN`, `OWLLIE_FIRE_TOKEN`,
`OWLLIE_TUNE_FIRE_TOKEN`, `OWLLIE_RESULTS_TOKEN` — go in the files of `environmentFiles`, read by systemd
when the service starts, as `NAME=value` lines like `.env`. Whatever manages the
secrets writes them: by default `~/.config/odatoruthall/env`, which may be
missing (the leading `-`); `%t/…` puts one in the runtime directory instead.
Their values override those of `environment`. After changing one:

```sh
systemctl --user restart odatoruthall
```

The worklog is kept in `~/.local/state/odatoruthall/odatoruthall.edn` unless
`ODATO_DB_FILE` says otherwise. `javaOptions` and `extraPath` complete the JVM
options and the PATH of the service; see _nix/module.nix_.

```sh
systemctl --user status odatoruthall
journalctl --user -u odatoruthall -f
```

The nix build pins the Maven dependencies in _deps-lock.json_. Regenerate it
after any change of a `deps.edn`:

```sh
nix run .#deps-lock
```

## Everything else

```sh
clojure -M:dev:test        # every brick's tests, against the mock services
./scripts/check-specs.sh   # the Allium gate (needs `cargo install allium-cli`)
npm run assets             # rebuild the stylesheet and the Datastar bundle
npm run css:watch          # ... and keep rebuilding the stylesheet while editing views
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
| `components/turso` | the durable half kept in a Turso table, as a DataScript storage | — |
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
| `components/owllie` | Owllie seen from here: the request format, branch names, and the launchers (scheduled task, CLI, mock) | — |
| `components/review` | review settings, launches, reading results branches, judgements of findings, cleanup; remarks for Owllie, batches, tunings, the review's diff, reviewers' comments | `owldesk/review`, `owldesk/tuning` |
| `components/ui` | every page and fragment | `gitdesk/dashboard`, `worklog/timesheet`, `owldesk/owldesk`, `shared/shell` |
| `bases/server` | the routes, the event stream, the process | — |
| `projects/server` | the one deployable artefact | — |

## How it is built, and why

**Two databases, not one.** The specification draws the line itself: the
configuration and the worklog outlive the process, and everything gitdesk
collects dies with it and is rebuilt by the collection that starts with the
process. So `components/db` opens a `:durable` connection and a `:volatile`
one. The volatile half is always in memory. The durable half is kept wherever
it is told: a DataScript storage, a file of EDN, or nowhere.

**Turso is a storage, not a rewrite.** DataScript keeps a database as a set of
addressed nodes and asks its storage to write `[addr data]` pairs, read one
address back, and — during a garbage collection — list and drop addresses. One
`addr integer primary key, content text` table answers that, so
`components/turso` is the HTTP protocol and nothing else: it knows about
neither application, and `components/db` takes a storage without knowing what
is behind it. A transaction writes through on its own, so nothing above has to
remember to save. A stored database carries the schema it was stored with, so
`db/open` re-initialises the datoms under the schema in force when that schema
has moved on. A daily sweep drops the nodes the tree no longer references.

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

**Jira is the authority on what Jira holds.** A day scan reads the operator's
own worklogs for that day — whether this installation wrote them or they
logged them in Jira itself — so a day worked on and logged elsewhere does not
read as an empty day. An entry holding no time of its own takes Jira's and
reads as acknowledged; one holding time they typed and have not sent keeps it
and says what Jira holds beside it, because adding the two together or
silently replacing one would both be wrong. A task whose time Jira holds under
several worklogs is read here and changed in Jira: one field cannot edit two
worklogs.

**Mock services are first-class.** `odato.github.mock` and `odato.jira.mock`
answer from a script: pages, per-source failures, a budget that can be made to
run out, and worklogs that are remembered so a day submitted twice can be shown
to log the time once. Every test runs against them, and so does `ODATO_DEMO=1`.

**Java 26.** The image and CI run it. Nothing in the code needs more than 21.

## What is not done

- **Turso against a real database.** The component is tested end to end —
  DataScript stores and restores a database through its own HTTP client — but
  against a Turso that answers from a map (`odato.fake-turso`), not against
  turso.tech. Point `TURSO_URL` and `TURSO_TOKEN` at a real one to find out.
- **A comment on a worklog.** An entry carries a duration and no note.
