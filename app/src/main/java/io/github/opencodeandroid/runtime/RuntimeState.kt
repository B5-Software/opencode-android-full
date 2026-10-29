package io.github.opencodeandroid.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.opencodeandroid.logging.DiagnosticLog

data class RuntimeSnapshot(val phase: String = "Stopped", val busy: Boolean = false, val ready: Boolean = false, val log: String = "", val pairingUrl: String? = null, val workspaceUrl: String? = null)

object RuntimeState {
    private val mutable = MutableStateFlow(RuntimeSnapshot())
    private val metadata = MutableStateFlow(RuntimeSnapshot())
    private var logRevision = 0L
    private val diagnostics = DiagnosticLog(48 * 1024, ::publishLog)
    val current = mutable.asStateFlow()
    /** Workspace navigation needs status, never the diagnostic body. */
    val status = metadata.asStateFlow()
    @Synchronized private fun publishLog(value: DiagnosticLog.Snapshot) {
        if (value.revision >= logRevision) { logRevision = value.revision; mutable.value = mutable.value.copy(log = value.text) }
    }
    private fun publish(value: RuntimeSnapshot) {
        val log = diagnostics.snapshot(); logRevision = log.revision
        mutable.value = value.copy(log = log.text)
        metadata.value = value.copy(log = "")
    }
    @Synchronized fun refreshInstalled(ready: Boolean) { publish(mutable.value.copy(ready = ready)) }
    @Synchronized fun paired(url: String?) { publish(mutable.value.copy(pairingUrl = url, workspaceUrl = if (url == null) null else mutable.value.workspaceUrl)) }
    @Synchronized fun workspace(url: String) {
        require(WorkspaceContext.localSession(url) != null)
        publish(mutable.value.copy(workspaceUrl = url))
    }
    @Synchronized fun update(phase: String, busy: Boolean, ready: Boolean = mutable.value.ready) {
        publish(mutable.value.copy(phase = phase, busy = busy, ready = ready))
    }
    fun log(line: String) = diagnostics.append(line)
}
