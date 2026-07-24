#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
release="${1:-}"

if [ -z "$release" ]; then
    echo "usage: $0 <monorepo-release-version>" >&2
    exit 2
fi

"$root/scripts/release-check.sh" "$release"
RELEASE_VERSION="$release" "$root/scripts/check-alpha.sh"

echo "release $release is prepared for review"
echo "no tag, commit, artifact publication, signing, or deployment was performed"
