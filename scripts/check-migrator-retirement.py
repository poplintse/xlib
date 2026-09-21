#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
PLAN_PATH = ROOT / "docs/architecture/migrator-retirement-plan.json"


def version(value: str) -> tuple[int, ...]:
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", value):
        raise AssertionError(f"invalid release version: {value}")
    return tuple(int(part) for part in value.split("."))


def release_statuses() -> dict[str, str]:
    result: dict[str, str] = {}
    for path in sorted((ROOT / "releases").glob("*.yaml")):
        text = path.read_text()
        release = re.search(r"^release:\s*(\S+)\s*$", text, re.MULTILINE)
        status = re.search(r"^status:\s*(\S+)\s*$", text, re.MULTILINE)
        if not release or not status:
            raise AssertionError(f"release manifest lacks release/status: {path}")
        result[release.group(1)] = status.group(1)
    return result


def manifest_value(text: str, field: str) -> str | None:
    match = re.search(rf"^\s+{re.escape(field)}:\s*(.*?)\s*$", text, re.MULTILINE)
    return match.group(1) if match else None


def git_tags() -> set[str]:
    completed = subprocess.run(
        ["git", "-C", str(ROOT), "tag", "--list"],
        check=True,
        capture_output=True,
        text=True,
    )
    return {line.strip() for line in completed.stdout.splitlines() if line.strip()}


def main() -> int:
    plan = json.loads(PLAN_PATH.read_text())
    if plan.get("schema_version") != 1:
        raise AssertionError("unsupported migrator retirement plan schema")
    first_sqlite = plan["first_sqlite_migration_release"]
    first_sqlite_version = version(first_sqlite)
    statuses = release_statuses()
    tags = git_tags()

    if first_sqlite not in statuses:
        raise AssertionError(f"missing first SQLite migration release manifest: {first_sqlite}")
    baseline_manifest = (ROOT / "releases" / f"{first_sqlite}.yaml").read_text()
    if not re.search(r"^\s+contains_legacy_migrator:\s+true\s*$", baseline_manifest, re.MULTILINE):
        raise AssertionError(f"migration baseline {first_sqlite} must declare contains_legacy_migrator: true")
    if not re.search(r"^\s+minimum_direct_from:\s+0\.9\.0\s*$", baseline_manifest, re.MULTILINE):
        raise AssertionError(f"migration baseline {first_sqlite} must accept direct upgrades from 0.9.0")
    released_pre_sqlite = sorted(
        (release for release, status in statuses.items() if status == "released" and version(release) < first_sqlite_version),
        key=version,
    )
    blockers: list[str] = []
    if statuses[first_sqlite] != "released":
        blockers.append(f"migration release {first_sqlite} is {statuses[first_sqlite]}")
    if first_sqlite not in tags:
        blockers.append(f"migration release {first_sqlite} has no git tag")

    minimum = plan.get("minimum_direct_upgrade_from")
    intermediate = plan.get("forced_intermediate_release")
    if minimum is None and intermediate is None:
        blockers.append("minimum direct-upgrade source and forced intermediate release are undecided")
    elif minimum is None or intermediate is None:
        blockers.append("minimum direct-upgrade source and forced intermediate release must be declared together")
    if minimum is not None and version(minimum) < first_sqlite_version:
        blockers.append(f"minimum direct-upgrade source {minimum} is pre-SQLite")
    if intermediate is not None:
        if intermediate not in statuses or statuses[intermediate] != "released" or intermediate not in tags:
            blockers.append(f"forced intermediate release {intermediate} is not both released and tagged")
        elif version(intermediate) < first_sqlite_version:
            blockers.append(f"forced intermediate release {intermediate} is pre-SQLite")
        if minimum is not None and version(minimum) < version(intermediate):
            blockers.append(
                f"minimum direct-upgrade source {minimum} precedes forced intermediate release {intermediate}"
            )
    if released_pre_sqlite and minimum is None and intermediate is None:
        blockers.append(f"released pre-SQLite direct upgrade remains supported: {', '.join(released_pre_sqlite)}")

    enabled = plan.get("enabled") is True
    if enabled and blockers:
        raise AssertionError("migrator retirement was enabled while blocked: " + "; ".join(blockers))
    if enabled:
        retirement = plan["retirement_release"]
        manifest_path = ROOT / "releases" / f"{retirement}.yaml"
        if not manifest_path.is_file():
            raise AssertionError(f"missing retirement release manifest: {retirement}")
        retirement_manifest = manifest_path.read_text()
        if manifest_value(retirement_manifest, "contains_legacy_migrator") != "false":
            raise AssertionError("retirement release must declare contains_legacy_migrator: false")
        if manifest_value(retirement_manifest, "minimum_direct_from") != minimum:
            raise AssertionError("retirement release minimum direct-upgrade source mismatch")
        if manifest_value(retirement_manifest, "required_intermediate") != intermediate:
            raise AssertionError("retirement release required intermediate mismatch")
        for relative, markers in plan["retired_source_markers"].items():
            source = (ROOT / relative).read_text()
            for marker in markers:
                if marker in source:
                    raise AssertionError(f"retired migrator marker remains in {relative}: {marker}")
        for relative in plan["retired_fixtures"]:
            if (ROOT / relative).exists():
                raise AssertionError(f"retired migration fixture still exists: {relative}")
        print("migrator retirement is enabled; release-chain policy and source retirement pass")
    else:
        print("migrator retirement remains disabled")
        for blocker in blockers:
            print(f"BLOCKED: {blocker}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, TypeError, json.JSONDecodeError, subprocess.SubprocessError) as error:
        print(f"migrator retirement check failed: {error}", file=sys.stderr)
        raise SystemExit(1)
