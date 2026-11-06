package com.apex.backup.capture

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class ContentCheck(val name: String, val status: String, val detail: String)

data class ContentComparisonReport(val checks: List<ContentCheck>) {
    val passed: Int get() = checks.count { it.status == "PASS" }
    val failed: Int get() = checks.count { it.status == "FAIL" }
    val skipped: Int get() = checks.count { it.status == "SKIP" }
}

/**
 * Compares the disposable Test Lab's known fixtures with the contents of extracted M4 TAR
 * streams. This is a content-level check; it never writes into an installed app's data folder.
 */
object TestLabContentComparator {
    private const val PACKAGE = "com.apex.backup.testlab"
    private const val MAX_CAPTURED_ENTRY = 32L * 1024L * 1024L
    private val expectedEntries = setOf(
        "files/apex-backup-test-lab/ce-files.txt",
        "shared_prefs/apex_backup_test_lab.xml",
        "databases/apex_lab.db",
        "files/apex-backup-test-lab/de-files.txt",
        "files/ApexBackupTestLab/external-fixture.txt",
        "main.1.com.apex.backup.testlab.obb",
        "cache/apex-lab-cache.tmp"
    )

    fun compare(extractedRoot: File): ContentComparisonReport {
        require(extractedRoot.isDirectory) { "No extracted backup directory is available." }
        val checks = mutableListOf<ContentCheck>()
        val ce = readArchive(extractedRoot, "CE", checks)
        val de = readArchive(extractedRoot, "DE", checks)
        val external = readArchive(extractedRoot, "EXTERNAL", checks)
        val obb = readArchive(extractedRoot, "OBB", checks)

        checkFixtureFile(
            checks, ce, "CE file",
            "files/apex-backup-test-lab/ce-files.txt",
            "CE synthetic file payload\nApex Backup Test Lab fixture v1\n"
        )
        checkPreferences(checks, ce, "CE SharedPreferences", "CE")
        checkDatabase(checks, ce, "CE database", "CE")

        checkFixtureFile(
            checks, de, "DE file",
            "files/apex-backup-test-lab/de-files.txt",
            "DE synthetic file payload\nApex Backup Test Lab fixture v1\n"
        )
        checkPreferences(checks, de, "DE SharedPreferences", "DE")
        checkDatabase(checks, de, "DE database", "DE")

        checkFixtureFile(
            checks, external, "External file",
            "files/ApexBackupTestLab/external-fixture.txt",
            "External synthetic payload\nApex Backup Test Lab v1\n"
        )
        checkFixtureFile(
            checks, obb, "OBB file",
            "main.1.com.apex.backup.testlab.obb",
            "OBB synthetic payload\nNot a real game asset\n"
        )

        listOf("CE" to ce, "DE" to de).forEach { (label, archive) ->
            if (archive == null) {
                checks += ContentCheck("$label cache exclusion", "SKIP", "Archive unavailable.")
            } else {
                val cacheIncluded = archive.paths.any {
                    it == "cache/apex-lab-cache.tmp" || it.startsWith("code_cache/")
                }
                checks += ContentCheck(
                    "$label cache exclusion",
                    if (cacheIncluded) "FAIL" else "PASS",
                    if (cacheIncluded) "Cache data unexpectedly exists in the TAR."
                    else "Regenerable cache paths are absent as required."
                )
            }
        }
        return ContentComparisonReport(checks)
    }

    private data class ArchiveContents(
        val paths: Set<String>,
        val files: Map<String, ByteArray>
    )

    private fun readArchive(
        root: File,
        category: String,
        checks: MutableList<ContentCheck>
    ): ArchiveContents? {
        val archive = File(root, "app-data/$PACKAGE/${category.lowercase()}.tar")
        if (!archive.isFile) {
            checks += ContentCheck("$category TAR", "FAIL", "Expected archive is missing.")
            return null
        }
        return try {
            FileInputStream(archive).use { readTar(it) }
        } catch (error: Exception) {
            checks += ContentCheck(
                "$category TAR", "FAIL",
                "Could not parse archive: ${error.message ?: error.javaClass.simpleName}"
            )
            null
        }
    }

    private fun checkFixtureFile(
        checks: MutableList<ContentCheck>,
        archive: ArchiveContents?,
        label: String,
        path: String,
        expected: String
    ) {
        if (archive == null) {
            checks += ContentCheck(label, "SKIP", "TAR archive unavailable.")
            return
        }
        val actual = archive.files[path]
        val valid = actual != null && String(actual, StandardCharsets.UTF_8) == expected
        checks += ContentCheck(
            label, if (valid) "PASS" else "FAIL",
            if (valid) "Exact fixture content matched." else "Missing file or content mismatch: $path"
        )
    }

