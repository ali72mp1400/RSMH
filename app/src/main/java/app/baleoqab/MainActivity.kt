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
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var rv: RecyclerView
    private lateinit var adapter: ServerAdapter

    private val prefs by lazy { getSharedPreferences("rsmh", Context.MODE_PRIVATE) }

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
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)
        tvCount = findViewById(R.id.tvCount)
        rv = findViewById(R.id.rvServers)

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
        val saved = try { SubscriptionParser.parseNodes(body) } catch (e: Exception) { emptyList() }
        adapter.submit(saved)
        updateCount(saved.size)

        btnUpdate.setOnClickListener { updateSubscription() }

        btnConnect.setOnClickListener {
            val s = HiddifyVpnService.state
            if (s == HiddifyVpnService.STATE_CONNECTED || s == HiddifyVpnService.STATE_CONNECTING) {
                stopVpnService()
            } else {
                prepareVpn()
            }
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
        tvCount.text = if (n > 0) "فهرست سرورها ($n)" else "فهرست سرورها"
    }

    private fun updateSubscription() {
        val src = etConfig.text.toString().trim()
        if (src.isEmpty()) {
            toast("لینک اشتراک رو وارد کن")
            return
        }
        btnUpdate.isEnabled = false
        Thread {
            try {
                val body = SubscriptionParser.fetchBody(src)
                val nodes = SubscriptionParser.parseNodes(body)
                runOnUiThread {
                    if (nodes.isEmpty()) {
                        toast("هیچ کانفیگ معتبری پیدا نشد")
                    } else {
                        prefs.edit().putString("source", src).putString("body", body)
                            .putInt("selected", 0).apply()
                        adapter.selected = 0
                        adapter.submit(nodes)
                        updateCount(nodes.size)
                        toast("${nodes.size} سرور دریافت شد")
                    }
                    btnUpdate.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast("خطا: ${e.message}")
                    btnUpdate.isEnabled = true
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
            toast("اول لینک اشتراک رو بذار و به‌روزرسانی بزن")
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
