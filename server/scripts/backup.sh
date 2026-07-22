#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
filename="xlib-sync-${timestamp}.dump"

docker compose exec -T postgres sh -eu -c '
  umask 077
  target="/backups/$1"
  temporary="/backups/.$1.tmp"
  pg_dump --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --format=custom --compress=6 --file "$temporary"
  mv "$temporary" "$target"
' sh "$filename"

echo "backup created: $filename"