    private fun checkPreferences(
        checks: MutableList<ContentCheck>,
        archive: ArchiveContents?,
        label: String,
        fixtureLabel: String
    ) {
        if (archive == null) {
            checks += ContentCheck(label, "SKIP", "TAR archive unavailable.")
            return
        }
        val xml = archive.files["shared_prefs/apex_backup_test_lab.xml"]
            ?.let { String(it, StandardCharsets.UTF_8) }
        val valid = xml != null &&
            xml.contains("name=\"fixture_label\">$fixtureLabel</string>") &&
            xml.contains("name=\"fixture_version\" value=\"1\"") &&
            xml.contains("name=\"known_value\">apex-test-$fixtureLabel-2026</string>")
        checks += ContentCheck(
            label, if (valid) "PASS" else "FAIL",
            if (valid) "All three known preference values matched." else "Missing or mismatched preference values."
        )
    }

    private fun checkDatabase(
        checks: MutableList<ContentCheck>,
        archive: ArchiveContents?,
        label: String,
        fixtureLabel: String
    ) {
        if (archive == null) {
            checks += ContentCheck(label, "SKIP", "TAR archive unavailable.")
            return
        }
        val bytes = archive.files["databases/apex_lab.db"]
        if (bytes == null) {
            checks += ContentCheck(label, "FAIL", "SQLite database file is missing.")
            return
        }
        val temp = File.createTempFile("apex-lab-check-", ".db")
        try {
            temp.writeBytes(bytes)
            val valid = SQLiteDatabase.openDatabase(
                temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("SELECT label, payload FROM fixture WHERE id=1", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0) == fixtureLabel &&
                        cursor.getString(1) == "known-db-payload-$fixtureLabel-2026"
                }
            }
            checks += ContentCheck(
                label, if (valid) "PASS" else "FAIL",
                if (valid) "SQLite row values matched." else "SQLite row missing or mismatched."
            )
        } catch (error: Exception) {
            checks += ContentCheck(label, "FAIL", "SQLite validation failed: ${error.message}")
        } finally {
            temp.delete()
        }
    }

    private fun readTar(input: InputStream): ArchiveContents {
        val paths = linkedSetOf<String>()
        val files = linkedMapOf<String, ByteArray>()
        val header = ByteArray(512)
        while (true) {
            readFully(input, header)
            if (header.all { it == 0.toByte() }) break
            validateChecksum(header)
            val name = field(header, 0, 100)
            val prefix = field(header, 345, 155)
            val rawPath = if (prefix.isBlank()) name else "$prefix/$name"
            val path = normalizePath(rawPath)
            val size = parseOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            if (type == '\u0000' || type == '0') {
                if (path.isEmpty()) throw IOException("TAR file entry has an empty path.")
                paths += path
                if (path in expectedEntries) {
                    if (size > MAX_CAPTURED_ENTRY) {
                        throw IOException("Fixture entry is unexpectedly large: $path")
                    }
                    val data = ByteArray(size.toInt())
                    readFully(input, data)
                    files[path] = data
                } else {
                    skipFully(input, size)
                }
            } else {
                skipFully(input, size)
            }
            val padding = (512L - (size % 512L)) % 512L
            skipFully(input, padding)
        }
        return ArchiveContents(paths, files)
    }

    private fun normalizePath(raw: String): String {
        if (raw.startsWith("/") || raw.startsWith("\\")) {
            throw IOException("Absolute TAR path rejected.")
        }
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) {
            throw IOException("Unsafe TAR path rejected.")
        }
        if (parts.isEmpty() && raw.trim('/') != ".") {
            throw IOException("Empty TAR path rejected.")
        }
        return parts.joinToString("/")
    }

    private fun validateChecksum(header: ByteArray) {
        val stored = parseOctal(header, 148, 8)
        var sum = 0L
        header.forEachIndexed { index, byte ->
            sum += if (index in 148..155) 32L else byte.toInt() and 0xff
        }
        if (sum != stored) throw IOException("TAR header checksum mismatch.")
    }

    private fun field(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() }
            ?: (offset + length)
        return String(bytes, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun parseOctal(bytes: ByteArray, offset: Int, length: Int): Long {
        val value = String(bytes, offset, length, StandardCharsets.US_ASCII)
            .trim('\u0000', ' ')
        if (value.isEmpty()) return 0L
        if (value.any { it !in '0'..'7' }) throw IOException("Invalid TAR numeric field.")
        return value.toLong(8)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw IOException("Truncated TAR archive.")
            if (count == 0) continue
            offset += count
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("Truncated TAR archive.")
                remaining -= read
            }
        }
    }
}
