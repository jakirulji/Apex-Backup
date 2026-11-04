# Architecture

Apex Backup is organized around clear boundaries between presentation, domain logic, platform adapters, privileged operations, and storage.

## Initial foundation

The first implementation is a single Android application module with Jetpack Compose. This is deliberate: establish a reproducible build and a tested application shell before splitting code into modules.

## Planned boundaries

- **Presentation:** Compose screens and navigation; no shell commands or filesystem policy.
- **Domain:** backup plans, restore plans, compatibility decisions, and operation results.
- **Platform adapters:** PackageManager, SAF, Android user/profile APIs, and system configuration adapters.
- **Root boundary:** one constrained root command runner; imported backup data must never become executable commands.
- **Storage:** versioned repository reader/writer independent of destination (internal, SAF/SD, PC transfer).
- **Security:** passphrase KDF, authenticated encryption, integrity verification, and secret handling.

## Dependency direction

Presentation -> domain interfaces <- platform/storage/root implementations. Domain code must remain testable without Android framework dependencies wherever practical.

## Planned package layout

```
com.apex.backup
  app/                 Application entry point and dependency wiring
  core/model/          Domain models and result types
  core/security/       Encryption and key derivation
  core/storage/        Repository and destination abstractions
  core/root/           Root detection and command execution
  engine/scanner/      Device and package inventory
  engine/backup/       Backup orchestration
  engine/restore/      Restore planning and execution
  engine/compatibility/Compatibility analysis
  feature/             Compose features
```

The package layout is a target, not a claim that these packages already exist.
