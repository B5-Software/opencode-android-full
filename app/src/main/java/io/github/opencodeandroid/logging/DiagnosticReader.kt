package io.github.opencodeandroid.logging

import java.io.InputStream
import java.io.InputStreamReader

/** Drains process output with fixed memory even when a process never writes a newline. */
object DiagnosticReader {
    fun read(input: InputStream, sink: (String) -> Unit, checkpoint: () -> Unit = {}) {
        InputStreamReader(input, Charsets.UTF_8).use { reader ->
            val buffer = CharArray(2048)
            val line = StringBuilder(DiagnosticLog.MAX_LINE)
            var oversized = false
            var afterCr = false
            fun emit() { sink(if (oversized) DiagnosticLog.OMITTED_LINE else line.toString()); line.setLength(0); oversized = false }
            while (true) {
                checkpoint()
                val count = reader.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    val char = buffer[index]
                    if (char == '\n' && afterCr) { afterCr = false; continue }
                    afterCr = char == '\r'
                    if (char == '\n' || char == '\r') emit()
                    else if (!oversized) {
                        if (line.length == DiagnosticLog.MAX_LINE) { line.setLength(0); oversized = true }
                        else line.append(char)
                    }
                }
            }
            if (oversized || line.isNotEmpty()) emit()
        }
    }
}
