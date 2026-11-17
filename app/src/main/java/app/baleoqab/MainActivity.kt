package app.baleoqab

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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

    companion object {
        private const val MODE_SMART = "smart"
        private const val MODE_COUNTRY = "country"
        private const val MODE_SERVER = "server"
        private const val SMART_COUNT = 8
    }

    private lateinit var screens: List<View>
    private var tab = 0

    // صفحه‌ی اصلی
    private lateinit var tvChip: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvUp: TextView
    private lateinit var tvTotal: TextView
    private lateinit var btnConnect: FrameLayout
    private lateinit var orb: OrbView
    private lateinit var tvOrbState: TextView
    private lateinit var tvOrbServer: TextView
    private lateinit var tileLeft: LinearLayout
    private lateinit var tvLeftIcon: TextView
    private lateinit var tvLeftName: TextView
    private lateinit var tileSmart: LinearLayout
    private lateinit var ivSmart: ImageView
    private lateinit var tvSmartSub: TextView
    private lateinit var tileRight: LinearLayout
    private lateinit var tvRightIcon: TextView
    private lateinit var tvRightName: TextView
    private lateinit var btnAllServers: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnMenu: ImageView
    private lateinit var btnApps: ImageView

    // لیست سرورها
    private lateinit var btnBackServers: ImageView
    private lateinit var btnUpdate: LinearLayout
    private lateinit var btnPing: LinearLayout
    private lateinit var btnFastest: LinearLayout
    private lateinit var tvCount: TextView
    private lateinit var rv: RecyclerView
    private lateinit var adapter: ServerAdapter

    // تنظیمات
    private lateinit var btnBackSettings: ImageView
    private lateinit var rowApps: View
    private lateinit var tvAppsValue: TextView
    private lateinit var etDns: EditText
    private lateinit var etConfig: EditText
    private lateinit var etExtra: EditText
    private lateinit var etTg1: EditText
    private lateinit var etTg2: EditText
    private lateinit var etTg3: EditText
    private lateinit var swAuto: SwitchCompat
    private lateinit var swSlow: SwitchCompat
    private lateinit var btnUpdateSettings: TextView
    private lateinit var rowLog: View
    private lateinit var rowAbout: View

    private var nodes: List<SubscriptionParser.Node> = emptyList()
    private var countries: List<Pair<String, List<Int>>> = emptyList()
    private var mode = MODE_SMART
    private var countryCode = ""
    private var pingGen = 0
    private var refreshing = false
    private var pendingStart = false
    private var shownState = ""
    private var transientHint = ""

    private var lastRx = 0L
    private var lastTx = 0L
    private var lastT = 0L
    private var baseRx = 0L
    private var baseTx = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("rsmh", Context.MODE_PRIVATE) }
    private val sixHours = 6L * 60 * 60 * 1000

    private val tick = object : Runnable {
        override fun run() {
            if (HiddifyVpnService.state != HiddifyVpnService.STATE_CONNECTED) return
            val since = prefs.getLong("connected_at", 0L)
            if (since > 0) {
                val s = (System.currentTimeMillis() - since) / 1000
                tvTimer.text = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            }
            sampleTraffic()
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
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        setContentView(R.layout.activity_main)

        bindViews()
        styleViews()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        loadSettings()
        swAuto.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("auto_update", c).apply() }
        swSlow.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("slow", c).apply() }

        val savedSel = prefs.getInt("selected", 0)
        mode = prefs.getString("mode", null) ?: if (savedSel > 0) MODE_SERVER else MODE_SMART
        countryCode = prefs.getString("country", "") ?: ""

        adapter = ServerAdapter { pos -> onServerPicked(pos) }
        adapter.selected = if (mode == MODE_SERVER) savedSel else 0
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        val body = prefs.getString("body", "") ?: ""
        nodes = try { SubscriptionParser.parseNodes(body) } catch (e: Exception) { emptyList() }
        adapter.submit(nodes)
        updateCount(nodes.size)
        showTab(0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tab != 0) {
                    showTab(0)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        btnMenu.setOnClickListener { showTab(2) }
        btnApps.setOnClickListener { pickApps() }
        btnAllServers.setOnClickListener { showTab(1) }
        btnBackServers.setOnClickListener { showTab(0) }
        btnBackSettings.setOnClickListener { saveSettings(); showTab(0) }

        tileSmart.setOnClickListener { chooseMode(MODE_SMART, null) }
        tileLeft.setOnClickListener { countries.getOrNull(0)?.let { chooseMode(MODE_COUNTRY, it.first) } }
        tileRight.setOnClickListener { countries.getOrNull(1)?.let { chooseMode(MODE_COUNTRY, it.first) } }

        btnUpdate.setOnClickListener { refreshSources() }
        btnPing.setOnClickListener { runPingTest() }
        btnFastest.setOnClickListener { selectFastest() }
        btnUpdateSettings.setOnClickListener {
            saveSettings()
            showTab(0)
            refreshSources()
        }
        rowApps.setOnClickListener { pickApps() }
        rowLog.setOnClickListener { showLog() }
        tvOrbState.setOnLongClickListener { showLog(); true }
        rowAbout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("درباره RSMH")
                .setMessage("هسته sing-box · نسخه ۱٫۱٫۰\nلیست سرورها از منابع عمومی (گیت‌هاب و کانال‌های تلگرام) گرفته میشه و صاحب هر سرور ناشناسه. وارد حساب‌های مهم نشو.")
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
        tvChip = findViewById(R.id.tvChip)
        tvTimer = findViewById(R.id.tvTimer)
        tvDown = findViewById(R.id.tvDown)
        tvUp = findViewById(R.id.tvUp)
        tvTotal = findViewById(R.id.tvTotal)
        btnConnect = findViewById(R.id.btnConnect)
        orb = findViewById(R.id.orb)
        tvOrbState = findViewById(R.id.tvOrbState)
        tvOrbServer = findViewById(R.id.tvOrbServer)
        tileLeft = findViewById(R.id.tileLeft)
        tvLeftIcon = findViewById(R.id.tvLeftIcon)
        tvLeftName = findViewById(R.id.tvLeftName)
        tileSmart = findViewById(R.id.tileSmart)
        ivSmart = findViewById(R.id.ivSmart)
        tvSmartSub = findViewById(R.id.tvSmartSub)
        tileRight = findViewById(R.id.tileRight)
        tvRightIcon = findViewById(R.id.tvRightIcon)
        tvRightName = findViewById(R.id.tvRightName)
        btnAllServers = findViewById(R.id.btnAllServers)
        tvHint = findViewById(R.id.tvHint)
        btnMenu = findViewById(R.id.btnMenu)
        btnApps = findViewById(R.id.btnApps)

        btnBackServers = findViewById(R.id.btnBackServers)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnPing = findViewById(R.id.btnPing)
        btnFastest = findViewById(R.id.btnFastest)
        tvCount = findViewById(R.id.tvCount)
        rv = findViewById(R.id.rvServers)

        btnBackSettings = findViewById(R.id.btnBackSettings)
        rowApps = findViewById(R.id.rowApps)
        tvAppsValue = findViewById(R.id.tvAppsValue)
        etDns = findViewById(R.id.etDns)
        etConfig = findViewById(R.id.etConfig)
        etExtra = findViewById(R.id.etExtra)
        etTg1 = findViewById(R.id.etTg1)
        etTg2 = findViewById(R.id.etTg2)
        etTg3 = findViewById(R.id.etTg3)
        swAuto = findViewById(R.id.swAuto)
        swSlow = findViewById(R.id.swSlow)
        btnUpdateSettings = findViewById(R.id.btnUpdateSettings)
        rowLog = findViewById(R.id.rowLog)
        rowAbout = findViewById(R.id.rowAbout)
    }

    private fun styleViews() {
        Ui.appBg(findViewById<View>(R.id.root))
        Ui.iconBtn(btnMenu)
        Ui.iconBtn(btnApps)
        Ui.wide(btnAllServers)
        Ui.wide(btnUpdateSettings)
        Ui.card(btnUpdate)
        Ui.card(btnPing)
        Ui.card(btnFastest)
        Ui.card(findViewById<View>(R.id.grp1))
        Ui.card(findViewById<View>(R.id.grp2))
        Ui.card(findViewById<View>(R.id.grp3))
        listOf(etDns, etConfig, etExtra, etTg1, etTg2, etTg3).forEach { Ui.input(it) }
        setChip("قطع", Ui.MUTED)
        tvOrbState.text = "قطع"
        orb.setState(OrbView.OFF)
    }

    private fun loadSettings() {
        etConfig.setText(prefs.getString("source", ""))
        etDns.setText(prefs.getString("dns", ""))
        etExtra.setText(prefs.getString("extra", ""))
        etTg1.setText(prefs.getString("tg1", SourceManager.DEFAULT_TELEGRAM[0]))
        etTg2.setText(prefs.getString("tg2", SourceManager.DEFAULT_TELEGRAM[1]))
        etTg3.setText(prefs.getString("tg3", SourceManager.DEFAULT_TELEGRAM[2]))
        swAuto.isChecked = prefs.getBoolean("auto_update", true)
        swSlow.isChecked = prefs.getBoolean("slow", true)
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("source", etConfig.text.toString().trim())
            .putString("dns", etDns.text.toString().trim())
            .putString("extra", etExtra.text.toString().trim())
            .putString("tg1", etTg1.text.toString().trim())
            .putString("tg2", etTg2.text.toString().trim())
            .putString("tg3", etTg3.text.toString().trim())
            .apply()
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
        saveSettings()
        handler.removeCallbacks(tick)
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun showTab(i: Int) {
        tab = i
        screens.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
    }

    private fun setChip(text: String, color: Int) {
        tvChip.text = text
        Ui.pill(tvChip, color)
    }

    private fun setHint(msg: String) {
        transientHint = msg
        tvHint.text = msg
    }

    // ---------- ترافیک ----------

    private fun fmtBytes(b: Long): String = when {
        b < 1024L -> "$b B"
        b < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    }

    private fun fmtSpeed(b: Long): String = fmtBytes(b) + "/s"

    /** ترافیک تونل = ترافیک خود برنامه روی شبکه‌ی واقعی (برنامه از VPN مستثنیه). */
    private fun sampleTraffic() {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) {
            tvDown.text = "—"
            tvUp.text = "—"
            tvTotal.text = "—"
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastT == 0L) {
            baseRx = rx
            baseTx = tx
            lastRx = rx
            lastTx = tx
            lastT = now
        }
        val dt = (now - lastT).coerceAtLeast(1L)
        val down = (rx - lastRx).coerceAtLeast(0L) * 1000L / dt
        val up = (tx - lastTx).coerceAtLeast(0L) * 1000L / dt
        lastRx = rx
        lastTx = tx
        lastT = now
        tvDown.text = fmtSpeed(down)
        tvUp.text = fmtSpeed(up)
        tvTotal.text = fmtBytes((rx - baseRx).coerceAtLeast(0L) + (tx - baseTx).coerceAtLeast(0L))
        orb.setActivity(down + up)
    }

    // ---------- حالت‌ها (هوشمند / کشور / سرور) ----------

    private fun rebuildCountries() {
        val map = LinkedHashMap<String, MutableList<Int>>()
        for (i in nodes.indices) {
            val c = Geo.codeOf(nodes[i].name) ?: continue
            map.getOrPut(c) { mutableListOf() }.add(i)
        }
        countries = map.entries
            .map { it.key to it.value.toList() }
            .sortedWith(compareBy({ -it.second.size }, { it.second.first() }))
    }

    private fun smartIndices(): IntArray {
        if (nodes.isEmpty()) return IntArray(0)
        val ok = ArrayList<Pair<Int, Int>>()
        val unknown = ArrayList<Int>()
        for (i in nodes.indices) {
            val ms = adapter.pingOf(i)
            if (ms >= 0) ok.add(i to ms)
            else if (ms != -1) unknown.add(i)
        }
        val list = ok.sortedBy { it.second }.map { it.first } + unknown
        val chosen = if (list.isEmpty()) nodes.indices.toList() else list
        return chosen.take(SMART_COUNT).toIntArray()
    }

    private fun currentIndices(): IntArray {
        if (nodes.isEmpty()) return IntArray(0)
        return when (mode) {
            MODE_COUNTRY -> {
                val l = nodes.indices.filter { Geo.codeOf(nodes[it].name) == countryCode }.take(SMART_COUNT)
                if (l.isEmpty()) smartIndices() else l.toIntArray()
            }
            MODE_SERVER -> {
                val s = adapter.selected - 1
                if (s in nodes.indices) intArrayOf(s) else smartIndices()
            }
            else -> smartIndices()
        }
    }

    private fun modeLabel(): String = when (mode) {
        MODE_COUNTRY ->
            if (countryCode.isNotEmpty()) "${Geo.flag(countryCode)} ${Geo.nameFa(countryCode)}" else "🌐 هوشمند"
        MODE_SERVER -> {
            val i = adapter.selected - 1
            if (i in nodes.indices) nodes[i].name else "🌐 هوشمند"
        }
        else -> "🌐 هوشمند"
    }

    private fun chooseMode(newMode: String, code: String?) {
        mode = newMode
        if (code != null) countryCode = code
        if (newMode != MODE_SERVER) {
            adapter.selected = 0
            adapter.notifyDataSetChanged()
        }
        prefs.edit()
            .putString("mode", mode)
            .putString("country", countryCode)
            .putInt("selected", adapter.selected)
            .apply()
        updateHome()
        val s = HiddifyVpnService.state
        if (s == HiddifyVpnService.STATE_CONNECTED || s == HiddifyVpnService.STATE_CONNECTING) {
            restartVpn()
        } else {
            prepareVpn()
        }
    }

    private fun onServerPicked(pos: Int) {
        mode = if (pos <= 0) MODE_SMART else MODE_SERVER
        prefs.edit().putString("mode", mode).putInt("selected", adapter.selected).apply()
        updateHome()
        showTab(0)
        val s = HiddifyVpnService.state
        if (s == HiddifyVpnService.STATE_CONNECTED || s == HiddifyVpnService.STATE_CONNECTING) restartVpn()
    }

    private fun bindCountryTile(tile: LinearLayout, icon: TextView, name: TextView, c: Pair<String, List<Int>>?) {
        if (c == null) {
            icon.text = "—"
            name.text = "—"
            tile.alpha = 0.4f
            tile.isEnabled = false
            Ui.tile(tile, false)
        } else {
            icon.text = Geo.flag(c.first)
            name.text = Geo.nameFa(c.first)
            tile.alpha = 1f
            tile.isEnabled = true
            Ui.tile(tile, mode == MODE_COUNTRY && countryCode == c.first)
        }
    }

    private fun appsCount(): Int = (prefs.getStringSet("vpn_apps", emptySet()) ?: emptySet()).size

    private fun ageText(last: Long): String {
        val min = (System.currentTimeMillis() - last) / 60000
        return if (min < 60) "$min دقیقه" else "${min / 60} ساعت"
    }

    private fun summaryHint(): String {
        if (nodes.isEmpty()) return "هنوز سروری نیست؛ در تنظیمات «ذخیره و به‌روزرسانی» را بزن"
        val last = prefs.getLong("last_refresh", 0L)
        val age = if (last == 0L) "" else " • ${ageText(last)} پیش"
        return "${nodes.size} سرور$age"
    }

    private fun updateHome() {
        rebuildCountries()
        bindCountryTile(tileLeft, tvLeftIcon, tvLeftName, countries.getOrNull(0))
        bindCountryTile(tileRight, tvRightIcon, tvRightName, countries.getOrNull(1))
        val smartOn = mode == MODE_SMART
        Ui.tile(tileSmart, smartOn)
        ivSmart.imageTintList = ColorStateList.valueOf(if (smartOn) Ui.GREEN else Ui.MUTED)
        tvSmartSub.text = if (smartOn) "فعال ✓" else "فعال‌سازی"
        tvSmartSub.setTextColor(if (smartOn) Ui.GREEN else Ui.MUTED)
        tvOrbServer.text = modeLabel()
        tvHint.text = if (transientHint.isNotEmpty()) transientHint else summaryHint()
        val n = appsCount()
        tvAppsValue.text = (if (n == 0) "همه" else "$n برنامه") + " ›"
    }

    private fun updateCount(n: Int) {
        val last = prefs.getLong("last_refresh", 0L)
        val age = if (last == 0L) "" else " • ${ageText(last)} پیش"
        tvCount.text = if (n > 0) "همه سرورها ($n)$age" else "همه سرورها"
        updateHome()
    }

    // ---------- ابزارها ----------

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
                updateHome()
                toast("از اتصال بعدی اعمال میشه")
            }
            .setNeutralButton("همه برنامه‌ها") { _, _ ->
                prefs.edit().remove("vpn_apps").apply()
                updateHome()
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
                if (gen == pingGen) adapter.setPing(i, ms)
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
        mode = MODE_SERVER
        prefs.edit().putString("mode", mode).putInt("selected", adapter.selected).apply()
        rv.scrollToPosition(best + 1)
        updateHome()
        toast("سریع‌ترین: ${nodes[best].name} (${bestMs}ms)")
    }

    private fun setUpdateBusy(busy: Boolean) {
        btnUpdate.isEnabled = !busy
        btnUpdate.alpha = if (busy) 0.5f else 1f
        btnUpdateSettings.isEnabled = !busy
        btnUpdateSettings.alpha = if (busy) 0.5f else 1f
    }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }

    private fun buildSources(): List<String> {
        val list = ArrayList<String>()
        val custom = etConfig.text.toString().trim()
        if (custom.isNotEmpty()) list.add(custom)
        etExtra.text.toString().lines().map { it.trim() }.filter { it.startsWith("http") }.forEach { list.add(it) }
        list.addAll(SourceManager.GITHUB_SOURCES)
        list.addAll(SourceManager.SITE_SOURCES)
        listOf(etTg1, etTg2, etTg3)
            .map { it.text.toString().trim() }
            .filter { it.isNotEmpty() }
            .forEach { list.add("tg:$it") }
        return list.distinct()
    }

    /** از همه‌ی منابع (گیت‌هاب، سایت‌ها، سه کانال تلگرام) لیست میگیره، تست میکنه و ۳۰ سرور برتر رو نگه میداره. */
    private fun refreshSources() {
        if (refreshing) return
        if (!hasNetwork()) {
            toast("اینترنت وصل نیست")
            return
        }
        refreshing = true
        setUpdateBusy(true)
        saveSettings()
        val sources = buildSources()
        val tuning = SourceManager.Tuning(prefs.getBoolean("slow", true))
        val cacheRoot = File(filesDir, "srccache")
        Thread {
            val result = try {
                SourceManager.refresh(sources, tuning, cacheRoot) { msg ->
                    runOnUiThread { setHint(msg); tvCount.text = msg }
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                refreshing = false
                setUpdateBusy(false)
                transientHint = ""
                if (result == null) {
                    toast("خطا در به‌روزرسانی")
                    updateCount(nodes.size)
                } else if (result.nodes.isEmpty()) {
                    toast("سرور سالمی پیدا نشد (اینترنت یا منابع رو چک کن)")
                    updateCount(nodes.size)
                } else {
                    if (mode == MODE_SERVER) mode = MODE_SMART
                    prefs.edit()
                        .putString("body", result.nodes.joinToString("\n") { it.raw })
                        .putLong("last_refresh", System.currentTimeMillis())
                        .putInt("selected", 0)
                        .putString("mode", mode)
                        .apply()
                    nodes = result.nodes
                    adapter.selected = 0
                    adapter.submit(nodes)
                    result.pings.forEachIndexed { i, ms -> adapter.setPing(i, ms) }
                    updateCount(nodes.size)
                    val extra = if (result.fromCache > 0) " (${result.fromCache} منبع از حافظه)" else ""
                    toast("${nodes.size} سرور سالم از ${result.candidates} تست‌شده$extra")
                }
            }
        }.start()
    }

    // ---------- اتصال ----------

    private fun applyState(state: String, message: String) {
        val changed = shownState != state
        shownState = state
        when (state) {
            HiddifyVpnService.STATE_CONNECTED -> {
                setChip("متصل", Ui.GREEN)
                tvOrbState.text = "متصل"
                orb.setState(OrbView.ON)
                if (prefs.getLong("connected_at", 0L) == 0L) {
                    prefs.edit().putLong("connected_at", System.currentTimeMillis()).apply()
                }
                if (changed) lastT = 0L
                transientHint = ""
                updateHome()
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
            HiddifyVpnService.STATE_CONNECTING -> {
                setChip("در حال اتصال", Ui.AMBER)
                tvOrbState.text = "..."
                orb.setState(OrbView.WAIT)
                handler.removeCallbacks(tick)
                setHint("لطفاً چند ثانیه صبر کن")
            }
            HiddifyVpnService.STATE_ERROR -> {
                setChip("خطا", Ui.RED)
                tvOrbState.text = "خطا"
                orb.setState(OrbView.ERR)
                prefs.edit().remove("connected_at").apply()
                handler.removeCallbacks(tick)
                resetTraffic()
                setHint(message)
            }
            else -> {
                setChip("قطع", Ui.MUTED)
                tvOrbState.text = "قطع"
                orb.setState(OrbView.OFF)
                prefs.edit().remove("connected_at").apply()
                handler.removeCallbacks(tick)
                resetTraffic()
                transientHint = ""
                updateHome()
                if (pendingStart) {
                    pendingStart = false
                    handler.postDelayed({ prepareVpn() }, 1200)
                }
            }
        }
    }

    private fun resetTraffic() {
        tvTimer.text = "00:00:00"
        tvDown.text = "0 B/s"
        tvUp.text = "0 B/s"
        tvTotal.text = "0 B"
        orb.setActivity(0L)
        lastT = 0L
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
        saveSettings()
        val dns = etDns.text.toString().trim()
        val intent = Intent(this, HiddifyVpnService::class.java).apply {
            action = HiddifyVpnService.ACTION_CONNECT
            putExtra(HiddifyVpnService.EXTRA_CONFIG, prefs.getString("body", "") ?: "")
            putExtra(HiddifyVpnService.EXTRA_DNS, dns)
            putExtra(HiddifyVpnService.EXTRA_INDEX, -1)
            putExtra(HiddifyVpnService.EXTRA_INDICES, currentIndices())
            putExtra(HiddifyVpnService.EXTRA_SLOW, prefs.getBoolean("slow", true))
        }
        ContextCompat.startForegroundService(this, intent)
        applyState(HiddifyVpnService.STATE_CONNECTING, "")
    }

    private fun stopVpnService() {
        startService(Intent(this, HiddifyVpnService::class.java).apply {
            action = HiddifyVpnService.ACTION_DISCONNECT
        })
    }

    private fun restartVpn() {
        pendingStart = true
        stopVpnService()
    }
}
