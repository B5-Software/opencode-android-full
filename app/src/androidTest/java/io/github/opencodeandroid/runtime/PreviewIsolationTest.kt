package io.github.opencodeandroid.runtime

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.Parcel
import android.os.ResultReceiver
import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.preview.PreviewActivity
import io.github.opencodeandroid.preview.PreviewTarget
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PreviewIsolationTest {
    @Test fun realPreviewRequestHasItsOwnCookiesAndNoCoreCredentials() = verifyPreview(guest = false)
    @Test fun realDebianProjectServerReceivesIsolatedPreviewRequests() = verifyPreview(guest = true)

    private fun verifyPreview(guest: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "core_probe_" + UUID.randomUUID().toString().replace("-", "")
        val seed = "preview_probe_" + UUID.randomUUID().toString().replace("-", "")
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setCookie(LocalOpenCode.ORIGIN, "$name=core-fixture; Path=/; HttpOnly; SameSite=Lax") { ready.countDown() }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        try {
            (if (guest) DebianServer() else EchoServer()).use { server ->
                val native = CountDownLatch(1); val page = CountDownLatch(1)
                val reports = ConcurrentHashMap<String, Bundle>()
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(code: Int, bundle: Bundle?) {
                        if (bundle == null) return
                        val phase = bundle.getString("phase") ?: return
                        reports[phase] = bundle
                        when (phase) { "native" -> native.countDown(); "page" -> page.countDown() }
                    }
                }
                // Send a framework ResultReceiver containing the callback Binder, rather than
                // asking the remote app process to deserialize a class in the test APK.
                val parcel = Parcel.obtain()
                val remoteReceiver = try {
                    receiver.writeToParcel(parcel, 0); parcel.setDataPosition(0)
                    ResultReceiver.CREATOR.createFromParcel(parcel)
                } finally { parcel.recycle() }
                val intent = PreviewActivity.intent(context, server.port).setClassName(context, "io.github.opencodeandroid.preview.PreviewValidationActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("preview-probe-receiver", remoteReceiver)
                    .putExtra("preview-probe-cookie", name).putExtra("preview-probe-seed", seed)
                context.startActivity(intent)
                assertTrue("Preview process did not initialize its private profile", native.await(20, TimeUnit.SECONDS))
                assertNotEquals("Preview used the primary app process", Process.myPid(), reports.getValue("native").getInt("pid"))
                assertFalse("Core cookie reached the preview profile", reports.getValue("native").getBoolean("hasMainCookie"))
                assertTrue("Preview WebView did not finish the actual HTTP page", page.await(30, TimeUnit.SECONDS))
                val deadline = System.currentTimeMillis() + 10000
                while (server.requests.none { it.path == "/probe" } && System.currentTimeMillis() < deadline) Thread.sleep(100)
                val request = server.requests.firstOrNull { it.path == "/probe" } ?: error("Project JavaScript did not make its real request")
                assertTrue("Preview cookies were disabled instead of isolated", request.cookie.contains("$seed=preview-fixture"))
                assertFalse("Core authentication cookie was sent to project port", request.cookie.contains("$name="))
                assertTrue("Native Authorization leaked to preview", request.authorization.isEmpty())
                instrumentation.runOnMainSync {
                    val primary = CookieManager.getInstance().getCookie(LocalOpenCode.ORIGIN).orEmpty()
                    assertTrue("Primary cookie was cleared", primary.contains("$name=core-fixture"))
                    assertFalse("Preview cookies reached the primary profile", primary.contains("$seed="))
                }
                instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nPASS ${if (guest) "actual Debian/PRoot Python project server" else "native HTTP fixture"}: separate preview process, actual project JavaScript HTTP request with its own HttpOnly cookie, no core Cookie/Authorization leakage, primary profile preserved.\n") })
            }
        } finally {
            instrumentation.runOnMainSync { CookieManager.getInstance().setCookie(LocalOpenCode.ORIGIN, "$name=; Path=/; Max-Age=0") }
        }
    }

    private data class Request(val path: String, val cookie: String, val authorization: String)
    private interface PreviewServer : AutoCloseable { val port: Int; val requests: List<Request> }
    private class DebianServer : PreviewServer {
        private val paths = RuntimePaths(InstrumentationRegistry.getInstrumentation().targetContext).apply { prepare() }
        private val root = File(paths.config, "preview-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
        private val receipts = File(root, "requests.ndjson")
        private val process: NativeProcess
        private val reader: Thread
        override val port: Int
        override val requests: List<Request> get() = if (!receipts.isFile) emptyList() else receipts.readLines().mapNotNull {
            runCatching { JSONObject(it).let { value -> Request(value.getString("path"), value.getString("cookie"), value.getString("authorization")) } }.getOrNull()
        }
        init {
            File(root, "server.py").writeText("""
                import http.server, json, os, pathlib, sys
                os.umask(0o077)
                root = pathlib.Path(sys.argv[1])
                class Handler(http.server.BaseHTTPRequestHandler):
                    def log_message(self, *args): pass
                    def do_GET(self):
                        with (root / 'requests.ndjson').open('a') as receipt:
                            receipt.write(json.dumps({'path': self.path, 'cookie': self.headers.get('Cookie', ''), 'authorization': self.headers.get('Authorization', '')}) + '\n')
                        body = b"<!doctype html><meta name=viewport content='width=device-width'><title>Debian project</title><p>Real Debian project preview</p><script>fetch('/probe',{credentials:'include'});</script>"
                        self.send_response(200)
                        self.send_header('Content-Type', 'text/html; charset=utf-8')
                        self.send_header('Content-Length', str(len(body)))
                        self.send_header('Cache-Control', 'no-store')
                        self.end_headers()
                        self.wfile.write(body)
                while True:
                    server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
                    if server.server_port not in (49374, 5901, 6080): break
                    server.server_close()
                (root / 'ready').write_text(str(server.server_port))
                server.serve_forever()
            """.trimIndent())
            val guest = "/android-runtime/${root.name}"
            process = ProotCommand(paths).process(listOf("/usr/bin/python3", "-u", "$guest/server.py", guest)).start()
            reader = thread(name = "debian-preview-fixture-output", isDaemon = true) { process.inputStream.bufferedReader().use { it.readText() } }
            val ready = File(root, "ready")
            val deadline = System.currentTimeMillis() + 10000
            while (!ready.isFile && process.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(50)
            if (!ready.isFile) { close(); error("Debian Python development server did not start") }
            port = ready.readText().trim().toInt()
        }
        override fun close() {
            process.destroyForcibly(); check(process.waitFor(15, TimeUnit.SECONDS)); reader.join(2000)
            check(root.canonicalFile.parentFile == paths.config.canonicalFile)
            Files.walk(root.toPath()).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
    private class EchoServer : PreviewServer {
        private val socket = serverSocket()
        override val port get() = socket.localPort
        override val requests = CopyOnWriteArrayList<Request>()
        private val worker = thread(name = "private-preview-http-fixture", isDaemon = true) {
            while (!socket.isClosed) runCatching { socket.accept().use { client ->
                client.soTimeout = 5000
                val input = client.getInputStream().bufferedReader()
                val first = input.readLine() ?: return@use
                val headers = mutableMapOf<String, String>()
                while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':', "").trim()
                }
                requests += Request(first.split(' ').getOrElse(1) { "" }, headers["cookie"].orEmpty(), headers["authorization"].orEmpty())
                val body = "<!doctype html><meta name=viewport content='width=device-width'><title>Private project fixture</title><p>Project preview</p><script>fetch('/probe',{credentials:'include'});</script>".toByteArray()
                client.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                }
            } }
        }
        override fun close() { socket.close(); worker.join(6000) }
        companion object {
            fun serverSocket(): ServerSocket {
                while (true) {
                    val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
                    if (runCatching { PreviewTarget.forPort(socket.localPort) }.isSuccess) return socket
                    socket.close()
                }
            }
        }
    }
}
