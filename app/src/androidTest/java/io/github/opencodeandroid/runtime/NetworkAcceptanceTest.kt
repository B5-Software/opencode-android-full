package io.github.opencodeandroid.runtime

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class NetworkAcceptanceTest {
    @Test fun androidAndDebianReachOfficialHttpsRepository() {
        assumeTrue("Opt in with includeNetwork=true", InstrumentationRegistry.getArguments().getString("includeNetwork") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val paths = RuntimePaths(context)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val report = StringBuilder()
        try {
            assertTrue(GuestNetwork.configure(context, paths))
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            report.appendLine("Android validated network: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
            val proxy = connectivity.defaultProxy
            report.appendLine("Android static HTTP proxy: ${!proxy?.host.isNullOrEmpty()}, PAC: ${proxy?.pacFileUrl?.toString()?.isNotEmpty() == true}")
            val host = InstrumentationRegistry.getArguments().getString("repositoryHost") ?: "deb.debian.org"
            require(host in setOf("deb.debian.org", "mirrors.ustc.edu.cn", "mirrors.tuna.tsinghua.edu.cn"))
            report.appendLine("Repository: $host")
            report.appendLine("Android DNS results: ${java.net.InetAddress.getAllByName(host).joinToString { it.hostAddress.orEmpty() }}")
            val target = "https://$host/debian/dists/trixie/InRelease"
            val androidResult = runCatching {
                val connection = URL(target).openConnection() as HttpURLConnection
                try { connection.requestMethod = "HEAD"; connection.connectTimeout = 10_000; connection.readTimeout = 10_000; connection.responseCode }
                finally { connection.disconnect() }
            }
            report.appendLine("Android HTTPS: ${androidResult.fold({ it.toString() }, { "${it.javaClass.simpleName}: ${it.message}" })}")
            val child = ProotCommand(paths).process(listOf("/bin/bash", "-c", "curl --verbose --fail --location --connect-timeout 8 --max-time 20 --head '$target' && wget --spider --timeout=15 --tries=1 '$target'")).start()
            val drain = Thread {
                try { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> synchronized(report) { if (report.length < 16_000) report.appendLine(line) } } } }
                catch (_: java.io.IOException) { }
            }.apply { start() }
            val done = child.waitFor(60, TimeUnit.SECONDS)
            if (!done) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) }
            drain.join(5000)
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", report.toString()) })
            assertEquals(report.toString(), 200, androidResult.getOrNull())
            assertTrue(report.toString(), done)
            assertEquals(report.toString(), 0, child.exitValue())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

}
