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
import java.net.URLEncoder

@RunWith(AndroidJUnit4::class)
class AgentExtensionsTest {
    @Test fun officialAgentInvokesStdioAndStreamableMcpPlusExternalPromisePlugin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            ScriptedModelFixture().use { model ->
                IsolatedAgentRuntime(context, model.port).use { server ->
                    try {
                        server.enableExtensions(instrumentation.context)
                        server.start()
                        val location = "?location%5Bdirectory%5D=" + URLEncoder.encode(server.directory, "UTF-8")
                        // The upstream MCP catalogs are asynchronous. Check their public readiness before prompting.
                        var catalog = JSONArray()
                        val deadline = System.currentTimeMillis() + 30_000
                        while (System.currentTimeMillis() < deadline) {
                            catalog = server.request("GET", "/api/mcp$location", timeout = 20000).getJSONArray("data")
                            if (catalog.length() == 3 && (0 until catalog.length()).all {
                                    catalog.getJSONObject(it).getJSONObject("status").getString("status") == "connected"
                                }) break
                            Thread.sleep(200)
                        }
                        assertEquals("MCP catalog: $catalog", 3, catalog.length())
                        for (index in 0 until catalog.length())
                            assertEquals("MCP catalog: $catalog", "connected", catalog.getJSONObject(index).getJSONObject("status").getString("status"))
                        // Connection status precedes the asynchronous tool registry update. Inspect the actual registry
                        // through a test plugin RPC rather than inducing a provider failure/retry or adding a fixed sleep.
                        var registry = ""
                        val registryDeadline = System.currentTimeMillis() + 15000
                        val fragments = listOf("stdio_fixture", "http_fixture", "sse_fixture", "fixture_plugin_double")
                        while (System.currentTimeMillis() < registryDeadline) {
                            registry = server.request("POST", "/api/rpc/android.fixture.rpc/catalog$location",
                                JSONObject().put("input", JSONObject())).getJSONArray("output").toString()
                            if (fragments.all { registry.contains(it) }) break
                            Thread.sleep(100)
                        }
                        assertTrue("Actual tool registry is incomplete: $registry", fragments.all { registry.contains(it) })
                        val session = server.request("POST", "/api/session", JSONObject()
                            .put("title", "MCP and plugin local protocol fixture")
                            .put("location", JSONObject().put("directory", server.directory))
                            .put("model", JSONObject().put("id", "fixture-chat").put("providerID", "android-fixture"))
                            .put("permissions", JSONArray().put(JSONObject().put("action", "*").put("resource", "*").put("effect", "allow"))))
                            .getJSONObject("data").getString("id")
                        server.request("POST", "/api/session/$session/prompt", JSONObject().put("text",
                            "EXTENSIONS_FIXTURE: Call each local stdio and Streamable HTTP (JSON and SSE response) MCP addition tool " +
                                "with 20 and 22, and the external plugin double tool with 21. This is a local scripted protocol test."))
                        server.request("POST", "/api/experimental/session/$session/wait")
                        val messages = server.request("GET", "/api/session/$session/message")
                        assertTrue("Extension loop failed: $messages; fixture errors=${model.errors}",
                            messages.toString().contains("MCP_AND_PLUGIN_ACCEPTANCE_OK"))
                        assertTrue(server.request("GET", "/api/plugin$location").toString().contains("android.fixture.extension"))
                        val receipts = server.extensionReceipts()
                        for (transport in listOf("stdio", "http", "sse", "plugin")) {
                            assertTrue("No real $transport result receipt: $receipts", receipts.any {
                                it.getString("transport") == transport && it.getString("event") == "call" && it.getInt("value") == 42
                            })
                        }
                        assertTrue("Promise tool hook did not run: $receipts", receipts.any {
                            it.getString("transport") == "plugin" && it.getString("event") == "execute.after" && it.getString("status") == "completed"
                        })
                        assertTrue("Fixture errors: ${model.errors}", model.errors.isEmpty())
                        instrumentation.sendStatus(2, Bundle().apply { putString("stream",
                            "\nPASS official dev Agent invoked actual Linux MCP stdio and Streamable HTTP (JSON/SSE responses), plus external Promise plugin/hook. Local scripted model only.\n") })
                    } catch (error: Throwable) {
                        println("Extension diagnostics: ${server.diagnostics()}\nFixture errors: ${model.errors}")
                        throw error
                    }
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
