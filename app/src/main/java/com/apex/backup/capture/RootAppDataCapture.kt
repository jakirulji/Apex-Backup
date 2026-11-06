package com.apex.backup.capture

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.apex.backup.diagnostics.DeviceInfo
import com.apex.backup.inventory.InstalledApp
import com.apex.backup.security.BackupStreamSource
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class CaptureCategoryResult(
    val packageName: String,
    val category: String,
    val status: String,
    val archivePath: String? = null
)

data class PreparedAppDataCapture(
    val sources: List<BackupStreamSource>,
    val results: List<CaptureCategoryResult>,
    val packageCount: Int,
    val dataCategoryCount: Int
)

/**
 * M4 capture is intentionally backup-only. It reads user-0 app directories through root,
 * streams each directory as a TAR entry directly into the encrypted container, and never
 * creates a plaintext archive on disk.
 */
object RootAppDataCapture {
    private const val COMMAND_TIMEOUT_SECONDS = 15L
    private const val TAR_TIMEOUT_MILLIS = 6L * 60L * 60L * 1000L
    private const val MAX_STDERR_BYTES = 16 * 1024

    private data class Category(val name: String, val path: (String) -> String)

    private val categories = listOf(
        Category("CE", { pkg -> "/data/user/0/$pkg" }),
        Category("DE", { pkg -> "/data/user_de/0/$pkg" }),
        Category("EXTERNAL", { pkg -> "/storage/emulated/0/Android/data/$pkg" }),
        Category("OBB", { pkg -> "/storage/emulated/0/Android/obb/$pkg" })
    )

    fun prepare(
        apps: List<InstalledApp>,
        packageManager: PackageManager,
        device: DeviceInfo,
        isCancelled: () -> Boolean = { false }
    ): PreparedAppDataCapture {
        require(apps.isNotEmpty()) { "Select at least one app." }
        val sources = mutableListOf<BackupStreamSource>()
        val results = mutableListOf<CaptureCategoryResult>()
        val appMetadata = mutableListOf<Map<String, String>>()
        var dataCategoryCount = 0

        apps.forEach { app ->
            checkCancelled(isCancelled)
            requireValidPackage(app.packageName)

            val packageInfo = getPackageInfo(packageManager, app.packageName)
            val certDigest = signingCertificateDigest(packageInfo)
            appMetadata += linkedMapOf(
                "package" to app.packageName,
                "label" to app.label,
                "version_name" to app.versionName,
                "version_code" to app.versionCode.toString(),
                "signing_certificate_sha256" to certDigest
            )

            app.apkFiles.forEachIndexed { index, file ->
                require(file.isFile && file.canRead()) {
                    "APK source is no longer readable for ${app.packageName}."
                }
                val name = if (index == 0) "base.apk" else "split-$index.apk"
                sources += BackupStreamSource(
                    "apps/${app.packageName}/apk/$name"
                ) { file.inputStream() }
            }

            results += CaptureCategoryResult(app.packageName, "CACHE", "EXCLUDED_BY_POLICY")
            results += CaptureCategoryResult(app.packageName, "CODE_CACHE", "EXCLUDED_BY_POLICY")
            categories.forEach { category ->
                checkCancelled(isCancelled)
                val path = category.path(app.packageName)
                val present = probeDirectory(path, isCancelled)
                val archivePath = "app-data/${app.packageName}/${category.name.lowercase()}.tar"
                if (present) {
                    sources += BackupStreamSource(archivePath) {
                        openTarStream(path, isCancelled)
                    }
                    results += CaptureCategoryResult(
                        app.packageName, category.name, "READY", archivePath
                    )
                    dataCategoryCount++
                } else {
                    results += CaptureCategoryResult(
                        app.packageName, category.name, "NOT_PRESENT_OR_INACCESSIBLE"
                    )
                }
            }
        }

        if (dataCategoryCount == 0) {
            throw IOException(
                "No app-data directories could be read through root. Check Magisk authorization and retry."
            )
        }

        // This entry is written last. At this point every earlier TAR stream has reached EOF
        // and its root process exit code has been checked, so READY can safely become CAPTURED.
        sources += BackupStreamSource("META-INF/app-data-metadata.json") {
            buildMetadata(device, appMetadata, results).byteInputStream(StandardCharsets.UTF_8)
        }

        return PreparedAppDataCapture(
            sources = sources,
            results = results,
            packageCount = apps.size,
            dataCategoryCount = dataCategoryCount
        )
    }

    private fun requireValidPackage(packageName: String) {
        if (!packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) {
            throw IllegalArgumentException("Invalid Android package name.")
        }
    }

