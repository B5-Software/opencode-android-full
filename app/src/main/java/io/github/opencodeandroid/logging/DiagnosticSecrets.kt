package io.github.opencodeandroid.logging

import java.util.concurrent.atomic.AtomicBoolean

/** Small, process-local leases for generated host credentials. No persistence or provider scan. */
object DiagnosticSecrets {
    private val secrets = linkedMapOf<String, Int>()
    private val ansi = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(?:\u0007|\u001B\\))""")
    private val controls = Regex("""[\p{Cc}&&[^\t]]""")
    private val header = Regex("""(?i)((?<![A-Za-z0-9_-])(?:(?:proxy-)?authorization|(?:set-)?cookie)\s*[:=]\s*).*$""")
    private val assignment = Regex("""(?i)(?<![A-Za-z0-9_.-])(["']?(?:[A-Za-z0-9_.-]*[_-])?(?:api[_-]?key|access[_-]?key|client[_-]?secret|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|credential|authorization|cookie|set-cookie)(?:[_-][A-Za-z0-9_.-]+)?["']?\s*[:=]\s*)(?:"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;&}\]]+)""")
    private val pairing = Regex("""/auth/connect/[A-Za-z0-9_%~.\-]+""")
    private val userInfo = Regex("""(?i)((?:https?|ssh|git|ftp|postgres(?:ql)?|mysql|mongodb(?:\+srv)?)://)[^\s/@]+@""")
    private val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/\-]{8,}=*""")
    private val keyShape = Regex("""\b(?:sk-(?:ant-api[0-9]+-|or-v1-)?[A-Za-z0-9_\-]{12,}|gh[pousr]_[A-Za-z0-9_]{16,}|github_pat_[A-Za-z0-9_]{16,}|AIza[0-9A-Za-z_\-]{20,}|xox[baprs]-[A-Za-z0-9\-]{12,})""")

    fun register(vararg values: String): AutoCloseable {
        val unique = values.filter { it.length in 4..DiagnosticLog.MAX_LINE }.toSet()
        synchronized(this) { unique.forEach { secrets[it] = (secrets[it] ?: 0) + 1 } }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(this) {
                unique.forEach { value -> val count = secrets.getValue(value); if (count == 1) secrets.remove(value) else secrets[value] = count - 1 }
            }
        }
    }

    internal fun plain(value: String): String = controls.replace(ansi.replace(value, "").replace('\r', ' ').replace('\n', ' '), "")

    internal fun redact(value: String): String {
        var result = value
        result = header.replace(result) { it.groupValues[1] + "[redacted]" }
        result = assignment.replace(result) { it.groupValues[1] + "[redacted]" }
        result = pairing.replace(result, "/auth/connect/[redacted]")
        result = userInfo.replace(result) { it.groupValues[1] + "[redacted]@" }
        result = bearer.replace(result, "Bearer [redacted]")
        result = keyShape.replace(result, "[redacted]")
        val active = synchronized(this) { secrets.keys.sortedByDescending { it.length } }
        active.forEach { result = result.replace(it, "[redacted]") }
        return result
    }
}
