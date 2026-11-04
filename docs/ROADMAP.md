# Roadmap

| Milestone | Status |
|---|---|
| M0 — Repository and build foundation | Complete |
| M1 — Device/root/storage diagnostics | Device-validated; CI verified |
| M2 — App inventory and APK export | Device-validated; CI verified |
| M3 — Encrypted repository format | v3 streaming engine; CI and device validation confirmed |
| M4 — App-data backup | In progress — safety design and capture boundary |
| M5 — Same-ROM restore | Not started |
| M6 — System config | Not started |
| M7 — Storage targets | Not started |
| M8 — History and incremental backups | Not started |
| M9 — Cross-ROM migration | Not started |
| M10 — Magisk migration | Not started |
| M11 — Hardening and release | Not started |

## M0 — Build and signing

- [x] Android project foundation and CI.
- [x] Permanent JKS signing configured.
- [x] CI validates the signed release APK.
- [x] CI publishes one user-facing APK: `Apex Backup.apk`.
- [x] Test/debug APK is not uploaded as a separate artifact.

## M1 — Device/root/storage diagnostics

- [x] Device model, codename, Android/API level, security patch, build and ABI data.
- [x] Internal data partition total/available/used space.
- [x] Explicit user-triggered root check; no root prompt at app launch.
- [x] Bounded root command execution and fixed read-only commands.
- [x] Magisk version, SELinux mode and Android encryption properties when root is granted.
- [x] User confirmed that device information is displayed correctly.
- [x] CI verification completed.

## M2 — App inventory and APK export

- [x] Enumerate installed applications using Android package visibility permission.
- [x] Display app label, package name, version code/name, user/system classification and readable APK file count.
- [x] Search, filter and multi-select installed apps.
- [x] Export selected base and split APK files into a ZIP using the system document picker.
- [x] Export runs off the main thread and reports errors.
- [x] Clearly disclose that APK export excludes private app data, accounts, sessions and settings.
- [x] CI and device inventory validation confirmed.

M2 APK ZIP export is not a complete application backup.

## M3 — Encrypted backup container

- [x] Versioned v1/v2/v3 container with backward read compatibility.
- [x] Argon2id key derivation (3 iterations, 64 MiB, parallelism 1).
- [x] v3 Tink AES-256-GCM-HKDF Streaming AEAD with a fresh wrapped keyset.
- [x] Stream APK files into the encrypted container without a plaintext intermediate archive.
- [x] Authenticated final manifest with file sizes and SHA-256 digests.
- [x] Reject unsafe paths, duplicate entries, malformed manifests and truncated/tampered streams.
- [x] Fast wrong-passphrase rejection for v2/v3 before payload reads.
- [x] Verification UI reports byte-based progress and average MB/s.
- [x] CI tests, lint, signed release build and APK signature verification passed.
- [x] User confirmed device verification, including progress display and wrong-passphrase abort.

M3 currently encrypts APK files only. It does not include private app data, accounts, sessions, permissions or system configuration.

## M4 — Root-gated private app-data backup

Design and safety requirements are tracked in [ADR-004: App-data capture](decisions/ADR-004-app-data-capture.md).

- [x] Define scope, root boundary, consistency policy and non-goals.
- [ ] Generalize the container input API so root-produced streams can be encrypted directly without plaintext temporary archives.
- [ ] Implement a bounded, cancellable root process session with stdout streaming and stderr draining.
- [ ] Capture CE and DE app-private data for explicitly selected Android user-0 packages.
- [ ] Capture supported app-specific external data and OBB data as separate categories.
- [ ] Exclude regenerable cache/code-cache and report each skipped/unsupported path.
- [ ] Record package/version/signing identity, source device/ROM/API/ABI, Android user and category outcomes in authenticated metadata.
- [ ] Require explicit confirmation before stopping selected apps; never silently force-stop apps.
- [ ] Report captured, skipped, failed and unsupported items separately.
- [ ] Add synthetic root-free fixtures and security tests for path traversal, symlinks, process failure, timeout, cancellation and partial streams.
- [ ] Validate on the target device with a disposable synthetic test app before any personal app data is used.

M4 is backup-only. It does not restore data or promise cross-ROM compatibility.

## M5 — Same-ROM restore

- [ ] Verify the entire container before extracting or changing device state.
- [ ] Generate a dry-run plan and show package/signature/version compatibility.
- [ ] Resolve destination UID/GID from the target PackageManager; never reuse source numeric ownership blindly.
- [ ] Reject unsafe paths and symlinks escaping the destination app-data root.
- [ ] Restore only scoped app data through a root-gated, package-aware process.
- [ ] Require explicit confirmation and provide per-package result reporting.
- [ ] Test only with synthetic app data on a controlled target.

## Later milestones

- **M6 — System configuration:** allow-listed, version-aware adapters only; no blind system database copying.
- **M7 — Storage targets:** SAF internal folders, removable SD and portable PC transfer with post-write verification.
- **M8 — History and incremental backups:** operation history, deduplication and incremental chunks.
- **M9 — Cross-ROM migration:** compatibility engine and controlled test matrix; block unsafe transfers.
- **M10 — Magisk migration:** inventory/export first; explicit compatibility review before any installation.
- **M11 — Hardening and release:** security review, device matrix, documentation and verified release.

## Working rules

- Work on `dev`; do not change `main` unless explicitly requested.
- Do not weaken encryption/KDF parameters for speed.
- Do not claim private app-data backup until the synthetic end-to-end capture and verification tests pass.
- Do not implement restore before M4 capture is reliable and verified.
- Never test destructive restore against the user's daily-driver data.
