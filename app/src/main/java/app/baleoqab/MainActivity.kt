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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etConfig: EditText
    private lateinit var etDns: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "مجوز اتصال VPN رد شد", Toast.LENGTH_SHORT).show()
        }
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
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

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

    private fun applyState(state: String, message: String) {
        when (state) {
            HiddifyVpnService.STATE_CONNECTED -> {
                tvStatus.text = getString(R.string.status_connected)
                btnConnect.text = getString(R.string.disconnect)
                btnConnect.isEnabled = true
                btnConnect.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            }
            HiddifyVpnService.STATE_CONNECTING -> {
                tvStatus.text = "در حال اتصال..."
                btnConnect.text = getString(R.string.disconnect)
                btnConnect.isEnabled = true
                btnConnect.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            }
            HiddifyVpnService.STATE_ERROR -> {
                tvStatus.text = "خطا: $message"
                resetButton()
            }
            else -> {
                tvStatus.text = getString(R.string.status_disconnected)
                resetButton()
            }
        }
    }

    private fun resetButton() {
        btnConnect.text = getString(R.string.connect)
        btnConnect.isEnabled = true
        btnConnect.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, HiddifyVpnService::class.java).apply {
            action = HiddifyVpnService.ACTION_CONNECT
            putExtra(HiddifyVpnService.EXTRA_CONFIG, etConfig.text.toString().trim())
            putExtra(HiddifyVpnService.EXTRA_DNS, etDns.text.toString().trim())
        }
        ContextCompat.startForegroundService(this, intent)
        applyState(HiddifyVpnService.STATE_CONNECTING, "")
    }

    private fun stopVpnService() {
        val intent = Intent(this, HiddifyVpnService::class.java).apply {
            action = HiddifyVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }
}
