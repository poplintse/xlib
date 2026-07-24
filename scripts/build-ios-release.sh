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

if [ -n "$requested" ] &&
    ! printf '%s\n' "$requested" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
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
if ! printf '%s\n' "$actual" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    fail "invalid iOS version: $actual"
fi
if [ -n "$requested" ] && [ "$requested" != "$actual" ]; then
    staged_project="$project.tmp.$$"
    trap 'rm -f "$staged_project"' EXIT HUP INT TERM
    if ! ruby -e '
      source, previous, requested, output = ARGV
      text = File.read(source)
      current = "MARKETING_VERSION = #{previous};"
      replacement = "MARKETING_VERSION = #{requested};"
      abort "MARKETING_VERSION entry was not found" unless text.include?(current)
      File.write(output, text.gsub(current, replacement))
    ' "$project" "$actual" "$requested" "$staged_project" 2>>"$log"; then
        fail "could not update iOS MARKETING_VERSION to $requested"
    fi
    mv "$staged_project" "$project"
    trap - EXIT HUP INT TERM
    printf 'iOS MARKETING_VERSION: %s -> %s\n' "$actual" "$requested" >>"$log"
    actual="$requested"
fi
if ! SRCROOT="$ios" "$ios/Scripts/increment_build_number.sh" >>"$log" 2>&1; then
    fail "could not increment the iOS build number"
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

before_build_project="$(shasum -a 256 "$project")"
before_build_plist="$(shasum -a 256 "$plist")"

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
    "$root/scripts/verify-artifact.sh" ios "$actual" "$artifact" "$build_number"
    [ -f "$artifact/embedded.mobileprovision" ]
    codesign --verify --deep --strict "$artifact"
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

printf 'OK iOS Release %s (build %s, signed)\nartifact: %s\nlog: %s\n' \
    "$actual" "$build_number" "$artifact" "$log"
