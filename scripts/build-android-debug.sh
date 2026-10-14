#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
android="$root/apps/android"
version_file="$android/version.properties"
if [ "${1:-}" != "--build-locked" ]; then
    requested="${VERSION:-${1:-}}"
    mkdir -p "$root/artifacts/.build"
    exec ruby "$root/scripts/with-build-lock.rb" "$root/artifacts/.build/android-build.lock" \
        "$0" --build-locked "$requested"
fi
shift
requested="${1:-}"
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

prepared="$(sh "$root/scripts/prepare-build-version.sh" android "$requested" "$root" 2>>"$log")" ||
    fail "could not prepare Android build number"
version="${prepared%%:*}"
code_values="${prepared#*:}"
previous_code="${code_values%%:*}"
version_code="${code_values#*:}"
printf 'Android version %s; versionCode %s -> %s\n' "$version" "$previous_code" "$version_code" >>"$log"

artifact_dir="$root/artifacts/android/$version"
artifact="$artifact_dir/xlib-debug-${version_code}.apk"
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
printf 'OK Android Debug %s (build %s)\nartifact: %s\nlog: %s\n' \
    "$version" "$version_code" "$artifact" "$log"
