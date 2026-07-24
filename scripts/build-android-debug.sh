#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
android="$root/apps/android"
version_file="$android/version.properties"
requested="${VERSION:-${1:-}}"
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

if [ -n "$requested" ] &&
    ! printf '%s\n' "$requested" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    fail "invalid VERSION: $requested" 2
fi

version_name_count="$(
    awk '
      /^versionName=/ { count += 1 }
      END { print count + 0 }
    ' "$version_file"
)"
if [ "$version_name_count" -ne 1 ]; then
    fail "apps/android/version.properties must contain exactly one versionName"
fi
version="$(sed -n 's/^versionName=//p' "$version_file")"
if ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    fail "invalid Android version: ${version:-missing}"
fi
if [ -n "$requested" ] && [ "$requested" != "$version" ]; then
    staged_version_file="$version_file.tmp.$$"
    trap 'rm -f "$staged_version_file"' EXIT HUP INT TERM
    sed "s/^versionName=.*/versionName=$requested/" \
        "$version_file" >"$staged_version_file"
    staged_version="$(sed -n 's/^versionName=//p' "$staged_version_file")"
    if [ "$staged_version" != "$requested" ]; then
        fail "could not update Android versionName to $requested"
    fi
    mv "$staged_version_file" "$version_file"
    trap - EXIT HUP INT TERM
    printf 'Android versionName: %s -> %s\n' "$version" "$requested" >>"$log"
    version="$requested"
fi
version_code_count="$(
    awk '
      /^versionCode=/ { count += 1 }
      END { print count + 0 }
    ' "$version_file"
)"
if [ "$version_code_count" -ne 1 ]; then
    fail "apps/android/version.properties must contain exactly one versionCode"
fi
version_code="$(sed -n 's/^versionCode=//p' "$version_file")"
if ! printf '%s\n' "$version_code" | grep -Eq '^[1-9][0-9]*$'; then
    fail "invalid Android versionCode: ${version_code:-missing}"
fi

artifact_dir="$root/artifacts/android/$version"
artifact="$artifact_dir/xlib-debug.apk"
mkdir -p "$artifact_dir"
before_build="$(shasum -a 256 "$version_file")"

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
    "$root/scripts/verify-artifact.sh" android "$version" "$artifact" "$version_code"
) >>"$log" 2>&1
status=$?
set -e
if [ "$status" -ne 0 ]; then
    fail "Gradle or artifact verification failed" "$status"
fi

after_build="$(shasum -a 256 "$version_file")"
if [ "$before_build" != "$after_build" ]; then
    fail "build mutated version.properties"
fi
if ! "$root/scripts/increment-android-version-code.sh" "$version_file" >>"$log" 2>&1; then
    fail "could not increment the Android versionCode"
fi
next_version_code="$(sed -n 's/^versionCode=//p' "$version_file")"

printf 'OK Android Debug %s (build %s; next %s)\nartifact: %s\nlog: %s\n' \
    "$version" "$version_code" "$next_version_code" "$artifact" "$log"
