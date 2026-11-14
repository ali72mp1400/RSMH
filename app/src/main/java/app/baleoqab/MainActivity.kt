package app.baleoqab

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etConfig: EditText
    private lateinit var etDns: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView

    private var isConnected = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "مجوز اتصال VPN رد شد", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etConfig = findViewById(R.id.etConfig)
        etDns = findViewById(R.id.etDns)
        btnConnect = findViewById(R.id.btnConnect)
        tvStatus = findViewById(R.id.tvStatus)

        btnConnect.setOnClickListener {
            if (!isConnected) {
                prepareVpn()
            } else {
                stopVpnService()
            }
        }
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
        val config = etConfig.text.toString().trim()
        val dns = etDns.text.toString().trim()

        val intent = Intent(this, HiddifyVpnService::class.java).apply {
            action = HiddifyVpnService.ACTION_CONNECT
            putExtra(HiddifyVpnService.EXTRA_CONFIG, config)
            putExtra(HiddifyVpnService.EXTRA_DNS, dns)
        }
        startService(intent)
        
        isConnected = true
        tvStatus.text = getString(R.string.status_connected)
        btnConnect.text = getString(R.string.disconnect)
        btnConnect.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, theme))
    }

    private fun stopVpnService() {
        val intent = Intent(this, HiddifyVpnService::class.java).apply {
            action = HiddifyVpnService.ACTION_DISCONNECT
        }
        startService(intent)

        isConnected = false
        tvStatus.text = getString(R.string.status_disconnected)
        btnConnect.text = getString(R.string.connect)
        btnConnect.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, theme))
    }
}
