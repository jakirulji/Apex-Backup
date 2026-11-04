package com.apex.backup.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class BackupFile(val archivePath: String, val source: File)

data class BackupSummary(
    val formatVersion: Int,
    val createdAt: String,
    val fileCount: Int,
    val totalBytes: Long
)

class BackupContainerException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Portable, password-protected APK archive.
 *
 * Wire format v1: fixed authenticated header followed by AES-256-GCM ciphertext.
 * The plaintext payload is a ZIP stream with payload files and a final authenticated manifest.
 */
object BackupContainer {
    const val FORMAT_VERSION = 1
    const val FILE_EXTENSION = ".apexbackup"

    private val MAGIC = "APEXBKUP".toByteArray(StandardCharsets.US_ASCII)
    private const val KDF_ARGON2ID = 1
    private const val ARGON2_ITERATIONS = 3
    private const val ARGON2_MEMORY_KIB = 64 * 1024
    private const val ARGON2_PARALLELISM = 1
    private const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12
    private const val KEY_LENGTH = 32
    private const val TAG_LENGTH_BITS = 128
    private const val MANIFEST_PATH = "META-INF/apex-manifest.txt"
    private const val MAX_ENTRIES = 100_000
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private const val MAX_PATH_LENGTH = 1024
    private val random = SecureRandom()

