#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
android="$root/apps/android"
version_file="$android/version.properties"
before="$(shasum -a 256 "$version_file")"

cd "$android"
./gradlew :app:assembleDebug

after="$(shasum -a 256 "$version_file")"
if [ "$before" != "$after" ]; then
    echo "Android build mutated version.properties" >&2
    exit 1
fi

echo "Android Debug APK: $android/app/build/outputs/apk/debug/xlib-debug.apk"