    private fun getPackageInfo(pm: PackageManager, packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    private fun signingCertificateDigest(info: PackageInfo): String {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return "unavailable"
        if (signatures.isEmpty()) return "unavailable"
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(",")
    }

    private fun probeDirectory(path: String, isCancelled: () -> Boolean): Boolean {
        checkCancelled(isCancelled)
        val command = "test -d '$path' && test -r '$path'"
        val process = ProcessBuilder("su", "-c", command).start()
        val stderr = ByteArrayOutputStream()
        val drain = Thread {
            process.errorStream.use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (stderr.size() < MAX_STDERR_BYTES) {
                        stderr.write(buffer, 0, minOf(count, MAX_STDERR_BYTES - stderr.size()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS)
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                checkCancelled(isCancelled)
                if (System.nanoTime() >= deadline) {
                    process.destroyForcibly()
                    throw IOException("Root directory check timed out.")
                }
            }
            drain.join(1000)
            return process.exitValue() == 0
        } catch (error: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Root directory check interrupted.")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun openTarStream(path: String, isCancelled: () -> Boolean): InputStream {
        // The only interpolated value is a fixed root path plus a validated package name.
        val command = "exec toybox tar -cpf - --exclude=./cache --exclude=./code_cache -C '$path' ."
        val process = ProcessBuilder("su", "-c", command).start()
        return RootTarInputStream(process, isCancelled, TAR_TIMEOUT_MILLIS)
    }

    private class RootTarInputStream(
        private val process: Process,
        private val isCancelled: () -> Boolean,
        timeoutMillis: Long
    ) : FilterInputStream(process.inputStream) {
        private val finished = AtomicBoolean(false)
        private val timedOut = AtomicBoolean(false)
        private val stderr = ByteArrayOutputStream()
        private val stderrThread = Thread {
            process.errorStream.use { input ->
                val buffer = ByteArray(2048)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    synchronized(stderr) {
                        if (stderr.size() < MAX_STDERR_BYTES) {
                            stderr.write(buffer, 0, minOf(count, MAX_STDERR_BYTES - stderr.size()))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        private val watchdog = Thread {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            try {
                while (!finished.get() && process.isAlive) {
                    if (isCancelled()) {
                        process.destroyForcibly()
                        break
                    }
                    if (System.nanoTime() >= deadline) {
                        timedOut.set(true)
                        process.destroyForcibly()
                        break
                    }
                    Thread.sleep(200)
                }
            } catch (_: InterruptedException) {
                // Normal close/EOF cancels the watchdog.
            }
        }.apply { isDaemon = true; start() }

        override fun read(): Int {
            checkActive()
            val value = super.read()
            if (value < 0) finishAndCheck()
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkActive()
            val count = super.read(buffer, offset, length)
            if (count < 0) finishAndCheck()
            return count
        }

        private fun checkActive() {
            if (isCancelled()) {
                process.destroyForcibly()
                throw InterruptedIOException("App-data capture was cancelled.")
            }
            if (timedOut.get()) throw InterruptedIOException("Root TAR capture timed out.")
        }

        private fun finishAndCheck() {
            if (isCancelled()) {
                process.destroyForcibly()
                throw InterruptedIOException("App-data capture was cancelled.")
            }
            if (!finished.compareAndSet(false, true)) return
            watchdog.interrupt()
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw IOException("Root TAR process did not exit.")
                }
                stderrThread.join(1000)
            } catch (error: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Root TAR capture interrupted.")
            }
            if (timedOut.get()) throw InterruptedIOException("Root TAR capture timed out.")
            if (process.exitValue() != 0) {
                val detail = synchronized(stderr) {
                    stderr.toString(StandardCharsets.UTF_8.name()).trim().take(1000)
                }
                throw IOException(
                    "Root TAR failed (exit ${process.exitValue()})" +
                        if (detail.isBlank()) "." else ": $detail"
                )
            }
        }

        override fun close() {
            if (!finished.get()) process.destroyForcibly()
            watchdog.interrupt()
            try {
                super.close()
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw InterruptedIOException("App-data capture was cancelled.")
    }

    private fun buildMetadata(
        device: DeviceInfo,
        appMetadata: List<Map<String, String>>,
        results: List<CaptureCategoryResult>
    ): String {
        fun quote(value: String): String = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
        val appJson = appMetadata.joinToString(",") { item ->
            item.entries.joinToString(",", "{", "}") { (key, value) ->
                "${quote(key)}:${quote(value)}"
            }
        }
        val categoryJson = results.joinToString(",") { item ->
            val finalStatus = when (item.status) {
                "READY" -> "captured"
                "NOT_PRESENT_OR_INACCESSIBLE" -> "skipped"
                "EXCLUDED_BY_POLICY" -> "excluded"
                else -> "unsupported"
            }
            val fields = linkedMapOf(
                "package" to item.packageName,
                "category" to item.category,
                "status" to finalStatus
            )
            when (item.status) {
                "NOT_PRESENT_OR_INACCESSIBLE" -> fields["reason"] = "not_present_or_inaccessible"
                "EXCLUDED_BY_POLICY" -> fields["reason"] = "regenerable_cache_policy"
            }
            item.archivePath?.let { fields["archive_path"] = it }
            fields.entries.joinToString(",", "{", "}") { (key, value) ->
                "${quote(key)}:${quote(value)}"
            }
        }
        return """
            {
              "format": "apex-app-data-metadata-v1",
              "created_at": ${quote(java.time.Instant.now().toString())},
              "source": {
                "manufacturer": ${quote(device.manufacturer)},
                "model": ${quote(device.model)},
                "device": ${quote(device.device)},
                "android_version": ${quote(device.androidVersion)},
                "api_level": ${device.apiLevel},
                "build_display": ${quote(device.buildDisplay)},
                "build_fingerprint": ${quote(device.fingerprint)},
                "supported_abis": ${quote(device.supportedAbis)},
                "android_user": 0,
                "consistency": "live-best-effort; apps are not force-stopped"
              },
              "apps": [$appJson],
              "categories": [$categoryJson]
            }
        """.trimIndent()
    }
}
