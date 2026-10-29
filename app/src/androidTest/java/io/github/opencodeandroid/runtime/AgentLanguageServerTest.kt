package io.github.opencodeandroid.runtime

import android.content.Intent
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class AgentLanguageServerTest {
    @Test fun realPylspHandlesUnicodeProjectFilesAndDiagnostics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ScriptedModelFixture().use { model -> IsolatedAgentRuntime(context, model.port).use { server ->
                server.enableBundledLsp(context)
                server.start()
                val session = createSession(server)
                server.request("POST", "/api/session/$session/prompt", JSONObject().put("text",
                    "PYLSP_FIXTURE: write the Unicode-named Python file, inspect undefined-name diagnostics, repair it and query definition/hover."))
                server.request("POST", "/api/experimental/session/$session/wait")
                val messages = server.request("GET", "/api/session/$session/message")
                assertTrue("Actual Python LSP failed: $messages; errors=${model.errors}", messages.toString().contains("PYTHON_LANGUAGE_SERVER_ACCEPTANCE_OK"))
                assertTrue(model.errors.toString(), model.errors.isEmpty())
                assertEquals(ScriptedModelFixture.PYTHON_FIXED, java.io.File(server.project, ScriptedModelFixture.PYTHON_FILE).readText())
                instrumentation.sendStatus(2, Bundle().apply { putString("stream",
                    "\nPASS actual pylsp/pyflakes: Unicode filename, automatic semantic diagnostics, repair, clean diagnostics, definition and hover.\n") })
            } }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun lspAndAutomaticFeedbackRespectRelativeReadDenial() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ScriptedModelFixture().use { model -> IsolatedAgentRuntime(context, model.port).use { server ->
                server.enableBundledLsp(context)
                server.start()
                val session = createSession(server, denyRead = true)
                server.request("POST", "/api/session/$session/prompt", JSONObject().put("text", "LSP_READ_DENIAL_FIXTURE"))
                server.request("POST", "/api/experimental/session/$session/wait")
                val messages = server.request("GET", "/api/session/$session/message").getJSONArray("data")
                val tools = (0 until messages.length()).map { messages.getJSONObject(it) }.flatMap { message ->
                    val content = message.optJSONArray("content") ?: JSONArray()
                    (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "tool" }
                }
                val write = tools.single { it.getString("name") == "write" }.getJSONObject("state")
                assertEquals("completed", write.getString("status"))
                assertTrue(write.getJSONObject("metadata").getBoolean("androidLspUnavailable"))
                val query = tools.single { it.getString("name") == "lsp" }.getJSONObject("state")
                assertEquals("Read-denied LSP query executed: $query", "error", query.getString("status"))
                val location = "?location%5Bdirectory%5D=" + URLEncoder.encode(server.directory, "UTF-8")
                val status = server.request("POST", "/api/rpc/opencode.android.lsp/status$location", JSONObject().put("input", JSONObject())).getJSONArray("output")
                for (index in 0 until status.length()) assertFalse("Read denial still spawned a server", status.getJSONObject(index).getBoolean("running"))
                assertTrue(model.errors.toString(), model.errors.isEmpty())
                instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nPASS relative read denial blocks explicit LSP and automatic diagnostics without preventing an authorized write.\n") })
            } }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun createSession(server: IsolatedAgentRuntime, denyRead: Boolean = false): String {
        val permissions = JSONArray().put(JSONObject().put("action", "*").put("resource", "*").put("effect", "allow"))
        if (denyRead) permissions.put(JSONObject().put("action", "read").put("resource", "private.c").put("effect", "deny"))
        return server.request("POST", "/api/session", JSONObject().put("title", "LSP integration fixture")
            .put("location", JSONObject().put("directory", server.directory))
            .put("model", JSONObject().put("id", "fixture-chat").put("providerID", "android-fixture"))
            .put("permissions", permissions)).getJSONObject("data").getString("id")
    }

    @Test fun officialAgentUsesRealClangdAndAutomaticWriteDiagnostics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ScriptedModelFixture().use { model ->
                IsolatedAgentRuntime(context, model.port).use { server ->
                    server.enableBundledLsp(context)
                    server.start()
                    val session = server.request("POST", "/api/session", JSONObject().put("title", "Actual clangd integration")
                        .put("location", JSONObject().put("directory", server.directory))
                        .put("model", JSONObject().put("id", "fixture-chat").put("providerID", "android-fixture"))
                        .put("permissions", JSONArray().put(JSONObject().put("action", "*").put("resource", "*").put("effect", "allow"))))
                        .getJSONObject("data").getString("id")
                    server.request("POST", "/api/session/$session/prompt", JSONObject().put("text",
                        "LANGUAGE_SERVER_FIXTURE: write a C source with missing_identifier, inspect actual LSP diagnostics, repair it, " +
                            "then query definitions, references, hover and document symbols. Use the bundled Linux LSP plugin. This is a scripted protocol fixture."))
                    server.request("POST", "/api/experimental/session/$session/wait")
                    val messages = server.request("GET", "/api/session/$session/message")
                    assertTrue("Real LSP loop failed: $messages; fixture errors=${model.errors}",
                        messages.toString().contains("LANGUAGE_SERVER_ACCEPTANCE_OK"))
                    assertTrue("Fixture protocol errors: ${model.errors}", model.errors.isEmpty())
                    val location = "?location%5Bdirectory%5D=" + URLEncoder.encode(server.directory, "UTF-8")
                    val status = server.request("POST", "/api/rpc/opencode.android.lsp/status$location", JSONObject().put("input", JSONObject())).getJSONArray("output")
                    val clangd = (0 until status.length()).map { status.getJSONObject(it) }.single { it.getString("id") == "clangd" }
                    assertTrue(clangd.toString(), clangd.getBoolean("running"))
                    val pid = clangd.getInt("pid")
                    assertTrue(pid > 0)
                    val processGroup = java.io.File("/proc/$pid/stat").readText().substringAfterLast(") ")
                        .trim().split(Regex("\\s+"))[2].toInt()
                    assertEquals("Language server must own its process group", pid, processGroup)
                    server.stop()
                    fun alive(): Boolean = try { Os.kill(pid, 0); true } catch (error: ErrnoException) {
                        if (error.errno == OsConstants.ESRCH) false else throw error
                    }
                    val deadline = System.currentTimeMillis() + 5000
                    while (alive() && System.currentTimeMillis() < deadline) Thread.sleep(100)
                    assertFalse("clangd survived its supervisor", alive())
                    instrumentation.sendStatus(2, Bundle().apply { putString("stream",
                        "\nPASS official Agent + APK Linux plugin: actual clangd semantic diagnostics, automatic write feedback, fix/clean diagnostics, " +
                            "definition, references, hover, symbols, and supervisor cleanup. Local scripted model only.\n") })
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
