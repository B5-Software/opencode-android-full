package io.github.opencodeandroid.runtime.update

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

internal data class OpenCodeUpdateCandidate(val file: File, val metadata: VerifiedOpenCodeUpdate)
internal data class OpenCodeUpdateStatus(val busy: Boolean = false, val cancellable: Boolean = false,
    val message: String = "", val candidate: OpenCodeUpdateCandidate? = null, val revision: Long = 0,
    val network: Boolean = false, val feed: VerifiedUpdateFeed? = null, val feedSource: String = "")

internal object OpenCodeUpdateState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(OpenCodeUpdateStatus())
    val current = mutable.asStateFlow()
    @Synchronized private fun change(edit: (OpenCodeUpdateStatus) -> OpenCodeUpdateStatus) { mutable.value = edit(mutable.value) }
    @Synchronized fun reserve(message: String, network: Boolean = false): Boolean {
        if (mutable.value.busy) return false
        mutable.value = mutable.value.copy(busy = true, cancellable = false, message = message, network = network)
        return true
    }
    fun progress(message: String, cancellable: Boolean = false) = change { it.copy(message = message, cancellable = cancellable) }
    fun finished(message: String) = change { it.copy(busy = false, cancellable = false, network = false, message = message, revision = it.revision + 1) }
    fun checked(source: String, feed: VerifiedUpdateFeed) = change { it.copy(feed = feed, feedSource = source) }
    fun publishCandidate(context: Context, candidate: OpenCodeUpdateCandidate) = change { previous ->
        previous.candidate?.takeIf { it.file != candidate.file }?.file?.let { file ->
            check(file.canonicalFile.parentFile == File(context.cacheDir, "opencode-updates").canonicalFile); file.delete()
        }
        previous.copy(candidate = candidate)
    }
    fun forgetCandidate(context: Context) = change { previous ->
        previous.candidate?.file?.let { file ->
            check(file.canonicalFile.parentFile == File(context.cacheDir, "opencode-updates").canonicalFile)
            file.delete()
        }
        previous.copy(candidate = null)
    }
    fun import(context: Context, uri: Uri) {
        if (!reserve("正在验证更新包签名与完整性…")) return
        val application = context.applicationContext
        scope.launch {
            var staged: File? = null
            try {
                val directory = UpdateFiles.directory(File(application.cacheDir, "opencode-updates"))
                val file = File(directory, "${UUID.randomUUID()}.ocaupdate"); staged = file
                application.contentResolver.openInputStream(uri).use { input ->
                    check(input != null) { "无法读取选择的文件" }
                    file.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024); var total = 0L
                        while (true) { ensureActive(); val count = input.read(buffer); if (count < 0) break; total += count; check(total <= 512L * 1024 * 1024 + 64 * 1024) { "更新包过大" }; output.write(buffer, 0, count) }
                        output.fd.sync()
                    }
                }
                android.system.Os.chmod(file.path, 384)
                val metadata = SignedOpenCodeUpdate(application).inspect(file)
                publishCandidate(application, OpenCodeUpdateCandidate(file, metadata)); staged = null
                finished("签名与包体校验通过，请核对版本后安装。")
            } catch (_: Exception) { finished("无法导入：文件无效、签名不匹配、Host 版本不兼容或存储空间不足。") }
            finally { staged?.delete() }
        }
    }
}
