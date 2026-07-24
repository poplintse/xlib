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

if [ -z "$requested" ]; then
    fail "VERSION is required (for example: make build-android-release VERSION=0.9.0)" 2
fi
if ! printf '%s\n' "$requested" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    fail "invalid VERSION: $requested" 2
fi

actual="$(sed -n 's/^versionName=//p' "$version_file")"
if [ "$requested" != "$actual" ]; then
    fail "VERSION $requested does not match Android versionName $actual"
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
before="$(shasum -a 256 "$version_file")"

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
    "$root/scripts/verify-artifact.sh" android "$actual" "$artifact"
    "$apksigner" verify "$artifact"
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

printf 'OK Android Release %s (%s)\nartifact: %s\nlog: %s\n' \
    "$actual" "$version_code" "$artifact" "$log"
