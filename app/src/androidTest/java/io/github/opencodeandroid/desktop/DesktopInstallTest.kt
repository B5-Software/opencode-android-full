package io.github.opencodeandroid.desktop

import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopInstallTest {
    @Test fun installSignedDebianDesktopPackages() {
        assumeTrue("Opt in to network installation", InstrumentationRegistry.getArguments().getString("includeDesktopInstall") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            context.startForegroundService(Intent(context, DesktopService::class.java).setAction(DesktopService.INSTALL))
            val deadline = android.os.SystemClock.elapsedRealtime() + 2450_000
            var started = false
            var nextReport = 0L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val snapshot = DesktopState.current.value
                if (snapshot.busy) started = true
                if (started && !snapshot.busy) break
                if (snapshot.phase == "需要处理") break
                if (android.os.SystemClock.elapsedRealtime() > nextReport) {
                    instrumentation.sendStatus(2, Bundle().apply { putString("stream", snapshot.phase + "\n" + snapshot.log.takeLast(1600)) })
                    nextReport = android.os.SystemClock.elapsedRealtime() + 15000
                }
                Thread.sleep(500)
            }
            val result = DesktopState.current.value
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", result.log.takeLast(12000)) })
            assertTrue("Desktop install never started", started)
            assertFalse("Desktop install timed out", result.busy)
            assertTrue(result.log, result.installed)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
