# Roadmap

| Milestone | Status |
|---|---|
| M0 — Repository and build foundation | Complete |
| M1 — Device/root/storage diagnostics | Next |
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

## M0 task status

- [x] M0.1 Repository reconnaissance: initial repository contained no source, README, or CI workflow.
- [x] M0.2 Buildable Android project foundation: Compose skeleton builds successfully in GitHub Actions.
- [x] M0.3 Documentation and CI baseline: project documentation and CI workflow are committed; unit tests, lint, debug APK assembly, and artifact upload all passed in CI.

## M0.2 CI verification

- Workflow: Android CI
- Run: [#9](https://github.com/jakirulji/Apex-Backup/actions/runs/41797865896)
- Commit: `82b4220ba1ac465e16bf568e4ac4cf62fe0b65d6`
- Result: success
- Verified steps: unit tests and lint, debug APK assembly, test artifact preparation, and upload.
- Artifact: `test-apk` (10,734,975 bytes; expires 2026-11-18).

A task is complete only after the relevant build/tests have been run and the results inspected.
