package app.baleoqab

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ServerAdapter(private val onSelect: (Int) -> Unit) :
    RecyclerView.Adapter<ServerAdapter.VH>() {

    private var items: List<Pair<String, String>> = emptyList()
    var selected = 0

    fun submit(nodes: List<SubscriptionParser.Node>) {
        val list = mutableListOf("⚡ خودکار (بهترین سرور)" to "انتخاب خودکار بین همه سرورها")
        nodes.forEach { list.add(it.name to describe(it)) }
        items = list
        if (selected >= items.size) selected = 0
        notifyDataSetChanged()
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
        val name: TextView = v.findViewById(R.id.tvName)
        val info: TextView = v.findViewById(R.id.tvInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val (name, info) = items[position]
        h.name.text = name
        h.info.text = info
        h.rb.isChecked = position == selected
        h.row.setBackgroundResource(
            if (position == selected) R.drawable.rsmh_card_sel else R.drawable.rsmh_card
        )
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
