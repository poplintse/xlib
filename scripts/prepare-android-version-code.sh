#!/bin/sh
set -eu

version_file="${1:-}"
artifacts_root="${2:-}"

if [ -z "$version_file" ] || [ -z "$artifacts_root" ] || [ ! -f "$version_file" ]; then
    echo "usage: $0 <version.properties> <android-artifacts-directory>" >&2
    exit 2
fi

# Portable cross-process lock: mkdir is atomic on POSIX. Works on both
# GitHub's ubuntu-latest (where BSD lockf doesn't exist) and macOS.
# The re-exec guard below makes the whole critical section reentrant-safe.
if [ "${XLIB_ANDROID_VERSION_CODE_PREPARED:-0}" != "1" ]; then
    export XLIB_ANDROID_VERSION_CODE_PREPARED=1
    lock_dir="${TMPDIR:-/tmp}/com.xlib.txtreader-version-code.lock"
    rm -rf "$lock_dir"
    if ! mkdir "$lock_dir" 2>/dev/null; then
        echo "could not acquire lock: $lock_dir" >&2
        exit 1
    fi
    trap 'rmdir '"$lock_dir"' 2>/dev/null || rm -rf '"$lock_dir"'' EXIT HUP INT TERM
    exec "$0" "$version_file" "$artifacts_root"
fi

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

version_code_count="$(
    awk '
      /^versionCode=/ { count += 1 }
      END { print count + 0 }
    ' "$version_file"
)"
if [ "$version_code_count" -ne 1 ]; then
    echo "version.properties must contain exactly one versionCode" >&2
    exit 1
fi
current="$(sed -n 's/^versionCode=//p' "$version_file")"
if ! printf '%s\n' "$current" | grep -Eq '^[1-9][0-9]*$'; then
    echo "invalid Android versionCode: ${current:-missing}" >&2
    exit 1
fi

highest=0
if [ -d "$artifacts_root" ]; then
    while IFS= read -r apk; do
        package_line="$($aapt dump badging "$apk" | sed -n '1p')"
        artifact_code="$(
            printf '%s\n' "$package_line" |
                sed -n "s/.* versionCode='\\([^']*\\)'.*/\\1/p"
        )"
        if ! printf '%s\n' "$artifact_code" | grep -Eq '^[1-9][0-9]*$'; then
            echo "could not read versionCode from $apk" >&2
            exit 1
        fi
        if [ "$artifact_code" -gt "$highest" ]; then
            highest="$artifact_code"
        fi
    done <<EOF
$(find "$artifacts_root" -type f -name '*.apk' -print)
EOF
fi

if [ "$current" -le "$highest" ]; then
    next=$((highest + 1))
    temporary="$version_file.version-code-prepare.$$"
    trap 'rm -f "$temporary"' EXIT HUP INT TERM
    sed "s/^versionCode=.*/versionCode=$next/" "$version_file" >"$temporary"
    updated="$(sed -n 's/^versionCode=//p' "$temporary")"
    if [ "$updated" != "$next" ]; then
        echo "could not prepare Android versionCode" >&2
        exit 1
    fi
    mv "$temporary" "$version_file"
    trap - EXIT HUP INT TERM
    printf 'Android versionCode reconciled: %s -> %s (highest artifact: %s)\n' \
        "$current" "$next" "$highest"
else
    printf 'Android versionCode ready: %s (highest artifact: %s)\n' \
        "$current" "$highest"
fi
