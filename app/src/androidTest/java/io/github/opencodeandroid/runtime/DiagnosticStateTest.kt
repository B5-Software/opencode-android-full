package io.github.opencodeandroid.runtime

import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.desktop.DesktopState
import io.github.opencodeandroid.logging.DiagnosticLog
import io.github.opencodeandroid.logging.DiagnosticSecrets
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticStateTest {
    @Test fun realStateFlowBatchesBurstAndPublishesPhaseImmediately() = runBlocking {
        val id = UUID.randomUUID().toString()
        val publications = AtomicInteger()
        val statusPublications = AtomicInteger()
        val desktopStatusPublications = AtomicInteger()
        val observer = launch(Dispatchers.Unconfined) { RuntimeState.current.collect { publications.incrementAndGet() } }
        val statusObserver = launch(Dispatchers.Unconfined) { RuntimeState.status.collect { statusPublications.incrementAndGet() } }
        val desktopObserver = launch(Dispatchers.Unconfined) { DesktopState.status.collect { desktopStatusPublications.incrementAndGet() } }
        try {
            val before = publications.get()
            val statusBefore = statusPublications.get()
            val desktopBefore = desktopStatusPublications.get()
            val started = android.os.SystemClock.elapsedRealtime()
            repeat(2000) { RuntimeState.log("fixture-$id row=$it") }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            val afterBurst = publications.get() - before
            repeat(2000) { DesktopState.log("fixture-$id row=$it") }
            assertEquals(statusBefore, statusPublications.get())
            assertEquals(desktopBefore, desktopStatusPublications.get())
            RuntimeState.update("diagnostic fixture complete", false)
            val immediate = RuntimeState.current.value
            assertEquals("diagnostic fixture complete", immediate.phase)
            assertEquals("diagnostic fixture complete", RuntimeState.status.value.phase)
            assertEquals("", RuntimeState.status.value.log)
            assertEquals("", DesktopState.status.value.log)
            assertTrue(immediate.log.endsWith("row=1999\n"))
            assertTrue(immediate.log.length <= 48 * 1024)
            delay(350)
            assertEquals(immediate.log, RuntimeState.current.value.log)
            assertEquals(statusBefore + 1, statusPublications.get())
            assertEquals(desktopBefore, desktopStatusPublications.get())
            // A burst must not cause one state notification per stdout line.
            assertTrue("Burst was published per line", afterBurst < 2000)
            assertTrue("Log timer exceeded 4 Hz", afterBurst <= elapsed / 250 + 1)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putString("stream", "DIAGNOSTIC_BATCH rows=2000 burstPublications=$afterBurst elapsedMs=$elapsed runtimeLogStatusPublications=0 desktopLogStatusPublications=0 deviceLocked=${context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked}\n")
            })
        } finally { observer.cancel(); statusObserver.cancel(); desktopObserver.cancel(); RuntimeState.update("Stopped", false) }
    }

    @Test fun exceptionSizedSecretsDesktopCredentialsAndStatusRemainSafe() {
        val secret = "fixture-native-private-${UUID.randomUUID()}"
        DiagnosticSecrets.register(secret, secret.take(8)).use {
            RuntimeState.log("IOException: $secret")
            RuntimeState.update("diagnostic failure fixture", false)
            assertFalse(RuntimeState.current.value.log.contains(secret))
            DesktopState.log("desktop process: $secret")
            DesktopState.update("diagnostic desktop fixture", false)
            assertFalse(DesktopState.current.value.log.contains(secret))
            assertTrue(DesktopState.current.value.log.contains("[redacted]"))
            assertEquals("diagnostic desktop fixture", DesktopState.current.value.phase)
        }
        DesktopState.log("x".repeat(DiagnosticLog.MAX_LINE + 1)); DesktopState.update("已停止", false)
        assertTrue(DesktopState.current.value.log.endsWith(DiagnosticLog.OMITTED_LINE + "\n"))
        assertTrue(DesktopState.current.value.log.length <= 12000)
        RuntimeState.update("Stopped", false)
    }
}
