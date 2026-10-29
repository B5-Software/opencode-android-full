package io.github.opencodeandroid.runtime

import android.util.Base64
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL

class LocalOpenCode(private val password: String, private val port: Int = PORT) {
    private val origin = "http://127.0.0.1:$port"
    fun assertPortAvailable() {
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)) }
    }

    fun ready(expectedVersion: String): Boolean {
        val (status, text) = request("/api/info")
        if (status != 200) return false
        check(JSONObject(text).getString("version") == expectedVersion) { "Local server version does not match the selected runtime" }
        val (anonymous, _) = request("/api/info", authenticated = false)
        check(anonymous == 401) { "Local OpenCode authentication is not enforced" }
        return true
    }

    fun pairingUrl(): String {
        val (status, body) = request("/api/pair", "POST")
        check(status == 200) { "Could not create local pairing code" }
        val code = JSONObject(body).getString("code")
        check(code.matches(Regex("[A-Za-z0-9_-]{16,128}")))
        return "$origin/auth/connect/$code"
    }

    fun registerDirectory(directory: String) {
        val value = WorkspaceContext.validDirectory(directory)
        val encoded = java.net.URLEncoder.encode(value, "UTF-8")
        check(request("/api/location?location%5Bdirectory%5D=$encoded").first == 200) { "无法打开项目目录" }
    }

    fun createSession(directory: String): String {
        val value = WorkspaceContext.validDirectory(directory)
        val body = JSONObject().put("location", JSONObject().put("directory", value))
        val (status, response) = request("/api/session", "POST", payload = body)
        check(status in 200..299) { "无法创建 OpenCode 会话" }
        val session = JSONObject(response).getJSONObject("data")
        check(session.getJSONObject("location").getString("directory") == value) { "OpenCode 会话目录不匹配" }
        return WorkspaceContext.sessionUrl(session.getString("id"))
    }

    fun sessionDirectory(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val (status, response) = request("/api/session/$id")
        check(status == 200) { "无法读取 OpenCode 会话" }
        return WorkspaceContext.validDirectory(JSONObject(response).getJSONObject("data").getJSONObject("location").getString("directory"))
    }

    private fun request(path: String, method: String = "GET", authenticated: Boolean = true, cookie: String? = null, payload: JSONObject? = null): Pair<Int, String> {
        val connection = LoopbackHttp.open(URL(origin + path))
        try {
            connection.connectTimeout = 1500; connection.readTimeout = 3000
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (cookie != null) connection.setRequestProperty("Cookie", cookie)
            if (authenticated) connection.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("opencode:$password".toByteArray(), Base64.NO_WRAP))
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val buffer = ByteArray(64 * 1024)
                var offset = 0
                while (offset < buffer.size) { val n = input.read(buffer, offset, buffer.size - offset); if (n < 0) break; offset += n }
                String(buffer, 0, offset, Charsets.UTF_8)
            }.orEmpty()
            return code to body
        } finally { connection.disconnect() }
    }

    companion object {
        const val PORT = 49374
        const val ORIGIN = "http://127.0.0.1:49374"
        private const val COOKIE = "opencode_session_49374"

        /** Validate the WebView credential itself; HTTP Basic would conceal a broken browser session. */
        fun browserReady(cookies: String): Boolean {
            val cookie = cookies.split(';').map(String::trim).firstOrNull { it.startsWith("$COOKIE=") } ?: return false
            if (cookie.length > 512 || cookie.any { it == '\r' || it == '\n' }) return false
            return runCatching { LocalOpenCode("").request("/api/info", authenticated = false, cookie = cookie).first == 200 }.getOrDefault(false)
        }

        /** Consume the upstream one-use link natively so an old WebView service worker cannot intercept pairing. */
        fun browserCookie(pairingUrl: String): String? {
            val url = URL(pairingUrl)
            require(url.protocol == "http" && url.host == "127.0.0.1" && url.port == PORT && url.userInfo == null &&
                url.path.matches(Regex("/auth/connect/[A-Za-z0-9_-]{16,128}")) && url.query == null && url.ref == null)
            val connection = LoopbackHttp.open(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 1500; connection.readTimeout = 3000
                connection.setRequestProperty("Accept", "text/html")
                if (connection.responseCode == 401) return null
                check(connection.responseCode == 302 && connection.getHeaderField("Location") == "/") { "Local browser pairing failed" }
                val cookie = connection.getHeaderField("Set-Cookie") ?: error("Local browser pairing omitted its credential")
                check(cookie.startsWith("$COOKIE=") && cookie.contains("httponly", ignoreCase = true) &&
                    cookie.length < 2048 && cookie.none { it == '\r' || it == '\n' }) { "Invalid local browser credential" }
                return cookie
            } finally { connection.disconnect() }
        }
    }
}
