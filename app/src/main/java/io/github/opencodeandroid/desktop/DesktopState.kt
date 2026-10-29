package io.github.opencodeandroid.desktop

import io.github.opencodeandroid.runtime.RuntimePaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import io.github.opencodeandroid.logging.DiagnosticLog

data class DesktopSnapshot(val installed: Boolean = false, val busy: Boolean = false, val connected: Boolean = false, val phase: String = "已停止", val log: String = "")
object DesktopState {
    private val mutable = MutableStateFlow(DesktopSnapshot())
    private val metadata = MutableStateFlow(DesktopSnapshot())
    private var logRevision = 0L
    private val diagnostics = DiagnosticLog(12000, ::publishLog)
    val current = mutable.asStateFlow()
    val status = metadata.asStateFlow()
    fun socketToken(password: String): String = android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(("desktop-websocket-v1:" + password).toByteArray()), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    fun available(paths: RuntimePaths) = listOf("usr/bin/Xtigervnc", "usr/bin/xfce4-session", "usr/bin/websockify", "usr/bin/chromium").all { File(paths.rootfs, it).isFile }
    @Synchronized private fun publishLog(value: DiagnosticLog.Snapshot) {
        if (value.revision >= logRevision) { logRevision = value.revision; mutable.value = mutable.value.copy(log = value.text) }
    }
    private fun publish(value: DesktopSnapshot) {
        val log = diagnostics.snapshot(); logRevision = log.revision
        mutable.value = value.copy(log = log.text)
        metadata.value = value.copy(log = "")
    }
    @Synchronized fun refresh(paths: RuntimePaths) { publish(mutable.value.copy(installed = available(paths))) }
    @Synchronized fun update(phase: String, busy: Boolean, connected: Boolean = false) { publish(mutable.value.copy(phase = phase, busy = busy, connected = connected)) }
    fun log(message: String) = diagnostics.append(message)
}
