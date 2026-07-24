#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
logs="$root/artifacts/logs"
timestamp="$(date '+%Y%m%d-%H%M%S')"
log="$logs/macos-debug-$timestamp-$$.log"
mkdir -p "$logs"
: >"$log"

message="macOS is planned: no buildable Xcode project exists in apps/macos"
printf '%s\n' "$message" >"$log"
printf 'FAILED macOS Debug: %s\nlog: %s\n' "$message" "$log" >&2

exit 2
