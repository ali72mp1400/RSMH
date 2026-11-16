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

    private var items: List<Triple<String, String, String>> = emptyList()
    private var pings = IntArray(0)
    var selected = 0

    fun submit(nodes: List<SubscriptionParser.Node>) {
        val list = mutableListOf(Triple("خودکار (بهترین سرور)", "انتخاب خودکار بین همه سرورها", "AU"))
        nodes.forEach { list.add(Triple(it.name, describe(it), badgeOf(it.outbound))) }
        items = list
        pings = IntArray(nodes.size) { NOT_TESTED }
        if (selected >= items.size) selected = 0
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
    }

    fun pingOf(nodeIndex: Int): Int = if (nodeIndex in pings.indices) pings[nodeIndex] else NOT_TESTED

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

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val (name, info, badge) = items[position]
        h.name.text = name
        h.info.text = info
        h.badge.text = badge
        Ui.badge(h.badge)
        h.rb.isChecked = position == selected
        if (position == selected) Ui.cardSel(h.row) else Ui.card(h.row)
        if (position == 0 || position - 1 !in pings.indices) {
            h.ping.text = ""
        } else {
            val (text, color) = pingText(pings[position - 1])
            h.ping.text = text
            h.ping.setTextColor(color)
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
