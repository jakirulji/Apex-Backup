# Apex Backup Security Notes

## Portable backup encryption (container v1)

- Argon2id v1.3 derives a 256-bit key from the user passphrase using a random 16-byte salt, 3 iterations, 64 MiB memory and parallelism 1.
- AES-256-GCM encrypts the payload with a fresh 12-byte nonce and a 128-bit authentication tag.
- The entire fixed-format header is authenticated as AES-GCM additional authenticated data.
- The encrypted ZIP contains a final manifest with per-file SHA-256 digests and byte lengths.
- Verification consumes the complete authenticated stream before reporting success and independently checks the manifest.
- Passphrases are not persisted. The app cannot recover a forgotten passphrase.

## Input validation

- KDF parameters are strictly fixed for format v1 before Argon2id runs.
- Archive paths must be relative, normalized slash-separated paths; traversal, absolute paths, backslashes, duplicate names and reserved manifest collisions are rejected.
- Manifest size and entry count are bounded.
- Verification never extracts files and never executes content from a backup.

## Scope limitation

Current M3 containers contain APK files only. They do not back up private app data, account credentials, sessions, device-bound Android Keystore keys, permissions or system configuration. A successful cryptographic verification is not proof that an APK can be installed or that its app data can be restored.

## Operational guidance

Use a unique, strong passphrase and store it separately from the backup. Never send it in chat, commit it to source control, or place it in logs. Keep another copy of important backups on a separate physical device.
