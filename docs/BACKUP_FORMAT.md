# Apex Backup Container Format

**Status:** Version 3 implementation; version 1 and version 2 read compatibility retained  
**Extension:** `.apexbackup`  
**Purpose:** Portable authenticated encryption container for backup payloads.

## v3 wire format

All integer fields are big-endian.

| Field | Size | Value |
|---|---:|---|
| Magic | 8 bytes | ASCII `APEXBKUP` |
| Format version | 4 bytes | `3` |
| KDF identifier | 1 byte | `1` = Argon2id |
| Argon2id iterations | 4 bytes | `3` |
| Argon2id memory | 4 bytes | `65536` KiB |
| Argon2id parallelism | 4 bytes | `1` |
| Salt length | 1 byte | `16` |
| Wrap nonce length | 1 byte | `12` |
| Salt | 16 bytes | Cryptographically random |
| Wrap nonce | 12 bytes | Cryptographically random AES-GCM nonce |
| Password verifier | 16 bytes | Truncated HMAC-SHA-256, domain-separated for v3 |
| Wrapped keyset length | 4 bytes | Bounded length, big-endian |
| Wrapped Tink keyset | Variable | AES-256-GCM encrypted Tink Streaming AEAD keyset |
| Streaming ciphertext | Remaining bytes | Tink AES-256-GCM-HKDF Streaming AEAD |

The Argon2id-derived 256-bit key is used to authenticate the password verifier and wrap the randomly generated Tink streaming keyset. The keyset is never written in plaintext to the container. The wrapped keyset is authenticated with the v3 fixed header as AES-GCM associated data. The Streaming AEAD payload is associated with the complete v3 header, including the wrapped keyset.

The verifier is compared in constant time immediately after reading the bounded header and deriving the Argon2id key. A mismatch returns **Wrong passphrase** before any streaming payload bytes are read. Argon2id remains intentionally expensive to resist offline guessing.

## Streaming encryption

- New backups use Tink's `AES256_GCM_HKDF_1MB` Streaming AEAD profile.
- ZIP data is fed directly into the encrypting stream; the complete payload is never loaded into RAM or written as a plaintext temporary file.
- APK payload entries use ZIP's no-compression mode because APKs are already ZIP-compressed; this avoids spending CPU on redundant compression.
- Each authenticated segment is bound to its position in the stream. Segment reordering, insertion, removal and cross-container substitution are detected.
- Verification consumes the complete decrypted stream, including its final segment, so truncation and final-segment authentication failures are detected.
- The streaming layer uses bounded, heap-adaptive I/O buffers (256 KiB–4 MiB). It does not impose a software throughput ceiling. Actual speed is bounded by device CPU/crypto implementation, source/destination storage and Android document-provider throughput.
- The Tink stream is sequential in this implementation; no unsafe parallel segment processing is attempted.

## v2 wire format and compatibility

Version 2 has the same fixed KDF/salt/nonce fields, followed by a 16-byte password verifier and a single AES-256-GCM ciphertext containing the ZIP payload. The complete header is AES-GCM additional authenticated data. Wrong passwords are rejected before reading the v2 payload.

## v1 wire format and compatibility

Version 1 has the same fields through the GCM nonce, but no password-verifier field. The reader continues to accept v1 containers and authenticates them using the original full AES-256-GCM process. A wrong passphrase on a v1 container may therefore require reading the full ciphertext.

New backups are v3. Existing v1 and v2 backups are read-only compatible; they are not rewritten or migrated automatically.

## Key derivation and encryption

- Passphrase encoding: UTF-8.
- KDF: Argon2id, version 1.3.
- Parameters: 3 iterations, 64 MiB memory, parallelism 1.
- Password-derived wrapping key: 256 bits.
- Keyset wrapping: AES-256-GCM with 128-bit tag.
- Payload: Tink AES-256-GCM-HKDF Streaming AEAD, 1 MiB segment profile.
- Salt and wrapping nonce are newly generated for every container.
- A fresh Tink payload keyset is generated for every backup and stored only in wrapped form.
- The passphrase is not stored in the file or application preferences.
- A forgotten passphrase cannot be recovered.

## Encrypted ZIP payload

The ZIP stream contains selected payload files and one final manifest:

```
apps/<package>/<version>/base.apk
apps/<package>/<version>/split-1.apk
...
META-INF/apex-manifest.txt
```

The manifest records format marker, creation timestamp, entry count, and for each payload file its Base64URL-encoded relative path, byte length and SHA-256 digest. The manifest is inside the authenticated stream.

## Verification

Verification must:
1. Validate the fixed header and supported parameters before KDF work.
2. Derive the key from the supplied passphrase.
3. For v2/v3, compare the password verifier in constant time and stop on mismatch before payload reads.
4. For v3, authenticate and unwrap the Tink keyset, then decrypt and consume the complete Streaming AEAD stream.
5. Reject truncation, segment corruption, duplicate entries, directory entries, absolute paths, traversal segments, backslashes and malformed manifests.
6. Compare every payload path, size and SHA-256 digest with the manifest.
7. Reject missing, extra, modified or unauthenticated content.

No extraction or restore is performed by the verification operation.

## Current scope

The container packages installed APK base/split files. It does not contain private app data, credentials, permissions, app settings, Android Keystore secrets or system configuration. The format is independent of Android Keystore so a backup can be copied to a PC or SD card and decrypted with a compatible future tool.

KDF parameters are fixed and validated; untrusted files cannot request arbitrary memory or CPU parameters.
