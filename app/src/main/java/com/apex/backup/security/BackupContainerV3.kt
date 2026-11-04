package com.apex.backup.security

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v3 uses Tink Streaming AEAD for the ZIP payload. Its randomly generated keyset is
 * wrapped by the Argon2id-derived passphrase key, keeping the container portable.
 */
internal object BackupContainerV3 {
    private val MAGIC = "APEXBKUP".toByteArray(StandardCharsets.US_ASCII)
    private const val VERSION = 3
    private const val KDF = 1
    private const val ITERATIONS = 3
    private const val MEMORY_KIB = 65536
    private const val PARALLELISM = 1
    private const val SALT_LENGTH = 16
    private const val WRAP_NONCE_LENGTH = 12
    private const val VERIFIER_LENGTH = 16
    private const val KEY_LENGTH = 32
    private const val TAG_BITS = 128
    private const val MANIFEST = "META-INF/apex-manifest.txt"
    private const val MAX_ENTRIES = 100_000
    private const val MAX_MANIFEST = 4 * 1024 * 1024
    private const val MAX_PATH = 1024
    private val random = SecureRandom()

    private fun initTink() {
        StreamingAeadConfig.register()
    }

    // Scale sequential I/O buffers with the app heap; cap memory use, not throughput.
    private fun ioBufferSize(): Int =
        (Runtime.getRuntime().maxMemory() / 128L).coerceIn(
            256L * 1024L, 4L * 1024L * 1024L
        ).toInt()

