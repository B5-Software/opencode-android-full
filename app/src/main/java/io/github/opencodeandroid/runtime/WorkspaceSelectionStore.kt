package io.github.opencodeandroid.runtime

import android.content.SharedPreferences

internal data class WorkspaceSelection(val directory: String, val session: String? = null)

/** Private navigation metadata only; no credentials, commands or conversation contents. */
internal class WorkspaceSelectionStore(private val preferences: SharedPreferences) {
    fun read(): WorkspaceSelection {
        val directory = runCatching { preferences.getString("directory", null)?.let(WorkspaceContext::validDirectory) }.getOrNull()
        val session = runCatching { preferences.getString("session-id", null)?.takeIf(::validSession) }.getOrNull()
        // Older builds observed the daemon's HOME as a default; it was not a saved project choice.
        val selected = directory?.takeUnless { session == null && it in setOf("/", "/root") } ?: WorkspaceContext.STARTER_DIRECTORY
        return WorkspaceSelection(selected, session.takeIf { selected == directory })
    }

    fun select(directory: String) {
        val edit = preferences.edit().putString("directory", directory)
        if (read().directory != directory) edit.remove("session-id")
        edit.apply()
    }

    /** Caller performs this on IO; persist directory/session together before advertising restoration. */
    fun remember(directory: String, session: String) {
        require(validSession(session)); WorkspaceContext.validDirectory(directory)
        if (read() == WorkspaceSelection(directory, session)) return
        check(preferences.edit().putString("directory", directory).putString("session-id", session).commit()) { "无法保存工作区位置" }
    }

    companion object { fun validSession(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
}
