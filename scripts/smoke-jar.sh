#!/bin/sh
# Starts the built jar against the scripted data and checks that both
# applications answer. No credential is used and nothing leaves the process.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
jar="$root/projects/server/target/odatoruthall.jar"
port=${PORT:-7788}

[ -f "$jar" ] || { echo "no jar at $jar; run scripts/build-jar.sh first" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is needed to probe the server" >&2; exit 1; }

ODATO_DEMO=1 PORT="$port" java -jar "$jar" &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT INT TERM

# The first collection runs at startup, so give it a moment to answer at all.
i=0
until curl -fsS "http://127.0.0.1:$port/gitdesk" -o /dev/null 2>/dev/null; do
    i=$((i + 1))
    [ "$i" -lt 60 ] || { echo "the server never answered on port $port" >&2; exit 1; }
    sleep 1
done

for path in /gitdesk /worklog /owldesk /settings /css/app.css /js/datastar.js; do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port$path")
    [ "$code" = "200" ] || { echo "$path answered $code" >&2; exit 1; }
    echo "$path $code"
done

echo "the jar serves every application"