    fun create(destination: OutputStream, files: List<BackupFile>, passphrase: CharArray): BackupSummary {
        validatePassphrase(passphrase)
        validateFiles(files)
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val wrapNonce = ByteArray(WRAP_NONCE_LENGTH).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        try {
            initTink()
            val keyset = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_1MB"))
            val serializedKeyset = TinkJsonProtoKeysetFormat.serializeKeyset(
                keyset, InsecureSecretKeyAccess.get()
            ).toByteArray(StandardCharsets.UTF_8)
            val verifier = passwordVerifier(key, salt)
            val prefix = buildPrefix(salt, wrapNonce, verifier)
            val wrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
            wrapCipher.init(
                Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, wrapNonce)
            )
            wrapCipher.updateAAD(prefix)
            val wrappedKeyset = try {
                wrapCipher.doFinal(serializedKeyset)
            } finally {
                serializedKeyset.fill(0)
            }
            val header = prefix + intBytes(wrappedKeyset.size) + wrappedKeyset
            val streamingAead = keyset.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
            val createdAt = java.time.Instant.now().toString()
            val records = ArrayList<Record>(files.size)
            var totalBytes = 0L
            destination.write(header)
            destination.flush()
            val encrypted = streamingAead.newEncryptingStream(destination, header)
            ZipOutputStream(encrypted).use { zip ->
                files.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.archivePath).apply { time = 0L })
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    file.source.inputStream().buffered(ioBufferSize()).use { input ->
                        val buffer = ByteArray(ioBufferSize())
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            zip.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            size += count
                        }
                    }
                    zip.closeEntry()
                    totalBytes += size
                    records += Record(file.archivePath, size, digest.digest().hex())
                }
                zip.putNextEntry(ZipEntry(MANIFEST).apply { time = 0L })
                zip.write(encodeManifest(createdAt, records))
                zip.closeEntry()
            }
            return BackupSummary(VERSION, createdAt, records.size, totalBytes)
        } catch (e: BackupContainerException) {
            throw e
        } catch (e: Exception) {
            throw BackupContainerException("Could not create v3 encrypted backup: " + (e.message ?: e.javaClass.simpleName), e)
        } finally {
            key.fill(0)
            passphrase.fill('\u0000')
        }
    }

    fun verify(source: InputStream, passphrase: CharArray, onProgress: (Long) -> Unit): BackupSummary {
        validatePassphrase(passphrase)
        val tracked = ProgressInputStream(source, onProgress)
        val header = readHeader(tracked)
        val key = deriveKey(passphrase, header.salt)
        try {
            val expected = passwordVerifier(key, header.salt)
            val matches = MessageDigest.isEqual(expected, header.verifier)
            expected.fill(0)
            if (!matches) throw BackupContainerException("Wrong passphrase.")
            val unwrap = Cipher.getInstance("AES/GCM/NoPadding")
            unwrap.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, header.wrapNonce)
            )
            unwrap.updateAAD(header.prefix)
            val keysetJson = try {
                unwrap.doFinal(header.wrappedKeyset).toString(StandardCharsets.UTF_8)
            } catch (e: Exception) {
                throw BackupContainerException("Backup keyset authentication failed.", e)
            }
            initTink()
            val keyset = TinkJsonProtoKeysetFormat.parseKeyset(
                keysetJson, InsecureSecretKeyAccess.get()
            )
            val streamingAead = keyset.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
            val decrypted = streamingAead.newDecryptingStream(tracked, header.encoded)
            val plaintext = decrypted.buffered(ioBufferSize())
            val zip = ZipInputStream(plaintext)
            val actual = LinkedHashMap<String, Record>()
            var manifestBytes: ByteArray? = null
            var manifestSeen = false
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) throw BackupContainerException("Directory entries are not allowed.")
                if (entry.name == MANIFEST) {
                    if (manifestSeen) throw BackupContainerException("Duplicate manifest.")
                    manifestSeen = true
                    manifestBytes = readBounded(zip, MAX_MANIFEST)
                } else {
                    if (manifestSeen) throw BackupContainerException("Manifest must be the final archive entry.")
                    validatePath(entry.name)
                    if (actual.size >= MAX_ENTRIES || actual.containsKey(entry.name)) {
                        throw BackupContainerException("Too many or duplicate archive entries.")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    val buffer = ByteArray(ioBufferSize())
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        size += count
                    }
                    actual[entry.name] = Record(entry.name, size, digest.digest().hex())
                }
                zip.closeEntry()
            }
            val drain = ByteArray(ioBufferSize())
            while (plaintext.read(drain) >= 0) { /* force final segment authentication */ }
            zip.close()
            val parsed = decodeManifest(manifestBytes ?: throw BackupContainerException("Backup manifest is missing."))
            if (parsed.second.size != actual.size) throw BackupContainerException("Backup contents do not match the manifest.")
            parsed.second.forEach { expectedRecord ->
                val found = actual[expectedRecord.path] ?: throw BackupContainerException("Missing file: " + expectedRecord.path)
                if (found.size != expectedRecord.size ||
                    !MessageDigest.isEqual(found.sha256.toByteArray(StandardCharsets.US_ASCII),
                        expectedRecord.sha256.toByteArray(StandardCharsets.US_ASCII))) {
                    throw BackupContainerException("Integrity check failed: " + expectedRecord.path)
                }
            }
            return BackupSummary(VERSION, parsed.first, actual.size, actual.values.sumOf { it.size })
        } catch (e: BackupContainerException) {
            throw e
        } catch (e: Exception) {
            throw BackupContainerException("Wrong passphrase, damaged backup, or unsupported v3 container.", e)
        } finally {
            key.fill(0)
            passphrase.fill('\u0000')
        }
    }

    private data class Header(
        val prefix: ByteArray, val encoded: ByteArray, val salt: ByteArray,
        val wrapNonce: ByteArray, val verifier: ByteArray, val wrappedKeyset: ByteArray
    )
    private fun buildPrefix(salt: ByteArray, nonce: ByteArray, verifier: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.write(MAGIC); it.writeInt(VERSION); it.writeByte(KDF)
            it.writeInt(ITERATIONS); it.writeInt(MEMORY_KIB); it.writeInt(PARALLELISM)
            it.writeByte(salt.size); it.writeByte(nonce.size)
            it.write(salt); it.write(nonce); it.write(verifier)
        }
        return out.toByteArray()
    }
    private fun readHeader(input: InputStream): Header {
        try {
            val fixedLength = MAGIC.size + 4 + 1 + 4 + 4 + 4 + 1 + 1
            val fixed = ByteArray(fixedLength)
            DataInputStream(input).readFully(fixed)
            val data = DataInputStream(ByteArrayInputStream(fixed))
            val magic = ByteArray(MAGIC.size).also(data::readFully)
            if (!magic.contentEquals(MAGIC) || data.readInt() != VERSION ||
                data.readUnsignedByte() != KDF || data.readInt() != ITERATIONS ||
                data.readInt() != MEMORY_KIB || data.readInt() != PARALLELISM ||
                data.readUnsignedByte() != SALT_LENGTH ||
                data.readUnsignedByte() != WRAP_NONCE_LENGTH) {
                throw BackupContainerException("Unsupported or unsafe v3 backup header.")
            }
            val salt = ByteArray(SALT_LENGTH).also { DataInputStream(input).readFully(it) }
            val nonce = ByteArray(WRAP_NONCE_LENGTH).also { DataInputStream(input).readFully(it) }
            val verifier = ByteArray(VERIFIER_LENGTH).also { DataInputStream(input).readFully(it) }
            val prefix = fixed + salt + nonce + verifier
            val lengthBytes = ByteArray(4).also { DataInputStream(input).readFully(it) }
            val length = DataInputStream(ByteArrayInputStream(lengthBytes)).readInt()
            if (length !in 64..65536) throw BackupContainerException("Invalid wrapped keyset length.")
            val wrapped = ByteArray(length).also { DataInputStream(input).readFully(it) }
            return Header(prefix, prefix + lengthBytes + wrapped, salt, nonce, verifier, wrapped)
        } catch (e: BackupContainerException) {
            throw e
        } catch (e: Exception) {
            throw BackupContainerException("Invalid or incomplete v3 header.", e)
        }
    }
    private fun passwordVerifier(key: ByteArray, salt: ByteArray): ByteArray {
        val verifierKey = MessageDigest.getInstance("SHA-256").digest(
            key + "APEX-BACKUP-PASSWORD-VERIFIER-KEY-V3".toByteArray(StandardCharsets.US_ASCII)
        )
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(verifierKey, "HmacSHA256"))
            mac.update("APEX-BACKUP-PASSWORD-VERIFIER-V3".toByteArray(StandardCharsets.US_ASCII))
            mac.doFinal(salt).copyOf(VERIFIER_LENGTH)
        } finally { verifierKey.fill(0) }
    }
    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(passphrase))
        val password = ByteArray(encoded.remaining()).also { encoded.get(it) }
        val key = ByteArray(KEY_LENGTH)
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ITERATIONS).withMemoryAsKB(MEMORY_KIB)
                .withParallelism(PARALLELISM).withSalt(salt).build()
            Argon2BytesGenerator().apply { init(params) }.generateBytes(password, key)
            return key
        } finally { password.fill(0) }
    }
    private data class Record(val path: String, val size: Long, val sha256: String)
    private fun encodeManifest(created: String, records: List<Record>): ByteArray {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return buildString {
            append("APEX-MANIFEST-1\n")
            append("created_at=").append(encoder.encodeToString(created.toByteArray(StandardCharsets.UTF_8))).append('\n')
            append("entry_count=").append(records.size).append('\n')
            records.forEachIndexed { i, r ->
                append("entry.").append(i).append('=')
                    .append(encoder.encodeToString(r.path.toByteArray(StandardCharsets.UTF_8)))
                    .append('|').append(r.size).append('|').append(r.sha256).append('\n')
            }
        }.toByteArray(StandardCharsets.UTF_8)
    }
    private fun decodeManifest(bytes: ByteArray): Pair<String, List<Record>> {
        val lines = bytes.toString(StandardCharsets.UTF_8).split('\n')
        if (lines.firstOrNull() != "APEX-MANIFEST-1") throw BackupContainerException("Invalid backup manifest.")
        val values = lines.drop(1).filter { it.isNotEmpty() }.associate { line ->
            val i = line.indexOf('=')
            if (i <= 0) throw BackupContainerException("Malformed manifest.")
            line.substring(0, i) to line.substring(i + 1)
        }
        val decoder = Base64.getUrlDecoder()
        val created = runCatching {
            String(decoder.decode(values["created_at"] ?: error("Missing creation time")), StandardCharsets.UTF_8)
        }.getOrElse { throw BackupContainerException("Invalid manifest timestamp.", it) }
        val count = values["entry_count"]?.toIntOrNull()
            ?: throw BackupContainerException("Invalid manifest entry count.")
        if (count !in 0..MAX_ENTRIES) throw BackupContainerException("Manifest entry limit exceeded.")
        val records = (0 until count).map { i ->
            val fields = values["entry." + i]?.split('|') ?: throw BackupContainerException("Manifest entry is missing.")
            if (fields.size != 3) throw BackupContainerException("Malformed manifest entry.")
            val path = runCatching { String(decoder.decode(fields[0]), StandardCharsets.UTF_8) }
                .getOrElse { throw BackupContainerException("Invalid manifest path.", it) }
            validatePath(path)
            val size = fields[1].toLongOrNull() ?: throw BackupContainerException("Invalid manifest size.")
            if (size < 0 || !fields[2].matches(Regex("[0-9a-f]{64}"))) throw BackupContainerException("Invalid manifest checksum.")
            Record(path, size, fields[2])
        }
        if (records.map { it.path }.toSet().size != records.size) throw BackupContainerException("Duplicate paths in manifest.")
        return created to records
    }
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw BackupContainerException("Backup manifest is too large.")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
    private fun validateFiles(files: List<BackupFile>) {
        if (files.isEmpty()) throw BackupContainerException("No files were selected.")
        if (files.size > MAX_ENTRIES) throw BackupContainerException("Too many files.")
        val seen = HashSet<String>()
        files.forEach {
            validatePath(it.archivePath)
            if (it.archivePath == MANIFEST || !seen.add(it.archivePath)) throw BackupContainerException("Reserved or duplicate archive path.")
            if (!it.source.isFile || !it.source.canRead()) throw BackupContainerException("Source file is not readable: " + it.archivePath)
        }
    }
    private fun validatePath(path: String) {
        if (path.isBlank() || path.length > MAX_PATH || path.startsWith("/") ||
            path.startsWith("\\") || path.contains('\\') || path.any { it.code < 0x20 }) {
            throw BackupContainerException("Unsafe archive path.")
        }
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) throw BackupContainerException("Unsafe archive path.")
    }
    private fun validatePassphrase(passphrase: CharArray) {
        if (passphrase.size < 12 || passphrase.size > 1024) throw BackupContainerException("Passphrase must contain 12–1024 characters.")
    }
    private fun intBytes(value: Int): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).writeInt(value)
    }.toByteArray()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    private class ProgressInputStream(input: InputStream, private val progress: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var next = 1024L * 1024L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) report(1) else progress(count)
            return value
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) report(n.toLong()) else if (n < 0) progress(count)
            return n
        }
        private fun report(n: Long) {
            count += n
            if (count >= next) { progress(count); next = count + 1024L * 1024L }
        }
    }
}