package io.github.opencodeandroid.bridge

import io.github.opencodeandroid.i18n.nativeMessage

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.Build
import android.os.Process
import android.system.Os
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.files.FileSharing
import io.github.opencodeandroid.files.ProjectFiles
import io.github.opencodeandroid.runtime.RuntimePaths
import io.github.opencodeandroid.preview.PreviewTarget
import io.github.opencodeandroid.preview.isListening
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.*

data class BridgeRequest(val id: String, val action: String, val payload: JSONObject, val peerPid: Int, val processing: Boolean = false, val prepared: Uri? = null, val previewPort: Int? = null)

/** Authenticated Unix IPC only. There is no HTTP listener or WebView system API. */
object AndroidBridge {
    const val MAX_FRAME = 512 * 1024
    private const val CHANNEL = "android-bridge"
    private val leases = mutableSetOf<String>()
    private var server: Server? = null
    private val requests = MutableStateFlow<List<BridgeRequest>>(emptyList())
    val pending = requests.asStateFlow()
    private val focus = MutableStateFlow<String?>(null)
    val focused = focus.asStateFlow()
    @Volatile var foreground = false

    @Synchronized fun acquire(context: Context, owner: String) {
        if (owner in leases) return
        if (server == null) server = Server(context.applicationContext)
        leases.add(owner)
    }
    @Synchronized fun release(owner: String) {
        leases.remove(owner)
        if (leases.isEmpty()) { server?.close(); server = null }
    }
    fun focus(id: String?) { focus.value = id }
    @Synchronized fun complete(id: String, status: String = "ok", value: JSONObject = JSONObject(), error: String? = null) {
        server?.complete(id, JSONObject().put("status", status).put("value", value).apply { if (error != null) put("error", error) })
    }
    fun cancel(id: String) = complete(id, "cancelled")
    fun fail(id: String) = complete(id, "error", error = "operation_failed")

