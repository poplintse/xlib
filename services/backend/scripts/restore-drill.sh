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

repo_root="$(CDPATH= cd -- "$(dirname "$0")/../../.." && pwd)"
service="$repo_root/services/backend"
backup_filename="$1"
drill_database="xlib_restore_drill_$(date -u +%Y%m%d%H%M%S)_$$"

compose() {
  docker compose --env-file "$repo_root/.env" -f "$service/compose.yaml" "$@"
}

cleanup() {
  compose exec -T postgres sh -eu -c \
    'dropdb --username "$POSTGRES_USER" --if-exists --force "$1"' \
    sh "$drill_database" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

compose exec -T postgres test -f "/backups/$backup_filename"
compose exec -T postgres sh -eu -c \
  'createdb --username "$POSTGRES_USER" "$1"' \
  sh "$drill_database"
compose exec -T postgres sh -eu -c \
  'pg_restore --username "$POSTGRES_USER" --dbname "$1" --exit-on-error --no-owner "$2"' \
  sh "$drill_database" "/backups/$backup_filename"

table_count="$(compose exec -T postgres sh -eu -c \
  'psql --username "$POSTGRES_USER" --dbname "$1" --tuples-only --no-align \
    --command "select count(*) from information_schema.tables where table_schema = '\''public'\'' and table_name in ('\''users'\'','\''devices'\'','\''reading_progress'\'');"' \
  sh "$drill_database")"

if [ "$table_count" != "3" ]; then
  echo "restore drill failed: expected 3 service tables, found $table_count" >&2
  exit 1
fi

echo "restore drill passed for $backup_filename"
