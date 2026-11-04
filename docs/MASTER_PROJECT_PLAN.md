# Apex Backup — Master Project Plan

**Project:** Apex Backup  
**Repository:** https://github.com/jakirulji/Apex-Backup  
**Application ID (provisional):** `com.apex.backup`  
**Initial target:** Redmi Note 9 Pro (`joyeuse`), 6 GB RAM, Evolution X 17 / Android 17, Magisk-rooted  
**Branch policy:** `main` = stable; `dev` = active development  
**Build artifacts:** CI test build = `test.apk`; release = `Apex Backup.apk`

> This document is the project source of truth. Update it when architecture, scope, security decisions, or milestone status changes. Never report an implementation or build as complete until it has been verified.

## 1. Product vision

Build a reliable, root-capable Android backup and migration application that captures the maximum practical amount of user data and supported system configuration, stores it in a portable, encrypted, integrity-checked backup repository, and restores selectively with explicit compatibility checks.

The product must optimize for **recoverability and safety**, not the largest possible unchecked copy. It must explain exactly what was backed up, what could not be captured, and what can or cannot be restored.

### Non-negotiable principles

1. No silent data loss: restore is previewed, scoped, and confirmed.
2. No false promises: never claim universal or byte-for-byte ROM migration.
3. Verify every backup with manifests, hashes, and archive validation.
4. Encrypt sensitive backup contents; never log secrets or private app data.
5. Keep backup creation and restore interruption-safe.
6. Prefer documented Android APIs; use root only where required.
7. Separate collection, storage, compatibility analysis, and restoration.
8. Never blindly write foreign ROM partitions or Android framework databases.
9. Preserve original application signing identity and package metadata.
10. Make every operation auditable through a clear per-item report.

## 2. Target environment and compatibility

Initial validation target:
- Device: Xiaomi Redmi Note 9 Pro, codename `joyeuse`
- RAM: 6 GB
- OS: Evolution X 17 / Android 17 (user-provided environment; confirm exact build during diagnostics)
- Root: Magisk
- Storage: internal shared storage, removable SD card, and PC transfer

The architecture must not hard-code assumptions specific to this phone. Device-specific adapters may be added after the generic engine is stable.

### Restore compatibility classes

- **Compatible:** same package/signing identity and a validated target environment.
- **Conditional:** data may restore, but OS version, ROM, ABI, permissions, SELinux policy, app version, or provider differences require warnings or user choices.
- **Incompatible:** unsafe or technically unsupported; block the operation or offer a safe partial alternative.

Cross-ROM migration is a first-class goal, but not every setting or app's private data can be migrated.

## 3. Android platform limits and explicit exclusions

Some data is intentionally not promised to transfer:
- Hardware-backed Android Keystore keys and secrets bound to device hardware.
- DRM-protected content and protected key material.
- Banking/payment app credentials, server-side sessions, and device-bound tokens.
- Apps that prohibit backup or enforce integrity/device binding.
- Data protected by inaccessible SELinux, filesystem, or hardware boundaries.
- Certain work-profile, secondary-user, and enterprise-managed data unless explicitly supported.

Never blindly restore:
- Entire `/data` or `/data/system` trees.
- System, vendor, product, odm, boot, vendor_boot, init_boot, vbmeta, modem, or firmware partitions across ROMs/devices.
- Framework databases or package-manager state copied from a different Android build.
- Foreign UID/GID, SELinux labels, or file capabilities without target-side validation.

Partition imaging may be considered later as a separate, device-specific, read-only backup capability with explicit compatibility labels. It is not part of ordinary cross-ROM restore.

## 4. Architecture

Use modular Kotlin with Jetpack Compose, MVVM, and Clean Architecture boundaries. Keep UI independent from privileged operations.

### Proposed modules

