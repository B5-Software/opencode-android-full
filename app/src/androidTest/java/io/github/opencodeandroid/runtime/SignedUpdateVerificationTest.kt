package io.github.opencodeandroid.runtime

import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.update.SignedOpenCodeUpdate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic ELF tests authenticate and stage bytes; they never install or execute a runtime. */
class SignedUpdateVerificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val key: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private fun verifier() = SignedOpenCodeUpdate(listOf(key.public), 1)
    private fun binary(architecture: Int = 183, alignment: Long = 16384): ByteArray {
        val bytes = ByteArray(16384)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put(byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1)); data.putShort(16, 3); data.putShort(18, architecture.toShort())
        data.putLong(32, 64); data.putShort(52, 64); data.putShort(54, 56); data.putShort(56, 2)
        data.putInt(64, 1); data.putLong(64 + 32, bytes.size.toLong()); data.putLong(64 + 40, bytes.size.toLong()); data.putLong(64 + 48, alignment)
        val interpreter = "/lib/ld-linux-aarch64.so.1\u0000".toByteArray()
        data.putInt(120, 3); data.putLong(128, 256); data.putLong(152, interpreter.size.toLong())
        interpreter.copyInto(bytes, 256)
        return bytes
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun packed(bytes: ByteArray) = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(bytes) } }.toByteArray()
    private fun metadata(binary: ByteArray, packed: ByteArray) = JSONObject().put("format", 1).put("kind", "opencode-pair").put("abi", "arm64-v8a")
        .put("repository", "https://github.com/anomalyco/opencode.git").put("version", "2.1.4-fixture").put("commit", "a".repeat(40)).put("minHostVersionCode", 1)
        .put("binary", JSONObject().put("size", binary.size).put("sha256", hash(binary)))
        .put("archive", JSONObject().put("size", packed.size).put("sha256", hash(packed)))
    private fun archive(directory: File, binary: ByteArray = binary(), signingKey: KeyPair = key, change: (JSONObject) -> Unit = {}, signedText: (String) -> String = { it }, corruptMetadata: Boolean = false, corruptPayload: Boolean = false, extra: Boolean = false, deflated: Boolean = false): File {
        val packed = packed(binary)
        val json = signedText(metadata(binary, packed).also(change).toString()).toByteArray()
        val signature = Signature.getInstance(if (signingKey.private.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA").run { initSign(signingKey.private); update(json); sign() }
        if (corruptMetadata) json[json.size - 2] = (json[json.size - 2].toInt() xor 1).toByte()
        if (corruptPayload) packed[packed.lastIndex] = (packed.last().toInt() xor 1).toByte()
        return File(directory, "${UUID.randomUUID()}.ocaupdate").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                for ((name, bytes) in listOf("manifest.json" to json, "manifest.sig" to signature, "opencode.pack" to packed) + if (extra) listOf("../unexpected" to byteArrayOf(1)) else emptyList()) {
                    zip.putNextEntry(ZipEntry(name).apply { method = if (deflated) ZipEntry.DEFLATED else ZipEntry.STORED; if (!deflated) { size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value } })
                    zip.write(bytes); zip.closeEntry()
                }
            }
        }
    }
    private fun fixture(action: (File) -> Unit) {
        val directory = File(context.cacheDir, "signed-update-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { action(directory) } finally { check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile); directory.deleteRecursively() }
    }
    private fun rejected(action: () -> Unit) { try { action(); fail("Invalid update was accepted") } catch (failure: Exception) { assertTrue(failure is IllegalStateException || failure is IllegalArgumentException || failure is java.io.IOException || failure is org.json.JSONException) } }
    @Test fun authenticatedFixtureStagesExactElfAndBindsTheConfirmation() = fixture { directory ->
        val binary = binary(); val file = archive(directory, binary)
        val manifest = verifier().inspect(file)
        val output = ByteArrayOutputStream()
        assertEquals(manifest, verifier().extract(file, manifest.manifestSha256, output))
        assertArrayEquals(binary, output.toByteArray())
        rejected { verifier().extract(file, "b".repeat(64), ByteArrayOutputStream()) }
    }
    @Test fun wrongPublisherAndModifiedManifestCannotPassApkTrust() = fixture { directory ->
        val file = archive(directory)
        rejected { SignedOpenCodeUpdate(context).inspect(file) }
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        rejected { SignedOpenCodeUpdate(listOf(other.public), 1).inspect(file) }
        rejected { verifier().inspect(archive(directory, corruptMetadata = true)) }
    }
    @Test fun ecPublisherSignaturesUseTheSameBoundedFormat() = fixture { directory ->
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val verifier = SignedOpenCodeUpdate(listOf(ec.public), 1)
        val file = archive(directory, signingKey = ec); val metadata = verifier.inspect(file)
        val output = ByteArrayOutputStream()
        verifier.extract(file, metadata.manifestSha256, output)
        assertArrayEquals(binary(), output.toByteArray())
    }
    @Test fun corruptTruncatedExtraAndCompressedZipEntriesAreRejected() = fixture { directory ->
        rejected { verifier().inspect(archive(directory, corruptPayload = true)) }
        rejected { verifier().inspect(archive(directory, extra = true)) }
        rejected { verifier().inspect(archive(directory, deflated = true)) }
        val truncated = archive(directory).apply { val bytes = readBytes(); writeBytes(bytes.copyOf(bytes.size / 2)) }
        rejected { verifier().inspect(truncated) }
    }
    @Test fun hostCompatibilityAndIntegerTypesAreCheckedAfterSignature() = fixture { directory ->
        for (value in listOf<Any>(2, "1", 1.5, true)) rejected { verifier().inspect(archive(directory, change = { it.put("minHostVersionCode", value) })) }
        // JSONObject serializes Double(1.0) as integer 1, so supply the lexical decimal explicitly.
        rejected { verifier().inspect(archive(directory, signedText = { it.replace("\"minHostVersionCode\":1", "\"minHostVersionCode\":1.0") })) }
        rejected { verifier().inspect(archive(directory, change = { it.put("abi", "x86_64") })) }
    }
    @Test fun wrongArchitectureAlignmentAndExpansionBeyondSignedSizeNeverActivate() = fixture { directory ->
        for (bytes in listOf(binary(62), binary(alignment = 4096), binary().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(32, Long.MAX_VALUE) })) {
            val file = archive(directory, bytes); val metadata = verifier().inspect(file)
            rejected { verifier().extract(file, metadata.manifestSha256, ByteArrayOutputStream()) }
        }
        val file = archive(directory, change = { it.getJSONObject("binary").put("size", 64) })
        val metadata = verifier().inspect(file); val output = ByteArrayOutputStream()
        rejected { verifier().extract(file, metadata.manifestSha256, output) }
        assertTrue(output.size() <= 64)
    }
}
