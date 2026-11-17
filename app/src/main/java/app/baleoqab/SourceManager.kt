package app.baleoqab

import android.text.Html
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object SourceManager {

    /** منابع گیت‌هاب (اولویت بالا). */
    val GITHUB_SOURCES = listOf(
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/top100.txt",
        "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/v2ray-base64.txt",
        "https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/verified/configs.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Splitted-By-Protocol/vless.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Splitted-By-Protocol/trojan.txt",
        "https://raw.githubusercontent.com/Epodonios/v2ray-configs/refs/heads/main/Sub1.txt"
    )

    /** ۷ منبع دیگه. اگه raw.githubusercontent بسته باشه، از آینه‌ی jsDelivr هم تلاش میشه. */
    val SITE_SOURCES = listOf(
        "https://raw.githubusercontent.com/nyeinkokoaung404/V2ray-Configs/main/Sub1.txt",
        "https://raw.githubusercontent.com/nyeinkokoaung404/V2ray-Configs/main/configs/proxy_configs_tested.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Configs/main/Sub1.txt",
        "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/All_Configs_Sub.txt",
        "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/vless.txt",
        "https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/filtered/subs/vmess.txt",
        "https://raw.githubusercontent.com/kasesm/Free-Config/refs/heads/main/vless_raw.txt"
    )

    /** سه کانال تلگرام پیش‌فرض (در تنظیمات قابل تغییره). */
    val DEFAULT_TELEGRAM = listOf("NetAccount", "R_v2ray_configs", "supreme_vpns")

    val DEFAULT_SOURCES: List<String> get() = GITHUB_SOURCES + SITE_SOURCES

    private const val KEEP = 30
    private const val CACHE_MAX_AGE = 48L * 60 * 60 * 1000

    /** تنظیمات بسته به کیفیت اینترنت. حالت «ضعیف»: زمان‌های بیشتر، حجم و تعداد همزمانی کمتر. */
    class Tuning(slow: Boolean) {
        val connectMs = if (slow) 15000 else 8000
        val readMs = if (slow) 30000 else 12000
        val retries = if (slow) 1 else 0
        val maxBytes = if (slow) 500_000 else 1_500_000
        val fetchThreads = if (slow) 3 else 4
        val fetchPhaseMs = if (slow) 100_000L else 40_000L
        val pingMs = if (slow) 4000 else 2500
        val pingThreads = if (slow) 16 else 32
        val pingPhaseMs = if (slow) 60_000L else 25_000L
        val perSource = if (slow) 40 else 80
        val maxCandidates = if (slow) 220 else 400
    }

    class Result(
        val nodes: List<SubscriptionParser.Node>,
        val pings: List<Int>,
        val candidates: Int,
        val failedSources: Int,
        val fromCache: Int
    )

    private val linkRegex =
        Regex("(?<![A-Za-z0-9])(?:vless|vmess|trojan|ss|hysteria2|hy2)://[^\\s<>\"'`]+")

    private fun isBigRaw(url: String): Boolean =
        listOf("barry-far", "Epodonios", "MatinGhanbari", "kasesm", "nyeinkokoaung404").any { url.contains(it) }

    private class Fetched(val nodes: List<SubscriptionParser.Node>, val cached: Boolean)

    /** روی thread پس‌زمینه صدا بزن. منابع با «tg:» شروع بشن یعنی کانال تلگرام. */
    fun refresh(
        sources: List<String>,
        tuning: Tuning,
        cacheRoot: File,
        onProgress: (String) -> Unit
    ): Result {
        val deadline = System.currentTimeMillis() + tuning.fetchPhaseMs
        onProgress("در حال دریافت از ${sources.size} منبع...")
        val done = AtomicInteger(0)
        val fetchPool = Executors.newFixedThreadPool(tuning.fetchThreads)
        val fetched: List<Fetched?> = try {
            val futures = sources.map { src ->
                fetchPool.submit(Callable<Fetched?> {
                    val r = fetchNodes(src, tuning, cacheRoot, deadline)
                    onProgress("دریافت ${done.incrementAndGet()} از ${sources.size}")
                    r
                })
            }
            futures.map { f ->
                val remain = (deadline - System.currentTimeMillis()).coerceAtLeast(1000L)
                try {
                    f.get(remain, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    f.cancel(true)
                    null
                }
            }
        } finally {
            fetchPool.shutdownNow()
        }
        val failed = fetched.count { it == null }
        val cachedCount = fetched.count { it != null && it.cached }

        // حذف تکراری‌ها، سپس چیدن نوبتی بین منابع تا همه سهم داشته باشن
        val seen = HashSet<String>()
        val perSource = fetched.map { f ->
            val out = ArrayList<SubscriptionParser.Node>()
            if (f != null) {
                for (n in f.nodes) {
                    if (out.size >= tuning.perSource) break
                    if (n.outbound.optString("type") == "hysteria2") continue
                    if (seen.add(key(n))) out.add(n)
                }
            }
            out
        }
        val candidates = ArrayList<SubscriptionParser.Node>()
        var idx = 0
        while (candidates.size < tuning.maxCandidates) {
            var any = false
            for (l in perSource) {
                if (idx < l.size && candidates.size < tuning.maxCandidates) {
                    candidates.add(l[idx])
                    any = true
                }
            }
            if (!any) break
            idx++
        }

        onProgress("تست ${candidates.size} سرور...")
        val pool = Executors.newFixedThreadPool(tuning.pingThreads)
        val pings: List<Int> = try {
            val tasks = candidates.map { n ->
                Callable<Int> {
                    tcpPing(n.outbound.optString("server"), n.outbound.optInt("server_port"), tuning.pingMs)
                }
            }
            pool.invokeAll(tasks, tuning.pingPhaseMs, TimeUnit.MILLISECONDS).map { f ->
                if (f.isCancelled) -1 else try { f.get() } catch (e: Exception) { -1 }
            }
        } finally {
            pool.shutdownNow()
        }

        val order = candidates.indices.filter { pings[it] >= 0 }.sortedBy { pings[it] }.take(KEEP)
        return Result(order.map { candidates[it] }, order.map { pings[it] }, candidates.size, failed, cachedCount)
    }

    private fun fetchNodes(src: String, t: Tuning, cacheRoot: File, deadline: Long): Fetched? {
        val cf = File(cacheRoot, "src_" + Integer.toHexString(src.hashCode()) + ".txt")
        var body: String? = null
        var cached = false
        try {
            body = when {
                src.startsWith("tg:") -> fetchTelegram(src.removePrefix("tg:"), t, deadline)
                src.startsWith("http://") || src.startsWith("https://") ->
                    SubscriptionParser.fetchBody(src, t.connectMs, t.readMs, t.maxBytes, t.retries, deadline)
                else -> src
            }
            if (body.isNotBlank() && (src.startsWith("http") || src.startsWith("tg:")) && body.length < 1_200_000) {
                try {
                    cacheRoot.mkdirs()
                    cf.writeText(body)
                } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            body = null
        }
        if (body == null && cf.exists() && System.currentTimeMillis() - cf.lastModified() < CACHE_MAX_AGE) {
            body = try { cf.readText() } catch (e: Exception) { null }
            cached = body != null
        }
        if (body == null) return null
        val all = try { SubscriptionParser.parseNodes(body) } catch (e: Exception) { return null }
        return Fetched(if (isBigRaw(src)) all.shuffled() else all, cached)
    }

    /** صفحه‌ی عمومی t.me/s/<channel> رو میخونه و لینک کانفیگ‌ها رو درمیاره (تازه‌ترین‌ها اول). */
    private fun fetchTelegram(raw: String, t: Tuning, deadline: Long): String {
        val name = raw.trim()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("t.me/").removePrefix("s/").removePrefix("@").trim('/')
        if (name.isEmpty()) throw IllegalArgumentException("empty channel")
        val urls = listOf(
            "https://t.me/s/$name",
            "https://telegram.me/s/$name",
            "https://telegram.dog/s/$name",
            "https://t-me.translate.goog/s/$name?_x_tr_sl=auto&_x_tr_tl=en"
        )
        var last: Exception? = null
        for (u in urls) {
            if (System.currentTimeMillis() > deadline) break
            try {
                val html = SubscriptionParser.download(u, t.connectMs, t.readMs, maxOf(t.maxBytes, 700_000))
                val text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
                val links = linkRegex.findAll(text)
                    .map { it.value.trimEnd('.', ',', ';') }
                    .toList().reversed().distinct().take(t.perSource * 2)
                if (links.isNotEmpty()) return links.joinToString("\n")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("tg: no configs")
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

    private fun tcpPing(host: String, port: Int, timeoutMs: Int): Int {
        val socket = Socket()
        return try {
            val addr = InetSocketAddress(host, port)
            if (addr.isUnresolved) return -1
            val t0 = System.nanoTime()
            socket.connect(addr, timeoutMs)
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        } catch (e: Exception) {
            -1
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }
}
