package io.github.opencodeandroid.runtime

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.*
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LoopbackHttpTest {
    private data class Request(val path: String, val headers: Map<String, String>)
    private data class Reply(val code: Int, val body: String = "{}", val headers: Map<String, String> = emptyMap())
    private class Fixture(port: Int = 0, private val reply: (Request) -> Reply) : AutoCloseable {
        val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("127.0.0.1", port)) }
        val port get() = server.localPort
        val requests = AtomicInteger()
        private val closed = AtomicBoolean()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val worker = thread(isDaemon = true, name = "loopback-fixture") {
            while (!closed.get()) try {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val bytes = ByteArrayOutputStream()
                    while (true) {
                        val value = socket.getInputStream().read(); check(value >= 0 && bytes.size() < 8192)
                        bytes.write(value)
                        val data = bytes.toByteArray()
                        if (data.size >= 4 && data.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
                    }
                    val lines = bytes.toString("ISO-8859-1").split("\r\n")
                    val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').lowercase(Locale.ROOT) to it.substringAfter(':').trim() }
                    requests.incrementAndGet()
                    val response = reply(Request(lines.first().split(' ')[1], headers))
                    val body = response.body.toByteArray(Charsets.UTF_8)
                    val extra = response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                    val text = "HTTP/1.1 ${response.code} Fixture\r\nContent-Length: ${body.size}\r\nConnection: close\r\nContent-Type: application/json\r\n$extra\r\n"
                    socket.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1)); socket.getOutputStream().write(body)
                }
            } catch (failure: Throwable) { if (!closed.get()) failures.add(failure) }
        }
        override fun close() { closed.set(true); server.close(); worker.join(3000); check(!worker.isAlive); check(failures.isEmpty()) { "Loopback fixture failed" } }
    }

    private fun withProxy(test: (Fixture, AtomicInteger) -> Unit) = synchronized(ProxySelector::class.java) {
        Fixture { Reply(200) }.use { trap ->
            val old = ProxySelector.getDefault(); val selections = AtomicInteger()
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> { selections.incrementAndGet(); return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", trap.port))) }
                override fun connectFailed(uri: URI, address: SocketAddress, failure: java.io.IOException) = Unit
            })
            try {
                // Non-secret control proves this selector can actually route a request into the trap.
                val control = URL("http://127.0.0.1:49374/proxy-control").openConnection() as HttpURLConnection
                try { control.connectTimeout = 1000; control.readTimeout = 1000; assertEquals(200, control.responseCode); control.inputStream.close() }
                finally { control.disconnect() }
                assertEquals(1, trap.requests.get()); assertTrue(selections.get() > 0)
                test(trap, selections)
            } finally { ProxySelector.setDefault(old) }
        }
    }

    @Test fun authenticatedApiAndAnonymousCheckBypassAWorkingProxySelector() = withProxy { trap, selections ->
        val before = selections.get()
        val password = "fixture-loopback-private"
        val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
        val authenticated = AtomicInteger(); val anonymous = AtomicInteger()
        Fixture { request ->
            assertEquals("/api/info", request.path)
            if (request.headers["authorization"] == basic) { authenticated.incrementAndGet(); Reply(200, "{\"version\":\"fixture-version\"}") }
            else { anonymous.incrementAndGet(); Reply(401) }
        }.use { local -> assertTrue(LocalOpenCode(password, local.port).ready("fixture-version")) }
        assertEquals(1, authenticated.get()); assertEquals(1, anonymous.get())
        assertEquals(before, selections.get()); assertEquals(1, trap.requests.get())
    }

    @Test fun pairingAndBrowserCookieStayDirectAndOneUse() = withProxy { trap, selections ->
        val before = selections.get(); val paired = AtomicBoolean()
        val cookie = "opencode_session_49374=fixture-browser-cookie"
        val code = "fixture-native-pair-code-123456"
        Fixture(LocalOpenCode.PORT) { request ->
            when (request.path) {
                "/auth/connect/$code" -> if (paired.compareAndSet(false, true)) Reply(302, headers = mapOf("Location" to "/", "Set-Cookie" to "$cookie; HttpOnly; SameSite=Strict; Path=/")) else Reply(401)
                "/api/info" -> { assertNull(request.headers["authorization"]); Reply(if (request.headers["cookie"] == cookie) 200 else 401) }
                else -> Reply(404)
            }
        }.use {
            val url = LocalOpenCode.ORIGIN + "/auth/connect/$code"
            assertTrue(checkNotNull(LocalOpenCode.browserCookie(url)).startsWith(cookie))
            assertTrue(LocalOpenCode.browserReady(cookie))
            assertNull(LocalOpenCode.browserCookie(url))
            assertFalse(LocalOpenCode.browserReady("opencode_session_49374=invalid-fixture"))
        }
        assertEquals(before, selections.get()); assertEquals(1, trap.requests.get())
    }

    @Test fun authenticatedApiDoesNotFollowRedirectToAnotherEndpoint() = withProxy { trap, selections ->
        val before = selections.get()
        Fixture { Reply(302, headers = mapOf("Location" to "http://127.0.0.1:${trap.port}/redirect-trap")) }.use { local ->
            assertFalse(LocalOpenCode("fixture-password", local.port).ready("fixture-version"))
        }
        assertEquals(before, selections.get()); assertEquals(1, trap.requests.get())
    }

    @Test fun nonLocalOrAmbiguousEndpointsAreRejectedBeforeProxySelection() = withProxy { trap, selections ->
        val before = selections.get()
        for (url in listOf("https://127.0.0.1:49374/", "http://localhost:49374/", "http://127.0.0.2:49374/", "http://example.invalid:49374/",
            "http://user:password@127.0.0.1:49374/", "http://127.0.0.1:49374/#fragment", "http://127.0.0.1:80/")) {
            assertTrue(runCatching { LoopbackHttp.open(URL(url)) }.isFailure)
        }
        assertEquals(before, selections.get()); assertEquals(1, trap.requests.get())
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("stream", "LOOPBACK_PROXY rejectedNonLocal=true controlProxyVerified=true\n") })
    }
}
