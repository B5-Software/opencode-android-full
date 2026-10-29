package io.github.opencodeandroid.files

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.opencodeandroid.runtime.RuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import java.io.File
import io.github.opencodeandroid.runtime.WorkspaceContext
import io.github.opencodeandroid.runtime.RuntimeState
import io.github.opencodeandroid.runtime.TerminalSessions
import io.github.opencodeandroid.runtime.LocalOpenCode
import io.github.opencodeandroid.runtime.CredentialVault
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

data class FileClipboard(val entry: ProjectEntry, val move: Boolean)
data class FilesSnapshot(
    val directory: String = "", val entries: List<ProjectEntry> = emptyList(),
    val document: ProjectDocument? = null, val draft: String = "", val trashOpen: Boolean = false,
    val trash: List<TrashedProjectEntry> = emptyList(), val busy: Boolean = false,
    val error: String? = null, val message: String? = null, val confirmClose: Boolean = false,
    val clipboard: FileClipboard? = null, val progress: FileTransferProgress? = null,
    val canCancel: Boolean = false, val share: Uri? = null,
    val openCodeUrl: String? = null, val openTerminal: Boolean = false
) { val dirty get() = document != null && draft != document.text }

class ProjectFilesViewModel(application: Application) : AndroidViewModel(application) {
    private val environmentRevision = io.github.opencodeandroid.backup.WorkstationBackupState.current.value.environmentRevision
    override fun onCleared() { synchronized(editors) { editors.remove(this) }; super.onCleared() }
    companion object {
        private val editors = java.util.WeakHashMap<ProjectFilesViewModel, Unit>()
        internal fun hasUnsavedEditors() = synchronized(editors) { editors.keys.any { it.state.value.dirty } }
    }
    private fun environmentChanged() = environmentRevision != io.github.opencodeandroid.backup.WorkstationBackupState.current.value.environmentRevision
    internal fun usesOldEnvironment() = environmentChanged()
    private val paths = RuntimePaths(application)
    private val repository by lazy { ProjectFiles(paths.workspace, File(paths.backups, "project-trash")) }
    private val current = MutableStateFlow(FilesSnapshot())
    val state = current.asStateFlow()
    init { synchronized(editors) { editors[this] = Unit } }
    private var operation: Job? = null
    private var pendingProject: String? = null

