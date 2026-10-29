package io.github.opencodeandroid.desktop

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.ProotCommand
import io.github.opencodeandroid.runtime.RuntimePaths
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ChromiumSandboxTest {
    @Test fun chromiumRendersLocalDocumentInSelectedProbeMode() {
        assumeTrue("Opt in to Chromium sandbox compatibility probe", InstrumentationRegistry.getArguments().getString("probeChromium") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val paths = RuntimePaths(instrumentation.targetContext)
        assumeTrue(DesktopState.available(paths))
        val compatibility = InstrumentationRegistry.getArguments().getString("probeWithoutSandbox") == "true"
        val flags = if (compatibility) "--no-sandbox" else ""
        val command = listOf("/bin/bash", "-c", "id; printf 'BEFORE_NAMESPACE\\n'; unshare -Ur true; printf 'NAMESPACE_STATUS=%s\\n' \"\$?\"; printf 'BEFORE_CHROMIUM\\n'; chromium $flags --headless --disable-gpu --disable-dev-shm-usage --disable-background-networking --disable-component-update --disable-sync --enable-logging=stderr --v=1 --no-first-run --user-data-dir=/desktop-state/chromium-sandbox-probe --dump-dom about:blank; result=\$?; printf 'CHROMIUM_STATUS=%s\\n' \"\$result\"; exit \"\$result\"")
        val child = ProotCommand(paths, 1000).process(command).start()
        val report = StringBuilder()
        val rendered = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            try { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                if (line.contains("<html>")) rendered.set(true)
                synchronized(report) { report.appendLine(line); if (report.length > 10000) report.delete(0, report.length - 10000) }
            } } } catch (_: java.io.IOException) { }
        }.apply { start() }
        try {
            val exited = child.waitFor(45, TimeUnit.SECONDS)
            if (!exited) child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS); reader.join(3000)
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "Compatibility probe=$compatibility; rendered local HTML=${rendered.get()}\n$report") })
            assertTrue("Chromium did not exit", exited)
            assertEquals(report.toString(), 0, child.exitValue())
            assertTrue(report.toString(), rendered.get())
        } finally { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS) }
    }
}