- `app` — application shell, navigation, dependency wiring
- `core:model` — domain models and result types
- `core:database` — Room metadata database
- `core:security` — key derivation, encryption, authentication helpers
- `core:storage` — internal, SAF/SD card, and PC-transfer abstractions
- `core:root` — Magisk/su detection and constrained root command execution
- `engine:scanner` — device, package, storage, and configuration inventory
- `engine:backup` — backup orchestration and archive/container writer
- `engine:restore` — restore planning and controlled execution
- `engine:compatibility` — source/target compatibility analysis
- `engine:system-config` — allow-listed system configuration adapters
- `engine:verification` — hashes, manifest validation, archive checks
- `feature:dashboard` — overview and recent operations
- `feature:apps` — app inventory and per-app backup selection
- `feature:backup` — backup creation and progress
- `feature:restore` — backup browsing, dry-run, and restore
- `feature:settings` — security, storage, and app settings
- `feature:logs` — safe operation reports

The exact module split may be adjusted after repository inspection; avoid creating empty modules without a concrete dependency or test need.

### Core technologies

- Kotlin and Android Gradle Plugin versions selected to match a currently supported toolchain.
- Jetpack Compose and Navigation.
- Room for local operation and backup metadata.
- WorkManager for deferrable work; foreground services for long-running user-visible operations where required by Android.
- Storage Access Framework (SAF) for user-selected SD/USB destinations.
- PackageManager and PackageInstaller for package inventory and supported install flows.
- Magisk `su` through a narrowly scoped root command runner.
- Zstandard compression where practical.
- AES-256-GCM authenticated encryption.
- Argon2id (or another vetted memory-hard KDF) for passphrase-derived backup keys, with parameters stored in the manifest and validated against resource limits.
- SHA-256 for content integrity; consider per-chunk hashes and a Merkle-style index for large/incremental repositories.

Use maintained, audited libraries. Do not implement cryptographic primitives manually.

## 5. Root execution and privilege boundaries

Root is optional for basic inventory and APK export, but required for many private app-data and system-level operations.

Root layer requirements:
- Detect root availability and identify the su provider/version.
- Request authorization only when a user starts a root-dependent operation.
- Use argument-safe process invocation; never concatenate untrusted user strings into shell commands.
- Use fixed, reviewed command templates and validated paths.
- Apply least privilege and short-lived root subprocesses.
- Capture exit status and bounded diagnostic output.
- Never run arbitrary commands received from backup metadata.
- Do not silently remount system partitions read-write.
- Provide a clear explanation when root is denied or unavailable.

For filesystem restore, resolve target UID/GID from the installed package on the destination ROM; do not blindly reuse source numeric IDs. Restore ownership, modes, extended attributes, and SELinux context only where validated and supported. Prefer Android-native package-manager coordination over direct filesystem replacement.

## 6. Backup coverage

Each category must report **captured**, **skipped**, **failed**, or **not supported**, with a reason.

### Applications

- Installed package inventory, version code/name, version, installer/source metadata where available.
- Base APK and split APKs, including ABI/density/language splits.
- Signing certificate digest and package identity metadata.
- App private data where root and platform policy permit: `/data/user/0/<package>` and device-encrypted data under `/data/user_de/0/<package>` for the supported Android user.
- App-specific external storage.
- OBB data where present.
- App databases, preferences, files, and supported app state as part of the permitted data snapshot.
- Per-app selection and exclusion rules.

### User files

- User-selected media, documents, downloads, and folders.
- App-specific shared files.
- Optional folder-level incremental backup.

### System and device configuration

Use explicit allow-listed adapters, not a blind system-directory copy. Candidate categories:
- Supported runtime permissions and app-op state.
- Notification permission/channel-related settings where accessible and safe.
- Default apps and selected role assignments where Android permits.
- Accessibility service inventory and user-approved settings (never silently enable a service).
- Battery optimization exemptions and supported background restrictions.
- Input method, display, sound, locale, and selected user preferences where stable APIs or safe adapters exist.
- Launcher layout/configuration only where launcher-specific export/import is supported.
- Wi-Fi/network configuration only where Android exposes a supported and safe mechanism; never promise extraction of protected credentials.
- Magisk module inventory, module metadata, and user-selected module configuration; do not auto-install or execute module scripts during restore.

