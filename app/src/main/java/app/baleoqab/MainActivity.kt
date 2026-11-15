package app.baleoqab

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var etConfig: EditText
    private lateinit var etDns: EditText
    private lateinit var btnUpdate: Button
    private lateinit var btnPing: Button
    private lateinit var btnApps: Button
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var rv: RecyclerView
    private lateinit var adapter: ServerAdapter

    private var nodes: List<SubscriptionParser.Node> = emptyList()
    private var pingGen = 0
    private var refreshing = false

    private val prefs by lazy { getSharedPreferences("rsmh", Context.MODE_PRIVATE) }

    private val sixHours = 6L * 60 * 60 * 1000

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
        setContentView(R.layout.activity_main)

        etConfig = findViewById(R.id.etConfig)
        etDns = findViewById(R.id.etDns)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnPing = findViewById(R.id.btnPing)
        btnApps = findViewById(R.id.btnApps)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvCount = findViewById(R.id.tvCount)
        rv = findViewById(R.id.rvServers)

        etConfig.hint = "لینک اشتراک دلخواه (اختیاری)"

        tvStatus.setOnLongClickListener {
            val f = File(filesDir, "box.log")
            val text = if (f.exists()) f.readLines().takeLast(30).joinToString("\n") else "لاگی نیست"
            AlertDialog.Builder(this).setTitle("لاگ").setMessage(text)
                .setPositiveButton("باشه", null).show()
            true
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        etConfig.setText(prefs.getString("source", ""))
        etDns.setText(prefs.getString("dns", ""))

        adapter = ServerAdapter { pos -> prefs.edit().putInt("selected", pos).apply() }
        adapter.selected = prefs.getInt("selected", 0)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        val body = prefs.getString("body", "") ?: ""
        nodes = try { SubscriptionParser.parseNodes(body) } catch (e: Exception) { emptyList() }
        adapter.submit(nodes)
        updateCount(nodes.size)
        updateAppsButton()

        btnUpdate.setOnClickListener { refreshSources() }
        btnPing.setOnClickListener { runPingTest() }
        btnApps.setOnClickListener { pickApps() }

        btnConnect.setOnClickListener {
            val s = HiddifyVpnService.state
            if (s == HiddifyVpnService.STATE_CONNECTED || s == HiddifyVpnService.STATE_CONNECTING) {
                stopVpnService()
            } else {
                prepareVpn()
            }
        }

        val last = prefs.getLong("last_refresh", 0L)
        if (nodes.isEmpty() || System.currentTimeMillis() - last > sixHours) {
            refreshSources()
        } else {
            runPingTest()
        }
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
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun updateCount(n: Int) {
        val last = prefs.getLong("last_refresh", 0L)
        val age = if (last == 0L) "" else {
            val min = (System.currentTimeMillis() - last) / 60000
            if (min < 60) " • $min دقیقه پیش" else " • ${min / 60} ساعت پیش"
        }
        tvCount.text = if (n > 0) "فهرست سرورها ($n)$age" else "فهرست سرورها"
    }

    private fun updateAppsButton() {
        val n = (prefs.getStringSet("vpn_apps", emptySet()) ?: emptySet()).size
        btnApps.text = if (n == 0) "برنامه‌ها: همه از VPN استفاده کنند" else "برنامه‌ها: فقط $n برنامه انتخاب‌شده"
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
                updateAppsButton()
                toast("از اتصال بعدی اعمال میشه")
            }
            .setNeutralButton("همه برنامه‌ها") { _, _ ->
                prefs.edit().remove("vpn_apps").apply()
                updateAppsButton()
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
            runOnUiThread { if (gen == pingGen) adapter.setPing(i, ms) }
        }
    }

    /** از منابع خودکار (و لینک دلخواه) لیست میگیره، تست میکنه و ۳۰ سرور برتر رو نگه میداره. */
    private fun refreshSources() {
        if (refreshing) return
        refreshing = true
        btnUpdate.isEnabled = false
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
                btnUpdate.isEnabled = true
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
                tvStatus.text = "متصل و ایمن ($message)"
                setConnectButton(true)
            }
            HiddifyVpnService.STATE_CONNECTING -> {
                tvStatus.text = "در حال اتصال..."
                setConnectButton(true)
            }
            HiddifyVpnService.STATE_ERROR -> {
                tvStatus.text = "خطا: $message"
                setConnectButton(false)
            }
            else -> {
                tvStatus.text = "قطع شد"
                setConnectButton(false)
            }
        }
    }

    private fun setConnectButton(connected: Boolean) {
        btnConnect.text = if (connected) "قطع اتصال" else "اتصال به سرور"
        btnConnect.setBackgroundColor(if (connected) 0xFFCC0000.toInt() else 0xFF2D7BFF.toInt())
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
