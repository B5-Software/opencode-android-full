package io.github.opencodeandroid.runtime

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.update.UpdateFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in real inference in a fresh runtime; optionally uses a hash-pinned local developer program. */
class PublicCloudAgentAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun report(value: String) = instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\n$value\n") })

    @Test fun realPublicModelCreatesBuildsAndTestsAnArm64CProject() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("includePublicCloudModel") == "true")
        val model = InstrumentationRegistry.getArguments().getString("publicCloudModel") ?: "glm-5.3-flash-free"
        require(model in setOf("glm-5.3-flash-free", "deepseek-v4.1-flash-free"))
        val runtimeSha = InstrumentationRegistry.getArguments().getString("publicCloudRuntimeSha256")
        require(runtimeSha == null || runtimeSha.matches(Regex("[a-f0-9]{64}")))
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "update-public-cloud-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val files = File(root, "files").apply { check(mkdir()) }
        val prefix = "public-cloud-${UUID.randomUUID()}-"
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
            report("REAL_CLOUD installing fresh APK-pinned Debian/developer kit; synthetic inputs only; model=opencode/$model")
            RootfsInstaller(isolated, paths).install { if (it.contains("installed")) report(it) }
            IsolatedAgentRuntime(isolated, 0, publicFreeModel = model).use { server ->
                if (runtimeSha != null) server.useLocallyRebuiltExecutable(
                    File(context.getExternalFilesDir(null), "relink-proof/opencode-${runtimeSha.take(12)}"), runtimeSha)
                server.start()
                if (runtimeSha != null) assertTrue(server.diagnostics().contains("OPENCODE_ANDROID_FULL_WEBKIT_RELINK_V1"))
                val session = server.request("POST", "/api/session", JSONObject()
                    .put("title", "Real cloud ARM64 synthetic C acceptance")
                    .put("location", JSONObject().put("directory", server.directory))
                    .put("model", JSONObject().put("id", model).put("providerID", "opencode"))
                    .put("permissions", JSONArray().put(JSONObject().put("action", "*").put("resource", "*").put("effect", "allow"))))
                    .getJSONObject("data").getString("id")
                server.request("POST", "/api/session/$session/prompt", JSONObject().put("text",
                    "In this empty synthetic project create src/calc.h, src/calc.c, tests/test_calc.c and Makefile. " +
                    "Export C functions int add_int(int a,int b) and int subtract_int(int a,int b). " +
                    "Write real assertions including positive, negative and zero values. Make make test compile with gcc " +
                    "and run a native binary named test-runner. Actually run make test, inspect any failures and repair them. " +
                    "Work only in this project. Do not access other directories, credentials, network tools or Git remotes. " +
                    "When the actual tests pass reply REAL_CLOUD_AGENT_OK."))
                server.request("POST", "/api/experimental/session/$session/wait", timeout = 240_000)
                val transcript = server.request("GET", "/api/session/$session/message")
                assertTrue("Real provider did not finish: ${transcript.toString().takeLast(10000)}", transcript.toString().contains("REAL_CLOUD_AGENT_OK"))
                listOf("src/calc.h", "src/calc.c", "tests/test_calc.c", "Makefile").forEach {
                    assertTrue("Real model did not create $it", File(server.project, it).isFile)
                }
                val modelBinary = File(server.project, "test-runner")
                assertTrue("Model did not produce a native test program", modelBinary.length() > 1000)
                assertArm64Elf(modelBinary)
                File(server.project, "independent-check.c").writeText("""
                    #include "calc.h"
                    #include <stdio.h>
                    int main(void) {
                        if (add_int(2,3)!=5 || add_int(-7,2)!=-5 || add_int(0,0)!=0) return 1;
                        if (subtract_int(7,2)!=5 || subtract_int(-7,-2)!=-5 || subtract_int(0,4)!=-4) return 2;
                        puts("REAL_CLOUD_C_BUILD_OK"); return 0;
                    }
                """.trimIndent() + "\n")
                runGuest(paths, listOf("/usr/bin/gcc", "-std=c11", "-Wall", "-Werror", "-I${server.directory}/src",
                    "${server.directory}/src/calc.c", "${server.directory}/independent-check.c", "-o", "${server.directory}/independent-runner"))
                assertArm64Elf(File(server.project, "independent-runner"))
                val output = runGuest(paths, listOf("${server.directory}/independent-runner"))
                assertTrue(output, output.contains("REAL_CLOUD_C_BUILD_OK"))
                val hashes = listOf("src/calc.h", "src/calc.c", "tests/test_calc.c", "Makefile").associateWith { UpdateFiles.sha(File(server.project, it)) }
                report("PASS REAL_CLOUD model=opencode/$model runtimeSha256=${runtimeSha ?: "APK-pinned"} sourceHashes=$hashes appUid=${android.system.Os.getuid()} pageSize=${NativeHost.pageSize()} " +
                    "actual official Agent wrote four files, produced an AArch64 test program, and passed independent compiler/runtime assertions. No scripted model or private account credential.")
            }
        } finally {
            preferences.forEach { context.deleteSharedPreferences(prefix + it) }
            UpdateTransactionTest.removeOwnedTestTree(context, root)
        }
    }

    private fun assertArm64Elf(file: File) {
        val header = ByteArray(20)
        java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
        assertEquals(20, header.size)
        assertArrayEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()), header.copyOfRange(0, 4))
        assertEquals(2, header[4].toInt())
        assertEquals(1, header[5].toInt())
        assertEquals(183, (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8))
    }

    private fun runGuest(paths: RuntimePaths, arguments: List<String>): String {
        val process = ProotCommand(paths).process(arguments).start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().useLines { lines -> lines.forEach {
            synchronized(output) { if (output.length < 16384) output.appendLine(it) }
        } } }.apply { isDaemon = true; start() }
        try {
            process.outputStream.close()
            check(process.waitFor(60, TimeUnit.SECONDS)) { "Independent C check timed out" }
            reader.join(2000)
            check(process.exitValue() == 0) { "Independent C check failed: $output" }
            return output.toString()
        } finally { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); reader.join(1000) }
    }
}
