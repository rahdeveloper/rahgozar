package com.rahgozar.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import com.rahgozar.app.AppConfig
import com.rahgozar.app.AppConfig.LOOPBACK
import com.rahgozar.app.BuildConfig
import com.rahgozar.app.ads.SessionLimit
import com.rahgozar.app.contracts.ServiceControl
import com.rahgozar.app.contracts.Tun2SocksControl
import com.rahgozar.app.core.CoreRoutePin
import com.rahgozar.app.core.CoreServiceManager
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.NotificationManager
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.panel.PanelStore
import com.rahgozar.app.root.RootLanSharing
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.MyContextWrapper
import com.rahgozar.app.util.Utils
import java.lang.ref.SoftReference
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Log prefix for the ad flow's tunnel, distinct from the user's own. */
private const val TAG_SMART = "StartCore-Smart"

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val isStartingLock = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // The tun is going away with this service, whatever else happens
        // below — nothing in this process is inside a tunnel any more.
        CoreRoutePin.insideTun = false
        PanelStore.sessionRideActive = false

        // Ensure VPN interface is properly closed when the service is destroyed without
        // going through stopAllService() (e.g. when killed unexpectedly). isRunning is
        // set to false at the start of stopAllService(), so this guard prevents a double-close.
        if (isRunning) {
            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed in onDestroy")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface in onDestroy", e)
            }
        }

        unlockStart()
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        // Always-on VPN restarts from OS deliver intent.action == SERVICE_INTERFACE or null intent.
        // Reset any stuck start lock left by a killed process to allow setupVpnService() to run.
        val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE
        if (isSystemVpnStart) {
            unlockStart()
        }
        if (!tryLockStart()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
            return START_NOT_STICKY
        }
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart")
        if (!setupVpnService()) {
            unlockStart()
            // Stop service if setup fails to avoid infinite restart loops (START_STICKY)
            stopSelf()
            return START_NOT_STICKY
        }
        startService()
        return START_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            return
        }
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService()
            return
        }

        // Start LAN sharing if enabled in settings
        RootLanSharing.startClientSharing(this)
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }

        runTun2socks()
        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        try {
            mInterface = builder.establish()!!
            isRunning = true
            // From here until teardown, resolutions made in this process go
            // through the tun — the config generator must reuse the pinned
            // answers instead of asking again. See CoreRoutePin.insideTun.
            CoreRoutePin.insideTun =
                PanelStore.smartSessionActive || PanelStore.sessionRideActive
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopAllService()
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // The addresses that must not enter this tun: the server the core is
        // dialling, whenever this app itself is carried inside (a smart
        // session, or a timed session's ride).
        //
        // Normally the app's own uid is excluded from the tun, which keeps
        // the core's outbound out of it for free. Those sessions cannot do
        // that — the ad traffic they exist to carry comes from this same
        // uid — so the exception has to be made per destination instead.
        // Without it the core's connection to the proxy is routed into the
        // tunnel it is trying to build, and the device loses the network
        // entirely.
        val coreExcludes = coreDialExclusions()

        val ipv6 = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true
        if (ipv6) builder.addAddress(vpnConfig.ipv6Client, 126)

        val baseRoutes = buildList {
            if (bypassLan) addAll(AppConfig.ROUTED_IP_LIST) else add("0.0.0.0/0")
            if (ipv6) {
                if (bypassLan) {
                    add("2000::/3") // Currently only 1/8 of total IPv6 is in use
                    add("fc00::/18") // Xray-core default FakeIPv6 Pool
                } else {
                    add("::/0")
                }
            }
        }

        if (coreExcludes.isNotEmpty() && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // No excludeRoute before Android 13, so the same table is built
            // the long way: the base coverage handed over as CIDR blocks with
            // the core's addresses cut out. Same kernel behaviour, more
            // lines — and it is what keeps smart sessions working for the
            // half of the fleet still on Android 10–12.
            val carved = RouteCarver.carve(baseRoutes, coreExcludes)
            carved.forEach { builder.addRoute(it.address, it.prefix) }
            LogUtil.i(
                AppConfig.TAG,
                "$TAG_SMART: carved ${coreExcludes.joinToString()} out of ${carved.size} routes",
            )
        } else {
            baseRoutes.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
            excludeCoreRoute(builder, coreExcludes)
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Keeps this core's own proxy server outside the tun it is building.
     *
     * Needed exactly when this app is *inside* the tun, which is now two
     * situations: an ad flow's smart session, and a timed session where the
     * rewarded ad has to reach Google over the user's own connection. In both,
     * the Xray core lives in this app's uid, so without an exception its
     * connection to the proxy is routed into the tunnel it is trying to
     * establish — and the whole device loses the network, which is exactly
     * how this arrived twice.
     *
     * Only ever this one address; everything else still goes through the
     * tunnel. The other two cores need none of this: openvpn3 and libbox both
     * protect their own sockets, which is what the Xray build here cannot do.
     */
    /**
     * The addresses the core will dial, when this tun must not carry them.
     *
     * The address itself, or — for a domain server — a resolution made right
     * here, before the tun exists, and pinned so the config generator reuses
     * it for the core's outbound. Same list on both sides, by construction:
     * the core dials only what was pinned, so excluding exactly that is
     * airtight, and a CDN rotating its answers between now and the dial
     * cannot open a gap.
     *
     * Empty when nothing needs excluding — no session carries this app — or
     * when nothing resolved. In the latter case PerAppProxy already kept this
     * app outside a timed session's tun; a smart session's tun has the app
     * inside regardless, the ad will simply fail, and the log says why.
     */
    private fun coreDialExclusions(): List<String> {
        if (!PanelStore.smartSessionActive && !SessionLimit.wantsRideUserTunnel) return emptyList()
        val guid = MmkvManager.getRunServer() ?: return emptyList()
        val server = MmkvManager.decodeServerConfig(guid)?.server ?: return emptyList()

        val addresses = CoreRoutePin.ensurePinned(server)
        if (addresses.isEmpty()) {
            LogUtil.w(AppConfig.TAG, "$TAG_SMART: no addresses known for $server, nothing excluded")
        }
        return addresses
    }

    /** Android 13's one-call version of the same exclusion. */
    private fun excludeCoreRoute(builder: Builder, addresses: List<String>) {
        if (addresses.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        for (address in addresses) {
            val prefix = if (address.contains(':')) 128 else 32
            runCatching { builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefix)) }
                .onSuccess { LogUtil.i(AppConfig.TAG, "$TAG_SMART: excluded $address from the tun") }
                .onFailure { LogUtil.e(AppConfig.TAG, "$TAG_SMART: could not exclude $address", it) }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        // Moved to PerAppProxy so OpenVpnService applies the identical rules —
        // the user picks apps once and should not have to care which core runs.
        // Xray is the one core here that cannot protect its own sockets, so
        // riding inside its tun is conditional on the exclusions being known.
        PerAppProxy.apply(builder, this, "StartCore-VPN", needsCoreExclusion = true)
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(isForced: Boolean = true) {
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        unlockStart()
        isRunning = false

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        RootLanSharing.stopClientSharing(this)

        CoreServiceManager.stopCoreLoop()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            // Add a small delay to allow the async core stop operation to complete
            // before closing the VPN interface, preventing a race condition that can
            // leave the VPN icon in the status bar after stopping the service.
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
            }

            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
            }
        }
    }

    fun tryLockStart(): Boolean {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: tryLockStart: ${isStartingLock.get()}")
        return isStartingLock.compareAndSet(false, true)
    }

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }
}
