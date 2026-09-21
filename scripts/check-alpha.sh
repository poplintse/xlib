#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
release_version="${RELEASE_VERSION:-0.11.0}"
snapshot_dir="$(mktemp -d "${TMPDIR:-/tmp}/xlib-alpha-versions.XXXXXX")"
before="$snapshot_dir/before"
after="$snapshot_dir/after"

version_files="
$root/apps/android/version.properties
$root/apps/ios/XLibReader.xcodeproj/project.pbxproj
$root/apps/ios/XLibReader/Resources/Info.plist
$root/services/backend/package.json
$root/releases/$release_version.yaml
"

snapshot_versions() {
    output="$1"
    : > "$output"
    for file in $version_files; do
        if [ ! -f "$file" ]; then
            echo "missing version source: $file" >&2
            return 1
        fi
        cksum "$file" >> "$output"
    done
}

finish() {
    status=$?
    trap - EXIT
    if ! snapshot_versions "$after"; then
        status=1
    elif ! cmp -s "$before" "$after"; then
        echo "Alpha checks modified version-managed files" >&2
        diff -u "$before" "$after" >&2 || true
        status=1
    fi
    rm -rf "$snapshot_dir"
    exit "$status"
}

snapshot_versions "$before"
trap finish EXIT

if [ -n "${XLIB_IOS_DESTINATION:-}" ]; then
    destination="$XLIB_IOS_DESTINATION"
else
    simulator_id="$(
        xcrun simctl list devices available -j |
            ruby -rjson -e '
              data = JSON.parse(STDIN.read)
              devices = data.fetch("devices").values.flatten
              device = devices.find { |item| item["state"] == "Booted" && item["name"].start_with?("iPhone") } ||
                       devices.find { |item| item["name"].start_with?("iPhone") }
              abort "no available iPhone Simulator" unless device
              puts device.fetch("udid")
            '
    )"
    destination="platform=iOS Simulator,id=$simulator_id"
fi

"$root/scripts/check-local.sh"
"$root/scripts/test-backend-postgres.sh"
"$root/scripts/build-android-debug.sh"

xcodebuild \
    -project "$root/apps/ios/XLibReader.xcodeproj" \
    -scheme XLibReader \
    -configuration Debug \
    -destination "$destination" \
    -derivedDataPath "${TMPDIR:-/tmp}/xlib-derived-data/ios-tests" \
    CODE_SIGNING_ALLOWED=NO \
    -parallel-testing-enabled NO \
    test

echo "Alpha checks passed"
