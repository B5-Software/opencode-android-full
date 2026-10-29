package io.github.opencodeandroid.bridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.preview.PreviewTarget
import io.github.opencodeandroid.runtime.NativeProcess
import io.github.opencodeandroid.runtime.ProotCommand
import io.github.opencodeandroid.runtime.RuntimePaths
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Actual Linux helper and authenticated IPC; native decisions are simulated, not UI acceptance. */
class PreviewBridgeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val paths get() = RuntimePaths(context)
    private val running = mutableListOf<Command>()
    private var previousForeground = false
    @Before fun acquire() {
        previousForeground = AndroidBridge.foreground; AndroidBridge.foreground = false
        AndroidBridge.acquire(context, "preview-bridge-tests")
    }
    @After fun cleanup() {
        running.forEach { if (it.process.isAlive) it.process.destroyForcibly(); it.process.waitFor(3, TimeUnit.SECONDS); it.reader.join(2000) }
        AndroidBridge.release("preview-bridge-tests"); AndroidBridge.foreground = previousForeground
    }
    private class Command(val process: NativeProcess) {
        val output = StringBuilder()
        val reader = Thread({ try { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } } } catch (_: java.io.IOException) { } }, "preview-helper-output")
            .apply { isDaemon = true; start() }
        fun finish(expected: Int): String {
            assertTrue("Real Debian helper did not finish", process.waitFor(10, TimeUnit.SECONDS)); reader.join(2000)
            assertEquals("Helper returned an unexpected status", expected, process.exitValue())
            return output.toString()
        }
    }
    private fun command(port: String) = Command(ProotCommand(paths).process(listOf("/android-runtime/bin/android-preview", port)).start()).also(running::add)
    private fun pending(): BridgeRequest {
        await { AndroidBridge.pending.value.isNotEmpty() }
        return AndroidBridge.pending.value.single().also { assertEquals("preview", it.action) }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for the real preview bridge", condition())
    }
    @Test fun helperWaitsWithoutProbingBeforeApprovalAndCancelReturns130() {
        listener().use { socket ->
            val client = command(socket.localPort.toString())
            val request = pending()
            assertEquals(socket.localPort, request.payload.getInt("port"))
            assertNull(request.previewPort); assertFalse(request.processing)
            assertTrue(client.process.isAlive)
            socket.soTimeout = 250
            try { socket.accept().use { fail("Preview port was contacted before native approval") } } catch (_: SocketTimeoutException) { }
            AndroidBridge.cancel(request.id)
            client.finish(130); await { AndroidBridge.pending.value.isEmpty() }
        }
        evidence("Linux android-preview waits for native decision; no project-port probe before approval; cancellation=130")
    }
    @Test fun approvedPortPreparationRemainsPendingUntilForegroundDispatchAndIsConsumedOnce() {
        listener().use { socket ->
            val client = command(socket.localPort.toString())
            val request = pending()
            // Test the trusted native decision entry point, without mounting a confirmation UI.
            AndroidBridge.preparePreview(request.id)
            await { AndroidBridge.pending.value.singleOrNull()?.previewPort == socket.localPort }
            assertFalse(AndroidBridge.foreground); assertTrue(client.process.isAlive)
            socket.soTimeout = 3000
            socket.accept().use { probe -> probe.soTimeout = 1000; assertEquals("Probe sent data or credentials", -1, probe.getInputStream().read()) }
            assertEquals(socket.localPort, AndroidBridge.consumePreview(request.id))
            assertNull(AndroidBridge.consumePreview(request.id))
            assertTrue(client.process.isAlive)
            val url = PreviewTarget.forPort(socket.localPort).url
            // Production completes only after the resumed UI successfully dispatches PreviewActivity.
            AndroidBridge.complete(request.id, value = JSONObject().put("url", url))
            assertEquals(url, client.finish(0).trim())
            await { AndroidBridge.pending.value.isEmpty() }
        }
        evidence("approved real TCP probe sends no credentials; background request and helper wait for dispatch; one-shot consumption and canonical URL response")
    }
    @Test fun closedPortReturnsActionableFailureInsteadOfOpeningPreview() {
        val port = listener().use { it.localPort }
        val client = command(port.toString())
        AndroidBridge.preparePreview(pending().id)
        assertTrue(client.finish(1).contains("No local server is listening"))
        await { AndroidBridge.pending.value.isEmpty() }
        evidence("closed development port returns helper exit=1 and actionable failure without a ready preview")
    }
    @Test fun malformedOrAdministrativePortsNeverBecomePendingRequests() {
        for (value in listOf("49374", "6080", "5901", "80", "65536", "http://127.0.0.1:3000", "3000;echo bad")) command(value).finish(1)
        val config = JSONObject(File(paths.config, "bridge-auth.json").readText())
        for (value in listOf<Any>("5173", true, 5173.5, 49374, 6080, 5901, 0, 65536, JSONObject.NULL)) {
            val response = LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(config.getString("socket"), LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 3000
                val request = JSONObject().put("version", 1).put("token", config.getString("token")).put("action", "preview")
                    .put("payload", JSONObject().put("port", value))
                socket.outputStream.write((request.toString() + "\n").toByteArray()); socket.outputStream.flush()
                JSONObject(socket.inputStream.bufferedReader().readLine())
            }
            assertEquals("error", response.getString("status"))
            assertTrue(AndroidBridge.pending.value.isEmpty())
        }
        evidence("real helper and authenticated raw IPC reject administrative, fractional, string, boolean, null and command-like ports before approval queue")
    }
    private fun listener(): ServerSocket {
        while (true) {
            val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            if (runCatching { PreviewTarget.forPort(socket.localPort) }.isSuccess) return socket
            socket.close()
        }
    }
    private fun evidence(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nPASS $value; native decision simulation only.\n") })
}
