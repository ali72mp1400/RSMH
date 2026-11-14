package app.baleoqab

import org.json.JSONArray
import org.json.JSONObject

object ConfigBuilder {
    private const val MAX_NODES = 30

    fun build(nodes: List<JSONObject>, dnsList: List<String>, logPath: String? = null): String {
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
                .put("interval", "5m")
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

        return JSONObject()
            .put("log", log)
            .put("dns", dns)
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", outbounds)
            .put("route", route)
            .toString()
    }
}
