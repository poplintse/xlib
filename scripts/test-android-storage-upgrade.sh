#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
old_ref="${XLIB_PRE_SQLITE_REF:-0.9.0}"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"
avdmanager="$sdk/cmdline-tools/latest/bin/avdmanager"
image="${XLIB_ANDROID_SYSTEM_IMAGE:-system-images;android-35;google_apis;arm64-v8a}"
serial="${XLIB_ANDROID_EMULATOR_SERIAL:-emulator-5580}"
port="${serial#emulator-}"
work="$(mktemp -d "${TMPDIR:-/tmp}/xlib-android-upgrade.XXXXXX")"
avd_home="$work/avd"
avd_name="xlib-p7-upgrade-$$"
emulator_pid=""

cleanup() {
    if [ -n "$emulator_pid" ]; then
        "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
        wait "$emulator_pid" >/dev/null 2>&1 || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for command in "$adb" "$emulator" "$avdmanager"; do
    if [ ! -x "$command" ]; then
        echo "required Android SDK tool is missing: $command" >&2
        exit 1
    fi
done
if "$adb" -s "$serial" get-state >/dev/null 2>&1; then
    echo "$serial is already in use; set XLIB_ANDROID_EMULATOR_SERIAL to an unused even port" >&2
    exit 1
fi

old_source="$work/old-source"
mkdir -p "$old_source" "$avd_home"
git -C "$root" archive "$old_ref" | tar -x -C "$old_source"

printf 'no\n' | ANDROID_AVD_HOME="$avd_home" "$avdmanager" create avd \
    --force --name "$avd_name" --package "$image" --device pixel_6 >/dev/null
ANDROID_AVD_HOME="$avd_home" "$emulator" -avd "$avd_name" -port "$port" \
    -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -gpu swiftshader_indirect \
    >"$work/emulator.log" 2>&1 &
emulator_pid=$!

echo "waiting for temporary Android emulator $serial"
"$adb" -s "$serial" wait-for-device
attempt=0
while [ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 240 ]; then
        tail -120 "$work/emulator.log" >&2
        echo "Android emulator did not finish booting" >&2
        exit 1
    fi
    if [ $((attempt % 20)) -eq 0 ]; then echo "still waiting for Android boot ($attempt seconds)"; fi
    sleep 1
done
"$adb" -s "$serial" shell settings put global window_animation_scale 0
"$adb" -s "$serial" shell settings put global transition_animation_scale 0
"$adb" -s "$serial" shell settings put global animator_duration_scale 0

build_android() {
    source_root="$1"
    log="$2"
    if ! (cd "$source_root/apps/android" && ./gradlew --no-daemon :app:assembleDebug) >"$log" 2>&1; then
        tail -160 "$log" >&2
        return 1
    fi
}

echo "building and installing Android $old_ref"
build_android "$old_source" "$work/old-build.log"
old_apk="$old_source/apps/android/app/build/outputs/apk/debug/xlib-debug.apk"
"$adb" -s "$serial" install "$old_apk" >/dev/null
"$adb" -s "$serial" shell am start -W -n com.xlib.txtreader/.MainActivity >/dev/null
sleep 1
"$adb" -s "$serial" shell am force-stop com.xlib.txtreader

data_root="$("$adb" -s "$serial" shell run-as com.xlib.txtreader pwd | tr -d '\r')"
python3 - "$work" "$data_root" <<'PY'
import hashlib
import json
import sys
from pathlib import Path
from xml.sax.saxutils import escape

work = Path(sys.argv[1])
data_root = sys.argv[2]
book_id = 101
bookmark_id = 201
content = "第一章\n这是 Android 0.9.0 格式的迁移样本。\n".encode()
book_path = f"{data_root}/files/books/p7-upgrade.txt"
book = {
    "id": book_id,
    "title": "P7 Android 升级样本",
    "sourceName": "legacy-0.9.0.txt",
    "author": "迁移测试",
    "path": book_path,
    "fileSize": len(content),
    "encoding": "UTF-8",
    "offset": min(12, len(content)),
    "progress": 0,
    "pageMode": False,
    "updatedAt": 1_700_000_100_000,
}
bookmark = {"id": bookmark_id, "bookId": book_id, "offset": 6, "createdAt": 1_700_000_200_000}
book_hash = hashlib.sha256(content).hexdigest()
hash_cache = json.dumps({
    "fileSize": len(content), "modifiedAt": 1_700_000_000_000, "hash": book_hash,
}, separators=(",", ":"))
remote = [{
    "bookHash": book_hash,
    "fileSize": len(content),
    "offset": min(18, len(content)),
    "progress": min(18, len(content)) / len(content),
    "readAtMs": 1_700_000_300_000,
    "version": "legacy-v1",
    "sourceDeviceId": "44444444-4444-4444-4444-444444444444",
    "sourceDeviceName": "P7 Android 旧设备",
    "sourcePlatform": "android",
    "fetchedAtMs": 1_700_000_400_000,
}]
strings = {
    "books": json.dumps([book], ensure_ascii=False, separators=(",", ":")),
    "bookmarks": json.dumps([bookmark], ensure_ascii=False, separators=(",", ":")),
    "app_theme": "dark",
    "font_family": "sans",
    "sync_configured_email": "Reader@Example.com",
    "sync_email": "reader@example.com",
    "sync_device_id": "55555555-5555-5555-5555-555555555555",
    "sync_device_name": "P7 Android 旧设备",
    "sync_server_url": "https://example.invalid/xlib",
    f"sync_book_hash_{book_id}": hash_cache,
    "sync_remote_email": "Reader@Example.com",
    "sync_remote_items": json.dumps(remote, ensure_ascii=False, separators=(",", ":")),
}
lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
for key, value in strings.items():
    lines.append(f'    <string name="{key}">{escape(value)}</string>')
lines.extend([
    '    <boolean name="auto_toc" value="true" />',
    '    <boolean name="keep_screen_on" value="true" />',
    '    <boolean name="sync_started" value="true" />',
    '    <int name="auto_page_interval" value="11" />',
    '    <float name="sensitivity" value="0.6" />',
    '    <float name="font_size" value="24.0" />',
    '    <float name="line_spacing" value="6.0" />',
    "</map>",
])
(work / "xlib_reader.xml").write_text("\n".join(lines) + "\n")
(work / "book.txt").write_bytes(content)
(work / "toc.json").write_text(json.dumps({
    "fileSize": len(content),
    "modifiedAt": 1_700_000_000_000,
    "encoding": "UTF-8",
    "entries": [{"level": 1, "title": "第一章", "offset": 0}],
}, ensure_ascii=False, separators=(",", ":")))
(work / "expected.json").write_text(json.dumps({
    "book_id": str(book_id), "bookmark_id": str(bookmark_id), "file_size": len(content),
    "offset": book["offset"], "read_at_ms": book["updatedAt"], "book_hash": book_hash,
    "remote_offset": remote[0]["offset"], "relative_path": "books/p7-upgrade.txt",
}))
PY

for file in xlib_reader.xml book.txt toc.json; do
    "$adb" -s "$serial" push "$work/$file" "/data/local/tmp/xlib-$file" >/dev/null
done
"$adb" -s "$serial" shell run-as com.xlib.txtreader mkdir -p files/books files/toc shared_prefs
"$adb" -s "$serial" shell run-as com.xlib.txtreader cp /data/local/tmp/xlib-book.txt files/books/p7-upgrade.txt
"$adb" -s "$serial" shell run-as com.xlib.txtreader cp /data/local/tmp/xlib-toc.json files/toc/101.json
"$adb" -s "$serial" shell run-as com.xlib.txtreader cp /data/local/tmp/xlib-xlib_reader.xml shared_prefs/xlib_reader.xml
"$adb" -s "$serial" shell run-as com.xlib.txtreader chmod 600 shared_prefs/xlib_reader.xml
"$adb" -s "$serial" shell rm /data/local/tmp/xlib-book.txt /data/local/tmp/xlib-toc.json /data/local/tmp/xlib-xlib_reader.xml

legacy_checksum() {
    {
        for path in shared_prefs/xlib_reader.xml files/toc/101.json files/books/p7-upgrade.txt; do
            printf '%s\n' "$path"
            "$adb" -s "$serial" exec-out run-as com.xlib.txtreader cat "$path"
        done
    } | shasum -a 256 | awk '{print $1}'
}
legacy_checksum_before="$(legacy_checksum)"

echo "building and installing current Android working tree"
build_android "$root" "$work/current-build.log"
current_apk="$root/apps/android/app/build/outputs/apk/debug/xlib-debug.apk"
"$adb" -s "$serial" install -r "$current_apk" >/dev/null
"$adb" -s "$serial" shell am start -W -n com.xlib.txtreader/.MainActivity >/dev/null
sleep 3
"$adb" -s "$serial" shell am force-stop com.xlib.txtreader

pull_database() {
    destination="$1"
    mkdir -p "$destination"
    for name in xlib.db xlib.db-wal xlib.db-shm; do
        if "$adb" -s "$serial" shell run-as com.xlib.txtreader test -f "databases/$name"; then
            "$adb" -s "$serial" exec-out run-as com.xlib.txtreader cat "databases/$name" >"$destination/$name"
        fi
    done
}

verify_database() {
    database_directory="$1"
    python3 - "$database_directory" "$work/expected.json" <<'PY'
import json
import sqlite3
import sys
from pathlib import Path

directory = Path(sys.argv[1])
expected = json.loads(Path(sys.argv[2]).read_text())
database = directory / "xlib.db"
assert database.exists(), "xlib.db was not created"
connection = sqlite3.connect(database)
assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
book = connection.execute(
    "SELECT local_id,title,source_name,author,relative_path,file_size,encoding FROM books"
).fetchone()
assert book == (
    expected["book_id"], "P7 Android 升级样本", "legacy-0.9.0.txt", "迁移测试",
    expected["relative_path"], expected["file_size"], "UTF-8",
), book
progress = connection.execute("SELECT offset_bytes,read_at_ms FROM reading_progress").fetchone()
assert progress == (expected["offset"], expected["read_at_ms"]), progress
assert connection.execute("SELECT page_mode FROM book_preferences").fetchone() == (0,)
bookmark = connection.execute("SELECT local_id,book_id,offset_bytes FROM bookmarks").fetchone()
assert bookmark == (expected["bookmark_id"], expected["book_id"], 6), bookmark
assert connection.execute("SELECT COUNT(*) FROM toc_entries").fetchone()[0] == 1
settings = dict(connection.execute("SELECT key,value FROM settings"))
assert settings["app_theme"] == "dark"
assert settings["font_size"] == "24.0"
assert settings["keep_screen_on"] == "true"
configuration = connection.execute(
    "SELECT configured_email,device_id,device_name,server_url,has_started FROM sync_configuration WHERE singleton_id=1"
).fetchone()
assert configuration == (
    "Reader@Example.com", "55555555-5555-5555-5555-555555555555",
    "P7 Android 旧设备", "https://example.invalid/xlib", 1,
), configuration
assert connection.execute("SELECT book_hash FROM book_hash_cache").fetchone() == (expected["book_hash"],)
remote = connection.execute("SELECT account_scope,offset_bytes FROM remote_progress_cache").fetchone()
assert remote == ("reader@example.com", expected["remote_offset"]), remote
assert connection.execute("SELECT COUNT(*) FROM legacy_migrations").fetchone()[0] == 1
connection.close()
PY
}

pull_database "$work/database-first"
verify_database "$work/database-first"
"$adb" -s "$serial" shell run-as com.xlib.txtreader test -f files/books/p7-upgrade.txt
"$adb" -s "$serial" shell run-as com.xlib.txtreader test -f files/toc/101.json
"$adb" -s "$serial" shell run-as com.xlib.txtreader test -f shared_prefs/xlib_reader.xml

"$adb" -s "$serial" shell am start -W -n com.xlib.txtreader/.MainActivity >/dev/null
sleep 2
"$adb" -s "$serial" shell am force-stop com.xlib.txtreader
pull_database "$work/database-second"
verify_database "$work/database-second"

legacy_checksum_after="$(legacy_checksum)"
if [ "$legacy_checksum_before" != "$legacy_checksum_after" ]; then
    echo "legacy migration evidence changed during Android upgrade" >&2
    exit 1
fi

echo "Android storage upgrade passed: $old_ref -> current working tree"
echo "verified SQLite data, TXT preservation, legacy preservation, and idempotent relaunch"
