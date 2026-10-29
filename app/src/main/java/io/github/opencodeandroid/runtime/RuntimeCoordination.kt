package io.github.opencodeandroid.runtime

import kotlinx.coroutines.flow.asStateFlow

/** Atomic ownership transitions prevent a rootfs replacement racing a desktop start. */
object RuntimeCoordination {
    val lock = Any()
    private val services = mutableSetOf<Any>()
    private var maintenance: Any? = null
    private val mutableMaintenance = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val maintenanceLabel = mutableMaintenance.asStateFlow()
    val maintenanceActive get() = synchronized(lock) { maintenance != null }
    fun claimService(): AutoCloseable? = synchronized(lock) {
        if (maintenance != null) return null
        val token = Any(); services += token
        AutoCloseable { synchronized(lock) { services -= token } }
    }
    fun claimMaintenance(label: String = "环境维护"): MaintenanceLease = synchronized(lock) {
        check(maintenance == null && services.isEmpty() && !RuntimeState.current.value.busy && !io.github.opencodeandroid.desktop.DesktopState.current.value.busy && TerminalSessions.sessions.value.isEmpty()) { "请先停止 OpenCode、终端和 Debian 桌面，并等待文件操作结束" }
        val token = Any(); maintenance = token; mutableMaintenance.value = label; MaintenanceLease(token)
    }
    class MaintenanceLease internal constructor(private val token: Any) : AutoCloseable {
        fun checkCurrent() { synchronized(lock) { check(maintenance === token) { "环境维护锁已失效" } } }
        fun resumeService(): AutoCloseable = synchronized(lock) {
            checkCurrent(); val service = Any(); services += service; maintenance = null; mutableMaintenance.value = null
            AutoCloseable { synchronized(lock) { services -= service } }
        }
        override fun close() { synchronized(lock) { if (maintenance === token) { maintenance = null; mutableMaintenance.value = null } } }
    }
}
