package io.github.opencodeandroid.backup

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.subtle.AesGcmJce
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class BackupEnvelopeTest {
    private fun password() = "fixture only backup password 2026".toCharArray()
    private fun encrypt(bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        val password = password()
        try { BackupEnvelope.encrypt(this, password).use { it.write(bytes) } } finally { password.fill('\u0000') }
    }.toByteArray()
    private fun decrypt(bytes: ByteArray, password: CharArray = password()): ByteArray =
        try { BackupEnvelope.decrypt(ByteArrayInputStream(bytes), password).use { it.readBytes() } } finally { password.fill('\u0000') }
    private fun rejected(action: () -> Unit) { try { action(); fail("Invalid backup was accepted") } catch (_: IOException) { } }
    @Test fun multipleAuthenticatedSegmentsRoundTripAndEncryptionIsRandomized() {
        val clear = ByteArray(3 * 1024 * 1024 + 37) { (it * 31).toByte() }
        val first = encrypt(clear); val second = encrypt(clear)
        assertFalse(first.contentEquals(second)); assertArrayEquals(clear, decrypt(first)); assertArrayEquals(clear, decrypt(second))
        assertArrayEquals(byteArrayOf(), decrypt(encrypt(byteArrayOf())))
    }
    @Test fun wrongPasswordHeaderAndPayloadModificationNeverAuthenticateToEof() {
        val ciphertext = encrypt(ByteArray(2 * 1024 * 1024 + 31) { (it xor 93).toByte() })
        rejected { decrypt(ciphertext, "wrong fixture password".toCharArray()) }
        for (index in listOf(16, ciphertext.size / 2, ciphertext.lastIndex)) {
            val changed = ciphertext.copyOf(); changed[index] = (changed[index].toInt() xor 1).toByte()
            rejected { decrypt(changed) }
        }
    }
    @Test fun validInitialSegmentsDoNotMakeTruncatedTailAValidBackup() {
        val ciphertext = encrypt(ByteArray(3 * 1024 * 1024) { (it * 13).toByte() })
        val truncated = ciphertext.copyOf(ciphertext.size - 9)
        val password = password()
        try {
            BackupEnvelope.decrypt(ByteArrayInputStream(truncated), password).use { stream ->
                assertTrue(stream.read(ByteArray(8192)) > 0)
                rejected { val buffer = ByteArray(128 * 1024); while (stream.read(buffer) >= 0) { } }
            }
        } finally { password.fill('\u0000') }
        rejected { decrypt(ciphertext.copyOf(12)) }
    }
    @Test fun headerIterationAndWrappedKeysetBoundsRejectBeforeLargeReads() {
        fun header(iterations: Int, size: Int) = ByteArrayOutputStream().apply { DataOutputStream(this).apply {
            write("OCABAK01".toByteArray()); writeInt(iterations); write(ByteArray(16)); writeInt(size)
        } }.toByteArray()
        for (size in listOf(-1, 0, 65537, Int.MAX_VALUE)) rejected { decrypt(header(600_000, size)) }
        rejected { decrypt(header(Int.MAX_VALUE, 1)) }
        val nested = ("[".repeat(1000) + "]".repeat(1000)).toByteArray()
        rejected { decrypt(header(600_000, nested.size) + nested) }
    }
    @Test fun authenticatedSenderCannotChooseMemoryExhaustingStreamingParameters() {
        // Register the supported template before constructing adversarial keysets.
        decrypt(encrypt(byteArrayOf(1)))
        for (segment in listOf(4096, 1 shl 30)) {
            val parameters = AesGcmHkdfStreamingParameters.builder().setKeySizeBytes(32).setDerivedAesGcmKeySizeBytes(32)
                .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256).setCiphertextSegmentSizeBytes(segment).build()
            val salt = ByteArray(16) { 7 }
            val prefix = ByteArrayOutputStream().apply { DataOutputStream(this).apply { write("OCABAK01".toByteArray()); writeInt(600_000); write(salt) } }.toByteArray()
            val password = password(); val specification = PBEKeySpec(password, salt, 600_000, 256)
            val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded } finally { specification.clearPassword(); password.fill('\u0000') }
            val wrapped = try { TinkJsonProtoKeysetFormat.serializeEncryptedKeyset(KeysetHandle.generateNew(parameters), AesGcmJce(key), prefix, RegistryConfiguration.get()).toByteArray() } finally { key.fill(0) }
            val forged = ByteArrayOutputStream().apply { write(prefix); DataOutputStream(this).apply { writeInt(wrapped.size); write(wrapped); write(0) } }.toByteArray()
            rejected { decrypt(forged) }
        }
    }
}
