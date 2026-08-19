package com.rahgozar.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.rahgozar.app.AppConfig
import com.rahgozar.app.ads.SessionLimit
import com.rahgozar.app.contracts.ServiceControl
import com.rahgozar.app.core.CoreServiceManager
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.NotificationManager
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.helper.MessageHelper
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.MyContextWrapper
import com.rahgozar.app.util.Utils
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a sing-box configuration, the third core beside Xray and openvpn3.
 *
 * A service of its own for the same reason [OpenVpnService] is: the cores want
 * opposite things from the tun. sing-box builds its interface through
 * [SingBoxPlatform.openTun] — the same inversion as openvpn3 — so per-app
 * proxy keeps working, while the routing presets and the panel's tunnel_* keys
 * stay Xray-only and mean nothing here.
 *
 * The panel's `singboxConfig` blob is handed to the core verbatim: the panel
 * owns the whole sing-box configuration, tun inbound and all, exactly as it
 * owns the `.ovpn` profile for OpenVPN.
 *
 * Unlike OpenVPN there is no connect deadline here: sing-box starting means
 * the config parsed and the tun is up, not that the server answered — its
 * outbounds dial lazily. Whether the server actually carries traffic is the
 * verification gate's question (HomeViewModel), the same as for Xray.
 */
@SuppressLint("VpnServicePolicy")
class SingBoxService : VpnService(), ServiceControl, SingBoxTunnel {

    private companion object {
        const val TAG = "SingBox-Service"

        /** Matches the Xray path's sampling rate so all three tapes move alike. */
        const val TRAFFIC_INTERVAL_NS = 3_000_000_000L
        const val TRAFFIC_INTERVAL_SEC = 3.0

        /**
         * Same guard as OpenVPN's, for the same reason: a stopping session's
         * native side may outlive its service instance, and libbox's command
         * server owns a unix socket path that two instances would fight over.
         */
        private val sessionActive = AtomicBoolean(false)

        /** Directory this process owns under filesDir; see [SingBoxNative]. */
        const val BASE_DIR = "singbox"
    }

    /** Shown as the VPN session name; set before the core is started. */
    @Volatile
    private var sessionLabel = "Rahgozar"

    @Volatile
    private var commandServer: CommandServer? = null

    @Volatile
    private var commandClient: CommandClient? = null

    @Volatile
    private var tunFd: ParcelFileDescriptor? = null

    @Volatile
    private var coreRunning = false

    private val isStopping = AtomicBoolean(false)
    private var receiverRegistered = false

    @Volatile
    private var ownsSession = false

