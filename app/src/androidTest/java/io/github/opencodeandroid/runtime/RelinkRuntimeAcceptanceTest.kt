package io.github.opencodeandroid.runtime

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Executes a hash-pinned local developer build through the APK loader in an entirely fresh runtime. */
class RelinkRuntimeAcceptanceTest {
    @Test fun modifiedWebkitBunAndOfficialOpencodeRunThroughAndroidApkLoader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("includeRelinkRuntime") == "true")
        val sha = checkNotNull(args.getString("relinkSha256"))
        check(sha.matches(Regex("[a-f0-9]{64}")))
        val context = instrumentation.targetContext
        assertEquals(36, context.applicationInfo.targetSdkVersion)
        assertTrue(android.system.Os.getuid() >= 10000)
        val input = File(context.getExternalFilesDir(null), "relink-proof/opencode-${sha.take(12)}")
        check(input.isFile)
        val root = File(context.cacheDir, "update-relink-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val files = File(root, "files").apply { check(mkdir()) }
        val prefix = "relink-${UUID.randomUUID()}-"
        val preferences = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = files
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                preferences += name
                return context.getSharedPreferences(prefix + name, mode)
            }
        }
        val paths = RuntimePaths(isolated)
        try {
            RootfsInstaller(isolated, paths).install { }
            IsolatedAgentRuntime(isolated, 0).use { server ->
                server.useLocallyRebuiltExecutable(input, sha)
                server.start()
                assertTrue("Modified VM constructor was not executed: ${server.diagnostics()}",
                    server.diagnostics().contains("OPENCODE_ANDROID_FULL_WEBKIT_RELINK_V1"))
                val info = server.request("GET", "/api/info")
                assertEquals("2.1.4-dev.f993a89d13c7", info.getString("version"))
                val html = server.frontendHtml()
                assertTrue("Matched official frontend not served", html.contains("<script") && html.contains("OpenCode", ignoreCase = true))
                val session = server.request("POST", "/api/session", JSONObject().put("title", "Locally relinked VM proof")
                    .put("location", JSONObject().put("directory", server.directory))).getJSONObject("data").getString("id")
                assertTrue(session.isNotBlank())
                val database = File(paths.config, "${server.project.name.removePrefix(".")}/data/opencode/opencode-dev.db")
                assertTrue("Relinked backend did not initialize its private SQLite database", database.isFile)
                instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream",
                    "\nPASS MODIFIED_RUNTIME_RELINK appUid=${android.system.Os.getuid()} targetSdk=${context.applicationInfo.targetSdkVersion} " +
                        "pageSize=${NativeHost.pageSize()} binarySha256=$sha version=${info.getString("version")} " +
                        "VM_MARKER_OBSERVED authenticatedApi=true officialFrontendBytes=${html.toByteArray().size} session=$session " +
                        "local developer program in fresh runtime; managed user program and signature verification unchanged\n") })
            }
        } finally {
            preferences.forEach { context.deleteSharedPreferences(prefix + it) }
            UpdateTransactionTest.removeOwnedTestTree(context, root)
        }
    }
}
