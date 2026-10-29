package io.github.opencodeandroid.logging

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Host diagnostics only. Never use this for terminal data or the official WebUI. */
class DiagnosticLog(
    private val capacity: Int,
    private val publish: (Snapshot) -> Unit,
    private val schedule: (() -> Unit) -> Unit = { task -> scheduler.schedule(task, 250, TimeUnit.MILLISECONDS); Unit },
) {
    data class Snapshot(val revision: Long, val text: String)
    private val lines = ArrayDeque<String>()
    private var characters = 0
    private var revision = 0L
    private var cached = Snapshot(0, "")
    private var pending = false
    private var privateKey = false

    init { require(capacity >= MAX_LINE + 1) }

    fun append(raw: String) {
        val queue = synchronized(this) {
            // Do not retain a prefix: a secret may straddle the length limit.
            val bounded = if (raw.length > MAX_LINE) OMITTED_LINE else raw
            val plain = DiagnosticSecrets.plain(bounded)
            val beginsKey = BEGIN_KEY.containsMatchIn(plain)
            if (privateKey || beginsKey) {
                val first = !privateKey
                privateKey = !END_KEY.containsMatchIn(plain)
                if (!first) return
            }
            val line = if (beginsKey) "[private key omitted]" else DiagnosticSecrets.redact(plain)
            if (line.isEmpty()) return
            lines.addLast(line); characters += line.length + 1; revision++
            while (characters > capacity || lines.size > 512) characters -= lines.removeFirst().length + 1
            if (pending) false else { pending = true; true }
        }
        if (queue) {
            try { schedule(::flush) }
            catch (_: java.util.concurrent.RejectedExecutionException) { flush() }
        }
    }

    /** Status transitions can include pending output immediately, without waiting for the timer. */
    @Synchronized fun snapshot(): Snapshot {
        if (cached.revision != revision) cached = Snapshot(revision, lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"))
        return cached
    }

    private fun flush() {
        val value = synchronized(this) { pending = false; snapshot() }
        // No log lock while publishing. State holders reject older revisions that arrive late.
        publish(value)
    }

    companion object {
        const val MAX_LINE = 4096
        const val OMITTED_LINE = "[oversized diagnostic line omitted]"
        private val BEGIN_KEY = Regex("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----")
        private val END_KEY = Regex("-----END [A-Z0-9 ]*PRIVATE KEY-----")
        private val scheduler by lazy {
            Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "host-diagnostic-log").apply { isDaemon = true } }
        }
    }
}
