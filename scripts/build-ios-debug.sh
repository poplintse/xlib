#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
ios="$root/apps/ios"
project="$ios/XLibReader.xcodeproj/project.pbxproj"
plist="$ios/XLibReader/Resources/Info.plist"
if [ "${1:-}" != "--build-locked" ]; then
    requested="${VERSION:-${1:-}}"
    mkdir -p "$root/artifacts/.build"
    exec ruby "$root/scripts/with-build-lock.rb" "$root/artifacts/.build/ios-build.lock" \
        "$0" --build-locked "$requested"
fi
shift
requested="${1:-}"
logs="$root/artifacts/logs"
timestamp="$(date '+%Y%m%d-%H%M%S')"
log="$logs/ios-debug-$timestamp-$$.log"
mkdir -p "$logs"
: >"$log"

fail() {
    message="$1"
    status="${2:-1}"
    printf '%s\n' "$message" >>"$log"
    printf 'FAILED iOS Debug: %s\nlog: %s\n' "$message" "$log" >&2
    exit "$status"
}

if [ -n "$requested" ] &&
    ! printf '%s\n' "$requested" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    fail "invalid VERSION: $requested" 2
fi

prepared="$(sh "$root/scripts/prepare-build-version.sh" ios "$requested" "$root" 2>>"$log")" ||
    fail "could not prepare iOS build number"
version="${prepared%%:*}"
build_values="${prepared#*:}"
previous_build="${build_values%%:*}"
build_number="${build_values#*:}"
printf 'iOS version %s; build number %s -> %s\n' "$version" "$previous_build" "$build_number" >>"$log"

artifact_dir="$root/artifacts/ios/$version"
artifact="$artifact_dir/XLibReader-Debug.app"
derived_data="$root/artifacts/.build/ios-debug"
mkdir -p "$artifact_dir" "$derived_data"

before_build_project="$(shasum -a 256 "$project")"
before_build_plist="$(shasum -a 256 "$plist")"

set +e
(
    set -eu
    printf 'xcodebuild iOS Debug %s\n' "$version"
    xcodebuild \
        -project "$ios/XLibReader.xcodeproj" \
        -scheme XLibReader \
        -configuration Debug \
        -destination "generic/platform=iOS Simulator" \
        -derivedDataPath "$derived_data" \
        CODE_SIGNING_ALLOWED=NO \
        CODE_SIGNING_REQUIRED=NO \
        clean build

    source_app="$derived_data/Build/Products/Debug-iphonesimulator/XLibReader.app"
    [ -d "$source_app" ]

    staged="$artifact_dir/.XLibReader-Debug.app.$$"
    rm -rf "$staged"
    cp -R "$source_app" "$staged"
    rm -rf "$artifact"
    mv "$staged" "$artifact"
    "$root/scripts/verify-artifact.sh" ios "$version" "$artifact" "$build_number"
) >>"$log" 2>&1
status=$?
set -e
if [ "$status" -ne 0 ]; then
    fail "xcodebuild or artifact verification failed" "$status"
fi

after_build_project="$(shasum -a 256 "$project")"
after_build_plist="$(shasum -a 256 "$plist")"
if [ "$before_build_project" != "$after_build_project" ] ||
    [ "$before_build_plist" != "$after_build_plist" ]; then
    fail "build mutated version-managed project files"
fi

printf 'OK iOS Debug %s (build %s)\nartifact: %s\nlog: %s\n' \
    "$version" "$build_number" "$artifact" "$log"
