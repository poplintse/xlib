#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"

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
"$root/scripts/build-android-debug.sh"

xcodebuild \
    -project "$root/apps/ios/XLibReader.xcodeproj" \
    -scheme XLibReader \
    -configuration Debug \
    -destination "$destination" \
    -derivedDataPath "${TMPDIR:-/tmp}/xlib-derived-data/ios-tests" \
    CODE_SIGNING_ALLOWED=NO \
    test

if [ -n "${TEST_DATABASE_URL:-}" ]; then
    cd "$root/services/backend"
    if command -v pnpm >/dev/null 2>&1; then
        pnpm test:integration
    else
        corepack pnpm test:integration
    fi
else
    echo "SKIP backend integration tests: TEST_DATABASE_URL is not set"
fi

echo "Alpha checks passed"
