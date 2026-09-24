#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
output="$root/artifacts/p8-performance"
results="$output/results"
derived_data="$output/derived-data"

if [ -z "${XLIB_IOS_DEVELOPMENT_TEAM:-}" ]; then
    echo "XLIB_IOS_DEVELOPMENT_TEAM is required to sign the physical-device UI test runner" >&2
    exit 2
fi

device_udid="${XLIB_IOS_DEVICE_UDID:-}"
if [ -z "$device_udid" ]; then
    device_json="${TMPDIR:-/tmp}/xlib-p8-ios-devices.json"
    xcrun devicectl list devices --json-output "$device_json" >/dev/null
    device_udid="$(python3 - "$device_json" <<'PY'
import json
import sys

devices = json.load(open(sys.argv[1], encoding="utf-8")).get("result", {}).get("devices", [])
matches = [
    device.get("hardwareProperties", {}).get("udid", "")
    for device in devices
    if device.get("hardwareProperties", {}).get("platform") == "iOS"
    and device.get("hardwareProperties", {}).get("reality") == "physical"
    and device.get("deviceProperties", {}).get("bootState") == "booted"
    and device.get("deviceProperties", {}).get("ddiServicesAvailable") is True
]
matches = [value for value in matches if value]
if len(matches) != 1:
    raise SystemExit(f"expected exactly one connected, developer-ready iPhone; found {len(matches)}")
print(matches[0])
PY
)"
fi

mkdir -p "$results" "$derived_data"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
result="$results/p8-ios-device-$stamp.xcresult"
metrics="$results/p8-ios-device-$stamp-metrics.json"
tests="$results/p8-ios-device-$stamp-tests.json"
log="$results/p8-ios-device-$stamp.log"

set +e
XLIB_P8_DEVICE_PERFORMANCE=1 xcodebuild \
    -project "$root/apps/ios/XLibReader.xcodeproj" \
    -scheme XLibReaderP8 \
    -configuration Release \
    -destination "platform=iOS,id=$device_udid" \
    -derivedDataPath "$derived_data" \
    -resultBundlePath "$result" \
    -parallel-testing-enabled NO \
    -allowProvisioningUpdates \
    -only-testing:XLibReaderUITests/P8PhysicalPerformanceUITests \
    DEVELOPMENT_TEAM="$XLIB_IOS_DEVELOPMENT_TEAM" \
    CODE_SIGN_STYLE=Automatic \
    'OTHER_SWIFT_FLAGS=$(inherited) -D XLIB_P8_DEVICE_PERFORMANCE' \
    test > "$log" 2>&1
test_status=$?
set -e

if [ "$test_status" -ne 0 ]; then
    tail -120 "$log" >&2
    exit "$test_status"
fi

xcrun xcresulttool get test-results metrics --path "$result" --compact > "$metrics"
xcrun xcresulttool get test-results tests --path "$result" --compact > "$tests"
python3 "$root/scripts/p8-performance.py" ingest-ios-open --metrics "$metrics"
python3 "$root/scripts/p8-performance.py" ingest-ios-runtime --metrics "$metrics" --tests "$tests"
python3 "$root/scripts/p8-performance.py" validate \
    --report "$output/performance-results.md"
printf 'P8 iOS result: %s\n' "$result"
printf 'P8 iOS metrics: %s\n' "$metrics"
printf 'P8 iOS tests: %s\n' "$tests"
printf 'P8 iOS log: %s\n' "$log"
