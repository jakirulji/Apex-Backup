# Roadmap

| Milestone | Status |
|---|---|
| M0 — Repository and build foundation | Complete |
| M1 — Device/root/storage diagnostics | Device-validated; CI verified |
| M2 — App inventory and APK export | Device-validated; CI verified |
| M3 — Encrypted backup container | v3 streaming engine; unified APK + app-data backup flow implemented; CI validation pending |
| M4 — App-data capture | Root-gated capture integrated into the unified backup flow; disposable-device validation pending |
| M5 — Same-ROM restore | Not started |
| M6 — Post-login settings restoration | Planned; depends on M5 safety foundation |
| M7 — System configuration | Not started |
| M8 — Storage targets | Not started |
| M9 — History and incremental backups | Not started |
| M10 — Cross-ROM migration | Not started |
| M11 — Magisk migration | Not started |
| M12 — Hardening and release | Not started |

## M0 — Build and signing

- [x] Android project foundation and CI.
- [x] Permanent JKS signing configured.
- [x] CI validates the signed release APK.
- [x] CI publishes one user-facing APK: `Apex Backup.apk`.
- [x] Test/debug APK is not uploaded as a separate artifact; the isolated M4 fixture app is published separately for device validation.

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
- [x] Integrate APK and supported app-data streams into one user-facing encrypted backup action and one `.apexbackup` file.
- [x] Keep legacy M3 APK-only and M4 app-data containers readable by the existing verification/extraction paths.
- [x] Authenticated final manifest with file sizes and SHA-256 digests.
- [x] Reject unsafe paths, duplicate entries, malformed manifests and truncated/tampered streams.
- [x] Fast wrong-passphrase rejection for v2/v3 before payload reads.
- [x] Verification UI reports byte-based progress and average MB/s.
- [x] CI tests, lint, signed release build and APK signature verification passed.
- [x] User confirmed device verification, including progress display and wrong-passphrase abort.

The unified backup action writes selected APKs and supported app-data TAR streams into one v3 encrypted `.apexbackup` file. The standalone M2 APK ZIP export remains available. Accounts, sessions, permissions and system configuration are not guaranteed.

## M4 — Root-gated private app-data backup

Design and safety requirements are tracked in [ADR-004: App-data capture](decisions/ADR-004-app-data-capture.md).

- [x] Define scope, root boundary, consistency policy and non-goals.
- [x] Add isolated `Apex Backup Test Lab` app (`com.apex.backup.testlab`) to generate and verify synthetic CE, DE, database, SharedPreferences, app-specific external, OBB and cache fixtures.
- [x] Build the Test Lab debug APK separately in CI; publish it as the `apex-backup-test-lab` artifact without changing the signed Apex Backup release artifact.
- [x] Generalize the container input API so one-shot root-produced streams are encrypted directly without plaintext temporary archives; retain the File convenience API.
- [x] Remove the separate M3/M4 backup actions; the unified action captures APKs and app data together.
- [x] Implement a bounded root TAR process with separate bounded stderr draining, exit-code validation, a six-hour hard timeout and cancellation watcher.
- [x] Add selected-package capture for CE and DE app-private data under Android user 0.
- [x] Add separately reported app-specific external-data and OBB categories.
- [x] Exclude root cache and code_cache directories; record exclusions and unavailable categories in authenticated metadata.
- [x] Record package/version/signing-certificate identity, source device/ROM/API/ABI, Android user and category outcomes in authenticated metadata.
- [x] Keep capture live and best-effort; never force-stop apps. The UI discloses that databases may change during capture.
- [x] Report captured, skipped and excluded categories; a TAR process failure aborts the backup instead of recording partial data as captured.
- [x] Add an in-app TAR content comparator for CE/DE files, SharedPreferences XML, SQLite fixture rows, External/OBB payloads and cache exclusion; document the disposable-device procedure in [M4 testing](M4-TESTING.md).
- [ ] Run the complete unified backup → encrypted extraction → content comparison flow with the disposable Test Lab on the target device; expected result is 14 PASS, 0 FAIL, 0 SKIP.

M4 is backup-only. It does not restore data or promise cross-ROM compatibility.

## M5 — Same-ROM restore

### Restore safety and isolation requirements

