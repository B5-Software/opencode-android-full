package io.github.opencodeandroid.runtime

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The project's directory, independent of the official service daemon's HOME working directory. */
object WorkspaceContext {
    const val DEFAULT_DIRECTORY = "/workspace"
    const val STARTER_DIRECTORY = "/workspace/helloworld"
    private val mutable = MutableStateFlow(STARTER_DIRECTORY)
    val directory = mutable.asStateFlow()
    private var preferences: android.content.SharedPreferences? = null
    private var selectionStore: WorkspaceSelectionStore? = null
    @Volatile var generation = 0L
        private set

    @Synchronized fun initialize(context: Context) {
        if (preferences != null) return
        val settings = context.applicationContext.getSharedPreferences("workspace-context", Context.MODE_PRIVATE)
        preferences = settings
        selectionStore = WorkspaceSelectionStore(settings)
        mutable.value = selectionStore!!.read().directory
    }

    @Synchronized fun select(directory: String) {
        val value = validDirectory(directory)
        mutable.value = value
        generation++
        selectionStore?.select(value)
    }

    @Synchronized fun reload(context: Context) {
        val settings = context.applicationContext.getSharedPreferences("workspace-context", Context.MODE_PRIVATE)
        preferences = settings
        selectionStore = WorkspaceSelectionStore(settings)
        mutable.value = selectionStore!!.read().directory
        generation++
    }

    @Synchronized fun selectIfCurrent(directory: String, expectedGeneration: Long) {
        if (generation == expectedGeneration) select(directory)
    }

    @Synchronized internal fun selection() = selectionStore?.read() ?: WorkspaceSelection(mutable.value)
    @Synchronized internal fun launchSelection() = selection() to generation

    @Synchronized internal fun rememberSessionIfCurrent(directory: String, session: String, expectedGeneration: Long): Boolean {
        if (generation != expectedGeneration) return false
        val value = validDirectory(directory)
        checkNotNull(selectionStore).remember(value, session)
        if (mutable.value != value) { mutable.value = value; generation++ }
        return true
    }

    fun validDirectory(value: String): String {
        require(value.startsWith('/') && '\u0000' !in value && value.toByteArray(Charsets.UTF_8).size <= 4096) { "无效的项目目录" }
        // Do not lexically collapse '..' across a Linux symlink: the kernel owns its meaning.
        return value.trimEnd('/').ifEmpty { "/" }
    }

    fun sharedRelative(directory: String): String? {
        if (directory == DEFAULT_DIRECTORY) return ""
        if (!directory.startsWith("$DEFAULT_DIRECTORY/")) return null
        val relative = directory.removePrefix("$DEFAULT_DIRECTORY/")
        return relative.takeIf { it.split('/').none { part -> part.isEmpty() || part == "." || part == ".." } }
    }

    fun sessionUrl(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val key = Base64.encodeToString(LocalOpenCode.ORIGIN.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "${LocalOpenCode.ORIGIN}/server/$key/session/$id"
    }

    /** Only local-session links can select a native working directory. Remote server tabs cannot. */
    fun localSession(url: String?): String? = runCatching {
        val uri = Uri.parse(url ?: return null)
        if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port != LocalOpenCode.PORT || uri.userInfo != null) return null
        val parts = uri.pathSegments
        if (parts.size != 4 || parts[0] != "server" || parts[2] != "session") return null
        val id = parts[3]
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}")) || sessionUrl(id).substringBefore('?') != "${LocalOpenCode.ORIGIN}${uri.encodedPath}") return null
        id
    }.getOrNull()
}
