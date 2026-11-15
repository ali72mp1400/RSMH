package app.baleoqab

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

object LatencyTester {
    private val pool = Executors.newFixedThreadPool(8)

    /** نتیجه: میلی‌ثانیه، -1 یعنی در دسترس نیست، -2 یعنی UDP (قابل تست با TCP نیست) */
    fun test(nodes: List<SubscriptionParser.Node>, onResult: (Int, Int) -> Unit) {
        nodes.forEachIndexed { i, n ->
            pool.execute {
                val ob = n.outbound
                val ms = if (ob.optString("type") == "hysteria2") {
                    -2
                } else {
                    tcpPing(ob.optString("server"), ob.optInt("server_port"))
                }
                onResult(i, ms)
            }
        }
    }

    private fun tcpPing(host: String, port: Int): Int {
        val socket = Socket()
        return try {
            val addr = InetSocketAddress(host, port)
            if (addr.isUnresolved) return -1
            val t0 = System.nanoTime()
            socket.connect(addr, 3000)
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        } catch (e: Exception) {
            -1
        } finally {
            try { socket.close() } catch (e: Exception) { }
        }
    }
}
