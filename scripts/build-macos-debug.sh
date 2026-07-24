#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"

if ! find "$root/apps/macos" -maxdepth 1 -name '*.xcodeproj' -print -quit | grep -q .; then
    echo "macOS is planned: no buildable Xcode project exists in apps/macos" >&2
    exit 2
fi

echo "a macOS project exists but no canonical scheme has been approved" >&2
exit 2
