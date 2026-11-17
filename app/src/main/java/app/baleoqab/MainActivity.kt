package app.baleoqab

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var screens: List<View>
    private lateinit var navs: List<LinearLayout>

    private lateinit var tvChip: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnConnect: LinearLayout
    private lateinit var ringDash: View
    private lateinit var tvPowerLabel: TextView
    private lateinit var cardActive: View
    private lateinit var cardStats: View
    private lateinit var tvActiveBadge: TextView
    private lateinit var tvActiveName: TextView
    private lateinit var tvActiveInfo: TextView
    private lateinit var tvActivePing: TextView
    private lateinit var tvStatServers: TextView
    private lateinit var tvStatUpdated: TextView
    private lateinit var tvStatApps: TextView

    private lateinit var tvLive: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvUp: TextView
    private lateinit var tvTotalDown: TextView
    private lateinit var tvTotalUp: TextView
    private lateinit var tvPeak: TextView
    private lateinit var graph: TrafficGraphView

    private lateinit var btnUpdate: LinearLayout
    private lateinit var btnPing: LinearLayout
    private lateinit var btnFastest: LinearLayout
    private lateinit var tvCount: TextView
    private lateinit var rv: RecyclerView
    private lateinit var adapter: ServerAdapter

    private lateinit var rowApps: View
    private lateinit var tvAppsValue: TextView
    private lateinit var etDns: EditText
    private lateinit var etConfig: EditText
    private lateinit var swAuto: SwitchCompat
    private lateinit var swRescue: SwitchCompat
    private lateinit var swMemory: SwitchCompat
    private lateinit var rowLog: View
    private lateinit var rowAbout: View

    private var nodes: List<SubscriptionParser.Node> = emptyList()
    private var pingGen = 0
    private var refreshing = false

    private var lastRx = 0L
    private var lastTx = 0L
    private var peakDown = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("rsmh", Context.MODE_PRIVATE) }
    private val sixHours = 6L * 60 * 60 * 1000

    private val colorWhite = 0xFFFFFFFF.toInt()
    private val colorGreen = 0xFF4ADE80.toInt()
    private val colorAmber = 0xFFF59E0B.toInt()
    private val colorRed = 0xFFEF4444.toInt()
    private val colorMuted = 0xFF9FB2C6.toInt()

    private fun uidRx(): Long {
        val v = TrafficStats.getUidRxBytes(Process.myUid())
        return if (v < 0) 0L else v
    }

    private fun uidTx(): Long {
        val v = TrafficStats.getUidTxBytes(Process.myUid())
        return if (v < 0) 0L else v
    }

    private fun fmtSpeed(b: Long): String = when {
        b >= 1_048_576L -> String.format(Locale.US, "%.1f MB/s", b / 1_048_576.0)
        b >= 1024L -> String.format(Locale.US, "%d KB/s", b / 1024)
        else -> "$b B/s"
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", b / 1_073_741_824.0)
        b >= 1_048_576L -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
        b >= 1024L -> String.format(Locale.US, "%d KB", b / 1024)
        else -> "$b B"
    }

    private val tick = object : Runnable {
        override fun run() {
            if (HiddifyVpnService.state != HiddifyVpnService.STATE_CONNECTED) return

            val since = prefs.getLong("connected_at", 0L)
            if (since > 0) {
                val s = (System.currentTimeMillis() - since) / 1000
                val t = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                tvTimer.text = "$t · تونل آماده است"
            }

            val rx = uidRx()
            val tx = uidTx()
            if (lastRx == 0L) {
                lastRx = rx
                lastTx = tx
            }
            val dRx = maxOf(0L, rx - lastRx)
            val dTx = maxOf(0L, tx - lastTx)
            lastRx = rx
            lastTx = tx

            tvDown.text = fmtSpeed(dRx)
            tvUp.text = fmtSpeed(dTx)
            graph.push(dRx.toFloat(), dTx.toFloat())
            if (dRx > peakDown) peakDown = dRx
            tvPeak.text = fmtSpeed(peakDown)
            tvTotalDown.text = fmtSize(maxOf(0L, rx - prefs.getLong("base_rx", rx)))
            tvTotalUp.text = fmtSize(maxOf(0L, tx - prefs.getLong("base_tx", tx)))

            handler.postDelayed(this, 1000)
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpnService()
        else toast("مجوز اتصال VPN رد شد")
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            val s = i.getStringExtra(HiddifyVpnService.EXTRA_STATE) ?: return
            applyState(s, i.getStringExtra(HiddifyVpnService.EXTRA_MESSAGE) ?: "")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0B1220.toInt()
        window.navigationBarColor = 0xFF0F1B33.toInt()
        setContentView(R.layout.activity_main)

        bindViews()
        addTrafficCard()
        styleViews()
        resetTraffic()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        etConfig.setText(prefs.getString("source", ""))
        etDns.setText(prefs.getString("dns", ""))
        swAuto.isChecked = prefs.getBoolean("auto_update", true)
        swAuto.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("auto_update", c).apply() }
        swRescue.isEnabled = false
        swMemory.isEnabled = false

        adapter = ServerAdapter { pos ->
            prefs.edit().putInt("selected", pos).apply()
            updateActiveCard()
        }
        adapter.selected = prefs.getInt("selected", 0)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        val body = prefs.getString("body", "") ?: ""
        nodes = try { SubscriptionParser.parseNodes(body) } catch (e: Exception) { emptyList() }
        adapter.submit(nodes)
        updateCount(nodes.size)

        navs[0].setOnClickListener { showTab(0) }
        navs[1].setOnClickListener { showTab(1) }
        navs[2].setOnClickListener { showTab(2) }
        showTab(0)

        btnUpdate.setOnClickListener { refreshSources() }
        btnPing.setOnClickListener { runPingTest() }
        btnFastest.setOnClickListener { selectFastest() }
        rowApps.setOnClickListener { pickApps() }
        rowLog.setOnClickListener { showLog() }
        tvStatus.setOnLongClickListener { showLog(); true }
        rowAbout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("درباره RSMH")
                .setMessage("هسته sing-box · نسخه ۱٫۰٫۰\nلیست سرورها از منابع عمومی گرفته میشه و صاحب هر سرور ناشناسه. وارد حساب‌های مهم نشو.")
                .setPositiveButton("باشه", null)
                .show()
        }

        btnConnect.setOnClickListener {
            val s = HiddifyVpnService.state
            if (s == HiddifyVpnService.STATE_CONNECTED || s == HiddifyVpnService.STATE_CONNECTING) {
                stopVpnService()
            } else {
                prepareVpn()
            }
        }

        val last = prefs.getLong("last_refresh", 0L)
        val old = System.currentTimeMillis() - last > sixHours
        if (nodes.isEmpty() || (swAuto.isChecked && old)) {
            refreshSources()
        } else {
            runPingTest()
        }
    }

    private fun bindViews() {
        screens = listOf<View>(
            findViewById(R.id.screenConnect),
            findViewById(R.id.screenConfigs),
            findViewById(R.id.screenSettings)
        )
        navs = listOf<LinearLayout>(
            findViewById(R.id.navConnect),
            findViewById(R.id.navConfigs),
            findViewById(R.id.navSettings)
        )
        tvChip = findViewById(R.id.tvChip)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        btnConnect = findViewById(R.id.btnConnect)
        ringDash = findViewById(R.id.ringDash)
        tvPowerLabel = findViewById(R.id.tvPowerLabel)
        cardActive = findViewById(R.id.cardActive)
        cardStats = findViewById(R.id.cardStats)
        tvActiveBadge = findViewById(R.id.tvActiveBadge)
        tvActiveName = findViewById(R.id.tvActiveName)
        tvActiveInfo = findViewById(R.id.tvActiveInfo)
        tvActivePing = findViewById(R.id.tvActivePing)
        tvStatServers = findViewById(R.id.tvStatServers)
        tvStatUpdated = findViewById(R.id.tvStatUpdated)
        tvStatApps = findViewById(R.id.tvStatApps)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnPing = findViewById(R.id.btnPing)
        btnFastest = findViewById(R.id.btnFastest)
        tvCount = findViewById(R.id.tvCount)
        rv = findViewById(R.id.rvServers)
        rowApps = findViewById(R.id.rowApps)
        tvAppsValue = findViewById(R.id.tvAppsValue)
        etDns = findViewById(R.id.etDns)
        etConfig = findViewById(R.id.etConfig)
        swAuto = findViewById(R.id.swAuto)
        swRescue = findViewById(R.id.swRescue)
        swMemory = findViewById(R.id.swMemory)
        rowLog = findViewById(R.id.rowLog)
        rowAbout = findViewById(R.id.rowAbout)
    }

    /** کارت ترافیک زنده رو درست زیر دایره اضافه میکنه. */
    private fun addTrafficCard() {
        val dial = btnConnect.parent as ViewGroup
        val col = dial.parent as ViewGroup
        val card = layoutInflater.inflate(R.layout.view_traffic, col, false)
        col.addView(card, col.indexOfChild(dial) + 1)
        Ui.card(card)
        tvLive = card.findViewById(R.id.tvLive)
        tvDown = card.findViewById(R.id.tvDown)
        tvUp = card.findViewById(R.id.tvUp)
        tvTotalDown = card.findViewById(R.id.tvTotalDown)
        tvTotalUp = card.findViewById(R.id.tvTotalUp)
        tvPeak = card.findViewById(R.id.tvPeak)
        graph = card.findViewById(R.id.graph)
    }

    private fun resetTraffic() {
        lastRx = 0L
        lastTx = 0L
        peakDown = 0L
        tvDown.text = "0 B/s"
        tvUp.text = "0 B/s"
        tvPeak.text = "—"
        tvTotalDown.text = "0 B"
        tvTotalUp.text = "0 B"
        tvLive.text = "○ آماده"
        tvLive.setTextColor(colorMuted)
        graph.clear()
    }

    private fun styleViews() {
        Ui.appBg(findViewById<View>(R.id.root))
        Ui.navBar(findViewById<View>(R.id.nav))
        Ui.card(tvChip)
        Ui.card(cardActive)
        Ui.card(cardStats)
        Ui.badge(tvActiveBadge)
        Ui.ringDash(ringDash)
        Ui.card(btnUpdate)
        Ui.card(btnPing)
        Ui.card(btnFastest)
        Ui.card(findViewById<View>(R.id.grp1))
        Ui.card(findViewById<View>(R.id.grp2))
        Ui.card(findViewById<View>(R.id.grp3))
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(HiddifyVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        applyState(HiddifyVpnService.state, "")
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun showTab(i: Int) {
        screens.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        navs.forEachIndexed { idx, v ->
            val active = idx == i
            if (active) Ui.navActive(v) else v.setBackgroundResource(0)
            val c = if (active) colorWhite else colorMuted
            (v.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(c)
            (v.getChildAt(1) as TextView).setTextColor(c)
        }
    }

    private fun ageText(last: Long): String {
        val min = (System.currentTimeMillis() - last) / 60000
        return if (min < 60) "$min دقیقه" else "${min / 60} ساعت"
    }

    private fun appsCount(): Int = (prefs.getStringSet("vpn_apps", emptySet()) ?: emptySet()).size

    private fun updateCount(n: Int) {
        val last = prefs.getLong("last_refresh", 0L)
        val age = if (last == 0L) "" else " • ${ageText(last)} پیش"
        tvCount.text = if (n > 0) "فهرست سرورها ($n)$age" else "فهرست سرورها"
        updateSummary()
        updateActiveCard()
    }

    private fun updateSummary() {
        tvChip.text = if (nodes.isEmpty()) "بدون سرور" else "${nodes.size} سرور"
        tvStatServers.text = nodes.size.toString()
        val last = prefs.getLong("last_refresh", 0L)
        tvStatUpdated.text = if (last == 0L) "—" else ageText(last)
        val n = appsCount()
        tvStatApps.text = if (n == 0) "همه" else n.toString()
        tvAppsValue.text = (if (n == 0) "همه" else "$n برنامه") + " ›"
    }

    private fun updateActiveCard() {
        val sel = adapter.selected
        if (sel <= 0 || sel - 1 >= nodes.size) {
            tvActiveBadge.text = "AU"
            tvActiveName.text = "خودکار"
            tvActiveInfo.text = if (nodes.isEmpty()) "هنوز سروری نیست" else "بهترین بین ${nodes.size} سرور"
            tvActivePing.text = ""
        } else {
            val n = nodes[sel - 1]
            tvActiveBadge.text = ServerAdapter.badgeOf(n.outbound)
            tvActiveName.text = n.name
            tvActiveInfo.text = "${n.outbound.optString("server")}:${n.outbound.optInt("server_port")}"
            val (t, c) = ServerAdapter.pingText(adapter.pingOf(sel - 1))
            tvActivePing.text = t
            tvActivePing.setTextColor(c)
        }
    }

    private fun showLog() {
        val f = File(filesDir, "box.log")
        val text = if (f.exists()) f.readLines().takeLast(30).joinToString("\n") else "لاگی نیست"
        AlertDialog.Builder(this).setTitle("لاگ").setMessage(text)
            .setPositiveButton("باشه", null).show()
    }

    private fun pickApps() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val saved = prefs.getStringSet("vpn_apps", emptySet()) ?: emptySet()
        val checked = BooleanArray(apps.size) { apps[it].first in saved }
        AlertDialog.Builder(this)
            .setTitle("برنامه‌هایی که از VPN استفاده کنند")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("ذخیره") { _, _ ->
                val set = apps.indices.filter { checked[it] }.map { apps[it].first }.toSet()
                prefs.edit().putStringSet("vpn_apps", set).apply()
                updateSummary()
                toast("از اتصال بعدی اعمال میشه")
            }
            .setNeutralButton("همه برنامه‌ها") { _, _ ->
                prefs.edit().remove("vpn_apps").apply()
                updateSummary()
                toast("از اتصال بعدی اعمال میشه")
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun runPingTest() {
        if (nodes.isEmpty()) return
        val gen = ++pingGen
        adapter.resetPings()
        LatencyTester.test(nodes) { i, ms ->
            runOnUiThread {
                if (gen == pingGen) {
                    adapter.setPing(i, ms)
                    updateActiveCard()
                }
            }
        }
    }

    private fun selectFastest() {
        var best = -1
        var bestMs = Int.MAX_VALUE
        for (i in nodes.indices) {
            val ms = adapter.pingOf(i)
            if (ms >= 0 && ms < bestMs) {
                bestMs = ms
                best = i
            }
        }
        if (best < 0) {
            toast("اول «تست همه» رو بزن و صبر کن")
            return
        }
        adapter.selected = best + 1
        adapter.notifyDataSetChanged()
        prefs.edit().putInt("selected", adapter.selected).apply()
        rv.scrollToPosition(best + 1)
        updateActiveCard()
        toast("سریع‌ترین: ${nodes[best].name} (${bestMs}ms)")
    }

    private fun setUpdateBusy(busy: Boolean) {
        btnUpdate.isEnabled = !busy
        btnUpdate.alpha = if (busy) 0.5f else 1f
    }

    /** از منابع خودکار (و لینک دلخواه) لیست میگیره، تست میکنه و ۳۰ سرور برتر رو نگه میداره. */
    private fun refreshSources() {
        if (refreshing) return
        refreshing = true
        setUpdateBusy(true)
        val custom = etConfig.text.toString().trim()
        prefs.edit().putString("source", custom).apply()
        val sources = (if (custom.isNotEmpty()) listOf(custom) else emptyList()) + SourceManager.DEFAULT_SOURCES
        Thread {
            val result = try {
                SourceManager.refresh(sources) { msg -> runOnUiThread { tvCount.text = msg } }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                refreshing = false
                setUpdateBusy(false)
                if (result == null) {
                    toast("خطا در به‌روزرسانی")
                    updateCount(nodes.size)
                } else if (result.nodes.isEmpty()) {
                    toast("سرور سالمی پیدا نشد (اینترنت یا منابع رو چک کن)")
                    updateCount(nodes.size)
                } else {
                    prefs.edit()
                        .putString("body", result.nodes.joinToString("\n") { it.raw })
                        .putLong("last_refresh", System.currentTimeMillis())
                        .putInt("selected", 0)
                        .apply()
                    nodes = result.nodes
                    adapter.selected = 0
                    adapter.submit(nodes)
                    result.pings.forEachIndexed { i, ms -> adapter.setPing(i, ms) }
                    updateCount(nodes.size)
                    toast("${nodes.size} سرور سالم از ${result.candidates} تست‌شده")
                }
            }
        }.start()
    }

    private fun applyState(state: String, message: String) {
        when (state) {
            HiddifyVpnService.STATE_CONNECTED -> {
                tvStatus.text = "متصل"
                tvStatus.setTextColor(colorGreen)
                tvPowerLabel.text = "قطع اتصال"
                Ui.powerOn(btnConnect)
                if (prefs.getLong("connected_at", 0L) == 0L) {
                    prefs.edit()
                        .putLong("connected_at", System.currentTimeMillis())
                        .putLong("base_rx", uidRx())
                        .putLong("base_tx", uidTx())
                        .apply()
                }
                lastRx = 0L
                lastTx = 0L
                tvLive.text = "● زنده"
                tvLive.setTextColor(colorGreen)
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
            HiddifyVpnService.STATE_CONNECTING -> {
                tvStatus.text = "در حال اتصال..."
                tvStatus.setTextColor(colorAmber)
                tvTimer.text = "لطفاً چند ثانیه صبر کن"
                tvPowerLabel.text = "لغو"
                Ui.powerWait(btnConnect)
                handler.removeCallbacks(tick)
            }
            HiddifyVpnService.STATE_ERROR -> {
                tvStatus.text = "خطا"
                tvStatus.setTextColor(colorRed)
                tvTimer.text = message
                tvPowerLabel.text = "اتصال"
                Ui.powerOff(btnConnect)
                prefs.edit().remove("connected_at").apply()
                handler.removeCallbacks(tick)
                resetTraffic()
            }
            else -> {
                tvStatus.text = "قطع شد"
                tvStatus.setTextColor(colorWhite)
                tvTimer.text = "برای اتصال، دکمه را بزن"
                tvPowerLabel.text = "اتصال"
                Ui.powerOff(btnConnect)
                prefs.edit().remove("connected_at").apply()
                handler.removeCallbacks(tick)
                resetTraffic()
            }
        }
    }

    private fun prepareVpn() {
        val body = prefs.getString("body", "") ?: ""
        if (body.isEmpty()) {
            toast("هنوز لیست سرورها آماده نیست. چند ثانیه صبر کن یا به‌روزرسانی بزن")
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startVpnService()
    }

    private fun startVpnService() {
        val dns = etDns.text.toString().trim()
        prefs.edit().putString("dns", dns).apply()
        val intent = Intent(this, HiddifyVpnService::class.java).apply {
            action = HiddifyVpnService.ACTION_CONNECT
            putExtra(HiddifyVpnService.EXTRA_CONFIG, prefs.getString("body", "") ?: "")
            putExtra(HiddifyVpnService.EXTRA_DNS, dns)
            putExtra(HiddifyVpnService.EXTRA_INDEX, adapter.selected - 1)
        }
        ContextCompat.startForegroundService(this, intent)
        applyState(HiddifyVpnService.STATE_CONNECTING, "")
    }

    private fun stopVpnService() {
        startService(Intent(this, HiddifyVpnService::class.java).apply {
            action = HiddifyVpnService.ACTION_DISCONNECT
        })
    }
}
