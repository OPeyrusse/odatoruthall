#!/bin/bash
# Prepares a Claude Code cloud session: the Clojure CLI, the Allium checker,
# the workspace's Maven dependencies and the front-end tooling, so that
# `clojure -M:dev:test`, `./scripts/check-specs.sh` and `npm run assets` work
# from the first prompt. Idempotent: what is already there at the right
# version is left alone. Local sessions are left untouched.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

# Pinned like CI pins them (.circleci/config.yml).
CLOJURE_VERSION=1.12.6.1673
ALLIUM_VERSION=3.6.1

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}"

# The Clojure CLI. download.clojure.org is not reachable from the cloud
# sandbox, so the installer comes from the GitHub release instead.
if ! clojure --version 2>/dev/null | grep -q "$CLOJURE_VERSION"; then
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    (cd "$tmp" &&
        curl -fsSL -o linux-install.sh \
            "https://github.com/clojure/brew-install/releases/download/$CLOJURE_VERSION/linux-install.sh" &&
        bash linux-install.sh >/dev/null)
fi

# The Allium checker, behind ./scripts/check-specs.sh and the editor hook.
export PATH="$HOME/.cargo/bin:$PATH"
if ! allium --version 2>/dev/null | grep -q "^allium $ALLIUM_VERSION "; then
    cargo install --quiet --locked allium-cli --version "$ALLIUM_VERSION"
fi
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    echo 'export PATH="$HOME/.cargo/bin:$PATH"' >> "$CLAUDE_ENV_FILE"
fi

# Every brick's dependencies, tests included, into ~/.m2.
clojure -P -M:dev:test >/dev/null

# Tailwind, for `npm run assets`.
npm install --no-audit --no-fund --silent
