package com.apex.backup

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.apex.backup.capture.CaptureCategoryResult
import com.apex.backup.capture.RootAppDataCapture
import com.apex.backup.diagnostics.DeviceInfo
import com.apex.backup.diagnostics.RootDiagnostics
import com.apex.backup.diagnostics.RootDiagnosticsRunner
import com.apex.backup.diagnostics.RootStatus
import com.apex.backup.diagnostics.StorageInfo
import com.apex.backup.inventory.InstalledApp
import com.apex.backup.inventory.PackageApkExporter
import com.apex.backup.inventory.PackageInventory
import com.apex.backup.security.BackupContainer
import com.apex.backup.security.BackupStreamSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

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

    var backupPassphrase by remember { mutableStateOf("") }
    var backupPassphraseConfirm by remember { mutableStateOf("") }
    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var captureDiagnostics by remember { mutableStateOf<List<CaptureCategoryResult>>(emptyList()) }
    var pendingBackupStreams by remember { mutableStateOf<List<BackupStreamSource>>(emptyList()) }

    var verifyPassphrase by remember { mutableStateOf("") }
    var verifyBusy by remember { mutableStateOf(false) }
    var verifyProgressBytes by remember { mutableLongStateOf(0L) }
    var verifyTotalBytes by remember { mutableLongStateOf(-1L) }
    var verifyAverageSpeed by remember { mutableStateOf("0.00 MB/s average") }
    var verifyStartedAt by remember { mutableLongStateOf(0L) }
    val verifyBytesCounter = remember { AtomicLong(0L) }
    var verifyMessage by remember { mutableStateOf<String?>(null) }
    var verifyUri by remember { mutableStateOf<Uri?>(null) }

    var extractUri by remember { mutableStateOf<Uri?>(null) }
    var extractPassphrase by remember { mutableStateOf("") }
    var extractBusy by remember { mutableStateOf(false) }
    var extractMessage by remember { mutableStateOf<String?>(null) }
    var extractedDirectory by remember { mutableStateOf<java.io.File?>(null) }
    var contentComparisonBusy by remember { mutableStateOf(false) }
    var contentComparisonMessage by remember { mutableStateOf<String?>(null) }

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

    val encryptedBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val backupJob = currentCoroutineContext()[Job]
                backupBusy = true
                backupMessage = "Encrypting APK and app-data streams…"
                val password = backupPassphrase.toCharArray()
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("Could not open the selected destination.")
                        output.use {
                            BackupContainer.createFromStreams(it, pendingBackupStreams, password) {
                                backupJob?.isActive != true
                            }
                        }
                    }
                }
                backupPassphrase = ""
                backupPassphraseConfirm = ""
                backupBusy = false
                backupMessage = result.fold(
                    onSuccess = {
                        "Unified encrypted backup created and authenticated: ${it.fileCount} entries, " +
                            "${formatBytes(it.totalBytes)}. Keep the passphrase safe."
                    },
                    onFailure = { "Backup failed: ${it.message ?: "unknown error"}" }
                )
                pendingBackupStreams = emptyList()
            }
        } else {
            if (pendingBackupStreams.isNotEmpty()) {
                backupMessage = "Destination selection cancelled. No backup was created."
            }
            pendingBackupStreams = emptyList()
        }
    }

    val verifyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        verifyUri = uri
        verifyTotalBytes = if (uri == null) {
            -1L
        } else {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.SIZE), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                } ?: -1L
            }.getOrDefault(-1L)
        }
        verifyMessage = if (uri == null) null else "Backup selected. Enter its passphrase and verify."
    }

    val extractPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        extractUri = uri
        extractedDirectory = null
        contentComparisonMessage = null
        extractMessage = if (uri == null) null else "Backup selected for extraction test."
    }

    LaunchedEffect(verifyBusy) {
        if (verifyBusy) {
            while (true) {
                verifyProgressBytes = verifyBytesCounter.get()
                val elapsedMs = (android.os.SystemClock.elapsedRealtime() - verifyStartedAt).coerceAtLeast(1L)
                val averageMbPerSecond = verifyProgressBytes.toDouble() / (1024.0 * 1024.0) /
                    (elapsedMs.toDouble() / 1000.0)
                verifyAverageSpeed = String.format(Locale.US, "%.2f MB/s average", averageMbPerSecond)
                delay(300)
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

                InfoCard("Create encrypted backup") {
                    Text(
                        "Creates one password-protected .apexbackup containing selected apps' base/split APKs " +
                            "and supported CE, DE, app-specific External and OBB data. Root access is required. " +
                            "Cache and code_cache are excluded."
                    )
                    InfoRow(
                        "Root access",
                        when {
                            rootLoading -> "Checking…"
                            rootResult?.isRoot == true -> "Granted"
                            rootResult == null -> "Not checked"
                            else -> "Unavailable — grant access in Magisk"
                        }
                    )
                    InfoRow("Selected apps", selectedPackages.size.toString())
                    Text(
                        "Capture is live best-effort: apps are not force-stopped and their data may change while read. " +
                            "Missing/inaccessible categories are recorded in the encrypted metadata. " +
                            "This is an app backup, not a full-device image.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = backupPassphrase,
                        onValueChange = { backupPassphrase = it },
                        label = { Text("New backup passphrase (12+ characters)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = backupPassphraseConfirm,
                        onValueChange = { backupPassphraseConfirm = it },
                        label = { Text("Confirm passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val root = rootResult
                            val chosen = apps.filter { it.packageName in selectedPackages }
                            if (root?.isRoot != true) {
                                backupMessage = "Check root access first and grant Apex Backup permission in Magisk."
                            } else if (chosen.isEmpty()) {
                                backupMessage = "Select at least one app."
                            } else if (backupPassphrase.length < 12 ||
                                backupPassphrase != backupPassphraseConfirm) {
                                backupMessage = "Enter matching passphrases of at least 12 characters."
                            } else {
                                backupBusy = true
                                captureDiagnostics = emptyList()
                                backupMessage = "Preparing APK and app-data streams through root…"
                                scope.launch {
                                    val job = currentCoroutineContext()[Job]
                                    try {
                                        val prepared = withContext(Dispatchers.IO) {
                                            RootAppDataCapture.prepare(
                                                chosen,
                                                context.packageManager,
                                                device
                                            ) { job?.isActive != true }
                                        }
                                        pendingBackupStreams = prepared.sources
                                        captureDiagnostics = prepared.results
                                        val ready = prepared.results.count { it.status == "READY" }
                                        val skipped = prepared.results.count {
                                            it.status == "NOT_PRESENT_OR_INACCESSIBLE"
                                        }
                                        val excluded = prepared.results.count {
                                            it.status == "EXCLUDED_BY_POLICY"
                                        }
                                        backupMessage =
                                            "Prepared ${prepared.packageCount} app(s): ${ready} data categories ready, " +
                                            "${skipped} missing/inaccessible, ${excluded} cache paths excluded. " +
                                            "Choose where to save the unified backup."
                                        encryptedBackupLauncher.launch(
                                            "Apex-Backup-${System.currentTimeMillis()}.apexbackup"
                                        )
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        pendingBackupStreams = emptyList()
                                        backupMessage = "Backup preparation failed: ${error.message ?: "unknown error"}"
                                    } finally {
                                        backupBusy = false
                                    }
                                }
                            }
                        },
                        enabled = selectedPackages.isNotEmpty() && !backupBusy && !rootLoading &&
                            rootResult?.isRoot == true &&
                            backupPassphrase.length >= 12 &&
                            backupPassphrase == backupPassphraseConfirm
                    ) {
                        Text("Create unified encrypted backup")
                    }
                    if (backupBusy) CircularProgressIndicator()
                    backupMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (captureDiagnostics.isNotEmpty()) {
                        InfoCard("Last app-data capture diagnostics") {
                            captureDiagnostics
                                .groupBy { it.packageName }
                                .forEach { (packageName, results) ->
                                    Text(packageName, style = MaterialTheme.typography.titleSmall)
                                    results.forEach { result ->
                                        val statusLabel = when (result.status) {
                                            "READY" -> "Ready — included in encrypted capture"
                                            "NOT_PRESENT_OR_INACCESSIBLE" -> "Missing or inaccessible — not captured"
                                            "EXCLUDED_BY_POLICY" -> "Excluded by policy (cache)"
                                            else -> result.status
                                        }
                                        InfoRow(result.category, statusLabel)
                                    }
                                    HorizontalDivider()
                                }
                            Text(
                                "If CE or DE shows “Missing or inaccessible”, that category was not captured. This status does not yet distinguish a missing directory from an access problem.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { verifyPicker.launch(arrayOf("*/*")) },
                        enabled = !verifyBusy
                    ) {
                        Text("Choose backup to verify")
                    }
                    verifyUri?.let {
                        Text("A backup file is selected.")
                    }
                    OutlinedTextField(
                        value = verifyPassphrase,
                        onValueChange = { verifyPassphrase = it },
                        label = { Text("Backup passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val uri = verifyUri
                            if (uri != null) {
                                scope.launch {
                                    verifyBusy = true
                                    verifyBytesCounter.set(0L)
                                    verifyProgressBytes = 0L
                                    verifyStartedAt = android.os.SystemClock.elapsedRealtime()
                                    verifyAverageSpeed = "0.00 MB/s average"
                                    verifyMessage = "Verifying encryption and file integrity…"
                                    val password = verifyPassphrase.toCharArray()
                                    try {
                                        val summary = withContext(Dispatchers.IO) {
                                            val input = context.contentResolver.openInputStream(uri)
                                                ?: error("Could not open the selected backup.")
                                            input.use {
                                                BackupContainer.verify(it, password) { bytesRead ->
                                                    verifyBytesCounter.set(bytesRead)
                                                }
                                            }
                                        }
                                        verifyMessage = "Verified: ${summary.fileCount} files, " +
                                            "${formatBytes(summary.totalBytes)}. Authentication and SHA-256 checks passed."
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        verifyMessage = "Verification failed: ${error.message ?: "unknown error"}"
                                    } finally {
                                        password.fill(0.toChar())
                                        verifyPassphrase = ""
                                        verifyProgressBytes = verifyBytesCounter.get()
                                        verifyBusy = false
                                    }
                                }
                            }
                        },
                        enabled = verifyUri != null && verifyPassphrase.length >= 12 && !verifyBusy
                    ) {
                        Text("Verify backup")
                    }
                    if (verifyBusy) {
                        val progress = if (verifyTotalBytes > 0L) {
                            (verifyProgressBytes.toFloat() / verifyTotalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            null
                        }
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "${(progress * 100).toInt()}% • ${formatBytes(verifyProgressBytes)} / ${formatBytes(verifyTotalBytes)} • $verifyAverageSpeed",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "${formatBytes(verifyProgressBytes)} processed • $verifyAverageSpeed",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    verifyMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

                    HorizontalDivider()
                    Text("Backup extraction test", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Decrypts the v3 container into a temporary private staging folder, then checks " +
                            "every extracted entry against the authenticated manifest. This does not restore app data.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { extractPicker.launch(arrayOf("*/*")) },
                        enabled = !extractBusy
                    ) {
                        Text("Choose backup to extract")
                    }
                    if (extractUri != null) Text("An extraction source is selected.")
                    OutlinedTextField(
                        value = extractPassphrase,
                        onValueChange = { extractPassphrase = it },
                        label = { Text("Backup passphrase for extraction") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val uri = extractUri
                            if (uri != null) {
                                scope.launch {
                                    extractBusy = true
                                    extractMessage = "Decrypting and extracting into private staging storage…"
                                    val password = extractPassphrase.toCharArray()
                                    val destination = java.io.File(
                                        context.cacheDir,
                                        "apex-recovery-" + System.currentTimeMillis()
                                    )
                                    try {
                                        val summary = withContext(Dispatchers.IO) {
                                            val input = context.contentResolver.openInputStream(uri)
                                                ?: error("Could not open the selected backup.")
                                            input.use {
                                                BackupContainer.extractToDirectory(it, password, destination)
                                            }
                                        }
                                        extractedDirectory = destination
                                        contentComparisonMessage = null
                                        val extractedFiles = destination.walkTopDown().count { it.isFile }
                                        extractMessage =
                                            "Extraction passed: ${summary.fileCount} authenticated entries, " +
                                            "${formatBytes(summary.totalBytes)}. Materialized files: $extractedFiles. " +
                                            "Temporary data is stored in app-private cache; no app data was restored."
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        extractMessage = "Extraction failed: ${error.message ?: "unknown error"}"
                                    } finally {
                                        password.fill(0.toChar())
                                        extractPassphrase = ""
                                        extractBusy = false
                                    }
                                }
                            }
                        },
                        enabled = extractUri != null && extractPassphrase.length >= 12 && !extractBusy
                    ) {
                        Text("Extract & verify contents")
                    }
                    if (extractBusy) CircularProgressIndicator()
                    extractMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton(
                        onClick = {
                            val directory = extractedDirectory
                            if (directory != null) {
                                scope.launch {
                                    contentComparisonBusy = true
                                    contentComparisonMessage = "Reading CE/DE/External/OBB TAR contents and checking known fixtures…"
                                    try {
                                        val report = withContext(Dispatchers.IO) {
                                            com.apex.backup.capture.TestLabContentComparator.compare(directory)
                                        }
                                        contentComparisonMessage = buildString {
                                            append("Test Lab content comparison: ")
                                            append("${report.passed} PASS • ${report.failed} FAIL • ${report.skipped} SKIP")
                                            report.checks.forEach { check ->
                                                append("\\n")
                                                append(check.status)
                                                append(" — ")
                                                append(check.name)
                                                append(": ")
                                                append(check.detail)
                                            }
                                        }
                                    } catch (error: Exception) {
                                        contentComparisonMessage = "Content comparison failed: ${error.message ?: "unknown error"}"
                                    } finally {
                                        contentComparisonBusy = false
                                    }
                                }
                            }
                        },
                        enabled = extractedDirectory != null && !contentComparisonBusy && !extractBusy
                    ) {
                        Text("Compare Test Lab file contents")
                    }
                    if (contentComparisonBusy) CircularProgressIndicator()
                    contentComparisonMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text(
                        "Use a unique passphrase and keep it somewhere safe. The app cannot recover " +
                            "a forgotten passphrase. This container can store APKs and supported app-data TAR streams; restore is not implemented.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                InfoCard("Safety status") {
                    Text(
                        "Unified encrypted backups include selected APKs and supported app data through root using best-effort live capture. " +
                            "Restore is not implemented; accounts, runtime sessions, permissions and system configuration are not guaranteed."
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
