package io.github.opencodeandroid.preview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Probe exactly the selected loopback port. Never sends cookies or HTTP credentials. */
suspend fun PreviewTarget.isListening(): Boolean = withContext(Dispatchers.IO) {
    try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
        true
    } catch (_: IOException) { false }
}
