package app.baleoqab

import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

object ConfigBuilder {
    private const val MAX_NODES = 30

    /** پورت و رمز API محلی هسته (برای فهمیدن سرور انتخاب‌شده). صفر یعنی غیرفعال. */
    @Volatile
    var clashPort = 0
        private set

    @Volatile
    var clashSecret = ""
        private set

    fun build(nodes: List<JSONObject>, dnsList: List<String>, logPath: String? = null): String {
        val port = freePort()
        val secret = randomSecret()
        val withApi = assemble(nodes, dnsList, logPath, port, secret)
        return try {
            Libbox.checkConfig(withApi)
            clashPort = port
            clashSecret = secret
            withApi
        } catch (e: Exception) {
            clashPort = 0
            clashSecret = ""
            assemble(nodes, dnsList, logPath, 0, "")
        }
    }

    private fun freePort(): Int = try {
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    } catch (e: Exception) {
        19099
    }

    private fun randomSecret(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun assemble(
        nodes: List<JSONObject>,
        dnsList: List<String>,
        logPath: String?,
        port: Int,
        secret: String
    ): String {
        val used = nodes.take(MAX_NODES)
        val tags = JSONArray()
        used.forEach { tags.put(it.getString("tag")) }

        val remoteDns = dnsList.firstOrNull() ?: "1.1.1.1"

        val outbounds = JSONArray()
        used.forEach { outbounds.put(it) }
        outbounds.put(
            JSONObject().put("type", "urltest").put("tag", "proxy")
                .put("outbounds", tags)
                .put("url", "https://www.gstatic.com/generate_204")
                .put("interval", "3m")
                .put("tolerance", 50)
        )
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val dns = JSONObject()
            .put(
                "servers", JSONArray()
                    .put(JSONObject().put("type", "local").put("tag", "dns-direct"))
                    .put(
                        JSONObject().put("type", "https").put("tag", "dns-remote")
                            .put("server", remoteDns).put("detour", "proxy")
                    )
            )
            .put("final", "dns-remote")
            .put("strategy", "ipv4_only")

        val tun = JSONObject().put("type", "tun").put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30"))
            .put("mtu", 1400)
            .put("auto_route", true)
            .put("stack", "mixed")

        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put("final", "proxy")
            .put("default_domain_resolver", "dns-direct")
            .put(
                "rules", JSONArray()
                    .put(JSONObject().put("action", "sniff"))
                    .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                    .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
            )

        val log = JSONObject().put("level", "info").put("timestamp", true)
        if (logPath != null) log.put("output", logPath)

        val root = JSONObject()
            .put("log", log)
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", outbounds)
            .put("route", route)

        if (port > 0) {
            root.put(
                "experimental",
                JSONObject().put(
                    "clash_api",
                    JSONObject()
                        .put("external_controller", "127.0.0.1:$port")
                        .put("secret", secret)
                )
            )
        }
        return root.toString()
    }
}
