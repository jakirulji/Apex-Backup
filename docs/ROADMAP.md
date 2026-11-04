# Roadmap

| Milestone | Status |
|---|---|
| M0 — Repository and build foundation | Complete |
| M1 — Device/root/storage diagnostics | Implemented; CI and device validation pending |
| M2 — App inventory and APK export | Not started |
| M3 — Encrypted repository format | Not started |
| M4 — App-data backup | Not started |
| M5 — Same-ROM restore | Not started |
| M6 — System configuration adapters | Not started |
| M7 — Internal/SD/PC storage targets | Not started |
| M8 — History and incremental backups | Not started |
| M9 — Cross-ROM migration | Not started |
| M10 — Magisk module migration | Not started |
| M11 — Hardening and release | Not started |

## M0 completion

- [x] Repository reconnaissance and Android project foundation.
- [x] Documentation and CI baseline.
- [x] Permanent JKS signing configured for debug and release builds.
- [x] CI verifies both APK signatures and confirms the certificate SHA-256 fingerprints match.

## M1 — Device/root/storage diagnostics

- [x] Read device model, codename, Android/API level, security patch, build and ABI data.
- [x] Read internal data partition total/available/used space.
- [x] Add explicit user-triggered root check; do not trigger a Magisk prompt at app launch.
- [x] Bound root command execution with an 8-second timeout.
- [x] When root is granted, read Magisk version, SELinux mode and Android encryption properties.
- [x] Keep root commands fixed and read-only; no user-supplied shell strings.
- [ ] Verify unit tests, lint, signed debug/release builds and matching certificate in CI.
- [ ] Install and validate on Redmi Note 9 Pro (joyeuse), Evolution X 17 and Magisk.
- [ ] Confirm root-denied and root-granted behavior on device.

M1 is not considered fully validated until CI passes and the app has been exercised on the target device.

## Next

M2 begins with a read-only package inventory, package metadata and APK export. It must not attempt app-data backup until the package inventory and storage/export path are tested.
