#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
release_version="${RELEASE_VERSION:-0.11.0}"
snapshot_dir="$(mktemp -d "${TMPDIR:-/tmp}/xlib-alpha-versions.XXXXXX")"
before="$snapshot_dir/before"
after="$snapshot_dir/after"

snapshot_versions() {
    output="$1"
    : > "$output"
    ruby - "$root" "$release_version" >"$output" <<'RUBY'
require "json"
require "digest"

root, release_version = ARGV
android_path = File.join(root, "apps/android/version.properties")
android_version = File.read(android_path)[/^versionName=(.+)$/, 1]
project_path = File.join(root, "apps/ios/XLibReader.xcodeproj/project.pbxproj")
ios_versions = File.read(project_path).scan(/MARKETING_VERSION = ([^;]+);/).flatten.uniq
backend_path = File.join(root, "services/backend/package.json")
backend_version = JSON.parse(File.read(backend_path)).fetch("version")
manifest_path = File.join(root, "releases", "#{release_version}.yaml")
info_path = File.join(root, "apps/ios/XLibReader/Resources/Info.plist")
abort "unable to snapshot component version names" if android_version.nil? || ios_versions.length != 1
puts "android=#{android_version}"
puts "ios=#{ios_versions.first}"
puts "backend=#{backend_version}"
puts "manifest=#{Digest::SHA256.file(manifest_path).hexdigest}"
puts "ios_info=#{Digest::SHA256.file(info_path).hexdigest}"
RUBY
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
