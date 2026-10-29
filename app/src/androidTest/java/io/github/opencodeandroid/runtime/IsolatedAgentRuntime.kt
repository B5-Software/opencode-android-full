package io.github.opencodeandroid.runtime

import android.content.Context
import android.system.Os
import android.util.Base64
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Test-only service namespace. Never changes the user's home, database, provider or credentials. */
internal class IsolatedAgentRuntime(context: Context, modelPort: Int) : Closeable {
    private val paths = RuntimePaths(context).apply { prepare() }
    private val suffix = UUID.randomUUID().toString()
    private val root = File(paths.config, "agent-fixture-$suffix").apply { check(mkdir()) }
    val project = File(paths.workspace, ".agent-fixture-$suffix").apply { check(mkdir()) }
    val directory = "/workspace/${project.name}"
    private val guest = "/android-runtime/${root.name}"
    private val password = UUID.randomUUID().toString()
    private val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    private var child: NativeProcess? = null
    private var reader: Thread? = null
    private var extensionChild: NativeProcess? = null
    private var extensionReader: Thread? = null
    private val output = StringBuilder()

    init {
        check(paths.installed) { "Install the bundled Debian and official backend first" }
        listOf("home", "data", "cache", "state", "tmp", "config/opencode").forEach {
            check(File(root, it).mkdirs())
        }
        // This is the upstream dev channel's configuration file, not a child-process secret env var.
        privateFile("config/opencode/service-dev.json", JSONObject().put("password", password)
            .put("hostname", "127.0.0.1").put("port", port))
        val model = JSONObject().put("name", "Local scripted protocol fixture (not a cloud model)")
            .put("capabilities", JSONObject().put("tools", true))
            .put("limit", JSONObject().put("context", 200000).put("output", 16000))
        val provider = JSONObject().put("name", "Android acceptance fixture")
            .put("package", "@opencode/ai/providers/openai-compatible")
            .put("settings", JSONObject().put("apiKey", "fixture-no-credentials")
                .put("baseURL", "http://127.0.0.1:$modelPort/v1"))
            .put("models", JSONObject().put("fixture-chat", model))
        privateFile("config/opencode/opencode.json", JSONObject()
            .put("model", "android-fixture/fixture-chat")
            .put("providers", JSONObject().put("android-fixture", provider)))
    }

    private fun privateFile(name: String, json: JSONObject) {
        val file = File(root, name)
        file.writeText(json.toString())
        Os.chmod(file.path, 0x180)
    }

