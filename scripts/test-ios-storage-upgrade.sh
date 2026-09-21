#!/bin/sh
set -eu

root="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
old_ref="${XLIB_PRE_SQLITE_REF:-0.9.0}"
runtime="${XLIB_IOS_SIM_RUNTIME:-com.apple.CoreSimulator.SimRuntime.iOS-26-5}"
device_type="${XLIB_IOS_SIM_DEVICE_TYPE:-com.apple.CoreSimulator.SimDeviceType.iPhone-17}"
work="$(mktemp -d "${TMPDIR:-/tmp}/xlib-ios-upgrade.XXXXXX")"
simulator_id=""

cleanup() {
    if [ -n "$simulator_id" ]; then
        xcrun simctl shutdown "$simulator_id" >/dev/null 2>&1 || true
        xcrun simctl delete "$simulator_id" >/dev/null 2>&1 || true
    fi
    rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

old_source="$work/old-source"
old_derived="$work/old-derived"
current_derived="$work/current-derived"
mkdir -p "$old_source"
git -C "$root" archive "$old_ref" | tar -x -C "$old_source"

simulator_id="$(xcrun simctl create "XLib P7 Upgrade $$" "$device_type" "$runtime")"
xcrun simctl boot "$simulator_id"
xcrun simctl bootstatus "$simulator_id" -b

build_app() {
    source_root="$1"
    derived="$2"
    log="$3"
    if ! xcodebuild \
        -project "$source_root/apps/ios/XLibReader.xcodeproj" \
        -scheme XLibReader \
        -configuration Debug \
        -destination "platform=iOS Simulator,id=$simulator_id" \
        -derivedDataPath "$derived" \
        build >"$log" 2>&1; then
        tail -120 "$log"
        return 1
    fi
}

build_app "$old_source" "$old_derived" "$work/old-build.log"
old_app="$old_derived/Build/Products/Debug-iphonesimulator/XLibReader.app"
xcrun simctl install "$simulator_id" "$old_app"
xcrun simctl launch "$simulator_id" com.xlib.txtreader >/dev/null
sleep 1
xcrun simctl terminate "$simulator_id" com.xlib.txtreader >/dev/null 2>&1 || true

container="$(xcrun simctl get_app_container "$simulator_id" com.xlib.txtreader data)"
python3 - "$container" <<'PY'
from __future__ import annotations

import hashlib
import json
import os
import plistlib
import sys
from pathlib import Path

container = Path(sys.argv[1])
root = container / "Library" / "Application Support" / "XLibReader"
books = root / "Books"
metadata = root / "Metadata"
toc = root / "TOC"
sync = root / "Sync"
for directory in (books, metadata, toc, sync):
    directory.mkdir(parents=True, exist_ok=True)

book_id = "11111111-1111-1111-1111-111111111111"
bookmark_id = "22222222-2222-2222-2222-222222222222"
toc_id = "33333333-3333-3333-3333-333333333333"
device_id = "44444444-4444-4444-4444-444444444444"
content = "第一章\n这是 0.9.0 生成格式的迁移样本。\n".encode("utf-8")
book_path = books / f"{book_id}.txt"
book_path.write_bytes(content)
os.utime(book_path, (1_700_000_000, 1_700_000_000))

book = {
    "id": book_id,
    "title": "P7 升级样本",
    "sourceName": "legacy-0.9.0.txt",
    "author": "迁移测试",
    "relativePath": f"Books/{book_id}.txt",
    "fileSize": len(content),
    "modifiedAt": "2023-11-14T22:13:20Z",
    "encoding": "UTF-8",
    "offset": min(12, len(content)),
    "updatedAt": "2023-11-14T22:15:00Z",
    "schemaVersion": 1,
}
library = {"schemaVersion": 1, "books": [book], "tombstones": []}
bookmarks = {
    "schemaVersion": 1,
    "bookmarks": [{
        "id": bookmark_id,
        "bookID": book_id,
        "offset": min(6, len(content)),
        "excerpt": "第一章",
        "createdAt": "2023-11-14T22:16:40Z",
    }],
}
toc_document = {
    "schemaVersion": 2,
    "fileSize": len(content),
    "modifiedAt": "2023-11-14T22:13:20Z",
    "entries": [{"id": toc_id, "title": "第一章", "offset": 0, "level": 1}],
}
metadata.joinpath("books.json").write_text(json.dumps(library, ensure_ascii=False), encoding="utf-8")
metadata.joinpath("bookmarks.json").write_text(json.dumps(bookmarks, ensure_ascii=False), encoding="utf-8")
toc.joinpath(f"{book_id}.json").write_text(json.dumps(toc_document, ensure_ascii=False), encoding="utf-8")

book_hash = hashlib.sha256(content).hexdigest()
sync_state = {
    # JSONEncoder represents dictionaries with UUID keys as alternating key/value arrays.
    "hashes": [book_id, {
        "fileSize": len(content),
        "modifiedAt": 1_700_000_000 - 978_307_200,
        "hash": book_hash,
    }],
    "remote": {},
}
sync.joinpath("sync-state.json").write_text(json.dumps(sync_state), encoding="utf-8")

settings = {
    "theme": "dark",
    "fontName": ".AppleSystemUIFont",
    "fontSize": 25.0,
    "lineSpacing": 6.0,
    "keepScreenAwake": True,
    "autoPageSeconds": 11,
    "turnSensitivity": 0.6,
}
preferences = container / "Library" / "Preferences" / "com.xlib.txtreader.plist"
preferences.parent.mkdir(parents=True, exist_ok=True)
try:
    with preferences.open("rb") as handle:
        values = plistlib.load(handle)
except (FileNotFoundError, plistlib.InvalidFileException):
    values = {}
values.update({
    "reader.settings.v1": json.dumps(settings, separators=(",", ":")).encode(),
    "sync.server.address.v1": "https://example.invalid/xlib",
    "sync.device.id.v1": device_id.lower(),
    "sync.device.name.v1": "P7 iOS 旧设备",
})
with preferences.open("wb") as handle:
    plistlib.dump(values, handle, fmt=plistlib.FMT_BINARY)

manifest = {
    "book_id": book_id.lower(),
    "bookmark_id": bookmark_id.lower(),
    "file_size": len(content),
    "offset": book["offset"],
    "read_at_ms": 1_700_000_100_000,
    "book_hash": book_hash,
    "legacy_files": [
        "Metadata/books.json", "Metadata/bookmarks.json",
        f"TOC/{book_id}.json", "Sync/sync-state.json",
    ],
}
(root / "pre-p7-expectations.json").write_text(json.dumps(manifest), encoding="utf-8")
PY

xcrun simctl shutdown "$simulator_id"
xcrun simctl boot "$simulator_id"
xcrun simctl bootstatus "$simulator_id" -b

legacy_root="$container/Library/Application Support/XLibReader"
legacy_checksum() {
    python3 - "$1" <<'PY'
import hashlib
import sys
from pathlib import Path

root = Path(sys.argv[1])
digest = hashlib.sha256()
for directory_name in ("Metadata", "TOC", "Sync"):
    directory = root / directory_name
    for path in sorted(candidate for candidate in directory.rglob("*") if candidate.is_file()):
        if path.name.startswith("xlib.db"):
            continue
        digest.update(str(path.relative_to(root)).encode())
        digest.update(path.read_bytes())
print(digest.hexdigest())
PY
}
legacy_checksum_before="$(legacy_checksum "$legacy_root")"

build_app "$root" "$current_derived" "$work/current-build.log"
current_app="$current_derived/Build/Products/Debug-iphonesimulator/XLibReader.app"
xcrun simctl install "$simulator_id" "$current_app"
xcrun simctl launch "$simulator_id" com.xlib.txtreader >/dev/null
sleep 3
xcrun simctl terminate "$simulator_id" com.xlib.txtreader >/dev/null 2>&1 || true

container_after="$(xcrun simctl get_app_container "$simulator_id" com.xlib.txtreader data)"
container="$container_after"
legacy_root="$container/Library/Application Support/XLibReader"
if [ ! -f "$legacy_root/pre-p7-expectations.json" ]; then
    echo "application data was not preserved during upgrade" >&2
    exit 1
fi

verify_database() {
    python3 - "$container" <<'PY'
import json
import plistlib
import sqlite3
import sys
from pathlib import Path

container = Path(sys.argv[1])
root = container / "Library" / "Application Support" / "XLibReader"
expected = json.loads((root / "pre-p7-expectations.json").read_text())
database = root / "Metadata" / "xlib.db"
assert database.exists(), "xlib.db was not created"
connection = sqlite3.connect(database)
assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
book = connection.execute(
    "SELECT local_id,title,source_name,author,relative_path,file_size,encoding FROM books"
).fetchone()
assert book == (
    expected["book_id"], "P7 升级样本", "legacy-0.9.0.txt", "迁移测试",
    f"Books/{expected['book_id'].upper()}.txt", expected["file_size"], "UTF-8",
), book
progress = connection.execute("SELECT offset_bytes,read_at_ms FROM reading_progress").fetchone()
assert progress == (expected["offset"], expected["read_at_ms"]), progress
bookmark = connection.execute("SELECT local_id,book_id,excerpt FROM bookmarks").fetchone()
assert bookmark == (expected["bookmark_id"], expected["book_id"], "第一章"), bookmark
assert connection.execute("SELECT COUNT(*) FROM toc_entries").fetchone()[0] == 1
settings = dict(connection.execute("SELECT key,value FROM settings"))
assert settings["reader.theme"] == "dark"
assert settings["reader.font_size"] == "25.0"
assert settings["reader.keep_screen_awake"] == "true"
configuration = connection.execute(
    "SELECT device_id,device_name,server_url FROM sync_configuration WHERE singleton_id=1"
).fetchone()
assert configuration == (
    "44444444-4444-4444-4444-444444444444", "P7 iOS 旧设备", "https://example.invalid/xlib"
), configuration
hash_row = connection.execute("SELECT book_hash FROM book_hash_cache").fetchone()
assert hash_row is not None, "legacy sync hash was not migrated"
assert hash_row[0] == expected["book_hash"], hash_row
assert connection.execute("SELECT COUNT(*) FROM legacy_migrations").fetchone()[0] == 1
for relative in expected["legacy_files"]:
    assert (root / relative).exists(), f"legacy evidence was removed: {relative}"
assert (root / f"Books/{expected['book_id'].upper()}.txt").exists()
preferences = container / "Library" / "Preferences" / "com.xlib.txtreader.plist"
with preferences.open("rb") as handle:
    values = plistlib.load(handle)
assert values["reader.settings.v1"], "legacy settings were removed"
connection.close()
PY
}

verify_database
xcrun simctl launch "$simulator_id" com.xlib.txtreader >/dev/null
sleep 2
xcrun simctl terminate "$simulator_id" com.xlib.txtreader >/dev/null 2>&1 || true
verify_database

legacy_checksum_after="$(legacy_checksum "$legacy_root")"
if [ "$legacy_checksum_before" != "$legacy_checksum_after" ]; then
    echo "legacy migration evidence changed during upgrade" >&2
    exit 1
fi

echo "iOS storage upgrade passed: $old_ref -> current working tree"
echo "verified SQLite data, TXT preservation, legacy preservation, and idempotent relaunch"