    fun create(
        destination: OutputStream,
        files: List<BackupFile>,
        passphrase: CharArray
    ): BackupSummary {
        validatePassphrase(passphrase)
        validateFiles(files)

        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        val header = buildHeader(salt, nonce)
        val key = deriveKey(passphrase, salt)
        val createdAt = java.time.Instant.now().toString()
        val records = ArrayList<EntryRecord>(files.size)
        var totalBytes = 0L

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_LENGTH_BITS, nonce)
            )
            cipher.updateAAD(header)
            destination.write(header)
            destination.flush()

            ZipOutputStream(CipherOutputStream(destination, cipher)).use { zip ->
                files.forEach { backupFile ->
                    val entry = ZipEntry(backupFile.archivePath)
                    entry.time = 0L
                    zip.putNextEntry(entry)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    backupFile.source.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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
                    records += EntryRecord(
                        backupFile.archivePath,
                        size,
                        digest.digest().toHex()
                    )
                }

                val manifest = encodeManifest(createdAt, records)
                zip.putNextEntry(ZipEntry(MANIFEST_PATH).apply { time = 0L })
                zip.write(manifest)
                zip.closeEntry()
            }
            return BackupSummary(FORMAT_VERSION, createdAt, records.size, totalBytes)
        } catch (error: Exception) {
            throw BackupContainerException(
                "Could not create encrypted backup: \${error.message ?: error.javaClass.simpleName}",
                error
            )
        } finally {
            key.fill(0)
            passphrase.fill('\\u0000')
        }
    }

    fun verify(source: InputStream, passphrase: CharArray): BackupSummary {
        validatePassphrase(passphrase)
        val headerData = readHeader(source)
        val key = deriveKey(passphrase, headerData.salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_LENGTH_BITS, headerData.nonce)
            )
            cipher.updateAAD(headerData.encoded)

            val cipherInput = CipherInputStream(source, cipher)
            val zip = ZipInputStream(cipherInput)
            val actual = LinkedHashMap<String, EntryRecord>()
            var manifestBytes: ByteArray? = null
            var manifestSeen = false

            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    throw BackupContainerException("Directory entries are not allowed.")
                }
                if (entry.name == MANIFEST_PATH) {
                    if (manifestSeen) throw BackupContainerException("Duplicate manifest.")
                    manifestSeen = true
                    manifestBytes = readBounded(zip, MAX_MANIFEST_BYTES)
                } else {
                    if (manifestSeen) {
                        throw BackupContainerException("Manifest must be the final archive entry.")
                    }
                    validateArchivePath(entry.name)
                    if (actual.size >= MAX_ENTRIES || actual.containsKey(entry.name)) {
                        throw BackupContainerException("Too many or duplicate archive entries.")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        size += count
                    }
                    actual[entry.name] = EntryRecord(entry.name, size, digest.digest().toHex())
                }
                zip.closeEntry()
            }

            // Consume the GCM stream to EOF: the authentication tag is checked only at the end.
            val drainBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (cipherInput.read(drainBuffer) >= 0) {
                // Drain remaining authenticated ZIP trailer bytes.
            }

            val manifest = manifestBytes
                ?: throw BackupContainerException("Backup manifest is missing.")
            val parsed = decodeManifest(manifest)
            if (parsed.records.size != actual.size) {
                throw BackupContainerException("Backup contents do not match the manifest.")
            }
            parsed.records.forEach { expected ->
                val found = actual[expected.path]
                    ?: throw BackupContainerException("Missing file: \${expected.path}")
                if (found.size != expected.size ||
                    !MessageDigest.isEqual(
                        found.sha256.toByteArray(StandardCharsets.US_ASCII),
                        expected.sha256.toByteArray(StandardCharsets.US_ASCII)
                    )
                ) {
                    throw BackupContainerException("Integrity check failed: \${expected.path}")
                }
            }
            return BackupSummary(
                FORMAT_VERSION,
                parsed.createdAt,
                actual.size,
                actual.values.sumOf { it.size }
            )
        } catch (error: BackupContainerException) {
            throw error
        } catch (error: Exception) {
            throw BackupContainerException(
                "Wrong password, damaged backup, or unsupported container.",
                error
            )
        } finally {
            key.fill(0)
            passphrase.fill('\\u0000')
        }
    }

    private fun validatePassphrase(passphrase: CharArray) {
        if (passphrase.size < 12 || passphrase.size > 1024) {
            throw BackupContainerException("Passphrase must contain 12–1024 characters.")
        }
    }

    private fun validateFiles(files: List<BackupFile>) {
        if (files.isEmpty()) throw BackupContainerException("No files were selected.")
        if (files.size > MAX_ENTRIES) throw BackupContainerException("Too many files.")
        val seen = HashSet<String>()
        files.forEach {
            validateArchivePath(it.archivePath)
            if (it.archivePath == MANIFEST_PATH || !seen.add(it.archivePath)) {
                throw BackupContainerException("Reserved or duplicate archive path.")
            }
            if (!it.source.isFile || !it.source.canRead()) {
                throw BackupContainerException("Source file is not readable: \${it.archivePath}")
            }
        }
    }

    private fun validateArchivePath(path: String) {
        if (path.isBlank() || path.length > MAX_PATH_LENGTH ||
            path.startsWith("/") || path.startsWith("\\\\") ||
            path.contains('\\\\') || path.any { it.code < 0x20 }
        ) {
            throw BackupContainerException("Unsafe archive path.")
        }
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw BackupContainerException("Unsafe archive path.")
        }
    }

    private data class Header(val encoded: ByteArray, val salt: ByteArray, val nonce: ByteArray)

    private fun buildHeader(salt: ByteArray, nonce: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeByte(KDF_ARGON2ID)
            out.writeInt(ARGON2_ITERATIONS)
            out.writeInt(ARGON2_MEMORY_KIB)
            out.writeInt(ARGON2_PARALLELISM)
            out.writeByte(salt.size)
            out.writeByte(nonce.size)
            out.write(salt)
            out.write(nonce)
        }
        return bytes.toByteArray()
    }

    private fun readHeader(source: InputStream): Header {
        try {
            val fixedLength = MAGIC.size + 4 + 1 + 4 + 4 + 4 + 1 + 1
            val fixed = ByteArray(fixedLength)
            DataInputStream(source).readFully(fixed)
            val data = DataInputStream(ByteArrayInputStream(fixed))
            val magic = ByteArray(MAGIC.size).also(data::readFully)
            if (!magic.contentEquals(MAGIC)) throw BackupContainerException("Not an Apex Backup file.")
            val version = data.readInt()
            val kdf = data.readUnsignedByte()
            val iterations = data.readInt()
            val memory = data.readInt()
            val parallelism = data.readInt()
            val saltLength = data.readUnsignedByte()
            val nonceLength = data.readUnsignedByte()

            if (version != FORMAT_VERSION || kdf != KDF_ARGON2ID ||
                iterations != ARGON2_ITERATIONS || memory != ARGON2_MEMORY_KIB ||
                parallelism != ARGON2_PARALLELISM ||
                saltLength != SALT_LENGTH || nonceLength != NONCE_LENGTH
            ) {
                throw BackupContainerException("Unsupported or unsafe backup parameters.")
            }
            val salt = ByteArray(saltLength)
            val nonce = ByteArray(nonceLength)
            DataInputStream(source).readFully(salt)
            DataInputStream(source).readFully(nonce)
            return Header(fixed + salt + nonce, salt, nonce)
        } catch (error: BackupContainerException) {
            throw error
        } catch (error: Exception) {
            throw BackupContainerException("Invalid or incomplete backup header.", error)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(passphrase))
        val passwordBytes = ByteArray(encoded.remaining())
        encoded.get(passwordBytes)
        val key = ByteArray(KEY_LENGTH)
        try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ARGON2_ITERATIONS)
                .withMemoryAsKB(ARGON2_MEMORY_KIB)
                .withParallelism(ARGON2_PARALLELISM)
                .withSalt(salt)
                .build()
            Argon2BytesGenerator().apply { init(parameters) }
                .generateBytes(passwordBytes, key)
            return key
        } finally {
            passwordBytes.fill(0)
        }
    }

    private data class EntryRecord(val path: String, val size: Long, val sha256: String)
    private data class Manifest(val createdAt: String, val records: List<EntryRecord>)

    private fun encodeManifest(createdAt: String, records: List<EntryRecord>): ByteArray {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val text = buildString {
            append("APEX-MANIFEST-1\\n")
            append("created_at=")
            append(encoder.encodeToString(createdAt.toByteArray(StandardCharsets.UTF_8)))
            append('\\n')
            append("entry_count=").append(records.size).append('\\n')
            records.forEachIndexed { index, record ->
                val path = encoder.encodeToString(record.path.toByteArray(StandardCharsets.UTF_8))
                append("entry.").append(index).append('=')
                    .append(path).append('|').append(record.size).append('|')
                    .append(record.sha256).append('\\n')
            }
        }
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodeManifest(bytes: ByteArray): Manifest {
        val lines = bytes.toString(StandardCharsets.UTF_8).split('\\n')
        if (lines.firstOrNull() != "APEX-MANIFEST-1") {
            throw BackupContainerException("Invalid backup manifest.")
        }
        val values = lines.drop(1).filter { it.isNotEmpty() }.associate { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) throw BackupContainerException("Malformed manifest.")
            line.substring(0, separator) to line.substring(separator + 1)
        }
        val decoder = Base64.getUrlDecoder()
        val createdAt = runCatching {
            String(decoder.decode(values["created_at"] ?: error("Missing creation time")), StandardCharsets.UTF_8)
        }.getOrElse { throw BackupContainerException("Invalid manifest timestamp.", it) }
        val count = values["entry_count"]?.toIntOrNull()
            ?: throw BackupContainerException("Invalid manifest entry count.")
        if (count < 0 || count > MAX_ENTRIES) throw BackupContainerException("Manifest entry limit exceeded.")
        val records = (0 until count).map { index ->
            val fields = values["entry.$index"]?.split('|')
                ?: throw BackupContainerException("Manifest entry is missing.")
            if (fields.size != 3) throw BackupContainerException("Malformed manifest entry.")
            val path = runCatching {
                String(decoder.decode(fields[0]), StandardCharsets.UTF_8)
            }.getOrElse { throw BackupContainerException("Invalid manifest path.", it) }
            validateArchivePath(path)
            val size = fields[1].toLongOrNull()
                ?: throw BackupContainerException("Invalid manifest size.")
            val hash = fields[2]
            if (size < 0 || !hash.matches(Regex("[0-9a-f]{64}"))) {
                throw BackupContainerException("Invalid manifest checksum.")
            }
            EntryRecord(path, size, hash)
        }
        if (records.map { it.path }.toSet().size != records.size) {
            throw BackupContainerException("Duplicate paths in manifest.")
        }
        return Manifest(createdAt, records)
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) {
                throw BackupContainerException("Backup manifest is too large.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
