package io.github.opencodeandroid.runtime.update

import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Platform TLS/hostname checks remain enabled. No WebView cookies, auth headers or executable activation. */
internal class UpdateHttpsClient(
    private val connect: (URI) -> HttpsURLConnection = { it.toURL().openConnection() as HttpsURLConnection },
    private val checkpoint: () -> Unit = {},
    private val progress: (Long, Long?) -> Unit = { _, _ -> },
) : AutoCloseable {
    @Volatile private var closed = false
    @Volatile private var active: HttpsURLConnection? = null
    @Volatile private var input: InputStream? = null
    private fun checkActive() { check(!closed) { "下载已取消" }; checkpoint() }
    private fun transfer(address: String, maximum: Long, exact: Long?, output: OutputStream): String {
        var uri = UpdateHttpsUrl.parse(address)
        val hash = MessageDigest.getInstance("SHA-256")
        repeat(6) { redirect ->
            checkActive(); val connection = connect(uri); active = connection
            try {
                check(connection.url.toURI() == uri && connection.url.protocol == "https")
                checkActive(); connection.instanceFollowRedirects = false; connection.useCaches = false
                connection.connectTimeout = 15000; connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/octet-stream")
                connection.setRequestProperty("Accept-Encoding", "identity")
                when (connection.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        check(redirect < 5) { "更新重定向次数过多" }
                        val location = checkNotNull(connection.getHeaderField("Location")); check(location.length <= 4096)
                        uri = UpdateHttpsUrl.parse(uri.resolve(location).toString())
                    }
                    200 -> {
                        val encoding = connection.getHeaderField("Content-Encoding")
                        check(encoding == null || encoding.equals("identity", true)) { "更新服务器未按原始字节传输" }
                        val rawLength = connection.getHeaderField("Content-Length")
                        val length = rawLength?.let { check(it.matches(Regex("[0-9]{1,18}"))); it.toLong().also { value -> check(value in 0..maximum) } }
                        if (exact != null && length != null) check(length == exact) { "更新文件长度不匹配" }
                        var total = 0L; val buffer = ByteArray(128 * 1024)
                        connection.inputStream.use { stream ->
                            input = stream
                            while (true) {
                                checkActive(); val count = stream.read(buffer); if (count < 0) break
                                check(total <= maximum - count && (exact == null || total <= exact - count)) { "更新文件超过声明大小" }
                                output.write(buffer, 0, count); hash.update(buffer, 0, count); total += count; progress(total, exact ?: length)
                            }
                        }
                        input = null; checkActive()
                        check((exact == null || total == exact) && (length == null || total == length)) { "更新文件未完整下载" }
                        return hash.digest().joinToString("") { "%02x".format(it) }
                    }
                    else -> error("更新服务器未返回完整文件")
                }
            } finally { input = null; connection.disconnect(); if (active === connection) active = null }
        }
        error("更新重定向次数过多")
    }
    fun feed(url: String, verifier: SignedOpenCodeUpdate, hostCode: Long): VerifiedUpdateFeed {
        val bytes = ByteArrayOutputStream()
        transfer(url, SignedUpdateFeed.MAX_BYTES.toLong(), null, bytes)
        return SignedUpdateFeed.inspect(bytes.toByteArray(), verifier, hostCode)
    }
    fun download(feed: VerifiedUpdateFeed, destination: File, verifier: SignedOpenCodeUpdate): OpenCodeUpdateCandidate {
        check(!UpdateFiles.exists(destination) && destination.parentFile!!.usableSpace > feed.packageSize + 64L * 1024 * 1024) { "更新暂存空间不足" }
        var created = false
        try {
            val fd = Os.open(destination.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
            created = true
            try { FileOutputStream(fd).use { output ->
                check(transfer(feed.packageUrl, feed.packageSize, feed.packageSize, output) == feed.packageSha256) { "更新文件 SHA-256 不匹配" }
                output.fd.sync()
            } } finally { Os.close(fd) }
            checkActive()
            val metadata = verifier.inspect(destination, ::checkActive)
            check(metadata.manifestSha256 == feed.manifestSha256 && metadata.binarySha256 == feed.binarySha256 &&
                metadata.version == feed.version && metadata.commit == feed.commit && metadata.minimumHost == feed.minimumHost) { "更新包与已确认的发布信息不一致" }
            return OpenCodeUpdateCandidate(destination, metadata)
        } catch (failure: Throwable) { if (created) destination.delete(); throw failure }
    }
    override fun close() { closed = true; runCatching { input?.close() }; active?.disconnect() }
}