Each adapter must declare source/target version support, required privilege, risk, and reversibility.

## 7. Backup repository format

Use a versioned, portable repository format. Initial conceptual layout:

```
ApexBackup/
  repository.json
  backups/
    <backup-id>/
      manifest.json
      metadata/
        device.json
        packages.json
        compatibility.json
      apps/
        <package-name>/
          app.json
          apk/
          data/
      user-files/
      system-config/
      magisk/
      chunks/
      checksums/
      COMPLETE
```

Requirements:
- Manifest includes format version, backup ID, creation time, source device/ROM/build/API/ABI, Android user, app package/version/signing metadata, category status, encryption/KDF parameters, and content index.
- Encrypt sensitive content and authenticate metadata that influences restore.
- Verify checksums and authentication tags before restore.
- Use safe path normalization; reject path traversal, symlinks escaping the restore root, duplicate/conflicting entries, and malformed manifests.
- Write to a temporary/incomplete location and atomically mark complete only after verification.
- Preserve interrupted jobs as recoverable incomplete backups; never present them as valid.
- Support repository format migrations with explicit versioning.
- Design for incremental/content-addressed chunks after the first full-backup flow is stable.
- A repository must be portable between internal storage, SD card, and PC without depending solely on Android Keystore.

### Encryption and recovery

- User passphrase is the portable recovery mechanism.
- Derive keys with a vetted memory-hard KDF and unique salt.
- Use random nonces correctly for AES-GCM; never reuse a nonce with the same key.
- Store KDF parameters and salt; authenticate manifest/metadata.
- Consider optional device Keystore wrapping as a convenience layer, never as the only way to decrypt a PC/SD backup.
- Explain that a forgotten passphrase cannot be recovered by the app.
- Do not store passphrases in logs, analytics, preferences, or crash reports.

## 8. Storage destinations and transfer

### Internal storage
- User-selected folder via SAF where possible.
- Warn that a backup on the same physical device is not a substitute for an off-device copy.

### SD card
- SAF document-tree access; handle removable media disconnection, revoked permissions, low space, and partial writes.
- Validate available space before starting.

### PC
- Initial support: create a portable repository that the user can copy over USB/MTP.
- Later option: local Wi-Fi transfer with explicit pairing, authenticated session, and encryption in transit.
- Do not depend on privileged MTP internals.

All destinations must support verification after write and safe resume/retry where feasible.

## 9. Restore workflow and safety

1. Select repository and authenticate/decrypt.
2. Verify repository format, manifest, checksums, and encryption before any write.
3. Scan target device and installed packages.
4. Generate a dry-run restore plan.
5. Classify each item as Compatible, Conditional, or Incompatible.
6. Show exact changes, required root actions, storage needs, and risks.
7. Let the user select items; default to no destructive action.
8. Create a safety checkpoint/backup of affected target data where practical.
9. Execute one scoped operation at a time with progress and cancellation boundaries.
10. Verify restored package/data/configuration and report partial failures.

Never silently downgrade an app, overwrite newer target data, change a default app, enable accessibility, or install a Magisk module. Require explicit confirmation for high-risk operations. Do not promise rollback where Android or the target app makes it impossible.

### Cross-ROM migration policy

- Restore user files and compatible APKs first.
- Install apps through PackageInstaller and validate signing identity.
- Restore app data only when package/signature/version/Android compatibility checks pass.
- Run app-specific post-restore checks where possible.
- Restore system configuration only through target-ROM-aware adapters.
- Treat Magisk modules as inventory/export by default; install only after explicit compatibility review and confirmation.
- Never copy source framework state into target ROM.

