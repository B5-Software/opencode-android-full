package io.github.opencodeandroid.preview

import java.net.URI

/** Fixed loopback authority; project ports cannot select administrative services. */
class PreviewTarget private constructor(val port: Int) {
    val url = "http://127.0.0.1:$port/"
    fun ownsNavigation(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == port && uri.rawUserInfo == null
    }.getOrDefault(false)

    companion object {
        private val reserved = setOf(49374, 6080, 5901)
        fun fromValue(value: Any?): PreviewTarget {
            val number = (value as? Number)?.toDouble()
            require(number != null && number.isFinite() && number % 1.0 == 0.0) { "开发端口必须是整数" }
            return forPort(number.toInt())
        }
        fun forPort(value: Int): PreviewTarget {
            require(value in 1024..65535 && value !in reserved) { "请选择有效的项目开发端口" }
            return PreviewTarget(value)
        }
        fun resourceAllowed(value: String): Boolean = runCatching {
            val uri = URI(value)
            when (uri.scheme) {
                "data", "blob" -> true
                "about" -> value == "about:blank"
                "http", "https" -> {
                    val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.') ?: return false
                    if (uri.rawUserInfo != null) return false
                    val local = host == "localhost" || host.startsWith("127.") || host == "::1" || host.startsWith("::ffff:127.")
                    val port = uri.port.takeIf { it >= 0 } ?: if (uri.scheme == "https") 443 else 80
                    port !in reserved && (!local || port in 1024..65535)
                }
                else -> false
            }
        }.getOrDefault(false)
    }
}
