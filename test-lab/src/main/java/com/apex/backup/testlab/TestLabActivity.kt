package com.apex.backup.testlab

import android.app.Activity
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.security.MessageDigest

class TestLabActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        output = TextView(this).apply { text = "Apex Backup Test Lab\n\nNo test data created yet." }
        content.addView(Button(this).apply {
            text = "Generate synthetic test data"
            setOnClickListener { generateFixtures() }
        })
        content.addView(Button(this).apply {
            text = "Verify synthetic test data"
            setOnClickListener { verifyFixtures() }
        })
        content.addView(output)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun generateFixtures() {
        val report = mutableListOf<String>()
        try {
            createForContext(this, "CE", report)
            createForContext(createDeviceProtectedStorageContext(), "DE", report)
            getExternalFilesDir("ApexBackupTestLab")?.let { dir ->
                report += writeFixture(File(dir, "external-fixture.txt"), EXTERNAL_CONTENT, "External")
            } ?: run { report += "External: UNSUPPORTED (no app-specific external volume)" }
            obbDir?.let { dir ->
                report += writeFixture(File(dir, "main.1.com.apex.backup.testlab.obb"), OBB_CONTENT, "OBB")
            } ?: run { report += "OBB: UNSUPPORTED (obbDir unavailable)" }
            report += writeFixture(File(cacheDir, "apex-lab-cache.tmp"), CACHE_CONTENT, "Cache")
            output.text = "Synthetic data generated.\nPackage: $packageName\n\n" +
                report.joinToString("\n") +
                "\n\nCache is intentionally included as a fixture; Apex Backup should report it as excluded."
        } catch (e: Exception) {
            output.text = "Fixture generation failed: ${e.javaClass.simpleName}: ${e.message}\n\n" +
                report.joinToString("\n")
        }
    }

    private fun createForContext(context: Context, label: String, report: MutableList<String>) {
        val files = File(context.filesDir, "apex-backup-test-lab").apply { mkdirs() }
        report += writeFixture(File(files, "${label.lowercase()}-files.txt"),
            "$label synthetic file payload\nApex Backup Test Lab fixture v1\n", "$label files")
        val prefs = context.getSharedPreferences("apex_backup_test_lab", Context.MODE_PRIVATE)
        check(prefs.edit().putString("fixture_label", label).putInt("fixture_version", 1)
            .putString("known_value", "apex-test-$label-2026").commit()) {
            "$label SharedPreferences commit failed"
        }
        val prefsFile = File(File(context.applicationInfo.dataDir, "shared_prefs"),
            "apex_backup_test_lab.xml")
        report += describeFile(prefsFile, "$label SharedPreferences")
        val database = context.getDatabasePath("apex_lab.db")
        database.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS fixture (id INTEGER PRIMARY KEY, label TEXT NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("DELETE FROM fixture")
            db.execSQL("INSERT INTO fixture (id, label, payload) VALUES (1, ?, ?)",
                arrayOf(label, "known-db-payload-$label-2026"))
        }
        report += describeFile(database, "$label database")
    }

    private fun verifyFixtures() {
        val checks = mutableListOf<String>()
        try {
            verifyContext(this, "CE", checks)
            verifyContext(createDeviceProtectedStorageContext(), "DE", checks)
            checks += verifyFile(getExternalFilesDir("ApexBackupTestLab")
                ?.let { File(it, "external-fixture.txt") }, EXTERNAL_CONTENT, "External")
            checks += verifyFile(obbDir?.let {
                File(it, "main.1.com.apex.backup.testlab.obb")
            }, OBB_CONTENT, "OBB")
            checks += verifyFile(File(cacheDir, "apex-lab-cache.tmp"), CACHE_CONTENT, "Cache")
            output.text = "Verification results\n\n" + checks.joinToString("\n") +
                "\n\nCache should exist in the test app but be excluded from an M4 backup."
        } catch (e: Exception) {
            output.text = "Verification failed: ${e.javaClass.simpleName}: ${e.message}\n\n" +
                checks.joinToString("\n")
        }
    }

    private fun verifyContext(context: Context, label: String, checks: MutableList<String>) {
        val file = File(File(context.filesDir, "apex-backup-test-lab"),
            "${label.lowercase()}-files.txt")
        val expected = "$label synthetic file payload\nApex Backup Test Lab fixture v1\n"
        checks += verifyFile(file, expected, "$label files")
        val prefs = context.getSharedPreferences("apex_backup_test_lab", Context.MODE_PRIVATE)
        val prefsOk = prefs.getString("fixture_label", null) == label &&
            prefs.getInt("fixture_version", -1) == 1 &&
            prefs.getString("known_value", null) == "apex-test-$label-2026"
        checks += "$label SharedPreferences: " + if (prefsOk) "PASS" else "FAIL"
        val dbFile = context.getDatabasePath("apex_lab.db")
        val dbOk = SQLiteDatabase.openDatabase(dbFile.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT label, payload FROM fixture WHERE id=1", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == label &&
                    cursor.getString(1) == "known-db-payload-$label-2026"
            }
        }
        checks += "$label database: " + if (dbOk) "PASS (${sha256(dbFile)})" else "FAIL"
    }

    private fun writeFixture(file: File, content: String, label: String): String {
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return describeFile(file, label)
    }

    private fun describeFile(file: File, label: String): String =
        "$label: ${if (file.isFile) "READY" else "MISSING"} | ${file.absolutePath} | " +
            "${if (file.isFile) sha256(file) else "no checksum"}"

    private fun verifyFile(file: File?, expected: String, label: String): String {
        if (file == null || !file.isFile) return "$label: FAIL (missing)"
        val valid = file.readText(Charsets.UTF_8) == expected
        return "$label: ${if (valid) "PASS" else "FAIL"} | SHA-256 ${sha256(file)}"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val EXTERNAL_CONTENT = "External synthetic payload\nApex Backup Test Lab v1\n"
        private const val OBB_CONTENT = "OBB synthetic payload\nNot a real game asset\n"
        private const val CACHE_CONTENT = "Regenerable cache fixture; expected to be excluded.\n"
    }
}
