package com.apex.backup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apex.backup.diagnostics.DeviceInfo
import com.apex.backup.diagnostics.RootDiagnostics
import com.apex.backup.diagnostics.RootDiagnosticsRunner
import com.apex.backup.diagnostics.RootStatus
import com.apex.backup.diagnostics.StorageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ApexBackupApp() }
    }
}

@Composable
private fun ApexBackupApp() {
    val device = remember { DeviceInfo.current() }
    val storage = remember { StorageInfo.internalData() }
    var refreshToken by remember { mutableIntStateOf(0) }
    var rootDiagnostics by remember { mutableStateOf<RootDiagnostics?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(refreshToken) {
        if (refreshToken > 0) {
            loading = true
            rootDiagnostics = withContext(Dispatchers.IO) {
                RootDiagnosticsRunner.run()
            }
            loading = false
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Apex Backup", style = MaterialTheme.typography.headlineMedium)
                Text("Device & root diagnostics", style = MaterialTheme.typography.titleMedium)

                InfoCard(title = "Device") {
                    InfoRow("Manufacturer", device.manufacturer)
                    InfoRow("Model", device.model)
                    InfoRow("Device codename", device.device)
                    InfoRow("Android", "${device.androidVersion} (API ${device.apiLevel})")
                    InfoRow("Security patch", device.securityPatch)
                    InfoRow("Build", device.buildDisplay)
                    InfoRow("Fingerprint", device.fingerprint)
                    InfoRow("Supported ABI", device.supportedAbis)
                }

                InfoCard(title = "Internal storage") {
                    InfoRow("Total", formatBytes(storage.totalBytes))
                    InfoRow("Available", formatBytes(storage.availableBytes))
                    InfoRow(
                        "Used",
                        formatBytes((storage.totalBytes - storage.availableBytes).coerceAtLeast(0))
                    )
                }

                InfoCard(title = "Root access") {
                    val result = rootDiagnostics
                    InfoRow(
                        "Status",
                        when {
                            loading -> "Checking…"
                            result == null -> "Not checked"
                            result.status == RootStatus.GRANTED -> "Granted (uid=0)"
                            result.status == RootStatus.TIMED_OUT -> "Timed out"
                            else -> "Denied / unavailable"
                        }
                    )
                    if (result != null) {
                        if (result.isRoot) {
                            InfoRow("Identity", result.uidOutput.ifBlank { "uid=0" })
                            InfoRow("Magisk", result.magiskVersion ?: "Not detected")
                            InfoRow("SELinux", result.selinuxMode ?: "Unavailable")
                            InfoRow("Encryption state", result.cryptoState ?: "Unavailable")
                            InfoRow("Encryption type", result.cryptoType ?: "Unavailable")
                        } else {
                            Text(result.detail.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text(
                            "Root is checked only when you press the button. " +
                                "Your root manager may ask whether Apex Backup can access root.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { refreshToken++ }, enabled = !loading) {
                            Text(if (rootDiagnostics == null) "Check root" else "Check again")
                        }
                        if (loading) CircularProgressIndicator()
                    }
                }

                InfoCard(title = "Safety status") {
                    Text(
                        "M1 performs read-only checks. It does not back up, restore, change " +
                            "system settings, mount partitions, or write to protected paths."
                    )
                }

                OutlinedButton(
                    onClick = { rootDiagnostics = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading
                ) {
                    Text("Clear diagnostic result")
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(start = 12.dp)
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(java.util.Locale.US, "%.2f %s", value, units[unit])
}
