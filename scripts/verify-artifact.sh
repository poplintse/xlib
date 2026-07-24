#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
component="${1:-}"
version="${2:-}"
artifact="${3:-}"
build_number="${4:-}"

usage() {
    echo "usage: $0 <ios|android|macos|backend> <version> <artifact-path> [build-number]" >&2
    exit 2
}

[ -n "$component" ] || usage
[ -n "$version" ] || usage
[ -n "$artifact" ] || usage

case "$component" in
    ios|android|macos|backend) ;;
    *) usage ;;
esac

if ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$'; then
    echo "invalid artifact version: $version" >&2
    exit 1
fi
if [ -n "$build_number" ] &&
    ! printf '%s\n' "$build_number" | grep -Eq '^[1-9][0-9]*$'; then
    echo "invalid artifact build number: $build_number" >&2
    exit 1
fi

case "$artifact" in
    /*) ;;
    *) artifact="$root/$artifact" ;;
esac

if [ ! -e "$artifact" ]; then
    echo "artifact does not exist: $artifact" >&2
    exit 1
fi

artifact_parent="$(CDPATH= cd -- "$(dirname "$artifact")" && pwd -P)"
artifact="$artifact_parent/$(basename "$artifact")"
expected_parent="$root/artifacts/$component/$version"
if [ "$artifact_parent" != "$expected_parent" ]; then
    echo "artifact must be directly inside artifacts/$component/$version/" >&2
    exit 1
fi

case "$component" in
    ios|macos)
        if [ ! -d "$artifact" ] || [ ! -f "$artifact/Info.plist" ]; then
            echo "invalid app bundle: $artifact" >&2
            exit 1
        fi
        bundle_version="$(plutil -extract CFBundleShortVersionString raw -o - "$artifact/Info.plist")"
        if [ "$bundle_version" != "$version" ]; then
            echo "app version $bundle_version does not match $version" >&2
            exit 1
        fi
        if [ -n "$build_number" ]; then
            bundle_build="$(plutil -extract CFBundleVersion raw -o - "$artifact/Info.plist")"
            if [ "$bundle_build" != "$build_number" ]; then
                echo "app build $bundle_build does not match $build_number" >&2
                exit 1
            fi
        fi
        ;;
    android)
        if [ ! -s "$artifact" ]; then
            echo "APK is missing or empty: $artifact" >&2
            exit 1
        fi
        unzip -tqq "$artifact"
        android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
        if [ -z "$android_sdk" ] || [ ! -d "$android_sdk/build-tools" ]; then
            echo "ANDROID_SDK_ROOT or ANDROID_HOME must point to an Android SDK" >&2
            exit 1
        fi
        aapt="$(
            find "$android_sdk/build-tools" -maxdepth 2 -type f -name aapt |
                sort -V |
                tail -n 1
        )"
        if [ -z "$aapt" ] || [ ! -x "$aapt" ]; then
            echo "aapt was not found in the Android SDK" >&2
            exit 1
        fi
        package_line="$("$aapt" dump badging "$artifact" | sed -n '1p')"
        apk_version="$(
            printf '%s\n' "$package_line" |
                sed -n "s/.* versionName='\\([^']*\\)'.*/\\1/p"
        )"
        apk_build="$(
            printf '%s\n' "$package_line" |
                sed -n "s/.* versionCode='\\([^']*\\)'.*/\\1/p"
        )"
        if [ "$apk_version" != "$version" ]; then
            echo "APK version $apk_version does not match $version" >&2
            exit 1
        fi
        if [ -n "$build_number" ] && [ "$apk_build" != "$build_number" ]; then
            echo "APK build $apk_build does not match $build_number" >&2
            exit 1
        fi
        ;;
    backend)
        if [ ! -s "$artifact" ]; then
            echo "backend bundle is missing or empty: $artifact" >&2
            exit 1
        fi
        tar -tzf "$artifact" >/dev/null
        if ! tar -tzf "$artifact" | grep -Eq '^\./dist/index\.js$'; then
            echo "backend bundle does not contain dist/index.js" >&2
            exit 1
        fi
        if ! tar -tzf "$artifact" | grep -Eq '^\./package\.json$'; then
            echo "backend bundle does not contain package.json" >&2
            exit 1
        fi
        packaged_version="$(
            tar -xOzf "$artifact" ./package.json |
                ruby -rjson -e 'puts JSON.parse(STDIN.read).fetch("version")'
        )"
        if [ "$packaged_version" != "$version" ]; then
            echo "backend package version $packaged_version does not match $version" >&2
            exit 1
        fi
        ;;
esac

printf 'verified %s %s: %s\n' "$component" "$version" "$artifact"
