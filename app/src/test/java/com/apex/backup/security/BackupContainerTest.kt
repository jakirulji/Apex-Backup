package com.apex.backup.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class BackupContainerTest {
    @Test
    fun encryptedContainerRoundTripsAndVerifies() {
        val source = File.createTempFile("apex-source", ".apk")
        try {
            source.writeBytes(ByteArray(32_768) { (it % 251).toByte() })
            val output = ByteArrayOutputStream()
            val created = BackupContainer.create(
                output,
                listOf(BackupFile("apps/example.app/1/base.apk", source)),
                "correct horse battery staple".toCharArray()
            )

            val verified = BackupContainer.verify(
                ByteArrayInputStream(output.toByteArray()),
                "correct horse battery staple".toCharArray()
            )

            assertEquals(1, created.fileCount)
            assertEquals(source.length(), created.totalBytes)
            assertEquals(created.fileCount, verified.fileCount)
            assertEquals(created.totalBytes, verified.totalBytes)
            assertEquals(created.createdAt, verified.createdAt)
        } finally {
            source.delete()
        }
    }

    @Test
    fun wrongPasswordIsRejected() {
        val source = File.createTempFile("apex-source", ".apk")
        try {
            source.writeText("test APK payload")
            val output = ByteArrayOutputStream()
            BackupContainer.create(
                output,
                listOf(BackupFile("apps/example.app/base.apk", source)),
                "correct horse battery staple".toCharArray()
            )
            assertThrows(BackupContainerException::class.java) {
                BackupContainer.verify(
                    ByteArrayInputStream(output.toByteArray()),
                    "this is the wrong password".toCharArray()
                )
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun modifiedAuthenticationTagIsRejected() {
        val source = File.createTempFile("apex-source", ".apk")
        try {
            source.writeText("tamper test")
            val output = ByteArrayOutputStream()
            BackupContainer.create(
                output,
                listOf(BackupFile("apps/example.app/base.apk", source)),
                "correct horse battery staple".toCharArray()
            )
            val damaged = output.toByteArray()
            damaged[damaged.lastIndex] = (damaged.last().toInt() xor 0x01).toByte()
            assertThrows(BackupContainerException::class.java) {
                BackupContainer.verify(
                    ByteArrayInputStream(damaged),
                    "correct horse battery staple".toCharArray()
                )
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun pathTraversalIsRejectedBeforeWriting() {
        val source = File.createTempFile("apex-source", ".apk")
        try {
            source.writeText("test")
            assertThrows(BackupContainerException::class.java) {
                BackupContainer.create(
                    ByteArrayOutputStream(),
                    listOf(BackupFile("../outside.apk", source)),
                    "correct horse battery staple".toCharArray()
                )
            }
        } finally {
            source.delete()
        }
    }
}
