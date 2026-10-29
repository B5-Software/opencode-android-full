package io.github.opencodeandroid.runtime

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.update.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpdateFetchServiceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(timeout: Long = 20000, condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition()) { check(android.os.SystemClock.elapsedRealtime() < until) { "Update service did not settle" }; Thread.sleep(20) }
    }
    @Test fun invalidSourceStopsWithoutChangingRuntimeOrSavedSource() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, OpenCodeFetchService::class.java), 0)
        assertFalse(info.exported); assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        val saved = OpenCodeFetchService.source(context)
        val paths = RuntimePaths(context); val active = java.io.File(paths.config, "opencode-active.json")
        val before = active.takeIf { it.exists() }?.readBytes()
        val revision = OpenCodeUpdateState.current.value.revision
        context.startForegroundService(Intent(context, OpenCodeFetchService::class.java).setAction(OpenCodeFetchService.CHECK).putExtra("source", "http://127.0.0.1/not-permitted"))
        await { OpenCodeUpdateState.current.value.revision > revision && !OpenCodeUpdateState.current.value.busy }
        assertEquals(saved, OpenCodeFetchService.source(context))
        assertArrayEquals(before, active.takeIf { it.exists() }?.readBytes())
        assertFalse(OpenCodeUpdateState.current.value.network)
    }
    @Test fun cancelDuringTlsHandshakeReleasesForegroundOperation() {
        val saved = OpenCodeFetchService.source(context)
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = CountDownLatch(1); val release = CountDownLatch(1)
        val thread = Thread {
            runCatching { server.accept().use { accepted.countDown(); release.await(20, TimeUnit.SECONDS) } }
        }.apply { isDaemon = true; start() }
        val revision = OpenCodeUpdateState.current.value.revision
        try {
            context.startForegroundService(Intent(context, OpenCodeFetchService::class.java).setAction(OpenCodeFetchService.CHECK)
                .putExtra("source", "https://127.0.0.1:${server.localPort}/stall"))
            assertTrue(accepted.await(10, TimeUnit.SECONDS))
            context.startService(Intent(context, OpenCodeFetchService::class.java).setAction(OpenCodeFetchService.CANCEL))
            await { OpenCodeUpdateState.current.value.revision > revision && !OpenCodeUpdateState.current.value.busy }
            assertFalse(OpenCodeUpdateState.current.value.cancellable)
            assertEquals(saved, OpenCodeFetchService.source(context))
        } finally {
            release.countDown(); server.close(); thread.join(10000)
            context.stopService(Intent(context, OpenCodeFetchService::class.java))
        }
    }
}
