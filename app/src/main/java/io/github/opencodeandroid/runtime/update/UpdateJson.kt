package io.github.opencodeandroid.runtime.update

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object UpdateJson {
    fun parse(bytes: ByteArray): JSONObject {
        check(bytes.size in 1..16384)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        var quoted = false; var escaped = false; var depth = 0
        for (value in text) {
            if (quoted) {
                if (escaped) escaped = false else if (value == '\\') escaped = true else if (value == '"') quoted = false
            } else when (value) {
                '"' -> quoted = true
                '{', '[' -> { depth++; check(depth <= 16) }
                '}', ']' -> { depth--; check(depth >= 0) }
            }
        }
        check(depth == 0 && !quoted); return JSONObject(text)
    }
}
