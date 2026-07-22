#!/bin/sh
set -eu

if [ -z "${XLIB_DB_USER:-}" ] || [ -z "${XLIB_DB_PASSWORD:-}" ]; then
  echo "XLIB_DB_USER and XLIB_DB_PASSWORD are required" >&2
  exit 1
fi

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=app_user="$XLIB_DB_USER" \
  --set=app_password="$XLIB_DB_PASSWORD" <<'SQL'
CREATE ROLE :"app_user"
  LOGIN
  PASSWORD :'app_password'
  NOSUPERUSER
  NOCREATEDB
  NOCREATEROLE;
SQL
