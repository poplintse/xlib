#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
component="${1:-}"
version="${2:-}"
artifact="${3:-}"

usage() {
    echo "usage: $0 <ios|android|macos|backend> <version> <artifact-path>" >&2
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
        ;;
    android)
        if [ ! -s "$artifact" ]; then
            echo "APK is missing or empty: $artifact" >&2
            exit 1
        fi
        unzip -tqq "$artifact"
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
        ;;
esac

printf 'verified %s %s: %s\n' "$component" "$version" "$artifact"
