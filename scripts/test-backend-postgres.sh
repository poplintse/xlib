#!/bin/sh
# Disposable PostgreSQL 17 cluster with a private Unix socket and no TCP listener.
set -eu
root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
pg_bin="${PG_BIN:-/opt/homebrew/opt/postgresql@17/bin}"
if [ ! -x "$pg_bin/initdb" ]; then
    echo 'Set PG_BIN to a PostgreSQL 17 bin directory.' >&2
    exit 1
fi
case "$("$pg_bin/postgres" --version)" in
    *' 17.'*) ;;
    *) echo 'PostgreSQL 17 is required for this test harness.' >&2; exit 1 ;;
esac
test_root="$(mktemp -d /tmp/xlib-pg17.XXXXXX)"
mkdir "$test_root/socket"
cleanup() {
    if [ -f "$test_root/data/postmaster.pid" ]; then
        "$pg_bin/pg_ctl" -D "$test_root/data" -m fast -w stop >> "$test_root/server.log" 2>&1
    fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"$pg_bin/initdb" -D "$test_root/data" -U xlib_test_owner --auth=trust --no-locale -E UTF8 > "$test_root/init.log" 2>&1
"$pg_bin/pg_ctl" -D "$test_root/data" -l "$test_root/server.log" \
    -o "-F -k $test_root/socket -c listen_addresses='' -c max_connections=20" -w start > /dev/null
"$pg_bin/createdb" -h "$test_root/socket" -U xlib_test_owner xlib_test
"$pg_bin/psql" -h "$test_root/socket" -U xlib_test_owner -d xlib_test -v ON_ERROR_STOP=1 -q \
    -c 'CREATE ROLE xlib_test_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;'
export TEST_DATABASE_URL="postgresql://xlib_test_owner@localhost/xlib_test?host=$test_root/socket"
export TEST_DATABASE_APP_URL="postgresql://xlib_test_app@localhost/xlib_test?host=$test_root/socket"
echo "PostgreSQL 17 isolated test cluster; logs: $test_root"
"$root/scripts/test-backend.sh"
