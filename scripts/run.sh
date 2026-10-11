#!/bin/sh
# Runs the server from the workspace.
#
# The process reads its credentials from the environment and nothing else, so
# this loads ./.env when there is one and exports what it holds. Everything in
# .env is a plain `NAME=value`; quote a value that contains spaces.
#
#   ./scripts/run.sh                 with ./.env
#   ODATO_DEMO=1 ./scripts/run.sh    with the scripted data, no credential used
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

if [ -f .env ]; then
    set -a
    . ./.env
    set +a
fi

exec clojure -M:dev:run "$@"
