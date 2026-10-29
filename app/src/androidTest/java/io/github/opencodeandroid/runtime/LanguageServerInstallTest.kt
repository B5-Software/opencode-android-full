package io.github.opencodeandroid.runtime

import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LanguageServerInstallTest {
    @Test fun signedDebianPackagesInstallThroughRuntimeService() {
        assumeTrue("Opt in with installLanguageServers=true",
            InstrumentationRegistry.getArguments().getString("installLanguageServers") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            assertFalse(RuntimeState.current.value.busy)
            context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.INSTALL_LANGUAGE_SERVERS))
            val started = System.currentTimeMillis() + 10000
            while (!RuntimeState.current.value.busy && System.currentTimeMillis() < started) Thread.sleep(50)
            assertTrue("Runtime installation did not start", RuntimeState.current.value.busy)
            val deadline = System.currentTimeMillis() + 1200000
            var reported = 0
            while (RuntimeState.current.value.busy && System.currentTimeMillis() < deadline) {
                val log = RuntimeState.current.value.log
                if (log.length > reported + 2000) {
                    instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\n${log.takeLast(1800)}\n") })
                    reported = log.length
                }
                Thread.sleep(500)
            }
            val state = RuntimeState.current.value
            assertFalse("Installation timed out: ${state.log}", state.busy)
            assertEquals(state.log, "Stopped", state.phase)
            assertTrue(state.log, state.log.contains("language servers installed and verified"))
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\n${state.log.takeLast(4500)}\nPASS signed apt clangd/pylsp installation through RuntimeService\n") })
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