## 10. User experience

Primary navigation:
- Dashboard
- Apps
- Backups
- Restore
- System & Modules
- Settings

Dashboard should show:
- Root status and device summary.
- Last successful backup and destination.
- Backup integrity/verification state.
- Selected categories and estimated storage.
- Recent operations and actionable warnings.

App list should show package name, app label, version, APK/data availability, size estimate, and compatibility state.

Backup flow:
- Choose categories/apps → choose destination → encryption/passphrase → review estimate → run → verify → report.

Restore flow:
- Choose repository → unlock → verify → target scan → compatibility report → select → dry-run → confirm → execute → verification report.

Design for readable typography, accessible contrast, clear Bengali/English-ready strings, and useful progress/error states. Avoid clutter and avoid implying unsupported capabilities.

## 11. Reliability, privacy, and threat model

Threats include malicious/tampered backup archives, path traversal, malicious symlinks, command injection, compromised root processes, stolen backup media, passphrase guessing, interrupted writes, wrong-device restores, and accidental destructive restore.

Controls:
- Authenticated encryption and integrity verification.
- Strict manifest/schema validation and resource limits.
- Safe path extraction and no archive path escape.
- No shell execution from imported metadata.
- Device/ROM/API compatibility gates.
- Explicit user confirmation for destructive or privileged operations.
- Minimal permissions and root access only when needed.
- Redacted logs; no app-private content or secrets in diagnostics.
- Backup lock and concurrency control to prevent conflicting writes.
- Atomic completion markers and crash/interruption recovery.
- Unit, instrumentation, and device-level tests.

## 12. Testing strategy

- Unit tests: manifest parser, path validation, KDF parameter validation, chunk indexing, compatibility rules, package metadata, and restore-plan generation.
- Security tests: tampered ciphertext, invalid tags, malicious paths, symlink escapes, oversized manifests, malformed archives, and shell-injection payloads.
- Storage tests: low space, revoked SAF permission, SD removal, partial write, duplicate destination, and resume.
- Root tests: no root, denied root, Magisk grant, command failure, timeout, and malformed output.
- Device tests on the target Redmi Note 9 Pro / Evolution X build.
- Same-ROM restore tests before any cross-ROM claims.
- Cross-ROM tests using a controlled test app with synthetic data; never experiment first on the user's only copy of real data.
- Verify APK signatures and package identity after install.
- CI must run formatting, static analysis, unit tests, and build tasks.

No destructive restore test should run against the user's daily-driver data. Maintain synthetic test fixtures and a separate test device/profile when possible.

## 13. Milestone roadmap

| Milestone | Scope | Exit criteria |
|---|---|---|
| M0 — Foundation | Repository inspection, project skeleton, docs, CI, architecture decisions | Reproducible baseline build and documented project structure |
| M1 — Diagnostics | Device/ROM/API/ABI/root/storage diagnostics | Accurate diagnostics with root/no-root test coverage |
| M2 — App inventory | Package scan, APK/split export, signing metadata | Verified APK export and inventory |
| M3 — Secure container | Versioned manifest, encryption, checksums, archive verification | Round-trip encrypted repository tests pass |
| M4 — App-data backup | Root-gated app data capture, status reporting | Synthetic app data backup is complete and verifiable |
| M5 — Same-ROM restore | Dry-run, package coordination, scoped data restore | Controlled same-ROM restore passes without data corruption |
| M6 — System config | Allow-listed adapters and compatibility metadata | Each adapter tested and independently gated |
| M7 — Storage targets | Internal, SD/SAF, USB/PC workflow | Verified write/read across destinations |
| M8 — History & incremental | Backup history, deduplication, incremental chunks | Consistent restore from full + incrementals |
| M9 — Cross-ROM migration | Compatibility engine and safe migration rules | Controlled cross-ROM test matrix with honest limitations |
| M10 — Magisk migration | Module inventory/export and explicit compatibility workflow | No automatic unsafe module execution; tested reports |
| M11 — Hardening & release | Security review, UX polish, CI/release, documentation | Release checklist, verified APK, known-limitations docs |

