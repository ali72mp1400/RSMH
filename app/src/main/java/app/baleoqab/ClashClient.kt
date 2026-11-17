package app.baleoqab

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

object ClashClient {

    /** برچسب سروری که هسته الان انتخاب کرده (مثلاً proxy-6)، یا null. روی thread پس‌زمینه صدا بزن. */
    fun currentTag(): String? {
        val port = ConfigBuilder.clashPort
        if (port <= 0) return null
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 800)
                s.soTimeout = 1500
                val req = "GET /proxies/proxy HTTP/1.0\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer ${ConfigBuilder.clashSecret}\r\n" +
                    "Connection: close\r\n\r\n"
                s.getOutputStream().write(req.toByteArray())
                s.getOutputStream().flush()
                val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
                val body = text.substringAfter("\r\n\r\n", "")
                JSONObject(body).optString("now").ifEmpty { null }
            }
        } catch (e: Exception) {
            null
        }
    }
}
