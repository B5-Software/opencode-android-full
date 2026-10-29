package io.github.opencodeandroid.runtime

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/** Host credentials belong only to the fixed-address local services, independent of proxy settings. */
internal object LoopbackHttp {
    fun open(url: URL): HttpURLConnection {
        require(url.protocol == "http" && url.host == "127.0.0.1" && url.port in 1024..65535 &&
            url.userInfo == null && url.ref == null) { "Invalid local service endpoint" }
        return (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply { instanceFollowRedirects = false }
    }
}
