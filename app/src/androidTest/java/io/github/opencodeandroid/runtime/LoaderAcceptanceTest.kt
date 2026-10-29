package io.github.opencodeandroid.runtime

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import android.net.ConnectivityManager
import android.system.Os
import android.system.OsConstants

@RunWith(AndroidJUnit4::class)
class LoaderAcceptanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val paths = RuntimePaths(context)

    private fun install() {
        if (!paths.installed) RootfsInstaller(context, paths).install(::println)
        paths.prepare()
    }

    @Test fun bundledDebianExecutesThroughApkLoader() {
        assertEquals(36, context.applicationInfo.targetSdkVersion)
        assertTrue("Acceptance must run as an ordinary Android app UID", Os.getuid() >= 10_000)
        assertTrue(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" })
        install()
        context.assets.open("guest/m0-smoke.sh").use { input -> File(paths.config, "m0-smoke.sh").outputStream().use { input.copyTo(it) } }
        val child = ProotCommand(paths).process(listOf("/bin/bash", "/android-runtime/m0-smoke.sh")).start()
        val output = StringBuilder()
        val drain = Thread { try { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } } } catch (_: java.io.IOException) { } }.apply { start() }
        val finished = child.waitFor(120, TimeUnit.SECONDS)
        if (!finished) child.destroyForcibly()
        drain.join(5000)
        println("APP_UID=${Os.getuid()} HOST_PAGE_SIZE=${NativeHost.pageSize()} ABI=${Build.SUPPORTED_ABIS.first()}\n$output")
        assertTrue("Loader timed out: $output", finished)
        assertEquals(output.toString(), 0, child.exitValue())
        assertTrue(output.toString(), output.contains("PASS fork exec wait exit status"))
        assertTrue(output.toString(), output.contains("PASS shebang"))
    }

    @Test fun nativeHostPtyHasControllingTerminal() {
        install()
        val command = ProotCommand(paths)
        val (fd, pid) = NativeHost.spawnPty(command.arguments(listOf("/bin/bash", "-c", "test -t 0 && test -t 1 && stty size")).toTypedArray(),
            command.hostEnvironment().map { "${it.key}=${it.value}" }.toTypedArray(), 27, 91)
        val master = android.os.ParcelFileDescriptor.adoptFd(fd)
        val output = StringBuilder()
        val drain = Thread {
            try { android.os.ParcelFileDescriptor.AutoCloseInputStream(master).bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } } }
            catch (_: java.io.IOException) { }
        }.apply { start() }
        var code = -999
        val waiter = Thread { code = NativeHost.waitFor(pid) }.apply { start() }
        try {
            waiter.join(30_000)
            assertFalse("PTY process timed out", waiter.isAlive)
            drain.join(5000)
            assertEquals(output.toString(), 0, code)
            assertTrue(output.toString(), output.contains("27 91"))
        } finally { NativeHost.signalGroup(pid, 9); master.close(); waiter.join(5000); drain.join(5000) }
    }

    @Test fun aptInstallAndRealCompilerChain() {
        assumeTrue("Requires explicit includeNetwork=true; skipped checks do not satisfy M0", InstrumentationRegistry.getArguments().getString("includeNetwork") == "true")
        install()
        val network = context.getSystemService(ConnectivityManager::class.java)
        val dns = (network.getLinkProperties(network.activeNetwork) ?: error("No active network: connect Wi-Fi or mobile data for apt acceptance")).dnsServers
        check(dns.isNotEmpty()) { "Active network has no DNS servers" }
        File(paths.rootfs, "etc/resolv.conf").writeText(dns.joinToString("\n", postfix = "\n") { "nameserver ${it.hostAddress}" })
        context.assets.open("guest/m0-build.sh").use { input -> File(paths.config, "m0-build.sh").outputStream().use { input.copyTo(it) } }
        val child = ProotCommand(paths).process(listOf("/bin/bash", "/android-runtime/m0-build.sh")).start()
        val output = StringBuilder()
        val drain = Thread { try { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { println(it); synchronized(output) { if (output.length < 256*1024) output.appendLine(it) } } } } catch (_: java.io.IOException) { } }.apply { start() }
        val finished = child.waitFor(15, TimeUnit.MINUTES)
        if (!finished) child.destroyForcibly()
        drain.join(5000)
        assertTrue("Compiler chain timed out: ${output.takeLast(8192)}", finished)
        assertEquals(output.takeLast(8192).toString(), 0, child.exitValue())
        assertTrue(output.toString(), output.contains("PASS apt install and multi-file dynamic/static build"))
    }

    @Test fun tracerDeathKillsSeparateProcessGroup() {
        install()
        val probe = File(paths.config, "lifecycle-probe.pid")
        probe.delete()
        val child = ProotCommand(paths).process(listOf("/bin/bash", "-c", "set -m; /bin/sleep 60 & printf '%s' \"\$!\" > /android-runtime/lifecycle-probe.pid; wait")).start()
        val drain = Thread { try { child.inputStream.use { input -> val buffer = ByteArray(4096); while (input.read(buffer) >= 0) { } } } catch (_: java.io.IOException) { } }.apply { start() }
        var pid = -1
        var identity: String? = null
        fun stat(): List<String>? = try {
            val data = File("/proc/$pid/stat").readText()
            data.substring(data.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
        } catch (_: java.io.IOException) { null }
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while ((!probe.isFile || probe.length() == 0L) && child.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("Supervisor probe did not start", probe.isFile)
            pid = probe.readText().trim().toInt()
            val original = stat()
            assertNotNull("Probe exited unexpectedly", original)
            identity = original!![19]
            assertEquals("Probe must lead its own process group", pid, original[2].toInt())
            child.destroyForcibly()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            val exitDeadline = System.currentTimeMillis() + 5000
            var remaining = stat()
            while (remaining != null && remaining[19] == identity && remaining[0] != "Z" && System.currentTimeMillis() < exitDeadline) {
                Thread.sleep(50); remaining = stat()
            }
            assertTrue("Guest escaped supervisor lifetime", remaining == null || remaining[19] != identity || remaining[0] == "Z")
            println("PASS forced tracer death kills a guest in another process group")
        } finally {
            child.destroyForcibly()
            if (pid > 1 && stat()?.get(19) == identity) try { Os.kill(pid, OsConstants.SIGKILL) } catch (_: Exception) { }
            drain.join(5000); probe.delete()
        }
    }
}
