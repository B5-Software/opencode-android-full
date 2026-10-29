package io.github.opencodeandroid.runtime

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class TerminalChunk(val sequence: Long, val bytes: ByteArray)

class PtySession(val id: Int, paths: RuntimePaths, private val scope: CoroutineScope, val directory: String = WorkspaceContext.directory.value) {
    private val command = ProotCommand(paths, directory = directory)
    private val process = NativeHost.spawnPty(command.arguments(listOf("/bin/bash", "-c", "cd -P -- \"\$1\" && exec /bin/bash --login", "opencode-terminal", directory)).toTypedArray(),
        command.hostEnvironment().map { "${it.key}=${it.value}" }.toTypedArray(), 24, 80)
    private val descriptor = ParcelFileDescriptor.adoptFd(process[0])
    private val input = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
    private val output = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
    private val chunks = ArrayDeque<TerminalChunk>()
    private var historySize = 0
    private var sequence = 0L
    private val closed = AtomicBoolean(false)
    private val inputQueue = Channel<ByteArray>(64)
    private val mutableOutput = MutableSharedFlow<TerminalChunk>(replay = 64, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events = mutableOutput.asSharedFlow()
    val exit = MutableStateFlow<Int?>(null)
    @Volatile var control = false
    @Volatile var alt = false
    private var lastRows = 24
    private var lastColumns = 80
    private val history = DevelopmentSessionJournal(paths)
    private val historyToken = runCatching { history.terminalOpened(id) }
        .onFailure { RuntimeState.log("终端运行记录未保存；进程回收后需新建终端。") }.getOrNull()
    internal val supervisorPid get() = process[1]

    init {
        scope.launch {
            val buffer = ByteArray(8192)
            try {
                while (isActive) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    append(buffer.copyOf(count))
                }
            } catch (_: IOException) { /* PTY closes with EIO when its slave exits. */ }
        }
        scope.launch {
            try { for (data in inputQueue) { output.write(data); output.flush() } }
            catch (_: IOException) { }
        }
        Thread({
            exit.value = NativeHost.waitFor(process[1])
            historyToken?.let { token -> runCatching { history.terminalClosed(token, id) }
                .onFailure { RuntimeState.log("终端退出记录未完成保存。") } }
            inputQueue.close()
            append("\r\n[Shell exited: ${exit.value}]\r\n".toByteArray())
        }, "pty-wait-$id").apply { isDaemon = true; start() }
    }

    @Synchronized private fun append(data: ByteArray) {
        val event = TerminalChunk(++sequence, data)
        chunks += event; historySize += data.size
        while (historySize > 512 * 1024 && chunks.size > 1) historySize -= chunks.removeFirst().bytes.size
        mutableOutput.tryEmit(event)
    }

    @Synchronized fun snapshot(): TerminalChunk {
        val history = ByteArrayOutputStream(historySize)
        chunks.forEach { history.write(it.bytes) }
        return TerminalChunk(sequence, history.toByteArray())
    }

    fun write(data: ByteArray): Boolean {
        if (closed.get() || exit.value != null || data.size > 64 * 1024) return false
        var translated = data
        if (control && data.size == 1 && data[0].toInt() in 32..126) {
            translated = byteArrayOf((if (data[0].toInt() == 63) 127 else (data[0].toInt() and 31)).toByte())
        }
        if (alt) translated = byteArrayOf(27) + translated
        control = false; alt = false
        return inputQueue.trySend(translated).isSuccess
    }

    @Synchronized fun resize(rows: Int, columns: Int) {
        if (!closed.get() && exit.value == null && rows in 1..300 && columns in 2..500) {
            try {
                NativeHost.resize(descriptor.fd, rows, columns)
                lastRows = rows; lastColumns = columns
            } catch (_: IOException) { }
        }
    }

    @Synchronized fun requestRedraw() {
        if (closed.get() || exit.value != null) return
        try {
            // Reattaching after a tab/Activity change may replay only bounded history.
            // A real SIGWINCH asks foreground TUIs to redraw their current screen.
            NativeHost.resize(descriptor.fd, if (lastRows < 300) lastRows + 1 else lastRows - 1, lastColumns)
            NativeHost.resize(descriptor.fd, lastRows, lastColumns)
        } catch (_: IOException) { }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (exit.value == null) NativeHost.signalGroup(process[1], 9)
        inputQueue.close()
        listOf(input, output, descriptor).forEach { try { it.close() } catch (_: IOException) { } }
    }
}

/** Owned by RuntimeService; changing Activities or tabs does not close shell processes. */
object TerminalSessions {
    private val mutable = MutableStateFlow<List<PtySession>>(emptyList())
    val sessions = mutable.asStateFlow()
    private val focus = MutableStateFlow<Int?>(null)
    val focusedSession = focus.asStateFlow()
    private val ids = AtomicInteger(0)
    private var owner: CoroutineScope? = null
    private var paths: RuntimePaths? = null

    @Synchronized fun start(runtime: RuntimePaths) {
        if (owner != null) return
        paths = runtime
        owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Synchronized fun open(directory: String = WorkspaceContext.directory.value): Deferred<PtySession?>? {
        if (RuntimeCoordination.maintenanceActive) return null
        val scope = owner ?: return null
        val runtime = paths ?: return null
        if (mutable.value.size >= 8) return null
        // A pending constructor may outlive stop()'s cancellation until it closes its child.
        // Keep maintenance excluded during that gap as well as during the owning service.
        val ownership = RuntimeCoordination.claimService() ?: return null
        return scope.async {
            try {
                val shell = PtySession(ids.incrementAndGet(), runtime, scope, WorkspaceContext.validDirectory(directory))
                synchronized(this@TerminalSessions) {
                    if (owner === scope && mutable.value.size < 8) {
                        mutable.value = mutable.value + shell; focus.value = shell.id; shell
                    } else { shell.close(); null }
                }
            } catch (failure: Exception) { RuntimeState.log("Could not open terminal: ${failure.javaClass.simpleName}"); null }
        }.also { pending -> pending.invokeOnCompletion { ownership.close() } }
    }

    @Synchronized fun focus(id: Int) { if (mutable.value.any { it.id == id }) focus.value = id }

    @Synchronized fun close(id: Int) {
        val target = mutable.value.find { it.id == id } ?: return
        val ownership = RuntimeCoordination.claimService()
        mutable.value = mutable.value.filterNot { it.id == id }
        if (focus.value == id) focus.value = mutable.value.firstOrNull()?.id
        // Closing a removed session must finish even if stop() cancels its old owner scope.
        CoroutineScope(Dispatchers.IO).launch { try { target.close() } finally { ownership?.close() } }
    }

    @Synchronized fun stop() {
        mutable.value.forEach { it.close() }
        mutable.value = emptyList()
        focus.value = null
        owner?.cancel(); owner = null; paths = null
    }
}
