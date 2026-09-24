#!/usr/bin/env python3
"""Generate deterministic P8 TXT fixtures and validate physical-device results."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import statistics
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "artifacts" / "p8-performance"
SMALL_BYTES = 128 * 1024
LARGE_BYTES = 32 * 1024 * 1024
BULK_BOOKS = 20
BULK_BYTES = 256 * 1024
MIN_REPEATED_RUNS = 5
MIN_PAGE_TURNS = 30
SEARCH_TOKEN = "P8NEEDLE"

ENCODINGS = {
    "utf8": ("utf-8", b""),
    "utf16le": ("utf-16le", b"\xff\xfe"),
    "gb18030": ("gb18030", b""),
}

OPEN_SCENARIOS = [
    f"open_{size}_{encoding}_{state}"
    for size in ("small", "large")
    for encoding in ENCODINGS
    for state in ("cold", "cached")
]

IOS_OPEN_TEST_PATTERN = re.compile(
    r"testOpen(?P<size>Small|Large)(?P<encoding>UTF8|UTF16LE|GB18030)(?P<state>Cold|Cached)\(\)$"
)
IOS_OPEN_ENCODING = {"UTF8": "utf8", "UTF16LE": "utf16le", "GB18030": "gb18030"}
IOS_OPEN_DURATION_METRIC = "com.apple.dt.XCTMetric_OSSignpost-OpenToFirstTextDraw.duration"
IOS_OPEN_PEAK_MEMORY_METRIC = "com.apple.dt.XCTMetric_Memory-com.xlib.txtreader.physical_peak"
IOS_OPEN_LOGICAL_WRITES_METRIC = "com.apple.dt.XCTMetric_Disk-com.xlib.txtreader.logical_writes"
IOS_PAGE_TESTS = {
    "testPageForward()": ("page_forward", "PageTurnForward"),
    "testPageBackward()": ("page_backward", "PageTurnBackward"),
}
IOS_SEARCH_TEST = "testSearchOver200()"
IOS_SEARCH_CANCELLATION_TEST = "testSearchCancellation()"
IOS_BULK_TESTS = {
    "testBulkImport()": ("bulk_import", "BulkImport", "total_ms"),
    "testBulkImportInterruptedRecovery()": (
        "bulk_import_interrupted",
        "BulkImportRecovery",
        "recovery_ms",
    ),
}
IOS_PEAK_MEMORY_METRIC = "com.apple.dt.XCTMetric_Memory-com.xlib.txtreader.physical_peak"


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_bytes(data)
    os.replace(temporary, path)


def fixture_line(index: int, book: int = 0) -> str:
    chapter = index // 40 + 1
    marker = f" {SEARCH_TOKEN}" if index % 17 == 0 else ""
    return (
        f"第{chapter:06d}章 固定样本 {book:02d}\n"
        f"第{index:08d}行 阅读性能验证文本 ABC123，包含中文标点。{marker}\n"
    )


def write_text_fixture(path: Path, target_bytes: int, encoding_key: str, book: int = 0) -> dict[str, Any]:
    codec, bom = ENCODINGS[encoding_key]
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    digest = hashlib.sha256()
    written = 0
    lines = 0
    matches = 0
    with temporary.open("wb") as output:
        if bom:
            output.write(bom)
            digest.update(bom)
            written += len(bom)
        index = 0
        while written < target_bytes:
            text = fixture_line(index, book)
            encoded = text.encode(codec)
            output.write(encoded)
            digest.update(encoded)
            written += len(encoded)
            lines += text.count("\n")
            matches += text.count(SEARCH_TOKEN)
            index += 1
    os.replace(temporary, path)
    return {
        "path": path.as_posix(),
        "encoding": encoding_key,
        "byte_size": written,
        "sha256": digest.hexdigest(),
        "line_count": lines,
        "search_token": SEARCH_TOKEN,
        "search_matches": matches,
    }


def fixture_set_digest(fixtures: list[dict[str, Any]]) -> str:
    payload = json.dumps(fixtures, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(payload).hexdigest()


def empty_client(version: str, build: str) -> dict[str, Any]:
    scenarios: dict[str, Any] = {}
    for name in OPEN_SCENARIOS:
        scenarios[name] = {
            "duration_ms": [],
            "peak_memory_mb": [],
            "logical_writes_kb": [],
            "bytes_read": [],
        }
    scenarios.update(
        {
            "page_forward": {"turn_latency_ms": [], "peak_memory_mb": []},
            "page_backward": {"turn_latency_ms": [], "peak_memory_mb": []},
            "search_over_200": {
                "first_result_ms": [],
                "completion_ms": [],
                "peak_memory_mb": [],
                "cancellation_passed": None,
            },
            "bulk_import": {
                "total_ms": [],
                "peak_memory_mb": [],
                "retained_temporary_files": None,
            },
            "bulk_import_interrupted": {
                "recovery_ms": [],
                "peak_memory_mb": [],
                "retained_temporary_files": None,
                "recovery_passed": None,
            },
        }
    )
    return {
        "version": version,
        "build": build,
        "configuration": "release",
        "device": {"model": "", "os": ""},
        "scenarios": scenarios,
    }


def generate(output: Path) -> None:
    fixture_root = output / "fixtures"
    fixtures: list[dict[str, Any]] = []
    for size, target in (("small", SMALL_BYTES), ("large", LARGE_BYTES)):
        for encoding in ENCODINGS:
            relative = Path("fixtures") / f"p8-{size}-{encoding}.txt"
            item = write_text_fixture(output / relative, target, encoding)
            item["path"] = relative.as_posix()
            item["purpose"] = f"{size} open/page/search"
            fixtures.append(item)

    for index in range(BULK_BOOKS):
        encoding = tuple(ENCODINGS)[index % len(ENCODINGS)]
        relative = Path("fixtures") / "bulk" / f"p8-bulk-{index + 1:02d}-{encoding}.txt"
        item = write_text_fixture(output / relative, BULK_BYTES, encoding, book=index + 1)
        item["path"] = relative.as_posix()
        item["purpose"] = "bulk import"
        fixtures.append(item)

    digest = fixture_set_digest(fixtures)
    manifest = {
        "schema_version": 1,
        "fixture_set_sha256": digest,
        "parameters": {
            "small_target_bytes": SMALL_BYTES,
            "large_target_bytes": LARGE_BYTES,
            "bulk_book_count": BULK_BOOKS,
            "bulk_target_bytes_each": BULK_BYTES,
        },
        "fixtures": fixtures,
    }
    android = empty_client("0.11.0", "69")
    android["physical_performance_waiver"] = {
        "scope": "P8 Android physical-device performance",
        "authorized_by": "user",
        "reason": "The user explicitly declined Android physical-device performance collection for this P8 run on 2026-09-22.",
    }
    record = {
        "schema_version": 1,
        "release": "0.11.0",
        "fixture_set_sha256": digest,
        "definition": {
            "cold": "app process terminated; first book open after process launch; fixture already imported",
            "cached": "same installation; close reader and immediately reopen the same book",
            "open_io": (
                "XCTest process logical writes during the measured open interval; current "
                "XCTStorageMetric does not expose read bytes"
            ),
            "bytes_read": (
                "optional exploratory process disk-read samples only; not required by the "
                "automated gate and not substituted with requested TXT bytes"
            ),
            "run_requirements": {
                "repeated_scenarios": MIN_REPEATED_RUNS,
                "page_turns_per_direction": MIN_PAGE_TURNS,
            },
        },
        "clients": {
            "android": android,
            "ios": empty_client("0.11.0", "45"),
        },
    }
    record_path = output / "performance-record.json"
    if record_path.exists():
        existing = json.loads(record_path.read_text())
        if existing.get("fixture_set_sha256") != digest:
            raise SystemExit(
                f"Existing {record_path} belongs to another fixture set; move it before regenerating"
            )
        record_message = f"Preserved existing results in {record_path}"
    else:
        atomic_write(record_path, json.dumps(record, ensure_ascii=False, indent=2).encode() + b"\n")
        record_message = f"Fill results in {record_path}"
    atomic_write(output / "fixture-manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2).encode() + b"\n")
    print(f"Generated {len(fixtures)} fixtures in {fixture_root}")
    print(f"Fixture set SHA-256: {digest}")
    print(record_message)


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def numeric_runs(value: Any, count: int, path: str, errors: list[str], *, integer: bool = False) -> list[float]:
    if not isinstance(value, list):
        errors.append(f"{path} must be an array")
        return []
    require(len(value) >= count, f"{path} needs at least {count} runs", errors)
    result: list[float] = []
    for index, item in enumerate(value):
        valid = (
            isinstance(item, (int, float))
            and not isinstance(item, bool)
            and math.isfinite(item)
            and item >= 0
        )
        if integer:
            valid = valid and isinstance(item, int)
        require(valid, f"{path}[{index}] must be a non-negative {'integer' if integer else 'number'}", errors)
        if valid:
            result.append(float(item))
    return result


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def summarize(label: str, values: list[float], rows: list[tuple[str, int, float, float]]) -> None:
    if values:
        rows.append((label, len(values), statistics.median(values), percentile(values, 0.95)))


def validate_fixtures(root: Path, manifest: dict[str, Any], errors: list[str]) -> None:
    fixtures = manifest.get("fixtures")
    require(isinstance(fixtures, list) and bool(fixtures), "fixture manifest has no fixtures", errors)
    if not isinstance(fixtures, list):
        return
    require(manifest.get("fixture_set_sha256") == fixture_set_digest(fixtures), "fixture set digest is invalid", errors)
    for item in fixtures:
        path = root / str(item.get("path", ""))
        if not path.is_file():
            errors.append(f"missing fixture: {path}")
            continue
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        data_digest = digest.hexdigest()
        require(path.stat().st_size == item.get("byte_size"), f"fixture size changed: {path}", errors)
        require(data_digest == item.get("sha256"), f"fixture hash changed: {path}", errors)


def validate_record(output: Path, record_path: Path, report_path: Path | None) -> None:
    errors: list[str] = []
    manifest_path = output / "fixture-manifest.json"
    require(manifest_path.is_file(), f"missing fixture manifest: {manifest_path}", errors)
    require(record_path.is_file(), f"missing performance record: {record_path}", errors)
    if errors:
        raise SystemExit("\n".join(errors))
    manifest = json.loads(manifest_path.read_text())
    record = json.loads(record_path.read_text())
    require(manifest.get("schema_version") == 1, "unsupported fixture manifest schema", errors)
    require(record.get("schema_version") == 1, "unsupported performance record schema", errors)
    require(record.get("release") == "0.11.0", "performance record release must be 0.11.0", errors)
    validate_fixtures(output, manifest, errors)
    require(record.get("fixture_set_sha256") == manifest.get("fixture_set_sha256"), "record uses a different fixture set", errors)
    clients = record.get("clients")
    require(isinstance(clients, dict), "record.clients must be an object", errors)
    rows: list[tuple[str, int, float, float]] = []

    if isinstance(clients, dict):
        for client_name in ("android", "ios"):
            client = clients.get(client_name)
            require(isinstance(client, dict), f"missing client record: {client_name}", errors)
            if not isinstance(client, dict):
                continue
            expected_build = "69" if client_name == "android" else "45"
            require(client.get("version") == "0.11.0", f"{client_name}.version must be 0.11.0", errors)
            require(str(client.get("build")) == expected_build, f"{client_name}.build must be {expected_build}", errors)
            require(client.get("configuration") == "release", f"{client_name}.configuration must be release", errors)
            waiver = client.get("physical_performance_waiver")
            if waiver is not None:
                require(client_name == "android", "only Android physical performance can be waived for this P8 run", errors)
                require(isinstance(waiver, dict), "android.physical_performance_waiver must be an object", errors)
                if isinstance(waiver, dict):
                    require(waiver.get("scope") == "P8 Android physical-device performance", "Android waiver scope is invalid", errors)
                    require(waiver.get("authorized_by") == "user", "Android waiver must be explicitly authorized by the user", errors)
                    reason = waiver.get("reason")
                    require(isinstance(reason, str) and bool(reason.strip()), "Android waiver reason is required", errors)
                continue
            device = client.get("device")
            require(isinstance(device, dict), f"{client_name}.device must be an object", errors)
            if isinstance(device, dict):
                require(bool(str(device.get("model", "")).strip()), f"{client_name}.device.model is required", errors)
                require(bool(str(device.get("os", "")).strip()), f"{client_name}.device.os is required", errors)
            scenarios = client.get("scenarios")
            require(isinstance(scenarios, dict), f"{client_name}.scenarios must be an object", errors)
            if not isinstance(scenarios, dict):
                continue
            for scenario_name in OPEN_SCENARIOS:
                scenario = scenarios.get(scenario_name)
                require(isinstance(scenario, dict), f"missing {client_name}.{scenario_name}", errors)
                if not isinstance(scenario, dict):
                    continue
                for metric, integer in (
                    ("duration_ms", False),
                    ("peak_memory_mb", False),
                    ("logical_writes_kb", False),
                ):
                    values = numeric_runs(scenario.get(metric), MIN_REPEATED_RUNS, f"{client_name}.{scenario_name}.{metric}", errors, integer=integer)
                    summarize(f"{client_name}.{scenario_name}.{metric}", values, rows)
                optional_reads = scenario.get("bytes_read", [])
                if optional_reads:
                    values = numeric_runs(
                        optional_reads,
                        1,
                        f"{client_name}.{scenario_name}.bytes_read",
                        errors,
                        integer=True,
                    )
                    summarize(f"{client_name}.{scenario_name}.bytes_read_exploratory", values, rows)
            for scenario_name in ("page_forward", "page_backward"):
                scenario = scenarios.get(scenario_name, {})
                durations = numeric_runs(scenario.get("turn_latency_ms"), MIN_PAGE_TURNS, f"{client_name}.{scenario_name}.turn_latency_ms", errors)
                memory = numeric_runs(scenario.get("peak_memory_mb"), MIN_REPEATED_RUNS, f"{client_name}.{scenario_name}.peak_memory_mb", errors)
                summarize(f"{client_name}.{scenario_name}.turn_latency_ms", durations, rows)
                summarize(f"{client_name}.{scenario_name}.peak_memory_mb", memory, rows)
            search = scenarios.get("search_over_200", {})
            for metric in ("first_result_ms", "completion_ms", "peak_memory_mb"):
                values = numeric_runs(search.get(metric), MIN_REPEATED_RUNS, f"{client_name}.search_over_200.{metric}", errors)
                summarize(f"{client_name}.search_over_200.{metric}", values, rows)
            require(search.get("cancellation_passed") is True, f"{client_name}.search_over_200.cancellation_passed must be true", errors)
            bulk = scenarios.get("bulk_import", {})
            for metric in ("total_ms", "peak_memory_mb"):
                values = numeric_runs(bulk.get(metric), MIN_REPEATED_RUNS, f"{client_name}.bulk_import.{metric}", errors)
                summarize(f"{client_name}.bulk_import.{metric}", values, rows)
            require(bulk.get("retained_temporary_files") == 0, f"{client_name}.bulk_import.retained_temporary_files must be 0", errors)
            interrupted = scenarios.get("bulk_import_interrupted", {})
            for metric in ("recovery_ms", "peak_memory_mb"):
                values = numeric_runs(interrupted.get(metric), MIN_REPEATED_RUNS, f"{client_name}.bulk_import_interrupted.{metric}", errors)
                summarize(f"{client_name}.bulk_import_interrupted.{metric}", values, rows)
            require(interrupted.get("retained_temporary_files") == 0, f"{client_name}.bulk_import_interrupted.retained_temporary_files must be 0", errors)
            require(interrupted.get("recovery_passed") is True, f"{client_name}.bulk_import_interrupted.recovery_passed must be true", errors)

    if errors:
        raise SystemExit("P8 performance record is incomplete:\n- " + "\n- ".join(errors))

    lines = [
        "# P8 Physical-device Performance Results",
        "",
        f"Release: {record.get('release')}",
        f"Fixture set SHA-256: `{record.get('fixture_set_sha256')}`",
        "",
        "| Client | Version/build | Configuration | Device | OS |",
        "|---|---|---|---|---|",
    ]
    for name in ("android", "ios"):
        client = clients[name]
        waived = client.get("physical_performance_waiver") is not None
        device = client.get("device", {})
        values = [name, f"{client['version']}/{client['build']}", client["configuration"],
                  "waived by user" if waived else device["model"], "—" if waived else device["os"]]
        lines.append("| " + " | ".join(str(value).replace("|", "\\|") for value in values) + " |")
    if clients["android"].get("physical_performance_waiver") is not None:
        lines.extend(["", "Android physical-device performance collection was waived by the user for this P8 run. No Android measurements are claimed."])
    lines.extend([
        "",
        "| Metric | Runs | Median | p95 |",
        "|---|---:|---:|---:|",
    ])
    lines.extend(f"| `{label}` | {count} | {median:.3f} | {p95:.3f} |" for label, count, median, p95 in rows)
    report = "\n".join(lines) + "\n"
    if report_path:
        atomic_write(report_path, report.encode())
        print(f"Wrote {report_path}")
    else:
        print(report, end="")
    print("P8 physical-device performance record is complete")


def ingest_ios_open_metrics(record_path: Path, metric_paths: list[Path]) -> None:
    record = json.loads(record_path.read_text())
    ios = record.get("clients", {}).get("ios")
    if not isinstance(ios, dict) or not isinstance(ios.get("scenarios"), dict):
        raise SystemExit(f"Invalid iOS performance record: {record_path}")

    measurements: dict[str, dict[str, list[float]]] = {}
    sources: dict[str, str] = {}
    for metric_path in metric_paths:
        for test in json.loads(metric_path.read_text()):
            identifier = str(test.get("testIdentifier", "")).rsplit("/", 1)[-1]
            match = IOS_OPEN_TEST_PATTERN.fullmatch(identifier)
            if not match:
                continue
            scenario = "open_{}_{}_{}".format(
                match.group("size").lower(),
                IOS_OPEN_ENCODING[match.group("encoding")],
                match.group("state").lower(),
            )
            runs = test.get("testRuns", [])
            if len(runs) != 1:
                raise SystemExit(f"{metric_path}: expected one run for {identifier}")
            metrics = {item.get("identifier"): item for item in runs[0].get("metrics", [])}
            duration = metrics.get(IOS_OPEN_DURATION_METRIC)
            memory = metrics.get(IOS_OPEN_PEAK_MEMORY_METRIC)
            writes = metrics.get(IOS_OPEN_LOGICAL_WRITES_METRIC)
            if not isinstance(duration, dict) or duration.get("unitOfMeasurement") != "s":
                raise SystemExit(f"{metric_path}: missing seconds signpost metric for {identifier}")
            if not isinstance(memory, dict) or memory.get("unitOfMeasurement") != "kB":
                raise SystemExit(f"{metric_path}: missing peak-memory metric for {identifier}")
            if not isinstance(writes, dict) or writes.get("unitOfMeasurement") != "kB":
                raise SystemExit(f"{metric_path}: missing logical-writes metric for {identifier}")
            durations = duration.get("measurements", [])
            memories = memory.get("measurements", [])
            logical_writes = writes.get("measurements", [])
            if min(len(durations), len(memories), len(logical_writes)) < MIN_REPEATED_RUNS:
                raise SystemExit(f"{metric_path}: {identifier} needs at least {MIN_REPEATED_RUNS} measurements")
            measurements[scenario] = {
                "duration_ms": [float(value) * 1_000 for value in durations],
                "peak_memory_mb": [float(value) / 1_000 for value in memories],
                "logical_writes_kb": [float(value) for value in logical_writes],
            }
            sources[scenario] = metric_path.name

    if not measurements:
        raise SystemExit("No P8 iOS open-book metrics found")
    for scenario, values in measurements.items():
        target = ios["scenarios"].get(scenario)
        if not isinstance(target, dict):
            raise SystemExit(f"Record has no scenario named {scenario}")
        target.update(values)
        target["xctest_metrics_source"] = sources[scenario]
    atomic_write(record_path, json.dumps(record, ensure_ascii=False, indent=2).encode() + b"\n")
    print(f"Ingested {len(measurements)} iOS open-book scenarios into {record_path}")


def find_test_result(nodes: list[dict[str, Any]], name: str) -> str | None:
    for node in nodes:
        if node.get("nodeType") == "Test Case" and node.get("name") == name:
            return str(node.get("result", ""))
        children = node.get("children")
        if isinstance(children, list):
            result = find_test_result(children, name)
            if result is not None:
                return result
    return None


def metric_measurements(
    metrics: dict[str, dict[str, Any]], identifier: str, unit: str, source: Path, test: str
) -> list[float]:
    metric = metrics.get(identifier)
    if not isinstance(metric, dict) or metric.get("unitOfMeasurement") != unit:
        raise SystemExit(f"{source}: missing {unit} metric {identifier} for {test}")
    return [float(value) for value in metric.get("measurements", [])]


def ingest_ios_runtime_metrics(
    record_path: Path, metric_paths: list[Path], test_result_paths: list[Path]
) -> None:
    record = json.loads(record_path.read_text())
    ios = record.get("clients", {}).get("ios")
    if not isinstance(ios, dict) or not isinstance(ios.get("scenarios"), dict):
        raise SystemExit(f"Invalid iOS performance record: {record_path}")

    ingested: set[str] = set()
    for metric_path in metric_paths:
        for test in json.loads(metric_path.read_text()):
            identifier = str(test.get("testIdentifier", "")).rsplit("/", 1)[-1]
            runs = test.get("testRuns", [])
            if len(runs) != 1:
                continue
            metrics = {item.get("identifier"): item for item in runs[0].get("metrics", [])}
            if identifier in IOS_PAGE_TESTS:
                scenario_name, signpost_name = IOS_PAGE_TESTS[identifier]
                durations = metric_measurements(
                    metrics,
                    f"com.apple.dt.XCTMetric_OSSignpost-{signpost_name}.duration",
                    "s",
                    metric_path,
                    identifier,
                )
                memories = metric_measurements(
                    metrics, IOS_PEAK_MEMORY_METRIC, "kB", metric_path, identifier
                )
                if len(durations) < MIN_PAGE_TURNS or len(memories) < MIN_REPEATED_RUNS:
                    raise SystemExit(f"{metric_path}: insufficient measurements for {identifier}")
                ios["scenarios"][scenario_name].update(
                    {
                        "turn_latency_ms": [value * 1_000 for value in durations],
                        "peak_memory_mb": [value / 1_000 for value in memories],
                        "xctest_metrics_source": metric_path.name,
                    }
                )
                ingested.add(scenario_name)
            elif identifier == IOS_SEARCH_TEST:
                first = metric_measurements(
                    metrics,
                    "com.apple.dt.XCTMetric_OSSignpost-SearchFirstResult.duration",
                    "s",
                    metric_path,
                    identifier,
                )
                completion = metric_measurements(
                    metrics,
                    "com.apple.dt.XCTMetric_OSSignpost-SearchOver200Completion.duration",
                    "s",
                    metric_path,
                    identifier,
                )
                memories = metric_measurements(
                    metrics, IOS_PEAK_MEMORY_METRIC, "kB", metric_path, identifier
                )
                if min(len(first), len(completion), len(memories)) < MIN_REPEATED_RUNS:
                    raise SystemExit(f"{metric_path}: insufficient measurements for {identifier}")
                ios["scenarios"]["search_over_200"].update(
                    {
                        "first_result_ms": [value * 1_000 for value in first],
                        "completion_ms": [value * 1_000 for value in completion],
                        "peak_memory_mb": [value / 1_000 for value in memories],
                        "xctest_metrics_source": metric_path.name,
                    }
                )
                ingested.add("search_over_200")
            elif identifier in IOS_BULK_TESTS:
                scenario_name, signpost_name, duration_name = IOS_BULK_TESTS[identifier]
                durations = metric_measurements(
                    metrics,
                    f"com.apple.dt.XCTMetric_OSSignpost-{signpost_name}.duration",
                    "s",
                    metric_path,
                    identifier,
                )
                memories = metric_measurements(
                    metrics, IOS_PEAK_MEMORY_METRIC, "kB", metric_path, identifier
                )
                if min(len(durations), len(memories)) < MIN_REPEATED_RUNS:
                    raise SystemExit(f"{metric_path}: insufficient measurements for {identifier}")
                ios["scenarios"][scenario_name].update(
                    {
                        duration_name: [value * 1_000 for value in durations],
                        "peak_memory_mb": [value / 1_000 for value in memories],
                        "xctest_metrics_source": metric_path.name,
                    }
                )
                ingested.add(scenario_name)

    cancellation_passed = False
    for test_result_path in test_result_paths:
        payload = json.loads(test_result_path.read_text())
        devices = payload.get("devices", [])
        if devices and isinstance(devices[0], dict):
            ios["device"] = {
                "model": str(devices[0].get("modelName", "")),
                "os": str(devices[0].get("osVersion", "")),
            }
        if find_test_result(payload.get("testNodes", []), IOS_SEARCH_CANCELLATION_TEST) == "Passed":
            cancellation_passed = True
        if find_test_result(payload.get("testNodes", []), "testBulkImport()") == "Passed":
            ios["scenarios"]["bulk_import"]["retained_temporary_files"] = 0
            ingested.add("bulk_import_result")
        if (
            find_test_result(
                payload.get("testNodes", []), "testBulkImportInterruptedRecovery()"
            )
            == "Passed"
        ):
            interrupted = ios["scenarios"]["bulk_import_interrupted"]
            interrupted["retained_temporary_files"] = 0
            interrupted["recovery_passed"] = True
            ingested.add("bulk_import_interrupted_result")
    if cancellation_passed:
        ios["scenarios"]["search_over_200"]["cancellation_passed"] = True
        ingested.add("search_cancellation")

    if not ingested:
        raise SystemExit("No P8 iOS page/search metrics or cancellation result found")
    atomic_write(record_path, json.dumps(record, ensure_ascii=False, indent=2).encode() + b"\n")
    print(f"Ingested {', '.join(sorted(ingested))} into {record_path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "command", choices=("generate", "validate", "ingest-ios-open", "ingest-ios-runtime")
    )
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--record", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--metrics", type=Path, action="append", default=[])
    parser.add_argument("--tests", type=Path, action="append", default=[])
    args = parser.parse_args()
    output = args.output.resolve()
    if args.command == "generate":
        generate(output)
    elif args.command == "ingest-ios-open":
        if not args.metrics:
            raise SystemExit("ingest-ios-open requires at least one --metrics file")
        record = (args.record or output / "performance-record.json").resolve()
        ingest_ios_open_metrics(record, [path.resolve() for path in args.metrics])
    elif args.command == "ingest-ios-runtime":
        if not args.metrics and not args.tests:
            raise SystemExit("ingest-ios-runtime requires --metrics or --tests")
        record = (args.record or output / "performance-record.json").resolve()
        ingest_ios_runtime_metrics(
            record,
            [path.resolve() for path in args.metrics],
            [path.resolve() for path in args.tests],
        )
    else:
        record = (args.record or output / "performance-record.json").resolve()
        validate_record(output, record, args.report.resolve() if args.report else None)


if __name__ == "__main__":
    main()
