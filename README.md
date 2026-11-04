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

## Safety

A backup is not valid merely because a copy operation completed. Integrity verification, restore compatibility checks, and an explicit restore preview are required before data can be considered recoverable. Never restore raw system partitions or framework state across ROMs.
