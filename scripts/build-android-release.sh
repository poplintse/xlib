#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
android="$root/apps/android"
version_file="$android/version.properties"
local_properties="$android/local.properties"
requested="${VERSION:-${1:-}}"
logs="$root/artifacts/logs"
timestamp="$(date '+%Y%m%d-%H%M%S')"
log="$logs/android-release-$timestamp-$$.log"
mkdir -p "$logs"
: >"$log"

fail() {
    message="$1"
    status="${2:-1}"
    printf '%s\n' "$message" >>"$log"
    printf 'FAILED Android Release: %s\nlog: %s\n' "$message" "$log" >&2
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
actual="$(sed -n 's/^versionName=//p' "$version_file")"
if ! printf '%s\n' "$actual" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    fail "invalid Android version: ${actual:-missing}"
fi
if [ -n "$requested" ] && [ "$requested" != "$actual" ]; then
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
    printf 'Android versionName: %s -> %s\n' "$actual" "$requested" >>"$log"
    actual="$requested"
fi
version_code="$(sed -n 's/^versionCode=//p' "$version_file")"
if ! printf '%s\n' "$version_code" | grep -Eq '^[1-9][0-9]*$'; then
    fail "invalid Android versionCode: ${version_code:-missing}"
fi

if [ ! -f "$local_properties" ]; then
    fail "apps/android/local.properties is required for the existing Release signing configuration"
fi
for property in RELEASE_STORE_FILE RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD; do
    if ! grep -Eq "^${property}=.+" "$local_properties"; then
        fail "apps/android/local.properties is missing $property"
    fi
done

android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$android_sdk" ] || [ ! -d "$android_sdk/build-tools" ]; then
    fail "ANDROID_SDK_ROOT or ANDROID_HOME must point to an Android SDK"
fi
apksigner="$(
    find "$android_sdk/build-tools" -maxdepth 2 -type f -name apksigner |
        sort -V |
        tail -n 1
)"
if [ -z "$apksigner" ] || [ ! -x "$apksigner" ]; then
    fail "apksigner was not found in the Android SDK"
fi

artifact_dir="$root/artifacts/android/$actual"
artifact="$artifact_dir/xlib-release.apk"
mkdir -p "$artifact_dir"
before_build="$(shasum -a 256 "$version_file")"

set +e
(
    set -eu
    cd "$android"
    printf 'Gradle Android Release %s\n' "$actual"
    ./gradlew --console=plain :app:assembleRelease
    source_apk="$android/app/build/outputs/apk/release/xlib-release.apk"
    [ -f "$source_apk" ]
    staged="$artifact.tmp.$$"
    cp "$source_apk" "$staged"
    mv "$staged" "$artifact"
    "$root/scripts/verify-artifact.sh" android "$actual" "$artifact" "$version_code"
    "$apksigner" verify "$artifact"
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

printf 'OK Android Release %s (build %s; next %s)\nartifact: %s\nlog: %s\n' \
    "$actual" "$version_code" "$next_version_code" "$artifact" "$log"
