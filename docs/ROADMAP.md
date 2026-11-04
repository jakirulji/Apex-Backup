# Roadmap

| Milestone | Status |
|---|---|
| M0 — Repository and build foundation | Complete |
| M1 — Device/root/storage diagnostics | Device-validated; CI verification tracked in Actions |
| M2 — App inventory and APK export | Implemented; CI and device validation pending |
| M3 — Encrypted repository format | Not started |
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
- [x] Test/debug APK is no longer uploaded as a separate artifact.

## M1 — Device/root/storage diagnostics

- [x] Device model, codename, Android/API level, security patch, build and ABI data.
- [x] Internal data partition total/available/used space.
- [x] Explicit user-triggered root check; no root prompt at app launch.
- [x] Bounded root command execution and fixed read-only commands.
- [x] Magisk version, SELinux mode and Android encryption properties when root is granted.
- [x] User has confirmed that device information is displayed correctly.
- [x] CI verification is tracked in the corresponding GitHub Actions run.

## M2 — App inventory and APK export

- [x] Enumerate installed applications using Android package visibility permission.
- [x] Display app label, package name, version, user/system classification and readable APK file count.
- [x] Search by app label or package name.
- [x] Filter all/user/system apps and select multiple apps.
- [x] Export selected base and split APK files into a ZIP using Android's system document picker.
- [x] Export runs off the main thread and reports errors.
- [x] Clearly disclose that APK export does not include private app data, accounts, login sessions or app settings.
- [ ] Verify CI and signed release APK.
- [ ] Validate package visibility and APK export on Redmi Note 9 Pro / Evolution X 17.

M2 does not back up or restore private app data. APK export is not a complete application backup.

## Next

M3 defines and tests the versioned encrypted backup container before any private app data is read or copied.