    private fun work(canCancel: Boolean = false, action: suspend () -> Unit) {
        if (current.value.busy) return
        if (environmentChanged() || io.github.opencodeandroid.backup.WorkstationBackupState.current.value.restartRequired) {
            current.update { it.copy(error = "环境已恢复，请重开工作区") }; return
        }
        if (io.github.opencodeandroid.runtime.RuntimeRecovery.pending(paths)) {
            current.update { it.copy(error = "请先在运行环境中完成中断操作的恢复") }; return
        }
        val ownership = io.github.opencodeandroid.runtime.RuntimeCoordination.claimService()
        if (ownership == null) { current.update { it.copy(error = "环境正在维护，请稍后重试") }; return }
        current.value = current.value.copy(busy = true, error = null, message = null, canCancel = canCancel)
        operation = viewModelScope.launch {
            try { action() }
            catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) { current.update { it.copy(message = "操作已取消") }; throw failure }
                current.value = current.value.copy(error = when (failure) {
                    is java.nio.file.FileAlreadyExistsException -> "已有同名文件，请换一个名称"
                    is java.nio.file.NoSuchFileException -> "文件已不存在，请刷新目录"
                    is java.nio.file.AccessDeniedException, is SecurityException -> "没有文件访问权限"
                    is IllegalArgumentException, is IllegalStateException -> failure.message ?: "无法完成操作"
                    else -> "操作未完成，请检查存储空间或重新选择文件"
                })
            } finally {
                current.update { it.copy(busy = false, canCancel = false, progress = null) }
                if (!current.value.confirmClose) pendingProject?.let { followProject("/workspace" + if (it.isEmpty()) "" else "/$it") }
            }
        }.also { it.invokeOnCompletion { ownership.close() } }
    }

    private suspend fun reload(directory: String = current.value.directory) {
        val entries = withContext(Dispatchers.IO) { repository.list(directory) }
        current.value = current.value.copy(directory = directory, entries = entries)
    }
    fun refresh() = work {
        if (current.value.trashOpen) current.value = current.value.copy(trash = withContext(Dispatchers.IO) { repository.trashEntries() })
        else reload()
    }
    fun followProject(directory: String) {
        val relative = WorkspaceContext.sharedRelative(directory) ?: return
        if (relative == current.value.directory) { pendingProject = null; return }
        pendingProject = relative
        if (current.value.busy) return
        if (current.value.dirty) { current.update { it.copy(confirmClose = true) }; return }
        pendingProject = null
        work { current.update { it.copy(document = null, draft = "", trashOpen = false) }; reload(relative) }
    }

    fun openProject(relative: String, terminal: Boolean, start: () -> Unit) {
        if (current.value.busy) return
        val directory = "/workspace" + if (relative.isEmpty()) "" else "/$relative"
        if (RuntimeState.current.value.pairingUrl == null) start()
        work {
            withContext(Dispatchers.IO) { repository.list(relative) }
            val ready = withTimeout(90_000) { RuntimeState.current.first { it.pairingUrl != null || (!it.busy && it.phase == "Needs attention") } }
            check(ready.pairingUrl != null) { "请在运行环境中检查 OpenCode 启动状态" }
            if (terminal) {
                val shell = TerminalSessions.open(directory)?.await()
                check(shell != null) { "无法打开新终端，请检查会话数量" }
                WorkspaceContext.select(directory)
                current.update { it.copy(openTerminal = true) }
            } else {
                val url = withContext(Dispatchers.IO) { LocalOpenCode(CredentialVault(paths).password()).createSession(directory) }
                WorkspaceContext.select(directory)
                val generation = WorkspaceContext.generation
                withContext(Dispatchers.IO) { WorkspaceContext.rememberSessionIfCurrent(directory, checkNotNull(WorkspaceContext.localSession(url)), generation) }
                RuntimeState.workspace(url)
                current.update { it.copy(openCodeUrl = url) }
            }
        }
    }
    fun consumeOpenCode(url: String): Boolean {
        if (current.value.openCodeUrl != url) return false
        current.update { it.copy(openCodeUrl = null) }; return true
    }
    fun consumeTerminal(): Boolean {
        if (!current.value.openTerminal) return false
        current.update { it.copy(openTerminal = false) }; return true
    }
    fun open(entry: ProjectEntry) = work {
        if (entry.link) error("符号链接可在终端中处理，文件管理器不会跟随链接")
        if (entry.directory) reload(entry.path)
        else {
            val document = withContext(Dispatchers.IO) { repository.read(entry.path) }
            current.value = current.value.copy(document = document, draft = document.text)
        }
    }
    fun create(name: String, directory: Boolean) = work {
        withContext(Dispatchers.IO) { repository.create(current.value.directory, name, directory) }; reload()
    }
    fun rename(entry: ProjectEntry, name: String) = work {
        withContext(Dispatchers.IO) { repository.rename(entry.path, name) }; reload()
    }
    fun selectTransfer(entry: ProjectEntry, move: Boolean) { if (!current.value.busy) current.update { it.copy(clipboard = FileClipboard(entry, move), message = null, error = null) } }
    fun cancelTransfer() { current.update { it.copy(clipboard = null) } }
    fun cancelOperation() { if (current.value.canCancel) operation?.cancel() }
    fun paste() {
        val source = current.value.clipboard ?: return
        val destination = current.value.directory
        work(canCancel = !source.move) {
            var lastUpdate = 0L
            withContext(Dispatchers.IO) {
                val active = currentCoroutineContext()
                repository.transfer(source.entry.path, destination, source.move, { active.ensureActive() }) { progress ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 100) { lastUpdate = now; current.update { it.copy(progress = progress) } }
                }
            }
            reload(destination)
            current.update { it.copy(clipboard = null, message = if (source.move) "移动完成" else "复制完成") }
        }
    }
    fun share(entry: ProjectEntry) = work {
        val uri = withContext(Dispatchers.IO) { FileSharing.snapshot(getApplication(), entry.path) }
        current.update { it.copy(share = uri) }
    }
    fun consumeShare(uri: Uri): Boolean {
        if (current.value.share != uri) return false
        current.update { it.copy(share = null) }; return true
    }
    fun reportError(message: String) { current.update { it.copy(error = message) } }
    fun trash(entry: ProjectEntry) = work {
        withContext(Dispatchers.IO) { repository.trash(entry.path) }; reload()
        current.value = current.value.copy(message = "已移入回收站")
    }
    fun openTrash() = work {
        val entries = withContext(Dispatchers.IO) { repository.trashEntries() }
        current.value = current.value.copy(trashOpen = true, trash = entries)
    }
    fun restore(entry: TrashedProjectEntry) = work {
        withContext(Dispatchers.IO) { repository.restore(entry.id) }
        current.value = current.value.copy(trash = withContext(Dispatchers.IO) { repository.trashEntries() }, message = "已恢复到原目录")
    }
    fun edit(text: String) {
        if (environmentChanged() || io.github.opencodeandroid.runtime.RuntimeCoordination.maintenanceActive) {
            current.update { it.copy(error = "环境正在维护或已切换，请重开工作区后继续编辑") }; return
        }
        current.value = current.value.copy(draft = text)
    }
    fun save() = work {
        val snapshot = current.value
        val saved = withContext(Dispatchers.IO) { repository.save(requireNotNull(snapshot.document), snapshot.draft) }
        current.value = current.value.copy(document = saved, message = "已保存")
    }
    fun back(): Boolean {
        val snapshot = current.value
        return when {
            snapshot.busy -> true
            snapshot.document != null -> {
                if (snapshot.dirty) current.value = snapshot.copy(confirmClose = true) else discard()
                true
            }
            snapshot.trashOpen -> { current.value = snapshot.copy(trashOpen = false); refresh(); true }
            snapshot.directory.isNotEmpty() -> { work { reload(snapshot.directory.substringBeforeLast('/', "")) }; true }
            else -> false
        }
    }
    fun discard() {
        current.value = current.value.copy(document = null, draft = "", confirmClose = false, message = null)
        val target = pendingProject; pendingProject = null
        if (target != null) work { current.update { it.copy(trashOpen = false) }; reload(target) } else refresh()
    }
    fun cancelClose() { pendingProject = null; current.value = current.value.copy(confirmClose = false) }
    fun clearMessage() { current.value = current.value.copy(error = null, message = null) }

    fun import(uris: List<Uri>, directory: String) = work {
        val count = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            var imported = 0
            for (uri in uris.take(64)) {
                require(uri.scheme == "content")
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "imported-file"
                resolver.openInputStream(uri)?.use { repository.import(directory, name, it) } ?: error("无法读取所选文件")
                imported++
            }
            imported
        }
        reload(); current.value = current.value.copy(message = "已导入 $count 个文件")
    }
    fun export(relative: String, uri: Uri) = work {
        withContext(Dispatchers.IO) {
            require(uri.scheme == "content")
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { repository.export(relative, it) } ?: error("无法写入所选位置")
        }
        current.value = current.value.copy(message = "导出完成")
    }
}
