package io.github.opencodeandroid.runtime

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

internal data class DevelopmentRecoverySnapshot(val history: DevelopmentHistory = DevelopmentHistory(), val error: Boolean = false, val loaded: Boolean = false)
internal object DevelopmentSessions {
    private val mutable = MutableStateFlow(DevelopmentRecoverySnapshot())
    val current = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var viewedRoot: File? = null
    fun refresh(context: Context) {
        val paths = RuntimePaths(context); viewedRoot = paths.files
        scope.launch { publish(paths) }
    }
    @Synchronized fun publish(paths: RuntimePaths) {
        if (viewedRoot != paths.files) return
        mutable.value = try {
            if (RuntimeRecovery.pending(paths)) DevelopmentRecoverySnapshot(loaded = true)
            else DevelopmentRecoverySnapshot(DevelopmentSessionJournal(paths).snapshot(), loaded = true)
        } catch (_: Exception) { DevelopmentRecoverySnapshot(error = true, loaded = true) }
    }
    fun reset(context: Context) { scope.launch {
        val paths = RuntimePaths(context)
        try {
            RuntimeCoordination.claimMaintenance("正在修复运行记录").use { lease ->
                check(!RuntimeRecovery.pending(paths)); DevelopmentSessionJournal(paths).reset(lease)
            }
        } catch (_: Exception) { /* UI retains the record error; no raw paths or payload enter logs. */ }
        publish(paths)
    } }
    fun reason(code: Int?): String = when (code) {
        1 -> "进程自行退出"; 2 -> "信号终止"; 3 -> "低内存回收"; 4 -> "应用崩溃"; 5 -> "原生进程崩溃"
        6 -> "应用无响应"; 7 -> "初始化失败"; 8 -> "权限变更"; 9 -> "资源限制"; 10 -> "用户请求停止"
        11 -> "用户停止"; 12 -> "依赖进程退出"; 13 -> "其他系统原因"; 14 -> "进程冻结处理"; 15 -> "应用状态变更"; 16 -> "应用更新"
        null -> "系统未提供退出原因"; else -> "系统退出（$code）"
    }
}
