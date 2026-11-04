package com.apex.backup.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    fun truncatedStreamingCiphertextIsRejected() {
        val source = File.createTempFile("apex-source", ".apk")
        try {
            source.writeBytes(ByteArray(2 * 1024 * 1024) { (it % 239).toByte() })
            val output = ByteArrayOutputStream()
            BackupContainer.create(
                output,
                listOf(BackupFile("apps/example.app/base.apk", source)),
                "correct horse battery staple".toCharArray()
            )
            val complete = output.toByteArray()
            val truncated = complete.copyOf(complete.size - 1)
            assertThrows(BackupContainerException::class.java) {
                BackupContainer.verify(
                    ByteArrayInputStream(truncated),
                    "correct horse battery staple".toCharArray()
                )
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun emptyPayloadFileIsSupported() {
        val source = File.createTempFile("apex-empty", ".apk")
        try {
            source.writeBytes(ByteArray(0))
            val output = ByteArrayOutputStream()
            val created = BackupContainer.create(
                output,
                listOf(BackupFile("apps/example.app/empty.apk", source)),
                "correct horse battery staple".toCharArray()
            )
            val verified = BackupContainer.verify(
                ByteArrayInputStream(output.toByteArray()),
                "correct horse battery staple".toCharArray()
            )
            assertEquals(0L, created.totalBytes)
            assertEquals(0L, verified.totalBytes)
            assertEquals(1, verified.fileCount)
        } finally {
            source.delete()
        }
    }

    @Test
    fun versionOneAndTwoContainersRemainReadable() {
        listOf(1, 2).forEach { version ->
            val legacy = createLegacyFixture(version)
            val verified = BackupContainer.verify(
                ByteArrayInputStream(legacy),
                "correct horse battery staple".toCharArray()
            )
            assertEquals(version, verified.formatVersion)
            assertEquals(1, verified.fileCount)
            assertEquals("legacy payload".toByteArray().size.toLong(), verified.totalBytes)
        }
    }

    private fun createLegacyFixture(version: Int): ByteArray {
        val password = "correct horse battery staple".toCharArray()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password))
        val passwordBytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        val key = ByteArray(32)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3).withMemoryAsKB(65536).withParallelism(1).withSalt(salt).build()
        Argon2BytesGenerator().apply { init(params) }.generateBytes(passwordBytes, key)
        passwordBytes.fill(0)
        val headerOut = ByteArrayOutputStream()
        DataOutputStream(headerOut).use { out ->
            out.write("APEXBKUP".toByteArray(StandardCharsets.US_ASCII))
            out.writeInt(version)
            out.writeByte(1)
            out.writeInt(3)
            out.writeInt(65536)
            out.writeInt(1)
            out.writeByte(16)
            out.writeByte(12)
            out.write(salt)
            out.write(nonce)
            if (version == 2) {
                val verifierKey = MessageDigest.getInstance("SHA-256").digest(
                    key + "APEX-BACKUP-PASSWORD-VERIFIER-KEY-V2".toByteArray(StandardCharsets.US_ASCII)
                )
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(verifierKey, "HmacSHA256"))
                mac.update("APEX-BACKUP-PASSWORD-VERIFIER-V2".toByteArray(StandardCharsets.US_ASCII))
                out.write(mac.doFinal(salt).copyOf(16))
                verifierKey.fill(0)
            }
        }
        val header = headerOut.toByteArray()
        val encrypted = ByteArrayOutputStream()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        ZipOutputStream(CipherOutputStream(encrypted, cipher)).use { zip ->
            val payload = "legacy payload".toByteArray()
            zip.putNextEntry(ZipEntry("apps/legacy/base.apk").apply { time = 0L })
            zip.write(payload)
            zip.closeEntry()
            val digest = MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it) }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val manifest = "APEX-MANIFEST-1\n" +
                "created_at=" + encoder.encodeToString("2026-01-01T00:00:00Z".toByteArray()) + "\n" +
                "entry_count=1\n" +
                "entry.0=" + encoder.encodeToString("apps/legacy/base.apk".toByteArray()) +
                "|13|" + digest + "\n"
            zip.putNextEntry(ZipEntry("META-INF/apex-manifest.txt").apply { time = 0L })
            zip.write(manifest.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        key.fill(0)
        password.fill('\u0000')
        return header + encrypted.toByteArray()
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
