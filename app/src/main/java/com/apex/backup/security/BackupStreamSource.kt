package com.apex.backup.security

import java.io.InputStream

/**
 * A one-shot stream source. The stream is opened only while the encrypted ZIP entry is written,
 * so root-produced data never needs a plaintext temporary archive.
 */
data class BackupStreamSource(
    val archivePath: String,
    val compress: Boolean = true,
    val openStream: () -> InputStream
)
