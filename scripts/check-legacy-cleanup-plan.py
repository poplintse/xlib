#!/usr/bin/env python3
from __future__ import annotations

import fnmatch
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
PLAN_PATH = ROOT / "docs/architecture/legacy-persistence-cleanup-plan.json"


def fail(message: str) -> None:
    raise AssertionError(message)


def quoted_values(source: str) -> set[str]:
    return set(re.findall(r'"([^"\\\n]+)"', source))


def validate_android(plan: dict[str, object]) -> None:
    source = (ROOT / "apps/android/app/src/main/java/com/xlib/txtreader/LocalDatabase.java").read_text()
    credential_source = (ROOT / "apps/android/app/src/main/java/com/xlib/txtreader/SyncTokenStore.java").read_text()
    remove = plan["remove"]
    preserve = plan["preserve"]
    assert isinstance(remove, dict) and isinstance(preserve, dict)
    exact = set(remove["preferences_exact"])
    prefixes = set(remove["preference_prefixes"])
    protected = set(preserve["preferences_exact"])

    if exact & protected:
        fail(f"Android cleanup removes protected preferences: {sorted(exact & protected)}")
    if any(any(key.startswith(prefix) for prefix in prefixes) for key in protected):
        fail("Android cleanup prefix overlaps protected preferences")
    if plan["migration_id"] not in source:
        fail("Android migration ID does not match LocalDatabase")
    if f'DATABASE_VERSION = {plan["database_schema_version"]}' not in source:
        fail("Android database schema version does not match LocalDatabase")

    discovered = {
        value
        for value in quoted_values(source + credential_source)
        if value in {"books", "bookmarks"}
        or (value.startswith("sync_") and value != "sync_configuration")
        or value in {
            "auto_toc", "app_theme", "keep_screen_on", "auto_page_interval",
            "sensitivity", "font_family", "font_size", "line_spacing",
        }
    }
    represented = exact | protected
    missing = {key for key in discovered if not any(key.startswith(prefix) for prefix in prefixes)} - represented
    if missing:
        fail(f"Android legacy preferences are absent from remove/preserve plan: {sorted(missing)}")
    for alias in preserve["secure_aliases"]:
        if alias not in credential_source:
            fail(f"Android protected Keystore alias is absent from source: {alias}")
    validate_paths("Android", remove["files"], preserve["paths"])


def validate_ios(plan: dict[str, object]) -> None:
    source = (ROOT / "apps/ios/XLibReader/Persistence/LocalDatabase.swift").read_text()
    server_source = (ROOT / "apps/ios/XLibReader/Sync/SyncServerConfiguration.swift").read_text()
    credential_source = (ROOT / "apps/ios/XLibReader/Sync/SyncCredentialVault.swift").read_text()
    remove = plan["remove"]
    preserve = plan["preserve"]
    assert isinstance(remove, dict) and isinstance(preserve, dict)
    defaults = set(remove["user_defaults_exact"])

    if plan["migration_id"] not in source:
        fail("iOS migration ID does not match LocalDatabase")
    if f'schemaVersion = {plan["database_schema_version"]}' not in source:
        fail("iOS database schema version does not match LocalDatabase")
    discovered = {
        value for value in quoted_values(source + server_source)
        if value == "reader.settings.v1" or (value.startswith("sync.") and value.endswith(".v1"))
    }
    if discovered != defaults:
        fail(
            "iOS UserDefaults cleanup plan differs from migrator keys: "
            f"missing={sorted(discovered - defaults)}, extra={sorted(defaults - discovered)}"
        )
    for identity in preserve["keychain"]:
        for value in (identity["service"], identity["account"]):
            if value not in credential_source:
                fail(f"iOS protected Keychain identity is absent from source: {value}")
    validate_paths("iOS", remove["files"], preserve["paths"])


def validate_paths(platform: str, removed: list[str], preserved: list[str]) -> None:
    if any(path in {"*", "**", "/"} for path in removed):
        fail(f"{platform} cleanup contains an unrestricted path")
    samples = {
        "files/books/sample.txt", "databases/xlib.db", "databases/xlib.db-wal",
        "Books/sample.txt", "Metadata/xlib.db", "Metadata/xlib.db-shm",
    }
    for sample in samples:
        if any(fnmatch.fnmatchcase(sample, keep) for keep in preserved) and any(
            fnmatch.fnmatchcase(sample, remove) for remove in removed
        ):
            fail(f"{platform} cleanup path overlaps protected data: {sample}")


def simulate_interrupted_cleanup(platform: str, remove: dict[str, list[str]], preserve: dict[str, object]) -> None:
    if platform == "android":
        keys = set(remove["preferences_exact"]) | {
            "sync_book_hash_101", "sync_token_ciphertext", "sync_token_iv", "future_unknown_key"
        }
        protected = set(preserve["preferences_exact"]) | {"future_unknown_key"}
        paths = {"files/toc/101.json", "files/books/sample.txt", "databases/xlib.db", "files/future.data"}
        protected_paths = {"files/books/sample.txt", "databases/xlib.db", "files/future.data"}
        ordered = sorted(key for key in keys if key in remove["preferences_exact"] or any(
            key.startswith(prefix) for prefix in remove["preference_prefixes"]
        ))
    else:
        keys = set(remove["user_defaults_exact"]) | {"future.unknown.key"}
        protected = {"future.unknown.key"}
        paths = {
            "Metadata/books.json", "TOC/101.json", "Sync/sync-state.json",
            "Books/sample.txt", "Metadata/xlib.db", "Future/data.bin",
        }
        protected_paths = {"Books/sample.txt", "Metadata/xlib.db", "Future/data.bin"}
        ordered = sorted(remove["user_defaults_exact"])

    midpoint = len(ordered) // 2
    for key in ordered[:midpoint]:
        keys.discard(key)
    for key in ordered:
        keys.discard(key)
    for key in ordered:
        keys.discard(key)
    if keys != protected:
        fail(f"{platform} interrupted/resumed cleanup did not preserve only protected or unknown keys: {keys}")

    targeted_paths = sorted(path for path in paths if any(
        fnmatch.fnmatchcase(path, pattern) for pattern in remove["files"]
    ))
    midpoint = len(targeted_paths) // 2
    for path in targeted_paths[:midpoint]:
        paths.discard(path)
    for path in targeted_paths:
        paths.discard(path)
    for path in targeted_paths:
        paths.discard(path)
    if paths != protected_paths:
        fail(f"{platform} interrupted/resumed file cleanup damaged protected or unknown paths: {paths}")


def main() -> int:
    plan = json.loads(PLAN_PATH.read_text())
    if plan.get("schema_version") != 1:
        fail("unsupported cleanup plan schema")
    if plan.get("enabled") is not True:
        fail("legacy cleanup must remain enabled after P7.2")
    validate_android(plan["android"])
    validate_ios(plan["ios"])
    simulate_interrupted_cleanup("android", plan["android"]["remove"], plan["android"]["preserve"])
    simulate_interrupted_cleanup("ios", plan["ios"]["remove"], plan["ios"]["preserve"])
    print("legacy cleanup is enabled, source-aligned, idempotent, and does not target protected data")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"legacy cleanup plan check failed: {error}", file=sys.stderr)
        raise SystemExit(1)
