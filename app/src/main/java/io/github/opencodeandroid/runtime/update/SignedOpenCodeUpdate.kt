package io.github.opencodeandroid.runtime.update

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal data class VerifiedOpenCodeUpdate(
    val version: String, val commit: String, val minimumHost: Long,
    val binarySize: Long, val binarySha256: String, val archiveSize: Long, val archiveSha256: String,
    val manifestSha256: String,
)
internal data class OpenCodeUpdateProof(val manifest: ByteArray, val signature: ByteArray)

/** Reads only authenticated bounded entries. Installation and state replacement are separate. */
internal class SignedOpenCodeUpdate internal constructor(private val keys: List<PublicKey>, private val hostCode: Long) {
    constructor(context: Context) : this(apkKeys(context).also { check("arm64-v8a" in android.os.Build.SUPPORTED_ABIS) { "独立运行包仅支持 ARM64" } }, packageInfo(context).longVersionCode)

    fun inspect(file: File, checkpoint: () -> Unit = {}): VerifiedOpenCodeUpdate = process(file) { input, manifest ->
        val verified = CheckedArchive(input, manifest.archiveSize, manifest.archiveSha256)
        val buffer = ByteArray(128 * 1024)
        while (true) { checkpoint(); if (verified.read(buffer) == -1) break }
        verified.finish()
    }
    fun proof(file: File): OpenCodeUpdateProof = ZipInputStream(file.inputStream().buffered()).use { zip ->
        entry(zip, "manifest.json", 16 * 1024L); val manifest = smallEntry(zip, 16 * 1024)
        entry(zip, "manifest.sig", 8 * 1024L); val signature = smallEntry(zip, 8 * 1024)
        verifyProof(OpenCodeUpdateProof(manifest, signature))
        OpenCodeUpdateProof(manifest, signature)
    }
    fun verifyProof(proof: OpenCodeUpdateProof): VerifiedOpenCodeUpdate {
        verifyPublisherMessage(proof.manifest, proof.signature)
        return parse(UpdateJson.parse(proof.manifest), MessageDigest.getInstance("SHA-256").digest(proof.manifest).hex())
    }
    fun verifyPublisherMessage(message: ByteArray, signature: ByteArray) {
        check(message.size in 1..16384 && signature.size in 1..8192)
        check(keys.size == 1 && verify(keys.single(), message, signature)) { "更新签名不属于当前 APK" }
    }

    /** Destination must be an unpublished staging stream, never the active executable. */
    fun extract(file: File, expectedManifest: String, output: OutputStream, onProgress: (Long) -> Unit = {}): VerifiedOpenCodeUpdate = process(file) { input, manifest ->
        require(manifest.manifestSha256 == expectedManifest) { "更新包在确认后发生变化" }
        val compressed = CheckedArchive(input, manifest.archiveSize, manifest.archiveSha256)
        val binaryHash = MessageDigest.getInstance("SHA-256")
        val header = ByteArrayOutputStream()
        var total = 0L
        val buffer = ByteArray(128 * 1024)
        GZIPInputStream(compressed).use { gzip ->
            while (true) {
                val read = gzip.read(buffer)
                if (read < 0) break
                check(total + read <= manifest.binarySize) { "更新文件超过声明大小" }
                if (header.size() < MAX_ELF_HEADER) header.write(buffer, 0, minOf(read, MAX_ELF_HEADER - header.size()))
                binaryHash.update(buffer, 0, read); output.write(buffer, 0, read)
                total += read; onProgress(total)
            }
        }
        // The non-closing wrapper keeps the ZIP positioned for the final entry/CRC checks.
        while (compressed.read(buffer) != -1) { }
        compressed.finish()
        check(total == manifest.binarySize && binaryHash.digest().hex() == manifest.binarySha256) { "更新程序摘要不匹配" }
        validateElf(header.toByteArray(), total)
    }

