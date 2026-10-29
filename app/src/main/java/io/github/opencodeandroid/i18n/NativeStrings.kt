package io.github.opencodeandroid.i18n

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

internal data class NativeMessage(@param:StringRes val resource: Int, val parts: List<String>) {
    // Bounded, deterministic matching for app-generated status messages. No regular
    // expressions, network translation, terminal output or file contents are used.
    fun arguments(message: String): List<String>? {
        if (parts.size < 2 || !message.startsWith(parts.first())) return null
        var offset = parts.first().length
        val result = ArrayList<String>(parts.size - 1)
        for (index in 1..parts.lastIndex) {
            val separator = parts[index]
            val end = if (index == parts.lastIndex && separator.isEmpty()) message.length
                else if (separator.isEmpty()) return null else message.indexOf(separator, offset)
            if (end < offset) return null
            result += message.substring(offset, end)
            offset = end + separator.length
        }
        return result.takeIf { offset == message.length }
    }
}

/** Android resources choose the system/app locale and its ordered fallback list. */
internal class NativeStrings(private val resources: Resources) {
    fun text(@StringRes resource: Int, vararg arguments: Any?): String =
        if (arguments.isEmpty()) resources.getString(resource) else resources.getString(resource, *arguments)

    /** Only for app-owned labels/status fields; never call this on arbitrary user text. */
    fun message(value: String): String {
        exact[phaseLabels[value] ?: value]?.let { return text(it) }
        if (value.length > 8192) return value
        for (entry in templates[value.take(2)].orEmpty() + templates[""].orEmpty()) {
            val arguments = entry.arguments(value) ?: continue
            return text(entry.resource, *arguments.toTypedArray())
        }
        return value
    }

    companion object {
        private val phaseLabels = mapOf("Stopped" to "已停止", "Starting" to "正在启动", "Starting OpenCode" to "正在启动",
            "Stopping" to "正在停止", "Needs attention" to "需要处理", "OpenCode running" to "运行中",
            "Installed" to "已安装", "Ready" to "就绪", "Verifying Loader" to "正在检查运行器", "Installing Debian" to "正在安装 Debian",
            "Interrupted" to "任务已异常结束。", "Not installed" to "未安装")
        private val exact = NativeMessages.filter { it.parts.size == 1 }.associate { it.parts.single() to it.resource }
        private val templates = NativeMessages.filter { it.parts.size > 1 }
            .sortedByDescending { it.parts.sumOf(String::length) }.groupBy { if (it.parts.first().length < 2) "" else it.parts.first().take(2) }
    }
}

internal fun android.content.Context.nativeMessage(value: String) = NativeStrings(resources).message(value)

@Composable
internal fun rememberNativeStrings(): NativeStrings {
    val context = LocalContext.current
    val languageTags = LocalConfiguration.current.locales.toLanguageTags()
    return remember(context, languageTags) { NativeStrings(context.resources) }
}
