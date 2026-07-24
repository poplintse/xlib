#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
android="$root/apps/android"
version_file="$android/version.properties"
logs="$root/artifacts/logs"
timestamp="$(date '+%Y%m%d-%H%M%S')"
log="$logs/android-debug-$timestamp-$$.log"
mkdir -p "$logs"
: >"$log"

fail() {
    message="$1"
    status="${2:-1}"
    printf '%s\n' "$message" >>"$log"
    printf 'FAILED Android Debug: %s\nlog: %s\n' "$message" "$log" >&2
    exit "$status"
}

version="$(sed -n 's/^versionName=//p' "$version_file")"
if ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    fail "invalid Android version: ${version:-missing}"
fi

artifact_dir="$root/artifacts/android/$version"
artifact="$artifact_dir/xlib-debug.apk"
mkdir -p "$artifact_dir"
before="$(shasum -a 256 "$version_file")"

set +e
(
    set -eu
    cd "$android"
    printf 'Gradle Android Debug %s\n' "$version"
    ./gradlew --console=plain :app:assembleDebug
    source_apk="$android/app/build/outputs/apk/debug/xlib-debug.apk"
    [ -f "$source_apk" ]
    staged="$artifact.tmp.$$"
    cp "$source_apk" "$staged"
    mv "$staged" "$artifact"
    "$root/scripts/verify-artifact.sh" android "$version" "$artifact"
) >>"$log" 2>&1
status=$?
set -e
if [ "$status" -ne 0 ]; then
    fail "Gradle or artifact verification failed" "$status"
fi

after="$(shasum -a 256 "$version_file")"
if [ "$before" != "$after" ]; then
    fail "build mutated version.properties"
fi

printf 'OK Android Debug %s\nartifact: %s\nlog: %s\n' "$version" "$artifact" "$log"
