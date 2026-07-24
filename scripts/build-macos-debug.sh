#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
requested="${VERSION:-${1:-}}"
logs="$root/artifacts/logs"
timestamp="$(date '+%Y%m%d-%H%M%S')"
log="$logs/macos-debug-$timestamp-$$.log"
mkdir -p "$logs"
: >"$log"

if [ -n "$requested" ] &&
    ! printf '%s\n' "$requested" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    message="invalid VERSION: $requested"
else
    message="macOS is planned: no buildable Xcode project exists in apps/macos"
fi
printf '%s\n' "$message" >"$log"
printf 'FAILED macOS Debug: %s\nlog: %s\n' "$message" "$log" >&2

exit 2
