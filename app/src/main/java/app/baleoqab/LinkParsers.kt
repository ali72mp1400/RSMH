package app.baleoqab

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

object LinkParsers {

    fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8)
    } catch (e: Exception) {
        String(Base64.decode(s, Base64.URL_SAFE), StandardCharsets.UTF_8)
    }

    private fun supported(t: String?) =
        t.isNullOrEmpty() || t in listOf("tcp", "ws", "grpc", "httpupgrade")

    private fun transport(type: String?, host: String?, path: String?, service: String?): JSONObject? =
        when (type) {
            "ws" -> JSONObject().put("type", "ws").put("path", path ?: "/").also {
                if (!host.isNullOrEmpty()) it.put("headers", JSONObject().put("Host", host))
            }
            "grpc" -> JSONObject().put("type", "grpc").put("service_name", service ?: path ?: "")
            "httpupgrade" -> JSONObject().put("type", "httpupgrade").put("path", path ?: "/").also {
                if (!host.isNullOrEmpty()) it.put("host", host)
            }
            else -> null
        }

    private fun tlsFromQuery(u: Uri, defaultSec: String): JSONObject? {
        val sec = u.getQueryParameter("security") ?: defaultSec
        if (sec != "tls" && sec != "reality") return null
        val tls = JSONObject().put("enabled", true)
        val sni = u.getQueryParameter("sni") ?: u.getQueryParameter("host")
        if (!sni.isNullOrEmpty()) tls.put("server_name", sni)
        if (u.getQueryParameter("allowInsecure") == "1" || u.getQueryParameter("insecure") == "1") {
            tls.put("insecure", true)
        }
        u.getQueryParameter("alpn")?.takeIf { it.isNotEmpty() }?.let {
            tls.put("alpn", JSONArray(it.split(",")))
        }
        val fp = u.getQueryParameter("fp")
        if (!fp.isNullOrEmpty()) {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        }
        if (sec == "reality") {
            tls.put(
                "reality",
                JSONObject().put("enabled", true)
                    .put("public_key", u.getQueryParameter("pbk") ?: "")
                    .put("short_id", u.getQueryParameter("sid") ?: "")
            )
            if (fp.isNullOrEmpty()) {
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
            }
        }
        return tls
    }

    fun vless(l: String): JSONObject? {
        val u = Uri.parse(l)
        val type = u.getQueryParameter("type")
        if (!supported(type)) return null
        val host = u.host?.trim('[', ']') ?: return null
        val o = JSONObject().put("type", "vless")
            .put("server", host)
            .put("server_port", if (u.port > 0) u.port else 443)
            .put("uuid", u.userInfo ?: return null)
        u.getQueryParameter("flow")?.takeIf { it.isNotEmpty() }?.let { o.put("flow", it) }
        tlsFromQuery(u, "none")?.let { o.put("tls", it) }
        transport(type, u.getQueryParameter("host"), u.getQueryParameter("path"), u.getQueryParameter("serviceName"))
            ?.let { o.put("transport", it) }
        return o
    }

    fun trojan(l: String): JSONObject? {
        val u = Uri.parse(l)
        val type = u.getQueryParameter("type")
        if (!supported(type)) return null
        val host = u.host?.trim('[', ']') ?: return null
        val o = JSONObject().put("type", "trojan")
            .put("server", host)
            .put("server_port", if (u.port > 0) u.port else 443)
            .put("password", u.userInfo ?: return null)
        tlsFromQuery(u, "tls")?.let { o.put("tls", it) }
        transport(type, u.getQueryParameter("host"), u.getQueryParameter("path"), u.getQueryParameter("serviceName"))
            ?.let { o.put("transport", it) }
        return o
    }

    fun vmess(l: String): JSONObject? {
        val j = JSONObject(b64(l.removePrefix("vmess://").trim()))
        val net = j.optString("net", "tcp")
        if (!supported(net)) return null
        val o = JSONObject().put("type", "vmess")
            .put("server", j.getString("add"))
            .put("server_port", j.get("port").toString().toInt())
            .put("uuid", j.getString("id"))
            .put("security", j.optString("scy", "auto").ifEmpty { "auto" })
            .put("alter_id", j.optString("aid", "0").toIntOrNull() ?: 0)
        if (j.optString("tls") == "tls") {
            val tls = JSONObject().put("enabled", true)
            val sni = j.optString("sni").ifEmpty { j.optString("host") }
            if (sni.isNotEmpty()) tls.put("server_name", sni)
            val fp = j.optString("fp")
            if (fp.isNotEmpty()) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
            o.put("tls", tls)
        }
        transport(net, j.optString("host"), j.optString("path"), j.optString("path"))
            ?.let { o.put("transport", it) }
        return o
    }

    fun shadowsocks(l: String): JSONObject? {
        if (l.contains("plugin=")) return null
        val body = l.removePrefix("ss://").substringBefore("#").substringBefore("?")
        val creds: String
        val hostPort: String
        if (body.contains("@")) {
            val userPart = body.substringBeforeLast("@")
            hostPort = body.substringAfterLast("@")
            creds = if (userPart.contains(":")) Uri.decode(userPart) else b64(userPart)
        } else {
            val dec = b64(body)
            creds = dec.substringBeforeLast("@")
            hostPort = dec.substringAfterLast("@")
        }
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: return null
        return JSONObject().put("type", "shadowsocks")
            .put("server", hostPort.substringBeforeLast(":").trim('[', ']'))
            .put("server_port", port)
            .put("method", creds.substringBefore(":"))
            .put("password", creds.substringAfter(":"))
    }

    fun hysteria2(l: String): JSONObject? {
        val u = Uri.parse(l.replaceFirst("hy2://", "hysteria2://"))
        val host = u.host?.trim('[', ']') ?: return null
        val o = JSONObject().put("type", "hysteria2")
            .put("server", host)
            .put("server_port", if (u.port > 0) u.port else 443)
            .put("password", u.userInfo ?: return null)
        val tls = JSONObject().put("enabled", true)
        val sni = u.getQueryParameter("sni")
        if (!sni.isNullOrEmpty()) tls.put("server_name", sni)
        if (u.getQueryParameter("insecure") == "1") tls.put("insecure", true)
        o.put("tls", tls)
        if (u.getQueryParameter("obfs") == "salamander") {
            o.put(
                "obfs",
                JSONObject().put("type", "salamander")
                    .put("password", u.getQueryParameter("obfs-password") ?: "")
            )
        }
        return o
    }
}
