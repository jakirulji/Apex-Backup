# Apex Backup

A root-capable Android backup and migration project focused on recoverability, security, and honest compatibility reporting.

> **Development status:** early foundation stage. Backup and restore functionality is not implemented yet. Do not use this app to protect important data until the relevant features have been implemented, tested, and documented.

## Goals

- Back up supported app packages, app data, user files, and selected device configuration.
- Store backups in a portable, encrypted, integrity-checked repository.
- Support internal storage, removable SD cards, and PC transfer.
- Provide dry-run restore plans and same-ROM/cross-ROM compatibility checks.
- Use root only for operations that require it.

## Current status

- [x] Master project plan committed.
- [x] Repository reconnaissance completed.
- [x] Initial Android application skeleton added on `dev`.
- [ ] Root/device diagnostics.
- [ ] Package inventory and APK export.
- [ ] Encrypted backup container.
- [ ] App-data backup and controlled restore.

See [Master Project Plan](docs/MASTER_PROJECT_PLAN.md), [Architecture](docs/ARCHITECTURE.md), and [Roadmap](docs/ROADMAP.md).

## Build

The project uses Gradle Kotlin DSL, Android Gradle Plugin, Kotlin, Jetpack Compose, and JDK 17. GitHub Actions is the initial verification environment. See the workflow in `.github/workflows/android-ci.yml`.

### APK signing

Configure these GitHub Actions repository secrets:

- `KEYSTORE_BASE64`: Base64-encoded JKS keystore.
- `KEYSTORE_PASSWORD`: keystore password.
- `KEY_ALIAS`: signing key alias.
- `KEY_PASSWORD`: signing key password.

The workflow decodes the keystore into the temporary GitHub runner directory; the keystore and passwords are never committed to the repository. When the secrets are present, both debug (test) and release APKs use the same permanent signing key and the same application ID (`com.apex.backup`), allowing either build to update an installation signed with that key. Pull requests without access to repository secrets can still run tests and build a debug APK using the standard debug key; branch builds and manual runs require the signing secrets.

Keep a secure offline backup of the original JKS file and its passwords. If the signing key is lost, future APKs signed with a different key generally cannot update existing installations.

## Safety

A backup is not valid merely because a copy operation completed. Integrity verification, restore compatibility checks, and an explicit restore preview are required before data can be considered recoverable. Never restore raw system partitions or framework state across ROMs.
