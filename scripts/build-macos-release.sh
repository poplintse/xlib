#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
requested="${VERSION:-${1:-}}"
logs="$root/artifacts/logs"
timestamp="$(date '+%Y%m%d-%H%M%S')"
log="$logs/macos-release-$timestamp-$$.log"
mkdir -p "$logs"
: >"$log"

if [ -z "$requested" ]; then
    message="VERSION is required (for example: make build-macos-release VERSION=0.9.0)"
    status=2
elif ! printf '%s\n' "$requested" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    message="invalid VERSION: $requested"
    status=2
else
    message="macOS is planned: no buildable Xcode project exists in apps/macos"
    status=2
fi

printf '%s\n' "$message" >"$log"
printf 'FAILED macOS Release: %s\nlog: %s\n' "$message" "$log" >&2
exit "$status"