    fun url(value: String): URI {
        require(value.length <= 8192 && value.none { it.code < 32 || it.code == 127 })
        val uri = URI(value)
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535))
        return uri
    }
    fun projectPath(value: String): String {
        require(value == "/workspace" || value.startsWith("/workspace/")) { "Only /workspace paths are allowed" }
        val relative = value.removePrefix("/workspace").removePrefix("/")
        require(relative.split('/').none { it == "." || it == ".." || it.contains('\\') || it.contains('\u0000') } && (relative.isEmpty() || relative.split('/').none { it.isBlank() }))
        return relative
    }
    private fun validate(action: String, payload: JSONObject) {
        when (action) {
            "open-url" -> url(payload.getString("url"))
            "preview" -> PreviewTarget.fromValue(payload.get("port"))
            "clipboard-get" -> Unit
            "clipboard-set" -> require(payload.getString("text").toByteArray().size <= 65536)
            "notify" -> require(payload.getString("title").length in 1..200 && payload.optString("text").length <= 2000)
            "share-file", "file-export" -> require(projectPath(payload.getString("path")).isNotEmpty())
            "file-import" -> projectPath(payload.getString("directory"))
            else -> error("Unsupported bridge action")
        }
    }

    @Synchronized fun prepareShare(id: String) {
        val owner = server ?: return
        owner.work(id, finish = false) { request ->
            val uri = FileSharing.snapshot(owner.context, projectPath(request.payload.getString("path")))
            synchronized(this) { requests.value = requests.value.map { if (it.id == id) it.copy(processing = false, prepared = uri) else it } }
            JSONObject()
        }
    }
    @Synchronized fun preparePreview(id: String) {
        val owner = server ?: return
        owner.work(id, finish = false) { request ->
            check(request.action == "preview")
            val target = PreviewTarget.fromValue(request.payload.get("port"))
            if (!target.isListening()) {
                complete(id, "error", error = "preview_not_listening")
                return@work JSONObject()
            }
            currentCoroutineContext().ensureActive()
            synchronized(this) {
                requests.value = requests.value.map { if (it.id == id) it.copy(processing = false, previewPort = target.port) else it }
            }
            JSONObject()
        }
    }
    @Synchronized fun consumePreview(id: String): Int? {
        val request = requests.value.firstOrNull { it.id == id && it.action == "preview" } ?: return null
        val port = request.previewPort ?: return null
        requests.value = requests.value.map { if (it.id == id) it.copy(processing = true, previewPort = null) else it }
        return port
    }
    @Synchronized fun consumePrepared(id: String): Uri? {
        val request = requests.value.firstOrNull { it.id == id } ?: return null
        val uri = request.prepared ?: return null
        requests.value = requests.value.map { if (it.id == id) it.copy(processing = true, prepared = null) else it }
        return uri
    }
    @Synchronized fun importFiles(id: String, uris: List<Uri>) {
        val owner = server ?: return
        owner.work(id) { request ->
            val directory = projectPath(request.payload.getString("directory"))
            val repository = owner.repository()
            val paths = org.json.JSONArray()
            for (uri in uris.take(64)) {
                currentCoroutineContext().ensureActive(); require(uri.scheme == "content")
                val name = owner.context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "imported-file"
                val imported = owner.context.contentResolver.openInputStream(uri)?.use { repository.import(directory, name, it) } ?: error("Cannot open document")
                paths.put("/workspace/" + repository.child(directory, imported))
            }
            JSONObject().put("paths", paths)
        }
    }
    @Synchronized fun exportFile(id: String, uri: Uri) {
        val owner = server ?: return
        owner.work(id) { request ->
            require(uri.scheme == "content")
            val relative = projectPath(request.payload.getString("path"))
            owner.context.contentResolver.openOutputStream(uri, "wt")?.use { owner.repository().export(relative, it) } ?: error("Cannot write document")
            JSONObject().put("path", "/workspace/$relative")
        }
    }

    private class Entry(val result: CompletableFuture<JSONObject>, var job: Job? = null)
    private class Server(val context: Context) : AutoCloseable {
        private val paths = RuntimePaths(context).apply { prepare() }
        private val token = android.util.Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        private val name = "opencode-android-${Process.myUid()}-${UUID.randomUUID()}"
        private val socket = LocalServerSocket(name)
        private val entries = ConcurrentHashMap<String, Entry>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val workers = ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, SynchronousQueue(), { runnable -> Thread(runnable, "android-bridge-client").apply { isDaemon = true } })
        @Volatile private var active = true
        init {
            try {
                installHelpers()
                getNotifications().createNotificationChannel(NotificationChannel(CHANNEL, context.nativeMessage("开发环境系统操作"), NotificationManager.IMPORTANCE_LOW))
                Thread({
                    while (active) {
                        val client = try { socket.accept() } catch (_: java.io.IOException) { break }
                        if (!active) { client.close(); break }
                        try { workers.execute { handle(client) } } catch (_: RejectedExecutionException) { client.close() }
                    }
                }, "android-bridge-listener").apply { isDaemon = true; start() }
            } catch (failure: Exception) {
                active = false; runCatching { socket.close() }; scope.cancel(); workers.shutdownNow(); throw failure
            }
        }
        fun repository() = ProjectFiles(paths.workspace, File(paths.backups, "project-trash"))
        private fun getNotifications() = context.getSystemService(NotificationManager::class.java)
        private fun notificationId(id: String) = 2000 + (id.hashCode() and 0x7fffff)
        private fun installHelpers() {
            val directory = File(paths.config, "bin")
            check(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
            check(directory.isDirectory || directory.mkdir())
            check(!java.nio.file.Files.isSymbolicLink(File(paths.config, "android-bridge.py").toPath()))
            context.assets.open("guest/android-bridge.py").use { input -> File(paths.config, "android-bridge.py").outputStream().use { input.copyTo(it) } }
            val script = "#!/bin/sh\nexec /usr/bin/python3 /android-runtime/android-bridge.py \"\${0##*/}\" \"\$@\"\n"
            for (command in listOf("xdg-open", "android-open", "android-preview", "android-clipboard", "android-notify", "android-share", "android-import", "android-export")) {
                val file = File(directory, command)
                check(!java.nio.file.Files.isSymbolicLink(file.toPath()))
                file.writeText(script); Os.chmod(file.path, 493)
            }
            val atomic = AtomicFile(File(paths.config, "bridge-auth.json"))
            for (suffix in listOf("", ".new", ".bak")) check(!java.nio.file.Files.isSymbolicLink(File(paths.config, "bridge-auth.json$suffix").toPath()))
            val output = atomic.startWrite()
            try {
                output.write(JSONObject().put("version", 1).put("socket", name).put("token", token).toString().toByteArray())
                atomic.finishWrite(output); Os.chmod(atomic.baseFile.path, 384)
            } catch (failure: Exception) { atomic.failWrite(output); throw failure }
        }
        private fun reply(client: LocalSocket, response: JSONObject) {
            val encoded = response.toString().toByteArray()
            require(encoded.size <= MAX_FRAME)
            client.outputStream.write(encoded); client.outputStream.write(10); client.outputStream.flush()
        }
        private fun handle(client: LocalSocket) {
            client.use {
                var id: String? = null
                try {
                    client.soTimeout = 4000
                    if (client.peerCredentials.uid != Process.myUid()) { reply(client, JSONObject().put("status", "error").put("error", "unauthorized_peer")); return }
                    val input = BufferedInputStream(client.inputStream)
                    val bytes = ByteArrayOutputStream()
                    while (true) {
                        val byte = input.read(); require(byte >= 0)
                        if (byte == 10) break
                        require(bytes.size() < MAX_FRAME); bytes.write(byte)
                    }
                    val message = JSONObject(bytes.toString("UTF-8"))
                    require(message.optInt("version") == 1 && MessageDigest.isEqual(message.optString("token").toByteArray(), token.toByteArray()))
                    val action = message.getString("action")
                    val payload = message.getJSONObject("payload")
                    validate(action, payload)
                    id = UUID.randomUUID().toString()
                    val request = BridgeRequest(id, action, payload, client.peerCredentials.pid)
                    val entry = Entry(CompletableFuture())
                    entries[id] = entry
                    synchronized(AndroidBridge) { check(active); requests.value = requests.value + request }
                    if (!foreground) notifyPending(request)
                    Thread({
                        while (!entry.result.isDone) {
                            try {
                                val descriptor = android.system.StructPollfd().apply {
                                    fd = client.fileDescriptor
                                    events = (android.system.OsConstants.POLLIN or android.system.OsConstants.POLLHUP or android.system.OsConstants.POLLERR).toShort()
                                }
                                if (Os.poll(arrayOf(descriptor), 1000) > 0 && !entry.result.isDone) {
                                    // One request per connection. EOF or additional input cancels it.
                                    entry.result.complete(JSONObject().put("status", "cancelled")); break
                                }
                            } catch (failure: android.system.ErrnoException) {
                                if (failure.errno != android.system.OsConstants.EINTR) { entry.result.complete(JSONObject().put("status", "cancelled")); break }
                            } catch (_: java.io.IOException) { entry.result.complete(JSONObject().put("status", "cancelled")); break }
                        }
                    }, "android-bridge-disconnect").apply { isDaemon = true; start() }
                    reply(client, entry.result.get(600, TimeUnit.SECONDS))
                } catch (_: Exception) {
                    runCatching { reply(client, JSONObject().put("status", "error").put("error", "invalid_or_expired_request")) }
                } finally { id?.let { complete(it, JSONObject().put("status", "cancelled")) } }
            }
        }
        fun complete(id: String, response: JSONObject) {
            val entry = entries.remove(id) ?: return
            entry.result.complete(response); entry.job?.cancel()
            synchronized(AndroidBridge) { requests.value = requests.value.filterNot { it.id == id }; if (focus.value == id) focus.value = null }
            getNotifications().cancel(notificationId(id))
        }
        fun work(id: String, finish: Boolean = true, action: suspend (BridgeRequest) -> JSONObject) {
            val request = requests.value.firstOrNull { it.id == id } ?: return
            val entry = entries[id] ?: return
            if (request.processing) return
            requests.value = requests.value.map { if (it.id == id) it.copy(processing = true) else it }
            entry.job = scope.launch {
                try {
                    val value = action(request)
                    if (finish) AndroidBridge.complete(id, value = value)
                } catch (_: kotlinx.coroutines.CancellationException) { }
                catch (_: Exception) { AndroidBridge.fail(id) }
            }
        }
        private fun notifyPending(request: BridgeRequest) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("bridge-request-id", request.id)
            val open = PendingIntent.getActivity(context, notificationId(request.id), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            getNotifications().notify(notificationId(request.id), NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle(context.nativeMessage("Debian 请求系统操作")).setContentText(context.nativeMessage("点按查看并处理请求")).setContentIntent(open).setAutoCancel(true).build())
        }
        override fun close() {
            active = false
            // Wake accept before closing its descriptor; no listener thread survives a lease.
            runCatching { LocalSocket().use { it.connect(android.net.LocalSocketAddress(name, android.net.LocalSocketAddress.Namespace.ABSTRACT)) } }
            runCatching { socket.close() }
            entries.keys.toList().forEach { complete(it, JSONObject().put("status", "error").put("error", "environment_stopped")) }
            scope.cancel(); workers.shutdownNow()
            AtomicFile(File(paths.config, "bridge-auth.json")).delete()
        }
    }
}
