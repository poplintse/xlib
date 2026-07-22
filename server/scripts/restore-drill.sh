#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <backup-filename>" >&2
  exit 2
fi

case "$1" in
  */*|.*|*[!A-Za-z0-9._-]*)
    echo "backup filename must be a plain safe filename" >&2
    exit 2
    ;;
esac

cd "$(dirname "$0")/.."
backup_filename="$1"
drill_database="xlib_restore_drill_$(date -u +%Y%m%d%H%M%S)_$$"

cleanup() {
  docker compose exec -T postgres dropdb --username "$POSTGRES_USER" \
    --if-exists --force "$drill_database" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

docker compose exec -T postgres test -f "/backups/$backup_filename"
docker compose exec -T postgres createdb --username "$POSTGRES_USER" "$drill_database"
docker compose exec -T postgres pg_restore --username "$POSTGRES_USER" \
  --dbname "$drill_database" --exit-on-error --no-owner "/backups/$backup_filename"

table_count="$(docker compose exec -T postgres psql --username "$POSTGRES_USER" \
  --dbname "$drill_database" --tuples-only --no-align \
  --command "select count(*) from information_schema.tables where table_schema = 'public' and table_name in ('users','devices','reading_progress');")"

if [ "$table_count" != "3" ]; then
  echo "restore drill failed: expected 3 service tables, found $table_count" >&2
  exit 1
fi

echo "restore drill passed for $backup_filename"
