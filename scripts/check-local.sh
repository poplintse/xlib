#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"

ruby "$root/scripts/check-contract.rb"
"$root/scripts/release-check.sh" "${RELEASE_VERSION:-0.9.9}"
"$root/scripts/test-backend.sh"
swift test --package-path "$root/packages/apple-shared"

cd "$root/apps/android"
./gradlew :app:testDebugUnitTest lintDebug

"$root/scripts/build-ios-debug.sh"

"$root/scripts/release-check.sh" "${RELEASE_VERSION:-0.9.9}"

echo "local checks passed"
