package app.baleoqab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class ServerAdapter(private val onSelect: (Int) -> Unit) :
    RecyclerView.Adapter<ServerAdapter.VH>() {

    companion object {
        const val NOT_TESTED = Int.MIN_VALUE

        fun pingText(ms: Int): Pair<String, Int> = when {
            ms == NOT_TESTED -> "…" to 0xFF7A8A99.toInt()
            ms == -2 -> "UDP" to 0xFF7A8A99.toInt()
            ms < 0 -> "✕ رد شد" to 0xFFE04B4B.toInt()
            ms < 150 -> "▂▄▆█ ${ms}ms" to 0xFF3DDC84.toInt()
            ms < 400 -> "▂▄▆ ${ms}ms" to 0xFFE6C84A.toInt()
            else -> "▂▄ ${ms}ms" to 0xFFFF9A3D.toInt()
        }

        fun badgeOf(outbound: JSONObject): String = when (outbound.optString("type")) {
            "vless" -> "VL"
            "vmess" -> "VM"
            "trojan" -> "TR"
            "shadowsocks" -> "SS"
            "hysteria2" -> "HY"
            else -> "??"
        }
    }

    private val white = 0xFFE6EDF7.toInt()
    private val green = 0xFF4ADE80.toInt()
    private val muted = 0xFF9FB2C6.toInt()

    private var names: List<String> = emptyList()
    private var infos: List<String> = emptyList()
    private var badges: List<String> = emptyList()
    private var pings = IntArray(0)
    private var connected = -1
    var selected = 0

    fun submit(nodes: List<SubscriptionParser.Node>) {
        names = nodes.map { it.name }
        infos = nodes.map { describe(it) }
        badges = nodes.map { badgeOf(it.outbound) }
        pings = IntArray(nodes.size) { NOT_TESTED }
        connected = -1
        if (selected > nodes.size) selected = 0
        notifyDataSetChanged()
    }

    fun resetPings() {
        pings.fill(NOT_TESTED)
        notifyDataSetChanged()
    }

    fun setPing(nodeIndex: Int, ms: Int) {
        if (nodeIndex !in pings.indices) return
        pings[nodeIndex] = ms
        notifyItemChanged(nodeIndex + 1)
        if (nodeIndex == connected) notifyItemChanged(0)
    }

    fun pingOf(nodeIndex: Int): Int = if (nodeIndex in pings.indices) pings[nodeIndex] else NOT_TESTED

    /** سروری که الان واقعاً بهش وصلیم (اندیس در لیست سرورها)، یا -1. */
    fun setConnected(i: Int) {
        val old = connected
        connected = i
        notifyItemChanged(0)
        if (old >= 0) notifyItemChanged(old + 1)
        if (i >= 0) notifyItemChanged(i + 1)
    }

    private fun describe(n: SubscriptionParser.Node): String {
        val ob = n.outbound
        val parts = mutableListOf(ob.optString("type"))
        ob.optJSONObject("transport")?.let { parts.add(it.optString("type")) }
        val tls = ob.optJSONObject("tls")
        if (tls != null) parts.add(if (tls.has("reality")) "reality" else "tls")
        return parts.joinToString(" · ")
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: View = v.findViewById(R.id.row)
        val rb: RadioButton = v.findViewById(R.id.rb)
        val badge: TextView = v.findViewById(R.id.tvBadge)
        val name: TextView = v.findViewById(R.id.tvName)
        val info: TextView = v.findViewById(R.id.tvInfo)
        val ping: TextView = v.findViewById(R.id.tvPing)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false))

    override fun getItemCount(): Int = names.size + 1

    override fun onBindViewHolder(h: VH, position: Int) {
        h.rb.isChecked = position == selected
        if (position == selected) Ui.cardSel(h.row) else Ui.card(h.row)
        Ui.badge(h.badge)

        if (position == 0) {
            h.badge.text = "AU"
            h.name.text = "⚡ خودکار (بهترین سرور)"
            h.name.setTextColor(white)
            if (selected == 0 && connected in names.indices) {
                h.info.text = "متصل به: ${names[connected]}"
                h.info.setTextColor(green)
                val (t, c) = pingText(pings[connected])
                h.ping.text = t
                h.ping.setTextColor(c)
            } else {
                h.info.text = "انتخاب خودکار بین همه سرورها"
                h.info.setTextColor(muted)
                h.ping.text = ""
            }
        } else {
            val idx = position - 1
            h.badge.text = badges[idx]
            val isConn = idx == connected
            h.name.text = if (isConn) "● ${names[idx]}" else names[idx]
            h.name.setTextColor(if (isConn) green else white)
            h.info.text = infos[idx]
            h.info.setTextColor(muted)
            val (t, c) = pingText(pings[idx])
            h.ping.text = t
            h.ping.setTextColor(c)
        }

        h.row.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val old = selected
            selected = pos
            notifyItemChanged(old)
            notifyItemChanged(pos)
            onSelect(pos)
        }
    }
    }