    fun enableExtensions(testContext: Context) {
        val extension = File(root, "extensions").apply { check(mkdir()) }
        listOf("mcp.py", "plugin/package.json", "plugin/index.mjs").forEach { name ->
            val target = File(extension, name)
            target.parentFile!!.mkdirs()
            testContext.assets.open("agent-fixtures/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        val base = "$guest/extensions"
        val fixtureToken = UUID.randomUUID().toString()
        val process = ProotCommand(paths).process(listOf("/usr/bin/python3", "-u", "$base/mcp.py", "--http",
            "$base/receipts.ndjson", "$base/ready.json", fixtureToken)).start()
        extensionChild = process
        extensionReader = Thread({ process.inputStream.bufferedReader().use { it.readText() } }, "mcp-fixture-log")
            .apply { isDaemon = true; start() }
        val ready = File(extension, "ready.json")
        val deadline = System.currentTimeMillis() + 10000
        while (!ready.isFile && process.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(50)
        check(ready.isFile) { "Linux HTTP MCP fixture failed to start" }
        val httpPort = JSONObject(ready.readText()).getInt("port")
        fun remote(endpoint: String) = JSONObject().put("type", "remote").put("url", "http://127.0.0.1:$httpPort/$endpoint")
            .put("oauth", false).put("codemode", false)
            .put("headers", JSONObject().put("Authorization", "Bearer $fixtureToken"))
        val servers = JSONObject()
            .put("stdio_fixture", JSONObject().put("type", "local").put("codemode", false)
                .put("command", org.json.JSONArray().put("/usr/bin/python3").put("-u").put("$base/mcp.py")
                    .put("--stdio").put("$base/receipts.ndjson")))
            .put("http_fixture", remote("mcp")).put("sse_fixture", remote("sse"))
        val file = File(root, "config/opencode/opencode.json")
        privateFile("config/opencode/opencode.json", JSONObject(file.readText())
            .put("mcp", JSONObject().put("servers", servers))
            .put("plugins", org.json.JSONArray().put(JSONObject().put("package", "$base/plugin")
                .put("options", JSONObject().put("receipt", "$base/receipts.ndjson")))))
    }

    fun extensionReceipts(): List<JSONObject> = File(root, "extensions/receipts.ndjson").readLines()
        .filter { it.isNotBlank() }.map { JSONObject(it) }

    fun enableBundledLsp(context: Context) = BundledPlugins.install(context.assets, File(root, "config/opencode"))

    fun start() {
        check(child == null)
        synchronized(output) { output.setLength(0) }
        val process = ProotCommand(paths).process(listOf("/usr/local/bin/opencode", "serve", "--service",
            "--hostname", "127.0.0.1", "--port", port.toString()), mapOf(
            "HOME" to "$guest/home", "OPENCODE_TEST_HOME" to "$guest/home",
            "XDG_DATA_HOME" to "$guest/data", "XDG_CACHE_HOME" to "$guest/cache",
            "XDG_STATE_HOME" to "$guest/state", "XDG_CONFIG_HOME" to "$guest/config",
            "OPENCODE_CONFIG_DIR" to "$guest/config/opencode", "TMPDIR" to "$guest/tmp",
            "OPENCODE_CONFIG_PROJECT_DISABLE" to "1", "OPENCODE_DISABLE_CLAUDE_CODE" to "1",
            "OPENCODE_DISABLE_AUTOUPDATE" to "1", "OPENCODE_DISABLE_MODELS_FETCH" to "1"
        )).start()
        child = process
        reader = Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> synchronized(output) {
                    output.append(line.replace(password, "[REDACTED]")).append('\n')
                    if (output.length > 16000) output.delete(0, output.length - 16000)
                } }
            }
        }, "agent-fixture-server-log").apply { isDaemon = true; start() }
        val deadline = System.currentTimeMillis() + 90_000
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            if (runCatching { request("GET", "/api/info", timeout = 3000).getString("version") }
                    .getOrNull()?.startsWith("2.1.4-dev.") == true) return
            Thread.sleep(250)
        }
        error("Isolated official backend did not become healthy: ${diagnostics()}")
    }

    fun request(method: String, path: String, body: JSONObject? = null, timeout: Int = 120_000): JSONObject {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 3000
        connection.readTimeout = timeout
        connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(
            "opencode:$password".toByteArray(), Base64.NO_WRAP))
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "$method $path returned $code: ${text.take(3000)}" }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }

    fun stop() {
        val process = child ?: return
        process.destroyForcibly()
        check(process.waitFor(15, TimeUnit.SECONDS)) { "Fixture backend failed to terminate" }
        reader?.join(2000)
        child = null
    }

    fun diagnostics(): String = synchronized(output) { output.toString().takeLast(5000) }

    override fun close() {
        stop()
        extensionChild?.let { process ->
            process.destroyForcibly()
            check(process.waitFor(15, TimeUnit.SECONDS))
            extensionReader?.join(2000)
        }
        deleteOwned(project, paths.workspace)
        deleteOwned(root, paths.config)
    }

    private fun deleteOwned(file: File, parent: File) {
        check(file.canonicalFile.parentFile == parent.canonicalFile)
        // Do not follow links, including any produced by an actual guest tool.
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(path); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(path); return FileVisitResult.CONTINUE
            }
        })
    }
}
