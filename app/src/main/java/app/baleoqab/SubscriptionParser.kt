package app.baleoqab

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object SubscriptionParser {

    data class Node(val name: String, val outbound: JSONObject, val raw: String)

    // برای سازگاری با کدهای قدیمی
    fun parseConfig(input: String): String = input.trim()

    /** روی thread پس‌زمینه صدا بزن. */
    fun load(input: String): List<JSONObject> = parseNodes(fetchBody(input)).map { it.outbound }

    /** اگه ورودی لینک باشه دانلود میکنه (با آینه‌های جایگزین)، وگرنه خود متن رو برمیگردونه. */
    fun fetchBody(
        input: String,
        connectMs: Int = 10000,
        readMs: Int = 10000,
        maxBytes: Int = 2_000_000,
        retries: Int = 0,
        deadline: Long = Long.MAX_VALUE
    ): String {
        val t = input.trim()
        if (!(t.startsWith("http://") || t.startsWith("https://"))) return t
        var lastError: Exception? = null
        for (attempt in 0..retries) {
            for (u in candidates(t)) {
                if (System.currentTimeMillis() > deadline) {
                    throw lastError ?: IllegalStateException("timeout")
                }
                try {
                    val body = download(u, connectMs, readMs, maxBytes)
                    if (body.isNotBlank()) return body
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        throw lastError ?: IllegalStateException("پاسخ خالی بود")
    }

    private fun candidates(url: String): List<String> {
        val list = mutableListOf(url)
        val m = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$")
            .find(url)
        if (m != null) {
            val (user, repo, branch, path) = m.destructured
            for (host in listOf("cdn.jsdelivr.net", "fastly.jsdelivr.net", "gcore.jsdelivr.net")) {
                list.add("https://$host/gh/$user/$repo@$branch/$path")
            }
        }
        return list
    }

    /** دانلود با سقف حجم (برای اینترنت ضعیف). */
    fun download(url: String, connectMs: Int, readMs: Int, maxBytes: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "sing-box")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { ins ->
                val buf = ByteArray(8192)
                while (out.size() < maxBytes) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            var text = out.toString("UTF-8")
            if (out.size() >= maxBytes) {
                // خروجی ناقص: خط آخر رو دور بنداز
                text = if (text.contains("://")) {
                    text.substringBeforeLast('\n', text)
                } else {
                    val clean = text.filter { !it.isWhitespace() }
                    clean.substring(0, clean.length - clean.length % 4)
                }
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    fun parseNodes(raw: String): List<Node> {
        val text = decodeIfBase64(raw.trim())
        val result = mutableListOf<Node>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val ob = (try { parseOne(l) } catch (e: Exception) { null }) ?: continue
            ob.put("tag", "proxy-${result.size + 1}")
            val name = nameOf(l).ifEmpty { "${ob.optString("server")}:${ob.optInt("server_port")}" }
            result.add(Node(name, ob, l))
        }
        return result
    }

    private fun nameOf(l: String): String {
        if (l.startsWith("vmess://")) {
            return try {
                JSONObject(LinkParsers.b64(l.removePrefix("vmess://").trim())).optString("ps")
            } catch (e: Exception) { "" }
        }
        return Uri.decode(l.substringAfter('#', "")).trim()
    }

    private fun decodeIfBase64(t: String): String {
        if (t.contains("://")) return t
        return try {
            String(Base64.decode(t, Base64.DEFAULT), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            try { String(Base64.decode(t, Base64.URL_SAFE), StandardCharsets.UTF_8) } catch (e2: Exception) { t }
        }
    }

    private fun parseOne(l: String): JSONObject? = when {
        l.startsWith("vless://") -> LinkParsers.vless(l)
        l.startsWith("vmess://") -> LinkParsers.vmess(l)
        l.startsWith("trojan://") -> LinkParsers.trojan(l)
        l.startsWith("ss://") -> LinkParsers.shadowsocks(l)
        l.startsWith("hy2://") || l.startsWith("hysteria2://") -> LinkParsers.hysteria2(l)
        else -> null
    }
}
