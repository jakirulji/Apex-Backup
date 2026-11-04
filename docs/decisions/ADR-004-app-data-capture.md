# ADR-004: Root-gated app-data capture

- **Status:** Accepted; implementation in progress
- **Date:** 2026-11-04
- **Milestone:** M4 — App-data backup

## Context

The current v3 container safely encrypts APK payloads, but `BackupFile` currently accepts only readable filesystem `File` objects. Private app data is root-protected and can be large. Writing an unencrypted TAR/ZIP to shared or app storage before encryption would create a second sensitive copy and an avoidable exposure window.

The root diagnostics implementation is read-only and bounded, but it is not a suitable app-data capture engine. A capture operation needs a dedicated process/session abstraction with streaming, cancellation, timeout, bounded diagnostics and reliable exit-status handling.

## Decision

### 1. Capture scope for the first M4 implementation

- Support Android user 0 only in the first release of this milestone.
- Capture explicitly selected packages only; never silently select every installed package.
- Capture credential-encrypted (CE) app data from `/data/user/0/<package>` when the user is unlocked.
- Capture device-encrypted (DE) app data from `/data/user_de/0/<package>` when present.
- Treat app-specific external data and OBB data as separately reported categories; include them only when the source path is present and safely readable.
- Exclude regenerable `cache` and `code_cache` directories by default. Record exclusions in the per-package report.
- Do not capture Android framework/package-manager databases, other users' data, work profiles, or arbitrary filesystem paths in this milestone.
- APKs remain a separate payload category and continue to be included by the existing package inventory flow.

### 2. Root privilege boundary

- Request Magisk/su authorization only after the user starts an app-data backup.
- Validate package names against Android package-name syntax before they can enter a root operation.
- Resolve source paths from fixed, app-owned path templates. Never accept a filesystem path or shell fragment from backup metadata or a text field.
- Use a reviewed fixed command template and a dedicated root process session. Imported archive content must never become a root command.
- Drain stderr independently with a strict byte limit; never merge stderr into the binary TAR stdout stream.
- Enforce an operation timeout, cancellation and process termination. Check the root process exit code before marking a category captured.
- Do not reuse the diagnostics runner's current wait-before-read pattern for large binary output; it can deadlock when a process fills its pipe.
- Do not remount system partitions read-write.

### 3. Streaming and plaintext handling

- Generalize the backup container's input abstraction from `File`-only sources to a closeable streaming source while preserving the existing `File` convenience API.
- Stream root-produced TAR bytes directly into the v3 encrypted ZIP entry. Do not create a plaintext TAR file on shared storage or in app cache.
- Keep memory bounded with buffered sequential I/O; no unbounded buffering and no artificial throughput cap.
- A root stream that exits non-zero, times out, is cancelled or ends unexpectedly must fail the whole affected category. Never mark partial output as captured.
- The authenticated v3 manifest must cover the exact TAR payload bytes and category metadata.

### 4. Consistency and user impact

- App data can change while an app is running. The UI must disclose that a live capture is best-effort and may contain inconsistent databases.
- If a consistent snapshot requires stopping an app, ask for explicit confirmation for the selected package before force-stop. Never force-stop silently.
- If the user declines, either proceed with an explicitly labelled best-effort capture or skip that package; the choice must be visible in the result.
- Do not claim that a backup preserves live sessions, hardware-backed Keystore material, DRM keys, banking credentials or server-side authentication.

### 5. Metadata and reporting

For each package, record only what is needed for recovery and compatibility:
- package name, label, version name/code and signing-certificate digest when available;
- source device model/codename, Android API, build fingerprint and ABI;
- Android user ID;
- CE, DE, app-specific external and OBB category outcomes;
- captured byte counts, skipped paths and bounded error reasons;
- whether capture was consistent (app stopped with user approval) or best-effort (app remained running).

Each category must have one explicit status: `captured`, `skipped`, `failed` or `unsupported`. Do not include private file contents, secrets or passphrases in logs.

### 6. Restore is out of scope

M4 only captures and verifies data. It must not extract to live app-data directories, change ownership, restore SELinux labels, install APKs or modify system state. Same-ROM restore begins in M5 after synthetic end-to-end capture tests pass.

## Security and reliability consequences

- Streaming avoids an extra plaintext archive copy, but root process handling becomes security-critical.
- TAR payloads can contain symlinks and metadata. M4 stores them as opaque encrypted data; the future restore engine must validate every member before extraction and must reject absolute paths, traversal, and symlink escapes.
- Numeric UID/GID from the source archive are evidence only. M5 must resolve target ownership from the installed package on the destination ROM.
- A successful encrypted-container verification proves ciphertext authenticity and payload integrity, not that the app data is compatible or restorable.

## Acceptance criteria

1. A disposable synthetic app with known CE and DE fixture files can be captured through root and included in a v3 container.
2. The container verifies and the extracted test fixture matches the original byte-for-byte in a root-free test harness.
3. No plaintext TAR or app-data file is written as an intermediate artifact.
4. Denied root, missing paths, non-zero TAR exit, timeout, cancellation, stream truncation and low storage produce explicit non-success results.
5. Package names and archive paths cannot inject shell commands or escape the intended source/destination roots.
6. CI unit tests and signed release build pass; device validation uses only a disposable synthetic app.
