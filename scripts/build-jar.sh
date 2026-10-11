#!/bin/sh
# Builds the uberjar into projects/server/target/odatoruthall.jar.
#
# The front-end assets are committed, so this needs nothing but a JDK and the
# Clojure CLI. Rebuild them with `npm run assets` when a view changes a class
# Tailwind has not seen before.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root/projects/server"
exec clojure -T:build uber "$@"
