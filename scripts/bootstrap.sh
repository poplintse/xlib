#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "missing required command: $1" >&2
        exit 1
    fi
}

require_command java
require_command node
require_command corepack
require_command ruby
require_command swift
require_command xcodebuild

java -version
node --version
swift --version
xcodebuild -version

cd "$root/services/backend"
corepack pnpm install --frozen-lockfile

echo "bootstrap complete"
