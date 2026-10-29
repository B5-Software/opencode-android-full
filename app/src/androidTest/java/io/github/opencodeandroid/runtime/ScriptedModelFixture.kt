package io.github.opencodeandroid.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A deterministic OpenAI Chat/SSE protocol fixture. It cannot execute tools or write project files. */
internal class ScriptedModelFixture : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val workers = Executors.newFixedThreadPool(3)
    val requests = CopyOnWriteArrayList<JSONObject>()
    val errors = CopyOnWriteArrayList<String>()
    @Volatile private var closed = false
    private val acceptor = Thread({
        while (!closed) {
            val socket = try { server.accept() } catch (_: java.io.IOException) { break }
            workers.execute { runCatching { serve(socket) }.onFailure { errors += it.toString() } }
        }
    }, "scripted-model-fixture").apply { isDaemon = true; start() }

    private fun serve(socket: Socket) = socket.use {
        socket.soTimeout = 15000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = line(input)
        check(requestLine.startsWith("POST /v1/chat/completions ")) { "Unexpected fixture route: $requestLine" }
        val headers = mutableMapOf<String, String>()
        var count = 0
        while (true) {
            check(++count <= 80) { "Too many HTTP headers" }
            val value = line(input)
            if (value.isEmpty()) break
            headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim()
        }
        val length = headers["content-length"]?.toInt() ?: error("Fixture requires a bounded Content-Length")
        check(length in 1..4_194_304)
        val bytes = ByteArray(length)
        java.io.DataInputStream(input).readFully(bytes)
        val request = JSONObject(bytes.toString(Charsets.UTF_8))
        requests += request
        // An assertion failure is a valid terminal response, so the backend does not waste time retrying
        // a deliberately reset socket. The acceptance test still fails on errors or a missing success marker.
        val chunks = try { response(request) } catch (error: Throwable) {
            errors += error.toString()
            text("FIXTURE_ASSERTION_FAILED: ${error.message?.take(4000)}")
        }
        val body = (chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n").toByteArray()
        socket.getOutputStream().use { output ->
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            output.write(body)
            output.flush()
        }
    }

    private fun response(request: JSONObject): List<JSONObject> {
        val messages = request.getJSONArray("messages").objects()
        val tools = request.optJSONArray("tools")?.objects().orEmpty()
        // Title generation is a separate upstream request and must not advance the tool script.
        if (tools.isEmpty()) return text("Android local acceptance")
        val user = messages.filter { it.optString("role") == "user" }.joinToString("\n") { it.optString("content") }
        val results = messages.filter { it.optString("role") == "tool" }
        fun result(id: String) = results.lastOrNull { it.optString("tool_call_id").endsWith(id) }
        if (user.contains("EXTERNAL_LSP_FIXTURE:")) {
            val settings = JSONObject(user.substringAfter("EXTERNAL_LSP_FIXTURE:").trim())
            if (result("external_lsp_query") == null) return call("external_lsp_query", "lsp", JSONObject()
                .put("operation", "diagnostics").put("filePath", settings.getString("file")))
            if (settings.getBoolean("allow")) {
                val response = modelToolJson(result("external_lsp_query")!!)
                check(response.getJSONArray("results").toString().contains("hidden_outside_identifier")) { "Actual external clangd diagnostics missing" }
                return text("EXTERNAL_LSP_ALLOWED_OK")
            }
            return text("EXTERNAL_LSP_DENIED_OK")
        }
        if (user.contains("LSP_READ_DENIAL_FIXTURE")) {
            if (result("lsp_denied_write") == null) return call("lsp_denied_write", "write", JSONObject()
                .put("path", "private.c").put("content", "int value(void) { return missing_identifier; }\n"))
            if (result("lsp_denied_query") == null) return call("lsp_denied_query", "lsp", JSONObject()
                .put("operation", "diagnostics").put("filePath", "private.c"))
            return text("LSP_READ_DENIAL_RECORDED")
        }
        if (user.contains("PYLSP_FIXTURE")) {
            if (result("python_broken") == null) return call("python_broken", "write", JSONObject()
                .put("path", PYTHON_FILE).put("content", PYTHON_BROKEN))
            if (result("python_bad_diagnostics") == null) return call("python_bad_diagnostics", "lsp", JSONObject()
                .put("operation", "diagnostics").put("filePath", PYTHON_FILE))
            if (result("python_fixed") == null) {
                check(result("python_bad_diagnostics").toString().contains("missing_identifier")) { "Real Python undefined-name diagnostic missing: ${result("python_bad_diagnostics")}" }
                check(result("python_broken").toString().contains("LSP diagnostics")) { "Python write feedback missing" }
                return call("python_fixed", "write", JSONObject().put("path", PYTHON_FILE).put("content", PYTHON_FIXED))
            }
            if (result("python_hover") == null) {
                val args = JSONObject().put("filePath", PYTHON_FILE).put("line", 5).put("character", 10)
                return calls(listOf(
                    tool("python_clean", "lsp", JSONObject().put("operation", "diagnostics").put("filePath", PYTHON_FILE)),
                    tool("python_definition", "lsp", JSONObject(args.toString()).put("operation", "goToDefinition")),
                    tool("python_hover", "lsp", JSONObject(args.toString()).put("operation", "hover"))))
            }
            val clean = modelToolJson(result("python_clean")!!).getJSONArray("results").getJSONObject(0)
            check(clean.getJSONArray("diagnostics").length() == 0) { "Fixed Python source is not clean: $clean" }
            val definition = modelToolJson(result("python_definition")!!).getJSONArray("results").getJSONObject(0).getJSONArray("result")
            check(definition.length() > 0 && definition.toString().contains("file:")) { "Python definition lookup failed: $definition" }
            check(result("python_hover").toString().contains("add")) { "Python hover missing" }
            return text("PYTHON_LANGUAGE_SERVER_ACCEPTANCE_OK")
        }
        if (user.contains("LANGUAGE_SERVER_FIXTURE")) {
            if (result("lsp_write_broken") == null) return calls(listOf(
                tool("lsp_write_header", "write", JSONObject().put("path", "calc.h").put("content", HEADER)),
                tool("lsp_write_broken", "write", JSONObject().put("path", "calc.c").put("content", LSP_BROKEN))))
            if (result("lsp_bad_diagnostics") == null)
                return call("lsp_bad_diagnostics", "lsp", JSONObject().put("operation", "diagnostics").put("filePath", "calc.c"))
            if (result("lsp_write_fixed") == null) {
                check(result("lsp_bad_diagnostics").toString().contains("missing_identifier")) { "Actual clangd semantic error missing: ${result("lsp_bad_diagnostics")}" }
                // The write's execute.after hook must also deliver real diagnostics, not merely an explicit query.
                check(result("lsp_write_broken").toString().contains("LSP diagnostics") &&
                    result("lsp_write_broken").toString().contains("missing_identifier")) { "Write hook did not return actual clangd diagnostics" }
                return calls(listOf(tool("lsp_write_fixed", "write", JSONObject().put("path", "calc.c").put("content", FIXED)),
                    tool("lsp_write_main", "write", JSONObject().put("path", "main.c").put("content", LSP_MAIN))))
            }
            if (result("lsp_symbols") == null) {
                val args = JSONObject().put("filePath", "main.c").put("line", 2).put("character", LSP_MAIN.lines()[1].indexOf("add(") + 1)
                return calls(listOf(
                    tool("lsp_clean", "lsp", JSONObject().put("operation", "diagnostics").put("filePath", "calc.c")),
                    tool("lsp_definition", "lsp", JSONObject(args.toString()).put("operation", "goToDefinition")),
                    tool("lsp_references", "lsp", JSONObject(args.toString()).put("operation", "findReferences")),
                    tool("lsp_hover", "lsp", JSONObject(args.toString()).put("operation", "hover")),
                    tool("lsp_symbols", "lsp", JSONObject(args.toString()).put("operation", "documentSymbol"))))
            }
            fun content(id: String): String {
                val value = result(id)?.opt("content") ?: error("Missing LSP tool result $id")
                return if (value is JSONArray) value.objects().joinToString("\n") { it.optString("text") } else value.toString()
            }
            val clean = JSONObject(content("lsp_clean")).getJSONArray("results").getJSONObject(0)
            check(clean.getJSONArray("diagnostics").length() == 0) { "Fixed source still has diagnostics: $clean" }
            val definition = JSONObject(content("lsp_definition")).getJSONArray("results").getJSONObject(0).getJSONArray("result")
            check(definition.length() > 0 && definition.toString().contains("calc.")) { "Definition did not resolve to real project source: $definition" }
            val references = JSONObject(content("lsp_references")).getJSONArray("results").getJSONObject(0).getJSONArray("result")
            check(references.length() > 0 && references.toString().contains("main.c")) { "Reference lookup failed: $references" }
            check(content("lsp_hover").contains("add") && content("lsp_symbols").contains("main")) { "Hover/symbols missing" }
            return text("LANGUAGE_SERVER_ACCEPTANCE_OK")
        }
        if (user.contains("EXTENSIONS_FIXTURE")) {
            if (result("invoke_plugin") != null) {
                mapOf("invoke_stdio" to "MCP_STDIO_42", "invoke_http" to "MCP_HTTP_42",
                    "invoke_sse" to "MCP_SSE_42", "invoke_plugin" to "PLUGIN_RESULT_42").forEach { (id, expected) ->
                    check(result(id).toString().contains(expected)) { "Real extension result missing $expected: ${result(id)}" }
                }
                return text("MCP_AND_PLUGIN_ACCEPTANCE_OK")
            }
            val names = tools.map { it.getJSONObject("function").getString("name") }
            fun name(fragment: String) = names.singleOrNull { it.contains(fragment) }
                ?: error("Extension tool $fragment missing or ambiguous: $names")
            val args = JSONObject().put("a", 20).put("b", 22)
            return calls(listOf(tool("invoke_stdio", name("stdio_fixture"), args),
                tool("invoke_http", name("http_fixture"), args), tool("invoke_sse", name("sse_fixture"), args),
                tool("invoke_plugin", name("fixture_plugin_double"), JSONObject().put("value", 21))))
        }
        if (user.contains("PERMISSION_APPROVE_FIXTURE") || user.contains("PERMISSION_REJECT_FIXTURE")) {
            val reject = user.contains("PERMISSION_REJECT_FIXTURE")
            if (result("approval_probe") != null) return text(if (reject) "PERMISSION_REJECTED_OK" else "PERMISSION_APPROVED_OK")
            return call("approval_probe", "shell", JSONObject().put("command",
                if (reject) "printf SHOULD_NOT_RUN > rejected.txt" else "printf APPROVED > approved.txt"))
        }
        if (user.contains("CONTINUATION_FIXTURE")) {
            check(messages.any { it.toString().contains("LOCAL_AGENT_ACCEPTANCE_OK") }) { "Restored model context lost earlier answer" }
            check(result("run_fixed_test") != null) { "Restored model context lost tool history" }
            return text("PERSISTED_CONTEXT_OK")
        }
        if (result("write_makefile") == null) {
            val supplied = tools.map { it.getJSONObject("function").getString("name") }
            check(supplied.containsAll(listOf("write", "shell"))) { "Official tool inventory missing write/shell: $supplied" }
            return calls(listOf(
                tool("write_header", "write", JSONObject().put("path", "calc.h").put("content", HEADER)),
                tool("write_broken_source", "write", JSONObject().put("path", "calc.c").put("content", BROKEN)),
                tool("write_test", "write", JSONObject().put("path", "test.c").put("content", TEST)),
                tool("write_makefile", "write", JSONObject().put("path", "Makefile").put("content", MAKEFILE))
            ))
        }
        if (result("run_failing_test") == null)
            return call("run_failing_test", "shell", JSONObject().put("command", "make test").put("timeout", 30000))
        if (result("write_fixed_source") == null) {
            check(result("run_failing_test").toString().contains("INTENTIONAL_TEST_FAILURE")) {
                "The actual shell did not return the expected failing test: ${result("run_failing_test")}"
            }
            return call("write_fixed_source", "write", JSONObject().put("path", "calc.c").put("content", FIXED))
        }
        if (result("run_fixed_test") == null) return call("run_fixed_test", "shell", JSONObject().put("timeout", 30000)
            .put("command", "make test && git init -b main . >/dev/null && " +
                "git add calc.h calc.c test.c Makefile && " +
                "git -c user.name=AndroidFixture -c user.email=fixture@example.invalid commit -m 'Local agent acceptance' >/dev/null && " +
                "git init --bare .local-origin.git >/dev/null && git remote add origin .local-origin.git && " +
                "git push -u origin main && printf 'AGENT_GIT_PUSH_OK\\n'"))
        check(result("run_fixed_test").toString().contains("AGENT_TESTS_OK") &&
            result("run_fixed_test").toString().contains("AGENT_GIT_PUSH_OK")) { "Actual test/Git tools failed: ${result("run_fixed_test")}" }
        return text("LOCAL_AGENT_ACCEPTANCE_OK")
    }

    private fun tool(id: String, name: String, args: JSONObject) = JSONObject().put("id", "call_$id")
        .put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))

    private fun call(id: String, name: String, args: JSONObject) = calls(listOf(tool(id, name, args)))

    private fun calls(tools: List<JSONObject>): List<JSONObject> {
        val delta = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().also { array ->
            tools.forEachIndexed { index, tool -> array.put(tool.put("index", index)) }
        })
        return listOf(chunk(delta), chunk(JSONObject(), "tool_calls"), usage())
    }

    private fun text(value: String) = listOf(chunk(JSONObject().put("role", "assistant").put("content", value)),
        chunk(JSONObject(), "stop"), usage())

    private fun chunk(delta: JSONObject, finish: String? = null) = JSONObject().put("id", "chatcmpl-android-fixture")
        .put("object", "chat.completion.chunk").put("created", 1700000000).put("model", "fixture-chat")
        .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", delta)
            .put("finish_reason", finish ?: JSONObject.NULL)))

    private fun usage() = JSONObject().put("id", "chatcmpl-android-fixture").put("choices", JSONArray())
        .put("usage", JSONObject().put("prompt_tokens", 64).put("completion_tokens", 64).put("total_tokens", 128))

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun modelToolJson(tool: JSONObject): JSONObject {
        val value = tool.get("content")
        val text = if (value is JSONArray) value.objects().joinToString("\n") { it.optString("text") } else value.toString()
        return JSONObject(text)
    }

    private fun line(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val next = input.read()
            check(next != -1) { "Unexpected end of HTTP request" }
            if (next == 10) return bytes.toByteArray().toString(Charsets.UTF_8).removeSuffix("\r")
            check(bytes.size < 16384)
            bytes += next.toByte()
        }
    }

    override fun close() {
        closed = true
        server.close()
        acceptor.join(2000)
        workers.shutdownNow()
        check(workers.awaitTermination(20, TimeUnit.SECONDS))
    }

    companion object {
        const val HEADER = "#ifndef CALC_H\n#define CALC_H\nint add(int a, int b);\n#endif\n"
        const val BROKEN = "#include \"calc.h\"\nint add(int a, int b) { return a - b; }\n"
        const val FIXED = "#include \"calc.h\"\nint add(int a, int b) { return a + b; }\n"
        const val TEST = "#include \"calc.h\"\n#include <stdio.h>\nint main(void) {\n" +
            "  if (add(2, 3) != 5) { fputs(\"INTENTIONAL_TEST_FAILURE\\n\", stderr); return 1; }\n" +
            "  puts(\"AGENT_TESTS_OK\"); return 0;\n}\n"
        const val MAKEFILE = "test: test-runner\n\t./test-runner\n\n" +
            "test-runner: calc.c calc.h test.c\n\tcc -std=c11 -Wall -Werror calc.c test.c -o test-runner\n\n.PHONY: test\n"
        const val LSP_BROKEN = "#include \"calc.h\"\nint add(int a, int b) { return a + missing_identifier; }\n"
        const val LSP_MAIN = "#include \"calc.h\"\nint main(void) { return add(2, 3); }\n"
        const val PYTHON_FILE = "示例.py"
        const val PYTHON_BROKEN = "def add(a, b):\n    return a + missing_identifier\n\n\nresult = add(2, 3)\n"
        const val PYTHON_FIXED = "def add(a, b):\n    return a + b\n\n\nresult = add(2, 3)\n"
    }
}
