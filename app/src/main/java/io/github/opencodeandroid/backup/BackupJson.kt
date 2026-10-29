package io.github.opencodeandroid.backup

import java.io.IOException

/** Bound parser recursion before either org.json or Tink/Gson sees an untrusted header. */
internal object BackupJson {
    fun read(file: java.io.File): org.json.JSONObject {
        check(!java.nio.file.Files.isSymbolicLink(file.toPath()) && !java.nio.file.Files.isSymbolicLink(java.io.File(file.path + ".bak").toPath()))
        val bytes = android.util.AtomicFile(file).openRead().use { input ->
            val data = ByteArray(65537); var length = 0
            while (length < data.size) { val count = input.read(data, length, data.size - length); if (count < 0) break; length += count }
            check(length <= 65536) { "恢复记录过大" }; data.copyOf(length)
        }
        val value = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        check(value.isNotBlank()); check(value.toByteArray().size <= 65536); check(value.length <= 65536)
        check(value); return org.json.JSONObject(value)
    }
    fun check(text: String) {
        if (text.length > 65536) throw IOException("备份元数据过大")
        var quoted = false; var escaped = false; var depth = 0
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 32) throw IOException("备份元数据嵌套过深") }
                '}', ']' -> { depth--; if (depth < 0) throw IOException("备份元数据无效") }
            }
        }
        if (depth != 0 || quoted) throw IOException("备份元数据无效")
    }
}