    private fun process(file: File, payload: (InputStream, VerifiedOpenCodeUpdate) -> Unit): VerifiedOpenCodeUpdate {
        check(file.length() in 1..MAX_PACKAGE) { "更新包大小无效" }
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            entry(zip, "manifest.json", 16 * 1024L)
            val manifestBytes = smallEntry(zip, 16 * 1024)
            entry(zip, "manifest.sig", 8 * 1024L)
            val signature = smallEntry(zip, 8 * 1024)
            verifyPublisherMessage(manifestBytes, signature)
            val manifest = parse(UpdateJson.parse(manifestBytes), MessageDigest.getInstance("SHA-256").digest(manifestBytes).hex())
            val archive = entry(zip, "opencode.pack", MAX_ARCHIVE)
            check(archive.size == manifest.archiveSize) { "更新包压缩大小不匹配" }
            payload(zip, manifest)
            check(zip.read() == -1 && zip.nextEntry == null) { "更新包包含额外数据项" }
            return manifest
        }
    }

    private fun parse(json: JSONObject, sha: String): VerifiedOpenCodeUpdate {
        check(integer(json, "format", 1, 1) == 1L && json.getString("kind") == "opencode-pair")
        check(json.getString("abi") == "arm64-v8a" && json.getString("repository") == "https://github.com/anomalyco/opencode.git") { "更新来源或架构不兼容" }
        val version = json.getString("version").also { check(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,95}"))) }
        val commit = json.getString("commit").also { check(it.matches(Regex("[a-f0-9]{40}"))) }
        val minimum = integer(json, "minHostVersionCode", 1, 2_100_000_000)
        check(hostCode >= minimum) { "此更新需要先升级 Android 应用" }
        val binary = json.getJSONObject("binary"); val archive = json.getJSONObject("archive")
        return VerifiedOpenCodeUpdate(version, commit, minimum, integer(binary, "size", 64, MAX_BINARY), digest(binary), integer(archive, "size", 1, MAX_ARCHIVE), digest(archive), sha)
    }

    private class CheckedArchive(input: InputStream, private val expected: Long, private val sha: String) : FilterInputStream(input) {
        private val hash = MessageDigest.getInstance("SHA-256")
        private var count = 0L
        override fun read(): Int = `in`.read().also { if (it >= 0) { count++; check(count <= expected); hash.update(it.toByte()) } }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also { if (it > 0) { count += it; check(count <= expected); hash.update(buffer, offset, it) } }
        override fun close() { /* ZIP owner closes the entry and package. */ }
        fun finish() { check(count == expected && hash.digest().hex() == sha) { "更新压缩包摘要不匹配" } }
    }

    companion object {
        private const val MAX_ARCHIVE = 512L * 1024 * 1024
        private const val MAX_BINARY = 1024L * 1024 * 1024
        private const val MAX_PACKAGE = MAX_ARCHIVE + 64 * 1024
        private const val MAX_ELF_HEADER = 1024 * 1024
        @Suppress("DEPRECATION")
        private fun packageInfo(context: Context) = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        private fun apkKeys(context: Context): List<PublicKey> {
            val signers = packageInfo(context).signingInfo?.apkContentsSigners.orEmpty()
            check(signers.size == 1) { "当前 APK 的签名形式不支持独立运行包更新" }
            val certificates = CertificateFactory.getInstance("X.509")
            return signers.map { certificates.generateCertificate(it.toByteArray().inputStream()).publicKey }
        }
        private fun verify(key: PublicKey, bytes: ByteArray, signature: ByteArray): Boolean = runCatching {
            val algorithm = when (key.algorithm) { "RSA" -> "SHA256withRSA"; "EC" -> "SHA256withECDSA"; else -> return false }
            Signature.getInstance(algorithm).run { initVerify(key); update(bytes); verify(signature) }
        }.getOrDefault(false)
        private fun entry(zip: ZipInputStream, name: String, maximum: Long): ZipEntry = requireNotNull(zip.nextEntry).also {
            check(it.name == name && !it.isDirectory && it.method == ZipEntry.STORED && it.size in 1..maximum && it.compressedSize == it.size) { "更新包数据项无效" }
        }
        private fun smallEntry(zip: ZipInputStream, maximum: Int): ByteArray {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                check(result.size() + read <= maximum)
                result.write(buffer, 0, read)
            }
            return result.toByteArray()
        }
        private fun integer(json: JSONObject, name: String, minimum: Long, maximum: Long): Long {
            val value = json.get(name)
            check(value is Int || value is Long) { "更新元数据必须使用整数" }
            return (value as Number).toLong().also { check(it in minimum..maximum) }
        }
        private fun digest(json: JSONObject) = json.getString("sha256").also { check(it.matches(Regex("[a-f0-9]{64}"))) }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun validateElf(header: ByteArray, size: Long) {
            check(header.size >= 64 && header.copyOfRange(0, 7).contentEquals(byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1))) { "更新程序不是 64 位 Linux ELF" }
            val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            fun u16(offset: Int) = data.getShort(offset).toInt() and 0xffff
            check(u16(16) in 2..3 && u16(18) == 183 && u16(52) == 64 && u16(54) == 56)
            val start = data.getLong(32); val count = u16(56)
            check(count in 1..256 && start in 64..(header.size.toLong() - count * 56L))
            var loads = 0; var interpreter = false
            repeat(count) { index ->
                val offset = (start + index * 56L).toInt()
                val type = data.getInt(offset); val fileOffset = data.getLong(offset + 8)
                val virtual = data.getLong(offset + 16); val fileSize = data.getLong(offset + 32)
                val memory = data.getLong(offset + 40); val alignment = data.getLong(offset + 48)
                if (type == 1 || type == 3) check(fileOffset in 0..size && fileSize in 0..(size - fileOffset))
                if (type == 1) {
                    check(alignment >= 16384 && (alignment and (alignment - 1)) == 0L && virtual >= 0 && virtual % alignment == fileOffset % alignment && memory >= fileSize) { "更新程序不满足 16 KiB ELF 对齐" }
                    loads++
                }
                if (type == 3) {
                    check(fileSize in 1..128 && fileOffset + fileSize <= header.size)
                    val value = header.copyOfRange(fileOffset.toInt(), (fileOffset + fileSize).toInt())
                    check(value.last() == 0.toByte() && value.toString(Charsets.US_ASCII).trimEnd('\u0000') == "/lib/ld-linux-aarch64.so.1")
                    interpreter = true
                }
            }
            check(loads > 0 && interpreter) { "更新程序缺少 Debian glibc 加载器" }
        }
    }
}
