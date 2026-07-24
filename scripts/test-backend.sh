#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
cd "$root/services/backend"

run_pnpm() {
    if command -v pnpm >/dev/null 2>&1; then
        pnpm "$@"
    else
        corepack pnpm "$@"
    fi
}

if [ ! -d node_modules ]; then
    echo "backend dependencies are missing; run make bootstrap" >&2
    exit 1
fi

run_pnpm lint
run_pnpm typecheck
run_pnpm test
run_pnpm build
