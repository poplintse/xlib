#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
ios="$root/apps/ios"
project="$ios/XLibReader.xcodeproj/project.pbxproj"
plist="$ios/XLibReader/Resources/Info.plist"
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

if ! version="$(
    ruby -e '
      values = File.read(ARGV.fetch(0)).scan(/MARKETING_VERSION = ([^;]+);/).flatten.uniq
      abort "MARKETING_VERSION is missing or inconsistent" unless values.length == 1
      puts values.first
    ' "$project" 2>>"$log"
)"; then
    fail "could not read the iOS version"
fi

if ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    fail "invalid iOS version: $version"
fi

artifact_dir="$root/artifacts/ios/$version"
artifact="$artifact_dir/XLibReader-Debug.app"
derived_data="$root/artifacts/.build/ios-debug"
mkdir -p "$artifact_dir" "$derived_data"

before_project="$(shasum -a 256 "$project")"
before_plist="$(shasum -a 256 "$plist")"

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
    "$root/scripts/verify-artifact.sh" ios "$version" "$artifact"
) >>"$log" 2>&1
status=$?
set -e
if [ "$status" -ne 0 ]; then
    fail "xcodebuild or artifact verification failed" "$status"
fi

after_project="$(shasum -a 256 "$project")"
after_plist="$(shasum -a 256 "$plist")"
if [ "$before_project" != "$after_project" ] || [ "$before_plist" != "$after_plist" ]; then
    fail "build mutated version-managed project files"
fi

printf 'OK iOS Debug %s\nartifact: %s\nlog: %s\n' "$version" "$artifact" "$log"
