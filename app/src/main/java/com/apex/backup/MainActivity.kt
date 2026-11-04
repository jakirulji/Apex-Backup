package com.apex.backup

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.apex.backup.diagnostics.DeviceInfo
import com.apex.backup.diagnostics.RootDiagnostics
import com.apex.backup.diagnostics.RootDiagnosticsRunner
import com.apex.backup.diagnostics.RootStatus
import com.apex.backup.diagnostics.StorageInfo
import com.apex.backup.inventory.InstalledApp
import com.apex.backup.inventory.PackageApkExporter
import com.apex.backup.inventory.PackageInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ApexBackupApp() }
    }
}

private enum class AppFilter { ALL, USER, SYSTEM }

@Composable
private fun ApexBackupApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val device = remember { DeviceInfo.current() }
    val storage = remember { StorageInfo.internalData() }

    var rootRefresh by remember { mutableIntStateOf(0) }
    var rootResult by remember { mutableStateOf<RootDiagnostics?>(null) }
    var rootLoading by remember { mutableStateOf(false) }

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var inventoryLoading by remember { mutableStateOf(true) }
    var inventoryError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.ALL) }
    val selectedPackages = remember { mutableStateListOf<String>() }
    var exportLoading by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var pendingExportApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                exportLoading = true
                exportMessage = null
                val result = withContext(Dispatchers.IO) {
                    PackageApkExporter.export(context.contentResolver, uri, pendingExportApps)
                }
                exportLoading = false
                exportMessage = if (result.error == null) {
                    "Exported ${result.exportedApps} apps / ${result.exportedApkFiles} APK files " +
                        "(${formatBytes(result.bytesWritten)}). APKs only; app data is not included."
                } else {
                    "Export failed: ${result.error}"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        inventoryLoading = true
        val result = withContext(Dispatchers.IO) {
            runCatching { PackageInventory.load(context.packageManager) }
        }
        result.onSuccess {
            apps = it
            inventoryError = null
        }.onFailure {
            inventoryError = it.message ?: "Could not read installed apps."
        }
        inventoryLoading = false
    }

    LaunchedEffect(rootRefresh) {
        if (rootRefresh > 0) {
            rootLoading = true
            rootResult = withContext(Dispatchers.IO) { RootDiagnosticsRunner.run() }
            rootLoading = false
        }
    }

    val visibleApps = apps.filter { app ->
        val matchesFilter = when (filter) {
            AppFilter.ALL -> true
            AppFilter.USER -> !app.isSystemApp
            AppFilter.SYSTEM -> app.isSystemApp
        }
        val query = searchQuery.trim().lowercase(Locale.getDefault())
        matchesFilter && (query.isEmpty() ||
            app.label.lowercase(Locale.getDefault()).contains(query) ||
            app.packageName.lowercase(Locale.getDefault()).contains(query))
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

                InfoCard("Device") {
                    InfoRow("Manufacturer", device.manufacturer)
                    InfoRow("Model", device.model)
                    InfoRow("Device codename", device.device)
                    InfoRow("Android", "${device.androidVersion} (API ${device.apiLevel})")
                    InfoRow("Security patch", device.securityPatch)
                    InfoRow("Build", device.buildDisplay)
                    InfoRow("Fingerprint", device.fingerprint)
                    InfoRow("Supported ABI", device.supportedAbis)
                }

                InfoCard("Internal storage") {
                    InfoRow("Total", formatBytes(storage.totalBytes))
                    InfoRow("Available", formatBytes(storage.availableBytes))
                    InfoRow(
                        "Used",
                        formatBytes((storage.totalBytes - storage.availableBytes).coerceAtLeast(0))
                    )
                }

                InfoCard("Root access") {
                    val result = rootResult
                    InfoRow(
                        "Status",
                        when {
                            rootLoading -> "Checking…"
                            result == null -> "Not checked"
                            result.status == RootStatus.GRANTED -> "Granted (uid=0)"
                            result.status == RootStatus.TIMED_OUT -> "Timed out"
                            else -> "Denied / unavailable"
                        }
                    )
                    if (result?.isRoot == true) {
                        InfoRow("Identity", result.uidOutput.ifBlank { "uid=0" })
                        InfoRow("Magisk", result.magiskVersion ?: "Not detected")
                        InfoRow("SELinux", result.selinuxMode ?: "Unavailable")
                        InfoRow("Encryption state", result.cryptoState ?: "Unavailable")
                        InfoRow("Encryption type", result.cryptoType ?: "Unavailable")
                    } else if (result != null) {
                        Text(result.detail.orEmpty())
                    }
                    Button(onClick = { rootRefresh++ }, enabled = !rootLoading) {
                        Text(if (rootResult == null) "Check root" else "Check again")
                    }
                    if (rootLoading) CircularProgressIndicator()
                }

                InfoCard("Installed apps") {
                    InfoRow("Detected", apps.size.toString())
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search app or package name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = filter == AppFilter.ALL,
                            onClick = { filter = AppFilter.ALL },
                            label = { Text("All") }
                        )
                        FilterChip(
                            selected = filter == AppFilter.USER,
                            onClick = { filter = AppFilter.USER },
                            label = { Text("User") }
                        )
                        FilterChip(
                            selected = filter == AppFilter.SYSTEM,
                            onClick = { filter = AppFilter.SYSTEM },
                            label = { Text("System") }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${selectedPackages.size} selected")
                        OutlinedButton(
                            onClick = {
                                val visible = visibleApps.map { it.packageName }
                                if (visible.isNotEmpty() && visible.all { it in selectedPackages }) {
                                    selectedPackages.removeAll(visible.toSet())
                                } else {
                                    visible.forEach {
                                        if (it !in selectedPackages) selectedPackages.add(it)
                                    }
                                }
                            },
                            enabled = visibleApps.isNotEmpty()
                        ) {
                            Text("Select visible")
                        }
                    }

                    when {
                        inventoryLoading -> CircularProgressIndicator()
                        inventoryError != null -> Text(inventoryError.orEmpty())
                        visibleApps.isEmpty() -> Text("No matching apps.")
                        else -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            visibleApps.forEach { app ->
                                AppRow(
                                    app = app,
                                    checked = app.packageName in selectedPackages,
                                    onCheckedChange = { checked ->
                                        if (checked && app.packageName !in selectedPackages) {
                                            selectedPackages.add(app.packageName)
                                        } else if (!checked) {
                                            selectedPackages.remove(app.packageName)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            pendingExportApps = apps.filter { it.packageName in selectedPackages }
                            val fileName = if (pendingExportApps.size == 1) {
                                "${pendingExportApps.first().packageName}.zip"
                            } else {
                                "Apex-Backup-APKs.zip"
                            }
                            exportLauncher.launch(fileName)
                        },
                        enabled = selectedPackages.isNotEmpty() && !exportLoading
                    ) {
                        Text("Export selected APKs")
                    }
                    if (exportLoading) CircularProgressIndicator()
                    exportMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        "APK files only, including split APKs where present. App data, accounts, " +
                            "login sessions and app settings are not included.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                InfoCard("Safety status") {
                    Text(
                        "M2 only reads installed package metadata and exports APK files. " +
                            "It does not copy private app data or modify installed apps."
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall)
            Text(
                "v${app.versionName} • ${if (app.isSystemApp) "System" else "User"} app • " +
                    "${app.apkCount} APK file(s)${if (app.enabled) "" else " • Disabled"}",
                style = MaterialTheme.typography.bodySmall
            )
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
    return String.format(Locale.US, "%.2f %s", value, units[unit])
}
