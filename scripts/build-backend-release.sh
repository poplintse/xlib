#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
backend="$root/services/backend"
package_json="$backend/package.json"
requested="${VERSION:-${1:-}}"
logs="$root/artifacts/logs"
timestamp="$(date '+%Y%m%d-%H%M%S')"
log="$logs/backend-release-$timestamp-$$.log"
mkdir -p "$logs"
: >"$log"

fail() {
    message="$1"
    status="${2:-1}"
    printf '%s\n' "$message" >>"$log"
    printf 'FAILED Backend Release: %s\nlog: %s\n' "$message" "$log" >&2
    exit "$status"
}

if [ -z "$requested" ]; then
    fail "VERSION is required (for example: make build-backend-release VERSION=0.9.0)" 2
fi
if ! printf '%s\n' "$requested" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    fail "invalid VERSION: $requested" 2
fi

if ! actual="$(
    ruby -rjson -e 'puts JSON.parse(File.read(ARGV.fetch(0))).fetch("version")' \
        "$package_json" 2>>"$log"
)"; then
    fail "could not read the backend version"
fi
if [ "$requested" != "$actual" ]; then
    fail "VERSION $requested does not match backend package version $actual"
fi

if ! command -v node >/dev/null 2>&1; then
    for node_candidate in \
        /opt/homebrew/opt/node@22/bin/node \
        /usr/local/opt/node@22/bin/node
    do
        if [ -x "$node_candidate" ]; then
            PATH="$(dirname "$node_candidate"):$PATH"
            export PATH
            break
        fi
    done
fi
if ! command -v node >/dev/null 2>&1; then
    fail "Node.js 22 or newer is required and was not found in PATH or Homebrew"
fi
node_major="$(node -p 'process.versions.node.split(".")[0]')"
if [ "$node_major" -lt 22 ]; then
    fail "Node.js 22 or newer is required (found $(node --version))"
fi
if [ ! -d "$backend/node_modules" ]; then
    fail "backend dependencies are missing; run make bootstrap"
fi

if command -v corepack >/dev/null 2>&1; then
    pnpm_command="corepack pnpm"
elif command -v pnpm >/dev/null 2>&1; then
    pnpm_command="pnpm"
else
    fail "pnpm or corepack is required"
fi

artifact_dir="$root/artifacts/backend/$actual"
artifact="$artifact_dir/xlib-backend-$actual.tar.gz"
mkdir -p "$artifact_dir"
before="$(shasum -a 256 "$package_json")"

set +e
(
    set -eu
    cd "$backend"
    printf 'Backend Release %s\n' "$actual"
    rm -rf "$backend/dist"
    if [ "$pnpm_command" = "pnpm" ]; then
        pnpm build
    else
        corepack pnpm build
    fi

    [ -f "$backend/dist/src/index.js" ]
    stage="$(mktemp -d "${TMPDIR:-/tmp}/xlib-backend-release.XXXXXX")"
    trap 'rm -rf "$stage"' EXIT HUP INT TERM
    mkdir -p "$stage/dist"
    cp -R "$backend/dist/src/." "$stage/dist/"
    cp -R "$backend/migrations" "$stage/migrations"
    cp "$backend/package.json" "$stage/package.json"
    cp "$backend/pnpm-lock.yaml" "$stage/pnpm-lock.yaml"
    cp "$backend/pnpm-workspace.yaml" "$stage/pnpm-workspace.yaml"

    staged_artifact="$artifact.tmp.$$"
    tar -czf "$staged_artifact" -C "$stage" .
    mv "$staged_artifact" "$artifact"
    "$root/scripts/verify-artifact.sh" backend "$actual" "$artifact"
    rm -rf "$stage"
    trap - EXIT HUP INT TERM
) >>"$log" 2>&1
status=$?
set -e
if [ "$status" -ne 0 ]; then
    fail "backend build or artifact verification failed" "$status"
fi

after="$(shasum -a 256 "$package_json")"
if [ "$before" != "$after" ]; then
    fail "build mutated services/backend/package.json"
fi

printf 'OK Backend Release %s\nartifact: %s\nlog: %s\n' "$actual" "$artifact" "$log"
