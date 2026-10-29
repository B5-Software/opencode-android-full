package io.github.opencodeandroid.runtime.update

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.URI
import java.security.MessageDigest

internal data class VerifiedUpdateFeed(val version: String, val commit: String, val minimumHost: Long,
    val packageUrl: String, val packageSize: Long, val packageSha256: String, val manifestSha256: String,
    val binarySha256: String, val identity: String)

internal object UpdateHttpsUrl {
    fun parse(value: String): URI {
        check(value.length in 1..4096 && value.none { it <= ' ' || it == '\u007f' || it == '\\' }) { "请输入有效的 HTTPS 更新源" }
        val uri = URI(value)
        check(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.port in -1..65535 && uri.port != 0) { "更新源仅支持无用户凭据的 HTTPS 地址" }
        return uri
    }
}

/** Bounded framing authenticates the exact JSON bytes before parsing any server-controlled metadata. */
internal object SignedUpdateFeed {
    const val MAX_BYTES = 8 + 8 + 16384 + 8192
    const val MAX_PACKAGE = 512L * 1024 * 1024 + 65536
    fun inspect(bytes: ByteArray, verifier: SignedOpenCodeUpdate, hostCode: Long): VerifiedUpdateFeed {
        check(bytes.size in 18..MAX_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        check(ByteArray(8).also(input::readFully).contentEquals("OCAFEED1".toByteArray(Charsets.US_ASCII)))
        val bodySize = input.readInt(); val signatureSize = input.readInt()
        check(bodySize in 1..16384 && signatureSize in 1..8192 && bytes.size == 16 + bodySize + signatureSize)
        val body = ByteArray(bodySize).also(input::readFully); val signature = ByteArray(signatureSize).also(input::readFully)
        verifier.verifyPublisherMessage(body, signature)
        val json = UpdateJson.parse(body)
        fun integer(name: String, minimum: Long, maximum: Long): Long {
            val value = json.get(name); check(value is Int || value is Long)
            return (value as Number).toLong().also { check(it in minimum..maximum) }
        }
        fun string(name: String) = (json.get(name) as? String) ?: error("更新源字段无效")
        fun hash(name: String) = string(name).also { check(it.matches(Regex("[a-f0-9]{64}"))) }
        check(integer("format", 1, 1) == 1L && string("kind") == "opencode-pair-feed" && string("abi") == "arm64-v8a")
        val minimum = integer("minHostVersionCode", 1, 2_100_000_000); check(hostCode >= minimum) { "请先升级 Android Host" }
        val version = string("version").also { check(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,95}"))) }
        val commit = string("commit").also { check(it.matches(Regex("[a-f0-9]{40}"))) }
        val url = string("packageUrl"); UpdateHttpsUrl.parse(url)
        return VerifiedUpdateFeed(version, commit, minimum, url, integer("packageSize", 1, MAX_PACKAGE),
            hash("packageSha256"), hash("manifestSha256"), hash("binarySha256"),
            MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) })
    }
}
