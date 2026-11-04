package com.apex.backup.diagnostics

import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DeviceInfo(
    val manufacturer: String = Build.MANUFACTURER,
    val model: String = Build.MODEL,
    val device: String = Build.DEVICE,
    val androidVersion: String = Build.VERSION.RELEASE,
    val apiLevel: Int = Build.VERSION.SDK_INT,
    val securityPatch: String = Build.VERSION.SECURITY_PATCH ?: "Unknown",
    val buildDisplay: String = Build.DISPLAY.ifBlank { "Unknown" },
    val fingerprint: String = Build.FINGERPRINT.ifBlank { "Unknown" },
    val supportedAbis: String = Build.SUPPORTED_ABIS.joinToString()
) {
    companion object {
        fun current(): DeviceInfo = DeviceInfo()
    }
}

data class StorageInfo(
    val totalBytes: Long,
    val availableBytes: Long
) {
    companion object {
        fun internalData(): StorageInfo {
            val stats = StatFs(Environment.getDataDirectory().absolutePath)
            return StorageInfo(
                totalBytes = stats.totalBytes,
                availableBytes = stats.availableBytes
            )
        }
    }
}

enum class RootStatus {
    GRANTED,
    DENIED_OR_UNAVAILABLE,
    TIMED_OUT,
    ERROR
}

data class RootDiagnostics(
    val status: RootStatus,
    val uidOutput: String,
    val magiskVersion: String?,
    val selinuxMode: String?,
    val cryptoState: String?,
    val cryptoType: String?,
    val detail: String?
) {
    val isRoot: Boolean
        get() = status == RootStatus.GRANTED
}

/**
 * Executes only fixed, read-only diagnostic commands.
 * Never pass user-controlled text to this class.
 */
object RootDiagnosticsRunner {
    private const val COMMAND_TIMEOUT_SECONDS = 8L

    fun run(): RootDiagnostics {
        val identity = execute("id")
            ?: return RootDiagnostics(
                status = RootStatus.DENIED_OR_UNAVAILABLE,
                uidOutput = "",
                magiskVersion = null,
                selinuxMode = null,
                cryptoState = null,
                cryptoType = null,
                detail = "su command is unavailable or root access was not granted."
            )

        if (identity.timedOut) {
            return RootDiagnostics(
                status = RootStatus.TIMED_OUT,
                uidOutput = identity.output,
                magiskVersion = null,
                selinuxMode = null,
                cryptoState = null,
                cryptoType = null,
                detail = "Root request timed out. Check the root manager prompt and try again."
            )
        }

        val isRoot = identity.exitCode == 0 &&
            Regex("""\\buid=0(?:\\(|\\s)""").containsMatchIn(identity.output)

        if (!isRoot) {
            return RootDiagnostics(
                status = RootStatus.DENIED_OR_UNAVAILABLE,
                uidOutput = identity.output,
                magiskVersion = null,
                selinuxMode = null,
                cryptoState = null,
                cryptoType = null,
                detail = "Root was denied, unavailable, or did not return uid=0."
            )
        }

        val magisk = execute("magisk -v")
        val selinux = execute("getenforce")
        val cryptoState = execute("getprop ro.crypto.state")
        val cryptoType = execute("getprop ro.crypto.type")

        return RootDiagnostics(
            status = RootStatus.GRANTED,
            uidOutput = identity.output.trim(),
            magiskVersion = magisk?.output?.trim()?.takeIf { it.isNotBlank() },
            selinuxMode = selinux?.output?.trim()?.takeIf { it.isNotBlank() },
            cryptoState = cryptoState?.output?.trim()?.takeIf { it.isNotBlank() },
            cryptoType = cryptoType?.output?.trim()?.takeIf { it.isNotBlank() },
            detail = null
        )
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false
    )

    private fun execute(command: String): CommandResult? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return CommandResult(-1, "", timedOut = true)
            }

            CommandResult(
                exitCode = process.exitValue(),
                output = process.inputStream.bufferedReader().use { it.readText() }
            )
        } catch (_: IOException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
