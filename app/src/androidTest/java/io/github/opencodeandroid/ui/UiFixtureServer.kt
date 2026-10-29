package io.github.opencodeandroid.ui

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/** Isolated loopback HTML fixture, not a replacement for the official application UI. */
class UiFixtureServer(private val accept: String = "image/*", private val multiple: Boolean = false) : AutoCloseable {
    val upload = AtomicReference<String?>(null)
    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 49374)) }
    @Volatile private var active = true
    private val worker = Thread {
        while (active) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = BufferedInputStream(socket.getInputStream())
                    fun line(): String {
                        val result = StringBuilder()
                        while (result.length < 8192) { val byte = input.read(); if (byte < 0 || byte == 10) break; if (byte != 13) result.append(byte.toChar()) }
                        return result.toString()
                    }
                    val request = line().split(' ')
                    var length = 0
                    repeat(100) { if (length >= 0) { val header = line(); if (header.isEmpty()) length = -length - 1 else if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt() } }
                    length = if (length < 0) -length - 1 else length
                    require(length in 0..1024*1024)
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val count = input.read(body, offset, length - offset); if (count < 0) break; offset += count }
                    val path = request.getOrNull(1).orEmpty()
                    val response = when (path) { "/upload" -> { upload.set(String(body)); "ok" }; "/native-cache-worker.js" -> "self.addEventListener('install',event=>event.waitUntil(self.skipWaiting()));self.addEventListener('activate',event=>event.waitUntil(clients.claim()));self.addEventListener('fetch', event => { if(!new URL(event.request.url).pathname.startsWith('/auth/')) event.respondWith(new Response('obsolete fixture worker')); });"; else -> html(path) }
                    val bytes = response.toByteArray()
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: ${when (path) { "/upload" -> "text/plain"; "/native-cache-worker.js" -> "application/javascript"; else -> "text/html" }}; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(bytes); output.flush()
                }
            } catch (_: Exception) { if (!active) break }
        }
    }.apply { isDaemon = true; start() }

    private fun html(path: String) = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>${if (path == "/second") "SECOND" else "FIRST"}</title></head>
        <body style="margin:0;background:#111512;color:#b6efce;font:20px sans-serif">
        ${if (path == "/second") "<div style='padding:40px'>Second page</div>" else "<a href='/second' style='color:inherit;display:block;padding:24px'>Next page</a>"}
        <label for="attachment" style="position:absolute;left:0;top:20%;width:100%;height:65%;display:grid;place-items:center">Choose attachments</label>
        <input id="attachment" type="file" accept="$accept" ${if (multiple) "multiple" else ""} hidden>
        <script>document.addEventListener('keydown',e=>{if(e.key==='Escape')document.querySelector('[role="dialog"]')?.remove()});document.getElementById('attachment').addEventListener('change',async e=>{
          const files=await Promise.all(Array.from(e.target.files).map(file=>new Promise(resolve=>{
            const reader=new FileReader();reader.onload=()=>resolve({name:file.name,type:file.type,data:reader.result});reader.onerror=()=>{window.uploadError=file.name+': '+reader.error;resolve({error:window.uploadError})};reader.readAsDataURL(file);
          })));await fetch('/upload',{method:'POST',body:JSON.stringify(files)});document.title='UPLOADED';
        });</script></body></html>
    """.trimIndent()

    override fun close() { active = false; server.close(); worker.join(3500) }
}
