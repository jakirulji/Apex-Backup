# Roadmap

| Milestone | Status |
|---|---|
| M0 — Repository and build foundation | Complete |
| M1 — Device/root/storage diagnostics | Device-validated; CI verification tracked in Actions |
| M2 — App inventory and APK export | Implemented; CI and device validation confirmed |
| M3 — Encrypted repository format | v3 streaming engine implemented; CI and device validation pending |
| M4 — App-data backup | Not started |
| M5 — Same-ROM restore | Not started |
| M6 — System configuration adapters | Not started |
| M7 — Internal/SD/PC storage targets | Not started |
| M8 — History and incremental backups | Not started |
| M9 — Cross-ROM migration | Not started |
| M10 — Magisk module migration | Not started |
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
- [x] Display app label, package name, version, user/system classification and readable APK file count.
- [x] Search, filter and multi-select installed apps.
- [x] Export selected base and split APK files into a ZIP using the system document picker.
- [x] Export runs off the main thread and reports errors.
- [x] Clearly disclose that APK export excludes private app data, accounts, sessions and settings.
- [x] CI, signed APK build and device inventory/export validation confirmed.

M2 APK ZIP export is not a complete application backup.

## M3 — Encrypted backup container

- [x] Define versioned binary container header and authenticated format metadata (v1/v2/v3).
- [x] Derive portable encryption keys with Argon2id (v1 parameters: 3 iterations, 64 MiB, parallelism 1).
- [x] Encrypt ZIP payload using AES-256-GCM with the complete header as authenticated additional data.
- [x] Include a final manifest with archive paths, byte sizes and SHA-256 digests.
- [x] Stream APK files into the encrypted container; do not create a plaintext intermediate archive.
- [x] Verify password, GCM authentication tag, manifest and per-file SHA-256 digests.
- [x] Add v2 password verifier for immediate wrong-passphrase rejection; retain v1 read compatibility.
- [x] Add v3 Tink AES-256-GCM-HKDF Streaming AEAD with a fresh per-backup keyset wrapped by the Argon2id-derived password key.
- [x] Keep v1/v2 read compatibility; write all new backups as v3.
- [x] Authenticate the complete stream and final segment; reject truncation and segment tampering.
- [x] Use heap-adaptive bounded I/O buffers (256 KiB–4 MiB) and a 1 MiB authenticated segment profile without a software throughput cap.
- [x] Reject unsafe paths, duplicate entries, unsupported KDF parameters and malformed manifests.
- [x] Add UI to create encrypted APK containers and verify an existing container through SAF.
- [ ] CI tests, lint, signed release build and signature verification.
- [ ] Add automated v3 tests for wrong passphrase early abort, segment tampering, truncation, empty and large payloads, plus v1/v2 compatibility fixtures.
- [ ] Device validation on Redmi Note 9 Pro / Evolution X 17, including wrong-password and damaged-file checks.

M3 currently encrypts APK files only. It does not yet capture private app data, accounts, sessions, permissions or system settings.

## Next

M4 will add carefully scoped, root-gated private app-data capture. It must use the M3 container and report captured, skipped, failed and unsupported items separately. Restore remains deferred until backup capture and verification are reliable.
