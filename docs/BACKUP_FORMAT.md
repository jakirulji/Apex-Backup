# Apex Backup Container Format

**Status:** Version 1 implementation  
**Extension:** \`.apexbackup\`  
**Purpose:** Portable authenticated encryption container for backup payloads.

## v1 wire format

All integer fields are big-endian.

| Field | Size | Value |
|---|---:|---|
| Magic | 8 bytes | ASCII \`APEXBKUP\` |
| Format version | 4 bytes | \`1\` |
| KDF identifier | 1 byte | \`1\` = Argon2id |
| Argon2id iterations | 4 bytes | \`3\` |
| Argon2id memory | 4 bytes | \`65536\` KiB |
| Argon2id parallelism | 4 bytes | \`1\` |
| Salt length | 1 byte | \`16\` |
| Nonce length | 1 byte | \`12\` |
| Salt | 16 bytes | Cryptographically random |
| GCM nonce | 12 bytes | Cryptographically random |
| Ciphertext | Remaining bytes | AES-256-GCM encrypted ZIP payload, including 128-bit tag |

The complete header, from magic through nonce, is supplied as AES-GCM additional authenticated data. Header fields are validated against strict v1 limits before key derivation; untrusted files cannot request arbitrary memory or CPU parameters.

## Key derivation and encryption

- Passphrase encoding: UTF-8.
- KDF: Argon2id, version 1.3.
- Parameters: 3 iterations, 64 MiB memory, parallelism 1.
- Output key: 256 bits.
- Cipher: AES-256-GCM, 128-bit authentication tag.
- Salt and nonce are newly generated for every container.
- The passphrase is not stored in the file or application preferences.
- A forgotten passphrase cannot be recovered.

## Encrypted ZIP payload

The ZIP stream contains selected payload files and one final manifest:

\`\`\`
apps/<package>/<version>/base.apk
apps/<package>/<version>/split-1.apk
...
META-INF/apex-manifest.txt
\`\`\`

The manifest records format marker, creation timestamp, entry count, and for each payload file its Base64URL-encoded relative path, byte length and SHA-256 digest. The manifest is inside the authenticated ciphertext.

## Verification

Verification must:
1. Validate the fixed header and supported parameters.
2. Derive the key from the supplied passphrase.
3. Decrypt the ZIP stream and consume it fully so the GCM tag is checked.
4. Reject duplicate entries, directory entries, absolute paths, traversal segments, backslashes and malformed manifests.
5. Compare every payload path, size and SHA-256 digest with the manifest.
6. Reject missing, extra, modified or unauthenticated content.

No extraction or restore is performed by the verification operation.

## Current scope and compatibility

Version 1 currently packages installed APK base/split files. It does not contain private app data, credentials, permissions, app settings, Android Keystore secrets or system configuration. The format is independent of Android Keystore so a backup can be copied to a PC or SD card and decrypted with a compatible future tool.

The v1 Argon2id parameters are fixed and authenticated. A future format version may define new parameter profiles; readers must never accept unbounded parameters from an untrusted header.