### Initial task tracker

**M0.1 — Repository reconnaissance**
- Inspect current branches, files, README, license, workflows, and repository settings.
- Confirm whether the repository is genuinely empty.
- Record the baseline state before making changes.

**M0.2 — Project foundation**
- Choose Kotlin/AGP/Gradle/JDK versions compatible with the available CI image.
- Create the smallest buildable Android application skeleton.
- Add a clear module strategy; defer unnecessary modules until needed.
- Add formatting, static analysis, unit-test, and debug-build CI.

**M0.3 — Documentation and decisions**
- Keep this master plan current.
- Add architecture decision records for security, repository format, root execution, and compatibility.
- Add contribution/build instructions and a truthful feature/limitations matrix.

**M1.1 — Device diagnostics**
- Capture model, codename, Android/API, ROM/build fingerprint, ABI, storage, and user/profile information.
- Mark unknown/unavailable fields rather than guessing.

**M1.2 — Root diagnostics**
- Detect Magisk/su and request access only after user action.
- Validate a harmless, bounded root command and report denial/failure cleanly.

**M1.3 — Storage diagnostics**
- Detect internal and SAF-selected destinations, available space, and write/read capability.

Do not start private app-data restore or system-state restore before M1–M5 foundations and safety tests are complete.

## 14. CI and release policy

- GitHub Actions is the authoritative build environment.
- Pin and document toolchain versions; update only with a tested change.
- CI should run formatting, lint/static analysis, unit tests, and assemble tasks.
- Use repository secrets for signing only when release signing is configured.
- Never commit keystores, passwords, tokens, personal backups, or private test data.
- Test APK naming: `test.apk`; release artifact: `Apex Backup.apk`.
- Do not publish nightly builds unless explicitly requested.
- Do not change workflows speculatively; inspect logs and make the smallest targeted fix.
- Report exact workflow run/result and artifact availability; never claim a build succeeded without checking it.

## 15. Documentation deliverables

Maintain:
- `README.md` — project overview, current status, build instructions, safety disclaimer.
- `docs/MASTER_PROJECT_PLAN.md` — this source-of-truth plan.
- `docs/ARCHITECTURE.md` — modules, boundaries, data flow.
- `docs/SECURITY.md` — threat model, encryption, root and restore safety.
- `docs/BACKUP_FORMAT.md` — repository format and versioning.
- `docs/COMPATIBILITY.md` — same-ROM/cross-ROM support matrix.
- `docs/ROADMAP.md` — milestone and task status.
- `docs/decisions/` — architecture decision records.
- `CHANGELOG.md` — user-visible changes.

## 16. Working agreement for future implementation sessions

1. Start by inspecting the current repository state and relevant source files.
2. Read this plan and the latest task/decision status before editing.
3. Work on one coherent milestone/task at a time.
4. Prefer small commits with clear messages.
5. Preserve existing working CI and branch identity policies.
6. Do not overwrite user changes or unrelated files.
7. Run the narrowest relevant tests, then the full required CI checks.
8. Verify changes from GitHub after writing; include commit SHA and exact paths.
9. State what is implemented, what is tested, what remains, and known risks.
10. If access, build, or test verification fails, report the failure plainly and stop short of claiming completion.

## 17. Current status

- [x] Product scope and target environment recorded.
- [x] Architecture, safety boundaries, security principles, and roadmap defined.
- [ ] Master plan committed and verified in GitHub.
- [ ] M0.1 repository reconnaissance.
- [ ] M0.2 buildable project foundation.
- [ ] M0.3 CI and supporting documentation.

**Next action:** complete M0.1 repository reconnaissance, then implement only the minimum verified M0.2 foundation.
