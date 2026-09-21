# P8 System Validation

Status: in progress

P8 verifies the P0–P7 architecture as a system. It does not add a Product Capability, change the sync contract, or authorize a release. Automated gates and local fault recovery are implemented; real-device cross-client continuity, measured performance, and distribution-channel enforcement still require evidence.

## Release Gate

`make check-alpha` is the required local Alpha gate. It now:

- runs the tracked-sensitive-data scan, contract checks, release checks, Backend checks, AppleShared tests, Android tests/lint/build, and iOS unit/UI tests;
- always provisions an isolated PostgreSQL 17 cluster through `test-backend-postgres.sh`; an unavailable database is a gate failure, not a skipped success;
- runs iOS tests serially to avoid test-host collisions;
- snapshots Android, iOS, Backend, and selected release-manifest version sources before the whole run and fails if any check changes them.

CI and release verification use PostgreSQL 17 with a database owner and a separate restricted application role. Release verification also runs Android tests/lint and iOS unit/UI tests before producing unsigned debug artifacts. It does not sign, publish, tag, deploy, or upload to an app store.

The sensitive-data gate examines tracked files and reports only the file path and finding category. It rejects committed environment files, credential/signing material, private keys, common provider tokens, and credentialed database URLs that are not explicit test placeholders.

## Verified Evidence

| Area | Evidence | Status |
|---|---|---|
| PostgreSQL behavior | Isolated PostgreSQL 17 run: 71 tests, including 13 database integration tests with a restricted application role | passed |
| Local release gate | `make check-alpha` completed Backend, AppleShared, Android, iOS unit/UI, build, contract, release, and version-immutability checks on the capacity-fault implementation | passed |
| Android storage exhaustion | Same-connection maximum page limit forces `SQLITE_FULL`; the whole library write rolls back and the original book remains | passed |
| iOS storage exhaustion | Same-connection maximum page limit forces `SQLITE_FULL`; the book transaction rolls back and the original book remains | passed |
| Migration and cleanup interruption | Existing Android/iOS tests cover idempotence, missing TXT, changed legacy input, interrupted allowlist cleanup, and pending file-deletion retry | passed |
| App-owned logging | Source review rejects credentials/body text/local paths in app diagnostics; Android database-open logging no longer includes the exception path; iOS test fixtures close shared SQLite connections before deleting their temporary roots | passed |
| Android migration baseline | User-confirmed Android 0.10.0 real-device test and release | historical prerequisite only |

## Outstanding Evidence

### Real-device continuity

P8 still needs a recorded Android/iOS run against the same non-production Backend identity. The run must cover opening a previously read book, comparing local and remote progress before reading time starts, accepting and rejecting a jump, making a real page movement, uploading only the active book, offline recovery, and single-book cloud-progress deletion. The record must include client versions, OS/device, Backend version, scenario result, and sanitized failure details.

The Android 0.10.0 migration release proves the mandatory intermediate baseline was exercised. It does not prove the current 0.11.0 Android-to-iOS sync sequence. The current iOS evidence is Simulator evidence, not a physical-device result.

At the 2026-09-21 validation run, `adb devices` reported no attached Android device and Xcode reported the registered iPhone `Laguna-15PM` as offline. The local run therefore cannot produce the missing physical-device evidence.

### Distribution floor

The 0.11.0 manifest enforces `minimum_direct_from: 0.10.0` and `required_intermediate: 0.10.0` inside the repository. The actual app distribution channel must also prevent a pre-0.10.0 installation from receiving 0.11.0 directly. Store/MDM enforcement has not been verified.

### Performance

Correctness tests cover sparse large-file caching, repeated page turns, and searches exceeding 200 results, but P8 has no comparable physical-device timing or memory measurements. Do not infer an SLA from unit-test duration.

The performance record must use fixed samples and devices and include:

| Scenario | Required dimensions | Measurements |
|---|---|---|
| Open book | small/large; UTF-8, UTF-16, GB18030; cold and cached | time to first readable page, peak memory, bytes read |
| Page continuously | forward/backward on a large book | median and p95 main-thread duration, peak memory |
| Search | first result and more than 200 matches | first-result time, completion time, cancellation behavior |
| Bulk import | representative batch and failure interruption | total time, peak memory, retained temporary files |

Record the device, OS, build, sample hash/size, run count, median, p95 where applicable, and peak memory. Establish a budget only after both client baselines are measured.

## Fault and Security Boundaries

- SQLite writes that fail for capacity reasons must not leave partial books, progress, bookmarks, TOC, settings, or sync state.
- Import interruption and file deletion use existing temporary-file and pending-deletion recovery paths.
- PostgreSQL rollback, capacity concurrency, upload/delete ordering, identity isolation, and revoked-device rejection remain mandatory.
- Logs may contain event type, result, duration, and short-lived diagnostic identifiers. They must not contain TXT content, email, Token, credential, local file path, or real service URL.
- TXT remains in the file system and credentials remain in Keystore/Keychain. P8 does not change the SQLite, Secure Store, or PostgreSQL boundaries.

## Completion Rule

P8 can be marked complete only after the final `make check-alpha` passes on the current code and the outstanding real-device continuity, distribution-floor, and performance records above are attached or linked. Missing environment evidence is recorded as pending; it is not converted into a passing result.
