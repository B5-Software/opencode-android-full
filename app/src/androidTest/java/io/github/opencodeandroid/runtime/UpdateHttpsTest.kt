package io.github.opencodeandroid.runtime

import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.update.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.URI
import java.net.URL
import java.security.*
import java.security.cert.Certificate
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.zip.*
import javax.net.ssl.*

/** All packages/state are isolated fixtures. Downloaded fixture bytes are never executed. */
class UpdateHttpsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val key by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private val verifier get() = SignedOpenCodeUpdate(listOf(key.public), 1)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun signed(body: ByteArray, pair: KeyPair = key): ByteArray {
        val sig = Signature.getInstance(if (pair.private.algorithm == "EC") "SHA256withECDSA" else "SHA256withRSA").run { initSign(pair.private); update(body); sign() }
        return ByteArrayOutputStream().apply { DataOutputStream(this).run { writeBytes("OCAFEED1"); writeInt(body.size); writeInt(sig.size); write(body); write(sig) } }.toByteArray()
    }
    private data class Fixture(val packageBytes: ByteArray, val feed: JSONObject)
    private fun fixture(): Fixture {
        val binary = ByteArray(16384) { (it % 251).toByte() }
        val payload = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(binary) } }.toByteArray()
        val manifest = JSONObject().put("format", 1).put("kind", "opencode-pair").put("abi", "arm64-v8a")
            .put("repository", "https://github.com/anomalyco/opencode.git").put("version", "2.1.4-download-fixture")
            .put("commit", "a".repeat(40)).put("minHostVersionCode", 1)
            .put("binary", JSONObject().put("size", binary.size).put("sha256", hash(binary)))
            .put("archive", JSONObject().put("size", payload.size).put("sha256", hash(payload))).toString().toByteArray()
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(manifest); sign() }
        val packageBytes = ByteArrayOutputStream().apply { ZipOutputStream(this).use { zip ->
            for ((name, bytes) in listOf("manifest.json" to manifest, "manifest.sig" to sig, "opencode.pack" to payload)) {
                zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value })
                zip.write(bytes); zip.closeEntry()
            }
        } }.toByteArray()
        val feed = JSONObject().put("format", 1).put("kind", "opencode-pair-feed").put("abi", "arm64-v8a")
            .put("version", "2.1.4-download-fixture").put("commit", "a".repeat(40)).put("minHostVersionCode", 1)
            .put("packageUrl", "https://packages.example.test/update.ocaupdate").put("packageSize", packageBytes.size)
            .put("packageSha256", hash(packageBytes)).put("manifestSha256", hash(manifest)).put("binarySha256", hash(binary))
        return Fixture(packageBytes, feed)
    }
    private fun inspect(feed: JSONObject) = SignedUpdateFeed.inspect(signed(feed.toString().toByteArray()), verifier, 1)
    private fun rejected(action: () -> Unit) { try { action(); fail("Unsafe update was accepted") } catch (_: Exception) { } }
    private fun owned(action: (File) -> Unit) {
        val directory = File(context.cacheDir, "update-https-${UUID.randomUUID()}").apply { check(mkdir()) }
        try { action(directory) } finally { check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile); directory.deleteRecursively() }
    }
    private class Response(url: URI, private val status: Int = 200, private val bytes: ByteArray = byteArrayOf(),
        private val headers: Map<String, String> = emptyMap()) : HttpsURLConnection(url.toURL()) {
        var disconnected = false
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getHeaderField(name: String?): String? = headers[name]
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun publisherAnchorFramingAndEcSignatures() {
        val body = fixture().feed.toString().toByteArray(); val bytes = signed(body)
        assertEquals("2.1.4-download-fixture", SignedUpdateFeed.inspect(bytes, verifier, 1).version)
        rejected { SignedUpdateFeed.inspect(bytes.copyOf(bytes.size - 1), verifier, 1) }
        rejected { SignedUpdateFeed.inspect(bytes + byteArrayOf(0), verifier, 1) }
        rejected { SignedUpdateFeed.inspect(bytes.clone().also { it[16] = (it[16].toInt() xor 1).toByte() }, verifier, 1) }
        rejected { SignedUpdateFeed.inspect(bytes.clone().also { it[8] = 0x7f }, verifier, 1) }
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val ecVerifier = SignedOpenCodeUpdate(listOf(ec.public), 1)
        assertEquals(hash(body), SignedUpdateFeed.inspect(signed(body, ec), ecVerifier, 1).identity)
        rejected { SignedUpdateFeed.inspect(bytes, ecVerifier, 1) }
    }
    @Test fun authenticatedMetadataStillMustMeetPolicy() {
        for ((name, value) in listOf("packageUrl" to "http://example.test/u", "packageUrl" to "https://user:pass@example.test/u",
            "packageSize" to 0, "packageSize" to (SignedUpdateFeed.MAX_PACKAGE + 1), "packageSize" to "10",
            "minHostVersionCode" to 2, "abi" to "x86_64", "commit" to "a".repeat(39), "version" to "<script>")) {
            rejected { inspect(fixture().feed.put(name, value)) }
        }
        rejected { SignedUpdateFeed.inspect(signed(("{".repeat(100) + "}".repeat(100)).toByteArray()), verifier, 1) }
        for (url in listOf("file:///tmp/update", "https://x.test/#fragment", "https://x.test:0/u", "https://x.test/\n")) rejected { UpdateHttpsUrl.parse(url) }
    }
    @Test fun redirectDownloadBindsCatalogPackageAndProof() = owned { directory ->
        val fixture = fixture(); val feedBytes = signed(fixture.feed.toString().toByteArray())
        val responses = mutableListOf<Response>()
        UpdateHttpsClient(connect = { uri ->
            val response = when (uri.path) {
                "/stable" -> Response(uri, 302, headers = mapOf("Location" to "/signed"))
                "/signed" -> Response(uri, bytes = feedBytes)
                else -> Response(uri, bytes = fixture.packageBytes, headers = mapOf("Content-Length" to fixture.packageBytes.size.toString()))
            }; responses += response; response
        }).use { client ->
            val feed = client.feed("https://updates.example.test/stable", verifier, 1)
            val target = File(directory, "download.ocaupdate")
            assertEquals(feed.manifestSha256, client.download(feed, target, verifier).metadata.manifestSha256)
            assertArrayEquals(fixture.packageBytes, target.readBytes())
            assertEquals(384, android.system.Os.stat(target.path).st_mode and 511)
        }
        assertEquals(3, responses.size); assertTrue(responses.all { it.disconnected && !it.instanceFollowRedirects && !it.useCaches })
    }
    @Test fun rejectsDowngradeLoopsEncodingAndBadLengths() {
        for (headers in listOf(mapOf("Location" to "http://evil.test/u"), mapOf("Location" to "https://user:pw@evil.test/u"), mapOf("Location" to "/again"))) {
            var count = 0
            UpdateHttpsClient(connect = { count++; Response(it, 302, headers = headers) }).use { client ->
                rejected { client.feed("https://updates.example.test/stable", verifier, 1) }
            }
            assertTrue(count in 1..6)
        }
        for (headers in listOf(mapOf("Content-Encoding" to "gzip"), mapOf("Content-Length" to "99999999"), mapOf("Content-Length" to "-1"), mapOf("Content-Length" to "5"))) {
            UpdateHttpsClient(connect = { Response(it, bytes = byteArrayOf(1), headers = headers) }).use { rejected { it.feed("https://x.test/u", verifier, 1) } }
        }
        UpdateHttpsClient(connect = { Response(it, 206, byteArrayOf(1)) }).use { rejected { it.feed("https://x.test/u", verifier, 1) } }
    }
    @Test fun failedAndCancelledDownloadsRemoveOnlyOwnedPartial() = owned { directory ->
        val fixture = fixture(); val feed = inspect(fixture.feed)
        for (bytes in listOf(fixture.packageBytes.copyOf(20), fixture.packageBytes + byteArrayOf(1), fixture.packageBytes.clone().also { it[100] = (it[100].toInt() xor 1).toByte() })) {
            val destination = File(directory, "partial.ocaupdate")
            UpdateHttpsClient(connect = { Response(it, bytes = bytes) }).use { rejected { it.download(feed, destination, verifier) } }
            assertFalse(destination.exists())
        }
        val destination = File(directory, "cancelled.ocaupdate")
        UpdateHttpsClient(connect = { Response(it, bytes = fixture.packageBytes) }, progress = { _, _ -> throw CancellationException() }).use {
            rejected { it.download(feed, destination, verifier) }
        }
        assertFalse(destination.exists())
        val existing = File(directory, "preserve.ocaupdate").apply { writeText("previous verified candidate") }
        UpdateHttpsClient().use { rejected { it.download(feed, existing, verifier) } }
        assertEquals("previous verified candidate", existing.readText())
    }
    @Test fun validPackageCannotReplaceDifferentAdvertisedProof() = owned { directory ->
        val fixture = fixture(); val feed = inspect(fixture.feed.put("manifestSha256", "0".repeat(64)))
        val destination = File(directory, "mismatch.ocaupdate")
        UpdateHttpsClient(connect = { Response(it, bytes = fixture.packageBytes) }).use { rejected { it.download(feed, destination, verifier) } }
        assertFalse(destination.exists())
    }

    private fun tlsFixture(): SSLContext {
        val store = KeyStore.getInstance("PKCS12")
        InstrumentationRegistry.getInstrumentation().context.assets.open("update-tls-fixture.p12").use { store.load(it, "fixture-only".toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "fixture-only".toCharArray()) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, SecureRandom()) }
    }
    private fun realTls(trustFixture: Boolean) {
        val tls = tlsFixture(); val response = signed(fixture().feed.toString().toByteArray())
        val server = tls.serverSocketFactory.createServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val headers = FutureTask<List<String>> {
            (server.accept() as SSLSocket).use { socket ->
                socket.soTimeout = 5000
                val reader = socket.inputStream.bufferedReader(Charsets.ISO_8859_1); val lines = mutableListOf<String>()
                repeat(100) { val line = reader.readLine() ?: error("No request"); if (line.isEmpty()) {
                    socket.outputStream.apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush() }
                    return@FutureTask lines
                }; check(line.length <= 8192); lines += line }
                error("Too many headers")
            }
        }
        val thread = Thread(headers, "isolated-update-tls-fixture").apply { isDaemon = true; start() }
        try {
            val url = "https://127.0.0.1:${server.localPort}/stable"
            if (trustFixture) {
                UpdateHttpsClient(connect = { it.toURL().openConnection().let { connection ->
                    (connection as HttpsURLConnection).apply { sslSocketFactory = tls.socketFactory }
                } }).use { assertEquals("2.1.4-download-fixture", it.feed(url, verifier, 1).version) }
                assertTrue(headers.get(10, TimeUnit.SECONDS).none { it.startsWith("Cookie:", true) || it.startsWith("Authorization:", true) || it.startsWith("Referer:", true) })
            } else {
                var rejectedTls = false
                try { UpdateHttpsClient().use { it.feed(url, verifier, 1) } } catch (_: SSLHandshakeException) { rejectedTls = true }
                assertTrue("Default platform trust must reject the fixture CA", rejectedTls)
            }
        } finally { server.close(); thread.join(10000); assertFalse(thread.isAlive) }
    }
    @Test fun realTlsTransfersWithoutBrowserCredentials() { realTls(true) }
    @Test fun defaultTrustRejectsUnknownCertificateAuthority() { realTls(false) }
}