- [ ] Verify the entire encrypted container, authenticated manifest and all payload digests before extracting files or changing device state.
- [ ] Build a dry-run plan and show package, signing-certificate and version compatibility before any write.
- [ ] For every app-data archive, require its archive path package segment, authenticated metadata package key, and selected target package to match exactly. Reject missing, malformed, duplicate or conflicting identity records.
- [ ] Keep each app's APK and CE/DE/External/OBB archives in a package-scoped restore plan and staging area; never infer one package from another package's metadata or fall back to a shared destination.
- [ ] Treat archive contents as untrusted: reject absolute paths, traversal, unsafe special files and symlink/hard-link targets that escape the exact destination app-data root. Validate paths after normalization and account for symlink traversal during extraction.
- [ ] Resolve the destination UID/GID and SELinux context from the target ROM/package installation; never blindly apply source numeric ownership or labels.
- [ ] Restore only explicitly selected, compatible packages through a root-gated, package-aware process. Imported metadata and TAR members must never become shell commands or arbitrary root paths.
- [ ] Require explicit confirmation of the dry-run plan, preserve cancellation/error handling, and report success/failure independently for every package.
- [ ] On any package identity mismatch or cross-package path collision, reject that package before writing; no fallback or partial cross-app restore is allowed.
- [ ] Test only with synthetic app data on a controlled disposable target, never the user's daily-driver data.

### M5 cross-app isolation acceptance tests

- [ ] Create at least two synthetic packages with distinct canary files in CE, DE, External and OBB data; restore them and prove each canary appears only under its matching package.
- [ ] Tamper with or construct mismatched archive-path/metadata/target-package identities and assert deterministic rejection before any destination write.
- [ ] Include traversal, absolute-path, symlink-escape, hard-link and duplicate-destination fixtures; assert no file is written outside the intended package root.
- [ ] Verify a failed package cannot redirect, overwrite or contaminate another package's staged or live data.
- [ ] Verify dry-run output identifies each package and each category separately, including skipped/unsupported categories.
- [ ] Record per-test results and require zero cross-app writes for M5 acceptance.

M5 is the mandatory foundation for M6. M6 must not bypass M5's package identity, integrity, staging or isolation controls.

## M6 — Post-login settings restoration

Detailed design and safety boundaries are tracked in [ADR-005: Post-login settings restoration](decisions/ADR-005-post-login-settings-restoration.md).

**Goal:** After the user installs or opens an app and signs in normally, Apex Backup can re-apply only those local settings that have been proven safe and recoverable for that specific app/version.

- [ ] Build an app-specific adapter framework; do not assume settings have a common format or location across apps.
- [ ] Keep settings restoration separate from raw app-data restore. Never overwrite a newly established login session with old app-private state.
- [ ] Require the user to sign in through the app's official UI. Never capture, export, inject or restore passwords, cookies, OAuth tokens, session databases, device-bound keys or other authentication material.
- [ ] Use an explicit user confirmation step (for example, “I'm signed in”) before applying post-login settings. Do not rely on inaccessible or unreliable automatic login-state detection.
- [ ] Classify each setting per app and version as **Automatic** (verified safe local preference), **Assisted** (user review/confirmation required), **Manual/Unsupported** (must be configured by the user or is not safely recoverable).
- [ ] Capture only allow-listed, documented or experimentally verified local preferences; never copy an entire preferences database or opaque app-data directory by default.
- [ ] Detect app package, signing certificate and version; adapters must declare supported version ranges and fail closed on unknown/incompatible versions.
- [ ] Prefer app-owned official export/import APIs or documented mechanisms when available. Treat server-synced settings as account-provided and avoid redundant local copying.
- [ ] Provide a preview/dry-run of settings to be applied, per-setting outcomes, cancellation and rollback where technically safe.
- [ ] Keep app-specific limitations explicit. Social apps such as Facebook and Instagram may store many preferences server-side; do not promise local restoration where account sync already owns the setting or no safe adapter exists.
- [ ] Keep WhatsApp chat backup/restore under WhatsApp's official process; Apex Backup must not replace it or claim to restore chats through settings adapters.
- [ ] Start with one disposable/test app and synthetic preferences; add a real-app adapter only after version-specific behavior and post-login safety are demonstrated.
- [ ] Test that a fresh successful login remains valid after settings restoration and that no old token/session material is imported.
- [ ] Report restored, skipped, unsupported and failed settings separately; never mark an unverified setting as restored.

**M6 non-goals:** automatic login, account/session migration, server-side account settings extraction, bypassing app security, universal settings restoration for every app, or replacing official chat/cloud backup.

## Later milestones

- **M7 — System configuration:** allow-listed, version-aware adapters only; no blind system database copying.
- **M8 — Storage targets:** SAF internal folders, removable SD and portable PC transfer with post-write verification.
- **M9 — History and incremental backups:** operation history, deduplication and incremental chunks.
- **M10 — Cross-ROM migration:** compatibility engine and controlled test matrix; block unsafe transfers.
- **M11 — Magisk migration:** inventory/export first; explicit compatibility review before any installation.
- **M12 — Hardening and release:** security review, device matrix, documentation and verified release.

## Working rules

- Work on `dev`; do not change `main` unless explicitly requested.
- Do not weaken encryption/KDF parameters for speed.
- Do not claim private app-data backup is device-validated until the synthetic end-to-end capture and encrypted-container verification tests pass.
- Do not implement restore before M4 capture is reliable and verified.
- Never test destructive restore against the user's daily-driver data.
