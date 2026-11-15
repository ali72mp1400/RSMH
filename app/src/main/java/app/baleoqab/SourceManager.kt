package app.baleoqab

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

object SourceManager {

    /** منابع پیش‌فرض (به ترتیب اولویت). اگه یکی بسته بود، بقیه استفاده میشن. */
    val DEFAULT_SOURCES = listOf(
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/top100.txt",
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs.txt",
        "https://raw.githubusercontent.com/Epodonios/v2ray-configs/refs/heads/main/Sub1.txt"
    )

    private const val PER_SOURCE = 150
    private const val MAX_PARSE = 1500
    private const val KEEP = 30

    class Result(
        val nodes: List<SubscriptionParser.Node>,
        val pings: List<Int>,
        val candidates: Int,
        val failedSources: Int
    )

    /** روی thread پس‌زمینه صدا بزن. */
    fun refresh(sources: List<String>, onProgress: (String) -> Unit): Result {
        onProgress("در حال دریافت از ${sources.size} منبع...")
        val fetchPool = Executors.newFixedThreadPool(4)
        val fetched: List<List<SubscriptionParser.Node>?> = try {
            sources.map { src ->
                fetchPool.submit(Callable<List<SubscriptionParser.Node>?> {
                    try {
                        SubscriptionParser.parseNodes(SubscriptionParser.fetchBody(src)).take(MAX_PARSE)
                    } catch (e: Exception) {
                        null
                    }
                })
            }.map { it.get() }
        } finally {
            fetchPool.shutdown()
        }
        val failed = fetched.count { it == null }

        val seen = HashSet<String>()
        val candidates = mutableListOf<SubscriptionParser.Node>()
        for (maybeList in fetched) {
            val list = maybeList ?: continue
            var taken = 0
            for (n in list) {
                if (taken >= PER_SOURCE) break
                if (n.outbound.optString("type") == "hysteria2") continue
                if (seen.add(key(n))) {
                    candidates.add(n)
                    taken++
                }
            }
        }

        onProgress("تست ${candidates.size} سرور...")
        val pool = Executors.newFixedThreadPool(24)
        val pings: List<Int> = try {
            candidates.map { n ->
                pool.submit(Callable<Int> {
                    tcpPing(n.outbound.optString("server"), n.outbound.optInt("server_port"))
                })
            }.map { it.get() }
        } finally {
            pool.shutdown()
        }

        val order = candidates.indices.filter { pings[it] >= 0 }.sortedBy { pings[it] }.take(KEEP)
        return Result(order.map { candidates[it] }, order.map { pings[it] }, candidates.size, failed)
    }

    private fun key(n: SubscriptionParser.Node): String {
        val o = n.outbound
        return listOf(
            o.optString("type"),
            o.optString("server"),
            o.optInt("server_port").toString(),
            o.optString("uuid"),
            o.optString("password")
        ).joinToString("|")
    }

    private fun tcpPing(host: String, port: Int): Int {
        val socket = Socket()
        return try {
            val addr = InetSocketAddress(host, port)
            if (addr.isUnresolved) return -1
            val t0 = System.nanoTime()
            socket.connect(addr, 2500)
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        } catch (e: Exception) {
            -1
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }
}
