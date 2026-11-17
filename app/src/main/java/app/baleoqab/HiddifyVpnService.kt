package app.baleoqab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface as BoxInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as BoxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

private const val TAG = "RSMH"

private class StringList(private val items: List<String>) : StringIterator {
    private var i = 0
    override fun hasNext(): Boolean = i < items.size
    override fun len(): Int = items.size
    override fun next(): String = items[i++]
}

private class IfaceList(private val items: List<BoxInterface>) : NetworkInterfaceIterator {
    private var i = 0
    override fun hasNext(): Boolean = i < items.size
    override fun next(): BoxInterface = items[i++]
}

private object LocalResolver : LocalDNSTransport {
    override fun raw(): Boolean = false

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        try {
            val ips = InetAddress.getAllByName(domain).filter {
                when (network) {
                    "ip4" -> it is Inet4Address
                    "ip6" -> it is Inet6Address
                    else -> true
                }
            }.mapNotNull { it.hostAddress }
            ctx.success(ips.joinToString("\n"))
        } catch (e: Exception) {
            ctx.errorCode(2)
        }
    }

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        ctx.errorCode(4)
    }
}

private fun RoutePrefixIterator.forEachPrefix(block: (RoutePrefix) -> Unit) {
    while (hasNext()) block(next())
}

private fun StringIterator.forEachString(block: (String) -> Unit) {
    while (hasNext()) block(next())
}

class HiddifyVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        const val ACTION_CONNECT = "app.baleoqab.START_VPN"
        const val ACTION_DISCONNECT = "app.baleoqab.STOP_VPN"
        const val ACTION_STATUS = "app.baleoqab.STATUS"
        const val EXTRA_CONFIG = "extra_config"
        const val EXTRA_DNS = "extra_dns"
        const val EXTRA_INDEX = "extra_index"
        const val EXTRA_INDICES = "extra_indices"
        const val EXTRA_SLOW = "extra_slow"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_ERROR = "error"

        @Volatile
        var state: String = STATE_DISCONNECTED

        private val setupLock = Any()
        private var setupDone = false
    }

    private var commandServer: CommandServer? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null

    // ---------- چرخه عمر سرویس ----------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: ""
                val dns = intent.getStringExtra(EXTRA_DNS) ?: ""
                val index = intent.getIntExtra(EXTRA_INDEX, -1)
                val indices = intent.getIntArrayExtra(EXTRA_INDICES)
                val slow = intent.getBooleanExtra(EXTRA_SLOW, true)
                startForegroundCompat()
                if (state != STATE_CONNECTING && state != STATE_CONNECTED) {
                    publish(STATE_CONNECTING, "")
                    Thread { connect(config, dns, index, indices, slow) }.start()
                }
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    private fun connect(input: String, dnsInput: String, index: Int, indices: IntArray?, slow: Boolean) {
        try {
            val all = SubscriptionParser.load(input)
            val nodes = when {
                indices != null && indices.isNotEmpty() ->
                    indices.filter { it in all.indices }.map { all[it] }.ifEmpty { all }
                index in all.indices -> listOf(all[index])
                else -> all
            }
            if (nodes.isEmpty()) throw IllegalStateException("هیچ کانفیگ معتبری پیدا نشد")
            val logFile = File(filesDir, "box.log")
            logFile.delete()
            val configJson = ConfigBuilder.build(
                nodes,
                DnsConfigGenerator.generateDnsRules(dnsInput),
                logFile.path,
                slow
            )
            Libbox.checkConfig(configJson)
            setupLibbox()
            val server = Libbox.newCommandServer(this, this)
            commandServer = server
            server.start()
            server.startOrReloadService(configJson, OverrideOptions())
            publish(STATE_CONNECTED, "${nodes.size} سرور")
        } catch (e: Throwable) {
            Log.e(TAG, "connect failed", e)
            cleanup()
            publish(STATE_ERROR, e.message ?: e.toString())
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun setupLibbox() {
        synchronized(setupLock) {
            if (setupDone) return
            val opts = SetupOptions()
            opts.setBasePath(filesDir.path)
            opts.setWorkingPath(filesDir.path)
            opts.setTempPath(cacheDir.path)
            opts.setFixAndroidStack(Build.VERSION.SDK_INT in 24..29)
            opts.setLogMaxLines(500L)
            Libbox.setup(opts)
            setupDone = true
        }
    }

    private fun disconnect() {
        Thread {
            cleanup()
            publish(STATE_DISCONNECTED, "")
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    private fun cleanup() {
        try { commandServer?.closeService() } catch (e: Exception) { Log.w(TAG, e) }
        try { commandServer?.close() } catch (e: Exception) { Log.w(TAG, e) }
        commandServer = null
        try { tunPfd?.close() } catch (e: Exception) { Log.w(TAG, e) }
        tunPfd = null
    }

    override fun onRevoke() {
        disconnect()
    }

    override fun onDestroy() {
        cleanup()
        publish(STATE_DISCONNECTED, "")
        super.onDestroy()
    }

    private fun publish(s: String, msg: String) {
        state = s
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_STATE, s)
                .putExtra(EXTRA_MESSAGE, msg)
        )
    }

    private fun startForegroundCompat() {
        val channelId = "rsmh_vpn"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "RSMH VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, channelId)
            .setContentTitle("RSMH")
            .setContentText("VPN فعال است")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- CommandServerHandler ----------

    override fun connectSSHAgent(): Int = -1
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun serviceReload() {}
    override fun serviceStop() { disconnect() }
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String) { Log.d(TAG, message) }

    // ---------- PlatformInterface ----------

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(this) != null) throw Exception("android: missing vpn permission")

        val builder = Builder().setSession("RSMH").setMtu(options.getMTU())
        options.getInet4Address()?.forEachPrefix { builder.addAddress(it.address(), it.prefix()) }
        options.getInet6Address()?.forEachPrefix { builder.addAddress(it.address(), it.prefix()) }

        if (options.getAutoRoute()) {
            options.getDNSServerAddress()?.forEachString { builder.addDnsServer(it) }

            var hasRoute4 = false
            options.getInet4RouteAddress()?.forEachPrefix {
                builder.addRoute(it.address(), it.prefix())
                hasRoute4 = true
            }
            if (!hasRoute4) builder.addRoute("0.0.0.0", 0)

            options.getInet6RouteAddress()?.forEachPrefix { builder.addRoute(it.address(), it.prefix()) }

            var hasInclude = false
            val chosen = getSharedPreferences("rsmh", Context.MODE_PRIVATE)
                .getStringSet("vpn_apps", emptySet()) ?: emptySet()
            for (pkg in chosen) {
                try { builder.addAllowedApplication(pkg); hasInclude = true } catch (e: Exception) { Log.w(TAG, e) }
            }
            options.getIncludePackage()?.forEachString {
                try { builder.addAllowedApplication(it); hasInclude = true } catch (e: Exception) { Log.w(TAG, e) }
            }
            if (!hasInclude) {
                options.getExcludePackage()?.forEachString {
                    try { builder.addDisallowedApplication(it) } catch (e: Exception) { Log.w(TAG, e) }
                }
                try { builder.addDisallowedApplication(packageName) } catch (e: Exception) { Log.w(TAG, e) }
            }
        }

        val pfd = builder.establish()
            ?: throw Exception("android: the application is not prepared or is revoked")
        tunPfd = pfd
        return pfd.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private fun push(network: Network) {
                currentNetwork = network
                val lp = cm.getLinkProperties(network) ?: return
                val name = lp.interfaceName ?: return
                val index = try {
                    java.net.NetworkInterface.getByName(name)?.index ?: 0
                } catch (e: Exception) { 0 }
                val caps = cm.getNetworkCapabilities(network)
                val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                listener.updateDefaultInterface(name, index, metered, false)
            }

            override fun onAvailable(network: Network) { push(network) }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) { push(network) }
            override fun onLost(network: Network) {
                if (network == currentNetwork) {
                    currentNetwork = null
                    listener.updateDefaultInterface("", -1, false, false)
                }
            }
        }
        netCallback = cb
        cm.requestNetwork(request, cb)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cb = netCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (e: Exception) { Log.w(TAG, e) }
        netCallback = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = getSystemService(ConnectivityManager::class.java)
        val javaIfs = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        val result = mutableListOf<BoxInterface>()
        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val name = lp.interfaceName ?: continue
            val ji = javaIfs.find { it.name == name } ?: continue

            val bi = BoxInterface()
            bi.setName(name)
            bi.setIndex(ji.index)
            try { bi.setMTU(ji.mtu) } catch (e: Exception) { Log.w(TAG, e) }
            bi.setDNSServer(StringList(lp.dnsServers.mapNotNull { it.hostAddress }))
            bi.setAddresses(
                StringList(ji.interfaceAddresses.mapNotNull { ia ->
                    ia.address.hostAddress?.substringBefore('%')?.let { "$it/${ia.networkPrefixLength}" }
                })
            )
            bi.setGateway(StringList(emptyList()))
            bi.setType(
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
            )
            var flags = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (ji.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (ji.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (ji.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
            bi.setFlags(flags)
            bi.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            result.add(bi)
        }
        return IfaceList(result)
    }

    override fun localDNSTransport(): LocalDNSTransport = LocalResolver
    override fun includeAllNetworks(): Boolean = false
    override fun underNetworkExtension(): Boolean = false
    override fun usePlatformBridge(): Boolean = false
    override fun usePlatformShell(): Boolean = false
    override fun tailscaleHostname(): String = ""
    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {}
    override fun registerMyInterface(name: String) {}
    override fun checkPlatformShell() {}
    override fun cancelNotification(id: String, n: Int) {}
    override fun sendNotification(notification: BoxNotification) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {}

    override fun createBridge(options: BridgeOptions): BridgeSession {
        throw UnsupportedOperationException("bridge not supported")
    }

    override fun findConnectionOwner(
        protocol: Int, sourceAddress: String, sourcePort: Int, destAddress: String, destPort: Int
    ): ConnectionOwner {
        throw UnsupportedOperationException("not supported")
    }

    override fun lookupSFTPServer(): String {
        throw UnsupportedOperationException("not supported")
    }

    override fun lookupUser(name: String): PlatformUser {
        throw UnsupportedOperationException("not supported")
    }

    override fun openShellSession(
        user: PlatformUser, command: String, args: StringIterator, dir: String, cols: Int, rows: Int
    ): ShellSession {
        throw UnsupportedOperationException("not supported")
    }

    override fun readSystemSSHHostKey(): String {
        throw UnsupportedOperationException("not supported")
    }
}
