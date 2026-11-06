# ADR-005: Post-login settings restoration

- **Status:** Proposed for M6; implementation not started
- **Date:** 2026-11-07
- **Milestone:** M6 — Post-login settings restoration
- **Dependency:** M5 same-ROM restore safety foundation

## Context

Apex Backup can capture APKs and selected app-private data, but restoring an app's entire old private-data directory after a fresh installation or login is unsafe. It can overwrite newly created authentication state, invalidate a fresh session, or import opaque secrets. Android apps also store preferences inconsistently: some are local, some are server-synced, and some are version-specific or inaccessible.

The desired workflow is narrower: the user installs/opens an app, signs in normally through its official interface, then asks Apex Backup to re-apply selected old preferences without moving the old account session.

## Decision

Implement this as a separate M6 subsystem, not as an extension that blindly replays M5 raw app data.

### 1. User-controlled post-login workflow

1. During backup, a supported app adapter captures only its allow-listed, verified local settings.
2. On the destination, the user installs/opens the app and completes sign-in through the app's official UI.
3. Apex Backup presents a user-controlled confirmation such as **“I'm signed in”**. Do not depend on automatic login detection; Android generally does not expose a reliable, universal signal for another app's authenticated state.
4. Apex Backup previews the settings that the adapter proposes to apply.
5. After explicit approval, the adapter applies only the approved settings and reports each result.

The confirmation is a user assertion, not cryptographic proof of login. The app must never claim it independently verified the account session.

### 2. Strict authentication-data exclusion

Never capture, export, inject or restore:
- passwords, passkeys or credential-manager secrets;
- cookies, OAuth/access/refresh tokens, session IDs or authenticated webviews;
- login/session databases or opaque files whose purpose is unknown;
- Android Keystore keys, hardware-backed secrets, DRM material or device-bound credentials.

Never automate login or bypass an app's authentication, device binding, integrity checks or security controls. A fresh login must remain owned by the app and its official authentication flow.

### 3. Adapter model and compatibility

- Each adapter is specific to an exact package identity and a declared app-version range.
- Adapter code must explicitly enumerate every supported setting, its source, validation, destination and restore method.
- Prefer official export/import APIs or documented settings mechanisms.
- Do not infer that a file is a preference store merely from its filename. Unknown formats and unknown versions fail closed.
- Do not copy whole SharedPreferences/XML/SQLite stores wholesale. A database may mix harmless preferences with tokens, account identifiers or other sensitive state.
- Treat server-synced settings as owned by the account/service. Do not duplicate them locally unless a verified, user-visible reason exists.

### 4. Three restoration classes

- **Automatic:** a specific local preference has been verified safe, version-compatible and independently testable; apply only after user approval of the preview.
- **Assisted:** the adapter can identify a candidate value but needs the user to review or confirm it before applying.
- **Manual/Unsupported:** the setting is server-side, inaccessible, security-sensitive, unknown or not reliably restorable; explain the limitation and leave it untouched.

The class is per setting and per supported app version, not a blanket promise for an entire app.

### 5. App-specific boundaries

- Social apps such as Facebook and Instagram may sync many preferences to the account. If the service restores a setting after normal login, Apex Backup should report it as account-synced/not needed rather than overwrite it.
- WhatsApp chat history remains under WhatsApp's official backup/restore flow. M6 settings adapters must not claim to replace chat migration or restore chat databases.
- No adapter is shipped as supported until its exact package/version behavior has been tested. Unsupported apps remain clearly unsupported.

### 6. Isolation, preview and failure handling

- Keep M6 data in a separate, typed settings payload namespace; do not merge it into raw app-data TAR restore.
- Bind every settings payload to package name, signing-certificate digest, app version/range and adapter schema version.
- Verify the encrypted container and payload integrity before reading/applying settings.
- Preview every proposed change; require explicit user approval and support cancellation.
- Validate values against adapter-defined schemas and strict size/type limits.
- Apply settings through the narrowest supported interface. Never turn imported setting names or values into shell commands or arbitrary file paths.
- Report each setting as restored, skipped, unsupported or failed.
- Provide rollback only where the adapter can safely snapshot the current setting and restore it without touching authentication state. If rollback is not safe, disclose that before applying.
- On package/signature/version/schema mismatch, fail closed and make no changes.

### 7. Validation plan

Start with a disposable synthetic app that has:
- harmless local preferences (theme, language, notification preference);
- a deliberately separate synthetic session/token fixture that must never be exported or restored;
- versioned preference schemas and malformed/unknown-version test cases.

Acceptance tests:
1. Only allow-listed preferences are present in the backup; synthetic session/token canaries are absent.
2. Applying settings after a fresh login does not replace, invalidate or modify the session fixture.
3. Package, signing-certificate, app-version or adapter-schema mismatch is rejected before changes.
4. Unknown settings, malformed values and oversized payloads are skipped/rejected without arbitrary writes.
5. Preview, user approval, cancellation and per-setting result reporting work.
6. A supported adapter demonstrates byte/value-level expected results on a disposable target; no universal app compatibility claim is made.

## Consequences

- The feature is narrower than whole-app-data restore, but substantially safer and more honest.
- App-specific adapter maintenance is required as apps change their internal formats and versions.
- Some settings will remain manual or unsupported; account-synced settings may return automatically after sign-in.
- M6 depends on M5's verified container, identity binding and safe staging, but it must never use M5 as permission to overwrite authentication state.
- No production app adapter or implementation is claimed by this decision record.
