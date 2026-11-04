package com.apex.backup.inventory

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.content.ContentResolver
import android.net.Uri

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val enabled: Boolean,
    val apkFiles: List<File>
) {
    val apkCount: Int get() = apkFiles.size
}

object PackageInventory {
    fun load(packageManager: PackageManager): List<InstalledApp> {
        val applications = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getInstalledApplications(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }

        return applications.mapNotNull { application ->
            runCatching {
                val packageInfo = getPackageInfo(packageManager, application.packageName)
                val apkFiles = buildList {
                    application.sourceDir?.let { add(File(it)) }
                    application.splitSourceDirs?.forEach { add(File(it)) }
                }.filter { it.isFile && it.canRead() }

                InstalledApp(
                    packageName = application.packageName,
                    label = packageManager.getApplicationLabel(application).toString()
                        .ifBlank { application.packageName },
                    versionName = packageInfo.versionName ?: "Unknown",
                    versionCode = if (Build.VERSION.SDK_INT >= 28) {
                        packageInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toLong()
                    },
                    isSystemApp = application.flags and (
                        ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                    ) != 0,
                    enabled = application.enabled,
                    apkFiles = apkFiles
                )
            }.getOrNull()
        }.sortedWith(
            compareBy<InstalledApp> { it.isSystemApp }
                .thenBy { it.label.lowercase() }
        )
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfo(
        packageManager: PackageManager,
        packageName: String
    ): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0L)
        )
    } else {
        packageManager.getPackageInfo(packageName, 0)
    }
}

/**
 * Exports APK files only. App-private data, accounts, permissions and runtime state are not copied.
 * Split APKs are included in the same archive so split-installed apps are not reduced to base.apk.
 */
object PackageApkExporter {
    fun export(
        contentResolver: ContentResolver,
        destination: Uri,
        apps: List<InstalledApp>
    ): ExportResult {
        if (apps.isEmpty()) return ExportResult(0, 0, "No apps were selected.")

        var apkCount = 0
        var bytesWritten = 0L
        return try {
            val output = contentResolver.openOutputStream(destination)
                ?: return ExportResult(0, 0, "Could not open the selected destination.")

            output.use { rawOutput ->
                ZipOutputStream(rawOutput).use { zip ->
                    apps.forEach { app ->
                        if (app.apkFiles.isEmpty()) {
                            throw IllegalStateException("APK files are not readable for ${app.packageName}")
                        }
                        val safePackage = app.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val safeVersion = app.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        app.apkFiles.forEachIndexed { index, file ->
                            val fileName = if (index == 0) "base.apk" else "split-$index.apk"
                            zip.putNextEntry(
                                ZipEntry("$safePackage/$safeVersion/$fileName")
                            )
                            FileInputStream(file).use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    zip.write(buffer, 0, read)
                                    bytesWritten += read
                                }
                            }
                            zip.closeEntry()
                            apkCount++
                        }
                    }
                }
            }
            ExportResult(apps.size, apkCount, null, bytesWritten)
        } catch (error: Exception) {
            ExportResult(
                exportedApps = 0,
                exportedApkFiles = apkCount,
                error = error.message ?: error.javaClass.simpleName,
                bytesWritten = bytesWritten
            )
        }
    }
}

data class ExportResult(
    val exportedApps: Int,
    val exportedApkFiles: Int,
    val error: String?,
    val bytesWritten: Long = 0L
)
