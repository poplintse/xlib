#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
ios="$root/apps/ios"
project="$ios/XLibReader.xcodeproj/project.pbxproj"
plist="$ios/XLibReader/Resources/Info.plist"
derived_data="${XLIB_DERIVED_DATA:-${TMPDIR:-/tmp}/xlib-derived-data/ios-debug}"
before_project="$(shasum -a 256 "$project")"
before_plist="$(shasum -a 256 "$plist")"

xcodebuild \
    -project "$ios/XLibReader.xcodeproj" \
    -scheme XLibReader \
    -configuration Debug \
    -destination "generic/platform=iOS Simulator" \
    -derivedDataPath "$derived_data" \
    CODE_SIGNING_ALLOWED=NO \
    build

after_project="$(shasum -a 256 "$project")"
after_plist="$(shasum -a 256 "$plist")"
if [ "$before_project" != "$after_project" ] || [ "$before_plist" != "$after_plist" ]; then
    echo "iOS build mutated version-managed project files" >&2
    exit 1
fi

echo "iOS Simulator Debug build: $derived_data/Build/Products/Debug-iphonesimulator/XLibReader.app"
