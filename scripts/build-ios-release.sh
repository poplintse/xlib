#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
ios="$root/apps/ios"
project="$ios/XLibReader.xcodeproj/project.pbxproj"
plist="$ios/XLibReader/Resources/Info.plist"
requested="${VERSION:-${1:-}}"
logs="$root/artifacts/logs"
timestamp="$(date '+%Y%m%d-%H%M%S')"
log="$logs/ios-release-$timestamp-$$.log"
mkdir -p "$logs"
: >"$log"

fail() {
    message="$1"
    status="${2:-1}"
    printf '%s\n' "$message" >>"$log"
    printf 'FAILED iOS Release: %s\nlog: %s\n' "$message" "$log" >&2
    exit "$status"
}

if [ -z "$requested" ]; then
    fail "VERSION is required (for example: make build-ios-release VERSION=0.9.0)" 2
fi
if ! printf '%s\n' "$requested" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    fail "invalid VERSION: $requested" 2
fi

if ! actual="$(
    ruby -e '
      values = File.read(ARGV.fetch(0)).scan(/MARKETING_VERSION = ([^;]+);/).flatten.uniq
      abort "MARKETING_VERSION is missing or inconsistent" unless values.length == 1
      puts values.first
    ' "$project" 2>>"$log"
)"; then
    fail "could not read the iOS version"
fi
if [ "$requested" != "$actual" ]; then
    fail "VERSION $requested does not match iOS MARKETING_VERSION $actual"
fi
if ! build_number="$(
    ruby -e '
      values = File.read(ARGV.fetch(0)).scan(/CURRENT_PROJECT_VERSION = ([^;]+);/).flatten.uniq
      abort "CURRENT_PROJECT_VERSION is missing or inconsistent" unless values.length == 1
      puts values.first
    ' "$project" 2>>"$log"
)"; then
    fail "could not read the iOS build number"
fi
if ! printf '%s\n' "$build_number" | grep -Eq '^[1-9][0-9]*$'; then
    fail "invalid iOS build number: $build_number"
fi

artifact_dir="$root/artifacts/ios/$actual"
artifact="$artifact_dir/XLibReader-Release.app"
derived_data="$root/artifacts/.build/ios-release"
mkdir -p "$artifact_dir" "$derived_data"

before_project="$(shasum -a 256 "$project")"
before_plist="$(shasum -a 256 "$plist")"

set +e
(
    set -eu
    printf 'xcodebuild iOS Release %s\n' "$actual"
    xcodebuild \
        -project "$ios/XLibReader.xcodeproj" \
        -scheme XLibReader \
        -configuration Release \
        -destination "generic/platform=iOS" \
        -derivedDataPath "$derived_data" \
        clean build

    source_app="$derived_data/Build/Products/Release-iphoneos/XLibReader.app"
    [ -d "$source_app" ]

    staged="$artifact_dir/.XLibReader-Release.app.$$"
    rm -rf "$staged"
    cp -R "$source_app" "$staged"
    rm -rf "$artifact"
    mv "$staged" "$artifact"
    "$root/scripts/verify-artifact.sh" ios "$actual" "$artifact"
    built_number="$(plutil -extract CFBundleVersion raw -o - "$artifact/Info.plist")"
    [ "$built_number" = "$build_number" ]
    [ -f "$artifact/embedded.mobileprovision" ]
    codesign --verify --deep --strict "$artifact"
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

printf 'OK iOS Release %s (signed)\nartifact: %s\nlog: %s\n' "$actual" "$artifact" "$log"
