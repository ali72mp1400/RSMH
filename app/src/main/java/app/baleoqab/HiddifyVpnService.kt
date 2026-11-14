package app.baleoqab

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.channels.FileChannel

class HiddifyVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    companion object {
        const val ACTION_CONNECT = "app.baleoqab.START_VPN"
        const val ACTION_DISCONNECT = "app.baleoqab.STOP_VPN"
        const val EXTRA_CONFIG = "extra_config"
        const val EXTRA_DNS = "extra_dns"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: ""
                val dns = intent.getStringExtra(EXTRA_DNS) ?: ""
                startVpn(config, dns)
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(config: String, dns: String) {
        if (isRunning) return

        val parsedConfig = SubscriptionParser.parseConfig(config)
        val dnsList = DnsConfigGenerator.generateDnsRules(dns)

        try {
            val builder = Builder().apply {
                setSession("RSMH")
                addAddress("10.0.0.2", 24)
                for (d in dnsList) {
                    addDnsServer(d)
                }
                addRoute("0.0.0.0", 0)
                setMtu(1500)
            }

            vpnInterface = builder.establish()
            isRunning = true

        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }

    private fun stopVpn() {
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isRunning = false
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
