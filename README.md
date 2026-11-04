# Apex Backup

A root-capable Android backup and migration project focused on recoverability, security, and honest compatibility reporting.

> **Development status:** early implementation stage. M1 device/root diagnostics are implemented on `dev`. Backup and restore functionality is not implemented yet. Do not use this app to protect important data until the relevant features have been implemented, tested, and documented.

## Goals

- Back up supported app packages, app data, user files, and selected device configuration.
- Store backups in a portable, encrypted, integrity-checked repository.
- Support internal storage, removable SD cards, and PC transfer.
- Provide dry-run restore plans and same-ROM/cross-ROM compatibility checks.
- Use root only for operations that require it.

## Current status

- [x] Master project plan committed.
- [x] Initial Android application skeleton and CI baseline.
- [x] Permanent JKS signing configured; CI verifies matching debug/release certificates.
- [x] M1 device information, internal storage information, and explicit root diagnostics UI.
- [ ] On-device M1 validation on Redmi Note 9 Pro / Evolution X 17 / Magisk.
- [ ] Package inventory and APK export.
- [ ] Encrypted backup container.
- [ ] App-data backup and controlled restore.

See [Master Project Plan](docs/MASTER_PROJECT_PLAN.md), [Architecture](docs/ARCHITECTURE.md), and [Roadmap](docs/ROADMAP.md).

## Build and signing

The project uses Gradle Kotlin DSL, Android Gradle Plugin, Kotlin, Jetpack Compose, and JDK 17. GitHub Actions is the initial verification environment. See `.github/workflows/android-ci.yml`.

Configure these GitHub Actions repository secrets:

- `KEYSTORE_BASE64`: Base64-encoded JKS keystore.
- `KEYSTORE_PASSWORD`: keystore password.
- `KEY_ALIAS`: signing key alias.
- `KEY_PASSWORD`: signing key password.

The workflow decodes the keystore into the temporary GitHub runner directory. The keystore and passwords are never committed. Both debug (test) and release APKs use the same permanent signing key and application ID (`com.apex.backup`) when secrets are present, allowing either build to update an installation signed with that key.

Keep a secure offline backup of the original JKS file and its passwords. If the signing key is lost, future APKs signed with a different key generally cannot update existing installations.

## M1 diagnostics scope

- Device manufacturer/model/codename, Android/API level, security patch, build display/fingerprint and supported ABIs.
- Internal data partition total, available and used space.
- Root status from a bounded `su -c id` check; when granted, read-only Magisk version, SELinux mode and encryption properties.
- Root is requested only after the user presses **Check root**.
- No arbitrary shell input, backup, restore, partition writes or system changes.

## Safety

A backup is not valid merely because a copy operation completed. Integrity verification, restore compatibility checks, and an explicit restore preview are required before data can be considered recoverable. Never restore raw system partitions or framework state across ROMs.
