package io.github.opencodeandroid.runtime

import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AgentWorkflowTest {
    @Test fun officialAgentWritesRepairsTestsPushesAndRestoresWithPermissionGates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            ScriptedModelFixture().use { model ->
                IsolatedAgentRuntime(context, model.port).use { server ->
                    try {
                        server.start()
                        val sessionID = create(server, "Multi-file local protocol acceptance", "allow")
                        prompt(server, sessionID, "LOCAL_WORKFLOW_FIXTURE: Create calc.h, calc.c, test.c and Makefile. " +
                            "Run make test, repair the intentional subtraction bug after observing failure, rerun tests, " +
                            "commit and push only to a local bare Git repository. This is a scripted protocol fixture.")
                        wait(server, sessionID)
                        val messages = server.request("GET", "/api/session/$sessionID/message")
                        assertTrue("Official loop did not complete: $messages; fixture errors=${model.errors}",
                            messages.toString().contains("LOCAL_AGENT_ACCEPTANCE_OK"))
                        assertEquals(ScriptedModelFixture.FIXED, File(server.project, "calc.c").readText())
                        assertEquals(ScriptedModelFixture.HEADER, File(server.project, "calc.h").readText())
                        assertTrue(File(server.project, "test-runner").length() > 1000)
                        val commit = File(server.project, ".git/refs/heads/main").readText().trim()
                        assertTrue(commit.matches(Regex("[a-f0-9]{40}")))
                        assertEquals(commit, File(server.project, ".local-origin.git/refs/heads/main").readText().trim())
                        evidence("Actual Agent file writes, observed failing test, repaired C build/test and local Git push passed.")
                        val before = messageIDs(messages)
                        checkPermission(server, approve = true)
                        checkPermission(server, approve = false)
                        evidence("Actual shell permission once/reject passed; neither command ran before its decision.")
                        // Abruptly terminate the actual supervisor, then use the same private SQLite/state namespace.
                        server.stop()
                        server.start()
                        val restored = server.request("GET", "/api/session/$sessionID/message")
                        assertEquals("Projected transcript changed across process restart", before, messageIDs(restored))
                        assertTrue(restored.toString().contains("LOCAL_AGENT_ACCEPTANCE_OK"))
                        prompt(server, sessionID, "CONTINUATION_FIXTURE: confirm the earlier answer and tool results remain in context.")
                        wait(server, sessionID)
                        assertTrue(server.request("GET", "/api/session/$sessionID/message").toString().contains("PERSISTED_CONTEXT_OK"))
                        assertTrue("Protocol fixture errors: ${model.errors}", model.errors.isEmpty())
                        evidence("Abrupt backend restart preserved ${before.size} message IDs and continued prior tool context. " +
                            "${model.requests.size} local scripted model protocol requests; no cloud-model acceptance claimed.")
                        println("PASS official dev Agent: actual four-file C project, failing test, repair, compile/test, local Git commit/push, " +
                            "permission once/reject, process restart and continued model context. LOCAL SCRIPTED MODEL, not cloud-model acceptance.")
                    } catch (error: Throwable) {
                        println("Agent fixture diagnostics: ${server.diagnostics()}\nProtocol errors: ${model.errors}")
                        throw error
                    }
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun create(server: IsolatedAgentRuntime, title: String, effect: String) = server.request("POST", "/api/session",
        JSONObject().put("title", title).put("location", JSONObject().put("directory", server.directory))
            .put("model", JSONObject().put("id", "fixture-chat").put("providerID", "android-fixture"))
            .put("permissions", JSONArray().put(JSONObject().put("action", "*").put("resource", "*").put("effect", effect))))
        .getJSONObject("data").getString("id")

    private fun prompt(server: IsolatedAgentRuntime, id: String, text: String) =
        server.request("POST", "/api/session/$id/prompt", JSONObject().put("text", text))

    private fun wait(server: IsolatedAgentRuntime, id: String) =
        server.request("POST", "/api/experimental/session/$id/wait")

    private fun messageIDs(response: JSONObject): List<String> = response.getJSONArray("data").let { data ->
        (0 until data.length()).map { data.getJSONObject(it).getString("id") }
    }

    private fun evidence(value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2,
        Bundle().apply { putString("stream", "\nPASS $value\n") })

    private fun checkPermission(server: IsolatedAgentRuntime, approve: Boolean) {
        val marker = if (approve) "approved.txt" else "rejected.txt"
        val session = create(server, "Permission ${if (approve) "once" else "reject"}", "ask")
        prompt(server, session, if (approve) "PERMISSION_APPROVE_FIXTURE" else "PERMISSION_REJECT_FIXTURE")
        var pending = JSONArray()
        val deadline = System.currentTimeMillis() + 30_000
        while (pending.length() == 0 && System.currentTimeMillis() < deadline) {
            pending = server.request("GET", "/api/session/$session/permission").getJSONArray("data")
            if (pending.length() == 0) Thread.sleep(150)
        }
        assertEquals("Expected an actual pending tool approval", 1, pending.length())
        assertFalse("Shell ran before approval", File(server.project, marker).exists())
        val request = pending.getJSONObject(0)
        assertEquals("shell", request.getString("action"))
        server.request("POST", "/api/session/$session/permission/${request.getString("id")}/reply",
            JSONObject().put("decision", if (approve) "once" else "reject"))
        wait(server, session)
        if (approve) assertEquals("APPROVED", File(server.project, marker).readText())
        else assertFalse("Rejected tool executed", File(server.project, marker).exists())
    }
}
