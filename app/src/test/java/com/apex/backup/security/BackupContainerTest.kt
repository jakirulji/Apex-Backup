package com.apex.backup.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

class BackupContainerTest {
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead = 0
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) bytesRead++
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) bytesRead += count
            return count
        }
    }

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

            assertEquals(3, created.formatVersion)
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
            val bytes = output.toByteArray()
            val counted = CountingInputStream(ByteArrayInputStream(bytes))
            val error = assertThrows(BackupContainerException::class.java) {
                BackupContainer.verify(counted, "this is the wrong password".toCharArray())
            }
            assertEquals("Wrong passphrase.", error.message)
            // v3 reads its bounded header and wrapped keyset, but no streaming payload.
            assertTrue(counted.bytesRead > 71)
            assertTrue(counted.bytesRead < bytes.size)
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