    /** The same control channel the other services listen on. */
    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                // Answered only when this service is the one carrying
                // traffic: every service hears this, and a "not running" from
                // an idle one would overwrite the answer of the one that is.
                AppConfig.MSG_REGISTER_CLIENT ->
                    if (coreRunning) notifyUi(AppConfig.MSG_STATE_RUNNING, "")

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "$TAG: stop requested")
                    stopVpn()
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "$TAG: Service created")

        // NotificationManager finds the service it posts against through this
        // reference; without it ensureForeground() silently does nothing and
        // Android kills the service. See docs/OPENVPN3-INTEGRATION.md.
        CoreServiceManager.serviceControl = SoftReference(this)

        ContextCompat.registerReceiver(
            this,
            messageReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE),
            Utils.receiverFlags(),
        )
        receiverRegistered = true
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let { MyContextWrapper.wrap(it, SettingsManager.getLocale()) }
        super.attachBaseContext(context)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()

        if (ownsSession) {
            LogUtil.w(AppConfig.TAG, "$TAG: already running, ignoring start")
            return START_STICKY
        }

        if (!sessionActive.compareAndSet(false, true)) {
            LogUtil.w(AppConfig.TAG, "$TAG: a previous session is still closing")
            notifyUi(AppConfig.MSG_STATE_START_FAILURE, "the previous session is still closing")
            stopSelf()
            return START_NOT_STICKY
        }
        ownsSession = true

        if (!SingBoxNative.ensureLoaded()) {
            stopWithFailure("the sing-box core is not available on this device")
            return START_NOT_STICKY
        }

        val guid = MmkvManager.getRunServer()
        val serverConfig = guid?.let { MmkvManager.decodeServerConfig(it) }
        val config = serverConfig?.singboxConfig?.takeIf { it.isNotBlank() }
        if (config == null) {
            stopWithFailure("no sing-box configuration in the selected server")
            return START_NOT_STICKY
        }

        if (prepare(this) != null) {
            stopWithFailure("VPN permission not granted")
            return START_NOT_STICKY
        }

        sessionLabel = serverConfig.remarks.takeIf { it.isNotBlank() } ?: "Rahgozar"
        NotificationManager.showNotification(serverConfig)

        // startOrReloadService opens the tun through openTun on the calling
        // thread and can block on I/O, so it runs off the main thread. Named
        // like openvpn3's worker, but it returns as soon as the core is up —
        // nothing stays parked on it.
        Thread({
            try {
                startCore(config)
                coreRunning = true
                notifyUi(AppConfig.MSG_STATE_START_SUCCESS, "")
                SessionLimit.arm(this)
                startTrafficClient()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "$TAG: start failed", e)
                stopWithFailure(e.message.orEmpty().ifBlank { "the sing-box core refused the configuration" })
            }
        }, "singbox-start").start()

        return START_STICKY
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "$TAG: permission revoked")
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(messageReceiver) }
            receiverRegistered = false
        }
        NotificationManager.cancelNotification()
        LogUtil.i(AppConfig.TAG, "$TAG: Service destroyed")
    }

    // ------------------------------------------------------------------ run --

    private fun startCore(config: String) {
        SingBoxNative.ensureSetUp(this, BASE_DIR, "rahgozar-tunnel")

        // What the panel stored may be a whole configuration or just the
        // server's outbound; the core only takes the first. See SingBoxConfig.
        val runnable = SingBoxConfig.forTunnel(config)

        val server = Libbox.newCommandServer(ServerHandler(), SingBoxPlatform(this, this))
        commandServer = server
        server.start()
        try {
            server.startOrReloadService(runnable, OverrideOptions())
        } catch (e: Exception) {
            // The command server is useless without its service; fold the
            // teardown in here so the failure path leaves nothing behind.
            runCatching { server.close() }
            commandServer = null
            throw e
        }
    }

    // ------------------------------------------------------------ tunnel ----

    /**
     * The core describing the interface it wants; we build it.
     *
     * Called on whichever thread libbox starts the service from, never the
     * main one. The descriptor is kept so it can be closed on stop — libbox
     * dups its own copy.
     */
    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) throw Exception("VPN permission not granted")

        val builder = Builder()
        builder.setSession(sessionLabel)
        builder.setMtu(options.getMTU())

        forEachPrefix(options.getInet4Address()) { addr, prefix -> builder.addAddress(addr, prefix) }
        forEachPrefix(options.getInet6Address()) { addr, prefix -> builder.addAddress(addr, prefix) }

        if (options.getAutoRoute()) {
            // The route *ranges*, not the raw route list: libbox has already
            // folded the config's excludes into a set of include ranges, so
            // this works on every API level instead of needing the API 33
            // excludeRoute().
            forEachPrefix(options.getInet4RouteRange()) { addr, prefix -> builder.addRoute(addr, prefix) }
            forEachPrefix(options.getInet6RouteRange()) { addr, prefix -> builder.addRoute(addr, prefix) }
        }

        runCatching { options.getDNSServerAddress() }.getOrNull()?.let { servers ->
            while (servers.hasNext()) {
                runCatching { builder.addDnsServer(servers.next()) }
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bypass = mutableListOf<String>()
            val domains = options.getHTTPProxyBypassDomain()
            while (domains.hasNext()) bypass.add(domains.next())
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    options.getHTTPProxyServer(),
                    options.getHTTPProxyServerPort(),
                    bypass,
                )
            )
        }

        // The config's own include/exclude package lists are deliberately not
        // read: the user's per-app choices are made in this app and must mean
        // the same thing on every core, so the shared implementation wins.
        PerAppProxy.apply(builder, this, TAG)

        val pfd = builder.establish()
            ?: throw Exception("the system refused to establish the VPN interface")
        tunFd = pfd
        return pfd.fd
    }

    override fun protectSocket(fd: Int): Boolean = protect(fd)

    private inline fun forEachPrefix(
        iterator: RoutePrefixIterator,
        action: (String, Int) -> Unit,
    ) {
        while (iterator.hasNext()) {
            val prefix = iterator.next()
            action(prefix.address(), prefix.prefix())
        }
    }

    // ----------------------------------------------------------------- stop --

    private fun stopWithFailure(reason: String) {
        LogUtil.e(AppConfig.TAG, "$TAG: $reason")
        notifyUi(AppConfig.MSG_STATE_START_FAILURE, reason)
        NotificationManager.cancelNotification()
        stopVpn(failed = true)
        stopSelf()
    }

    /**
     * Brings the tunnel down. Safe to call more than once, from any thread.
     *
     * The libbox teardown happens on its own thread: closeService() waits for
     * the box instance to unwind, which is network cleanup that must not run
     * on — or block — the main thread. The session latch is released only when
     * that finishes, so a quick reconnect is refused with "still closing"
     * instead of racing the old command server for its socket.
     */
    private fun stopVpn(failed: Boolean = false) {
        if (!isStopping.compareAndSet(false, true)) return

        SessionLimit.disarm()
        coreRunning = false
        val client = commandClient
        val server = commandServer
        val pfd = tunFd
        commandClient = null
        commandServer = null
        tunFd = null

        Thread({
            runCatching { client?.disconnect() }
            runCatching { server?.closeService() }
                .onFailure { LogUtil.e(AppConfig.TAG, "$TAG: error closing service", it) }
            runCatching { server?.close() }
            runCatching { pfd?.close() }
            if (ownsSession) {
                ownsSession = false
                sessionActive.set(false)
            }
            LogUtil.i(AppConfig.TAG, "$TAG: session closed")
        }, "singbox-stop").start()

        if (!failed) notifyUi(AppConfig.MSG_STATE_STOP_SUCCESS, "")
    }

    private fun notifyUi(what: Int, message: String) {
        MessageHelper.sendMsg2UI(this, what, message)
    }

    // -------------------------------------------------------------- traffic --

    /**
     * Feeds the home screen's throughput tapes.
     *
     * Same story as OpenVPN: NotificationManager's speed loop reads the Xray
     * core and produces nothing here. The daemon behind the command server
     * counts traffic whenever a platform log writer exists — which the Android
     * binding always registers — so a status subscription over the command
     * socket is the core-native way to the same numbers. It pushes on the
     * server's own cadence; each push becomes one MSG_SPEED_UPDATE, the exact
     * message the tapes already consume.
     */
    private fun startTrafficClient() {
        if (commandClient != null || isStopping.get()) return
        val options = CommandClientOptions().apply {
            statusInterval = TRAFFIC_INTERVAL_NS
        }
        options.addCommand(Libbox.CommandStatus)
        val client = CommandClient(TrafficHandler(), options)
        commandClient = client
        Thread({
            try {
                client.connect()
            } catch (e: Exception) {
                // The tunnel works without tapes; not worth failing the session.
                LogUtil.e(AppConfig.TAG, "$TAG: status client could not connect", e)
            }
        }, "singbox-status").start()
    }

    private inner class TrafficHandler : CommandClientHandler {
        override fun connected() {
            LogUtil.i(AppConfig.TAG, "$TAG: status stream connected")
        }

        override fun disconnected(message: String?) {
            if (!isStopping.get()) {
                LogUtil.w(AppConfig.TAG, "$TAG: status stream lost: $message")
            }
        }

        override fun writeStatus(message: StatusMessage) {
            // Uplink/Downlink are bytes over the subscription interval, the
            // totals are cumulative since the service started — which is the
            // session, so they map straight onto the tape's four fields.
            val upPerSec = (message.uplink / TRAFFIC_INTERVAL_SEC).toLong()
            val downPerSec = (message.downlink / TRAFFIC_INTERVAL_SEC).toLong()
            notifyUi(
                AppConfig.MSG_SPEED_UPDATE,
                "$upPerSec,$downPerSec,${message.uplinkTotal},${message.downlinkTotal}",
            )
        }

        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String?) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit

        override fun writeGroups(message: OutboundGroupIterator?) = Unit
        override fun writeLogs(messageList: LogIterator?) = Unit
        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
    }

    // ------------------------------------------------------- server handler --

    private inner class ServerHandler : CommandServerHandler {
        /** The core asking to stop — a clash-mode client, an OOM kill. */
        override fun serviceStop() {
            stopVpn()
            stopSelf()
        }

        override fun serviceReload() {
            // Reload-in-place is a sing-box UI feature; this app restarts the
            // whole service instead, and nothing reaches this path.
            throw Exception("reload is not supported")
        }

        override fun getSystemProxyStatus(): SystemProxyStatus =
            SystemProxyStatus().apply {
                available = false
                enabled = false
            }

        override fun setSystemProxyEnabled(enabled: Boolean) {
            throw Exception("system proxy is not supported")
        }

        override fun connectSSHAgent(): Int {
            throw Exception("SSH agent is not supported")
        }

        override fun triggerNativeCrash() {
            throw Exception("refused")
        }

        override fun writeDebugMessage(message: String?) {
            LogUtil.d(AppConfig.TAG, "$TAG: $message")
        }
    }

    // ------------------------------------------------------- ServiceControl --

    override fun getService(): Service = this

    /** Starting is driven by onStartCommand; nothing to do on demand. */
    override fun startService() = Unit

    override fun stopService() {
        stopVpn()
        stopSelf()
    }

    override fun vpnProtect(socket: Int): Boolean = protect(socket)

    /** Disambiguates VpnService's method from ServiceControl's default. */
    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }
}
