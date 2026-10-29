package io.github.opencodeandroid.runtime

import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ExternalLanguageServerTest {
    @Test fun externalApprovalPrecedesReadApprovalAndActualClangd() = external(allow = true, alias = false)
    @Test fun rejectedExternalDirectoryNeverStartsLanguageServer() = external(allow = false, alias = false)
    @Test fun symlinkCannotBypassReadDenialOnItsExternalTarget() = external(allow = false, alias = true)
    @Test fun retargetedSymlinkInvalidatesApprovedFileBeforeLanguageServerStarts() = external(allow = false, alias = true, retarget = true)

    private fun external(allow: Boolean, alias: Boolean, retarget: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ScriptedModelFixture().use { model -> IsolatedAgentRuntime(context, model.port).use { server ->
                val outside = File(server.project.parentFile, server.project.name + "-external").apply { check(mkdir()) }
                try {
                    // Give the location its own real worktree, independent of any repository the user
                    // may have created around /workspace. External approval must not be skipped.
                    val git = ProotCommand(RuntimePaths(context)).process(listOf("git", "-c", "init.templateDir=", "init", "-b", "main", server.directory),
                        mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1")).start()
                    try { git.inputStream.bufferedReader().use { it.readText() }; assertTrue(git.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, git.exitValue()) }
                    finally { git.destroyForcibly(); git.waitFor(3, TimeUnit.SECONDS) }
                    File(outside, ".git").mkdir() // Upstream's saved external-directory boundary uses the nearest marker.
                    val source = File(outside, "outside.c").apply { writeText("int value(void) { return hidden_outside_identifier; }\n") }
                    val externalPath = "/workspace/${outside.name}/outside.c"
                    val input = if (alias) {
                        Files.createSymbolicLink(File(server.project, "linked.c").toPath(), java.nio.file.Paths.get("../${outside.name}/outside.c"))
                        "linked.c"
                    } else externalPath
                    server.enableBundledLsp(context); server.start()
                    val permissions = JSONArray().put(rule("*", "*", "allow"))
                        .put(rule("external_directory", "*", "ask"))
                        .put(rule("read", if (alias && !retarget) externalPath else "*", if (alias && !retarget) "deny" else "ask"))
                    val session = server.request("POST", "/api/session", JSONObject().put("title", "External LSP permissions")
                        .put("location", JSONObject().put("directory", server.directory))
                        .put("model", JSONObject().put("id", "fixture-chat").put("providerID", "android-fixture"))
                        .put("permissions", permissions)).getJSONObject("data").getString("id")
                    server.request("POST", "/api/session/$session/prompt", JSONObject().put("text", "EXTERNAL_LSP_FIXTURE:" + JSONObject().put("file", input).put("allow", allow)))
                    val external = pending(server, session)
                    assertEquals("external_directory", external.getString("action"))
                    assertEquals(JSONArray().put("/workspace/${outside.name}/*").toString(), external.getJSONArray("resources").toString())
                    assertEquals(JSONArray().put("/workspace/${outside.name}/*").toString(), external.getJSONArray("save").toString())
                    assertNoServers(server)
                    reply(server, session, external, if (allow || alias) "once" else "reject")
                    if (allow || retarget) {
                        val read = pending(server, session)
                        assertEquals("read", read.getString("action"))
                        val expected = JSONArray().apply { if (alias) put("linked.c"); put(externalPath) }
                        assertEquals(expected.toString(), read.getJSONArray("resources").toString())
                        assertNoServers(server)
                        if (retarget) {
                            File(outside, "retarget.c").writeText("int SECOND_PRIVATE_DATA = 1;\n")
                            val link = File(server.project, "linked.c").toPath()
                            Files.delete(link)
                            Files.createSymbolicLink(link, java.nio.file.Paths.get("../${outside.name}/retarget.c"))
                        }
                        reply(server, session, read, "once")
                    }
                    server.request("POST", "/api/experimental/session/$session/wait")
                    val messages = server.request("GET", "/api/session/$session/message")
                    assertTrue(model.errors.toString(), model.errors.isEmpty())
                    if (allow) assertTrue(messages.toString(), messages.toString().contains("EXTERNAL_LSP_ALLOWED_OK"))
                    else {
                        assertNoServers(server)
                        assertFalse("Read-denied source was exposed", messages.toString().contains("hidden_outside_identifier"))
                        assertFalse("Retargeted source was exposed", messages.toString().contains("SECOND_PRIVATE_DATA"))
                        val states = messages.getJSONArray("data").let { items -> (0 until items.length()).flatMap { index ->
                            (items.getJSONObject(index).optJSONArray("content") ?: JSONArray()).let { parts -> (0 until parts.length()).map(parts::getJSONObject) }
                        } }.filter { it.optString("type") == "tool" && it.optString("name") == "lsp" }.map { it.getJSONObject("state").getString("status") }
                        assertTrue("Denied LSP tool did not finish as an error: $states", states.any { it != "completed" })
                    }
                    assertEquals("int value(void) { return hidden_outside_identifier; }\n", source.readText())
                    instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nPASS actual external LSP ${if (allow) "directory -> read approvals -> clangd diagnostics" else if (retarget) "retargeted symlink rejected" else if (alias) "symlink target read denial" else "directory rejection"}; no language server or source read before permission.\n") })
                } finally {
                    server.stop()
                    check(outside.canonicalFile.parentFile == server.project.canonicalFile.parentFile)
                    Files.walk(outside.toPath()).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                }
            } }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun rule(action: String, resource: String, effect: String) = JSONObject().put("action", action).put("resource", resource).put("effect", effect)
    private fun pending(server: IsolatedAgentRuntime, session: String): JSONObject {
        val deadline = System.currentTimeMillis() + 30000
        do {
            val list = server.request("GET", "/api/session/$session/permission").getJSONArray("data")
            if (list.length() > 0) { assertEquals(1, list.length()); return list.getJSONObject(0) }
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        error("No actual pending LSP permission request")
    }
    private fun reply(server: IsolatedAgentRuntime, session: String, request: JSONObject, decision: String) {
        server.request("POST", "/api/session/$session/permission/${request.getString("id")}/reply", JSONObject().put("decision", decision))
    }
    private fun assertNoServers(server: IsolatedAgentRuntime) {
        val query = "?location%5Bdirectory%5D=" + URLEncoder.encode(server.directory, "UTF-8")
        val statuses = server.request("POST", "/api/rpc/opencode.android.lsp/status$query", JSONObject().put("input", JSONObject())).getJSONArray("output")
        for (i in 0 until statuses.length()) assertFalse("Permission gate was bypassed", statuses.getJSONObject(i).getBoolean("running"))
    }
}
