package io.github.opencodeandroid.backup

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.KeyStatus
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.subtle.AesGcmJce
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Password wraps a fresh Tink keyset; the payload uses bounded-memory, authenticated segments. */
object BackupEnvelope {
    private val magic = "OCABAK01".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600_000
    private const val MAX_KEYSET = 64 * 1024
    init { StreamingAeadConfig.register() }

    private fun prefix(salt: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).apply { write(magic); writeInt(ITERATIONS); write(salt) }
    }.toByteArray()
    private fun wrappingKey(password: CharArray, salt: ByteArray): AesGcmJce {
        require(password.isNotEmpty() && password.size <= 4096) { "备份密码长度无效" }
        val specification = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded }
        finally { specification.clearPassword() }
        return try { AesGcmJce(bytes) } finally { bytes.fill(0) }
    }

    fun encrypt(output: OutputStream, password: CharArray): OutputStream {
        require(password.size >= 12) { "备份密码至少需要 12 个字符" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val associated = prefix(salt)
        val handle = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB)
        val wrapped = TinkJsonProtoKeysetFormat.serializeEncryptedKeyset(handle, wrappingKey(password, salt), associated,
            RegistryConfiguration.get()).toByteArray(Charsets.UTF_8)
        check(wrapped.size in 1..MAX_KEYSET)
        val header = ByteArrayOutputStream().apply {
            write(associated); DataOutputStream(this).apply { writeInt(wrapped.size); write(wrapped) }
        }.toByteArray()
        output.write(header)
        return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java).newEncryptingStream(output, header)
    }

    /** Caller must read through EOF before accepting any staged extraction or activating restored data. */
    fun decrypt(input: InputStream, password: CharArray): InputStream {
        try {
            val data = DataInputStream(input)
            val marker = ByteArray(magic.size).also(data::readFully)
            if (!marker.contentEquals(magic) || data.readInt() != ITERATIONS) throw IOException("不支持的备份格式")
            val salt = ByteArray(16).also(data::readFully)
            val length = data.readInt()
            if (length !in 1..MAX_KEYSET) throw IOException("备份头无效")
            val wrapped = ByteArray(length).also(data::readFully)
            val wrappedText = String(wrapped, Charsets.UTF_8).also(BackupJson::check)
            val associated = prefix(salt)
            val header = ByteArrayOutputStream().apply {
                write(associated); DataOutputStream(this).apply { writeInt(length); write(wrapped) }
            }.toByteArray()
            val handle = TinkJsonProtoKeysetFormat.parseEncryptedKeyset(wrappedText, wrappingKey(password, salt),
                associated, RegistryConfiguration.get())
            // A password-authenticated archive can still be crafted by its sender. Do not let
            // attacker-chosen segment sizes allocate unbounded buffers before authentication.
            if (handle.size() != 1 || handle.getAt(0).status != KeyStatus.ENABLED || !handle.getAt(0).isPrimary ||
                handle.getAt(0).key.parameters != PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB) {
                throw IOException("不支持的备份加密参数")
            }
            return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java).newDecryptingStream(input, header)
        } catch (_: GeneralSecurityException) { throw IOException("备份密码错误或文件损坏") }
    }
}
