#!/bin/sh
set -eu

version_file="${1:-}"

if [ -z "$version_file" ] || [ ! -f "$version_file" ]; then
    echo "usage: $0 <version.properties>" >&2
    exit 2
fi

# Portable cross-process lock: mkdir is atomic on POSIX. Works on both
# GitHub's ubuntu-latest (where BSD lockf doesn't exist) and macOS.
# The re-exec guard below makes the whole critical section reentrant-safe.
if [ "${XLIB_ANDROID_VERSION_CODE_LOCKED:-0}" != "1" ]; then
    export XLIB_ANDROID_VERSION_CODE_LOCKED=1
    lock_dir="${TMPDIR:-/tmp}/com.xlib.txtreader-version-code.lock"
    rm -rf "$lock_dir"
    if ! mkdir "$lock_dir" 2>/dev/null; then
        echo "could not acquire lock: $lock_dir" >&2
        exit 1
    fi
    trap 'rmdir '"$lock_dir"' 2>/dev/null || rm -rf '"$lock_dir"'' EXIT HUP INT TERM
    exec "$0" "$version_file"
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

next=$((current + 1))
temporary="$version_file.version-code.$$"
trap 'rm -f "$temporary"' EXIT HUP INT TERM
sed "s/^versionCode=.*/versionCode=$next/" "$version_file" >"$temporary"

updated="$(sed -n 's/^versionCode=//p' "$temporary")"
if [ "$updated" != "$next" ]; then
    echo "could not increment Android versionCode" >&2
    exit 1
fi

mv "$temporary" "$version_file"
trap - EXIT HUP INT TERM

printf 'Android versionCode: %s -> %s\n' "$current" "$next"
