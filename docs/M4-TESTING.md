# M4 disposable-device content validation

This procedure validates the **contents** of the unified backup's app-data TAR streams. It is separate from the encrypted-container extraction test: extraction verifies authenticated container entries and SHA-256 values, while this comparison opens the extracted TAR files and checks known fixture values.

## Test sequence

1. Install the CI artifact named `apex-backup-test-lab` on the disposable test device.
2. Open **Apex Backup Test Lab** and tap **Generate synthetic test data**.
3. Tap **Verify synthetic test data**. Resolve any fixture-generation or verification failure before continuing.
4. Open Apex Backup. Check root access and grant Magisk permission.
5. In Installed apps, select **Apex Backup Test Lab** only. Use **Create encrypted backup** to create the unified APK + app-data encrypted backup.
6. In the M4 extraction test, select that `.apexbackup` file, enter the same passphrase, and tap **Extract & verify contents**.
7. After extraction passes, tap **Compare Test Lab file contents**.

## Expected content checks

The comparator checks:
- CE: known file payload, all three SharedPreferences values, and the expected SQLite row.
- DE: known file payload, all three SharedPreferences values, and the expected SQLite row.
- App-specific external storage: exact synthetic file payload.
- OBB: exact synthetic OBB fixture payload.
- CE and DE cache exclusion: the cache fixture must not appear in either TAR.

A normal fully supported device should report **14 PASS, 0 FAIL, 0 SKIP**. Missing/inaccessible TAR categories are reported as failures; a TAR that cannot be parsed or contains unsafe paths is rejected. Cache exclusion is a PASS only when the cache fixture is absent.

## Safety boundary

This is a read-only comparison of extracted files in Apex Backup's app-private cache. It does not restore, overwrite, or modify Test Lab or any other installed app's data. Do not use a daily-driver app as a restore target. M5 restore remains a separate, unimplemented milestone.
