package com.rahgozar.app.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.rahgozar.app.AppConfig
import com.rahgozar.app.ads.SessionLimit
import com.rahgozar.app.contracts.ServiceControl
import com.rahgozar.app.core.CoreServiceManager
import com.rahgozar.app.enums.EConfigType
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.NotificationManager
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.helper.MessageHelper
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.MyContextWrapper
import com.rahgozar.app.util.Utils
import com.whitedns.whiteaesther.core.NativeAetherBridge
import com.whitedns.whiteaesther.core.NativeEngineListener
import com.whitedns.whiteaesther.core.NativeSocketProtector
import com.whitedns.whiteaesther.core.PreparedEngine
import java.io.File
import java.lang.ref.SoftReference
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the Aether (Cloudflare WARP over MASQUE) core in its own process.
 *
 * A non-Xray core service modelled on [SingBoxService]: it plugs into the same
 * control channel (the [AppConfig.BROADCAST_ACTION_SERVICE] broadcast and the
 * MSG_* UI protocol) so the normal connect/disconnect UI drives and observes it.
 * The engine owns the tunnel — this service only establishes the TUN, hands the
 * engine a socket protector so its QUIC carrier to Cloudflare bypasses the
 * tunnel, and calls the blocking [NativeAetherBridge.run] on a worker thread.
 *
 * The tunnel itself is unverified until tested on a phone.
 */
class AetherVpnService : VpnService(), ServiceControl {

    private val sessionActive = AtomicBoolean(false)
    private var receiverRegistered = false

    @Volatile
    private var ownsSession = false

    @Volatile
    private var coreRunning = false

    private var worker: Thread? = null

    /** The same control channel the other services listen on. */
    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT ->
                    if (coreRunning) notifyUi(AppConfig.MSG_STATE_RUNNING, "")

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "$TAG: stop requested")
                    stopEverything()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "$TAG: Service created")
        // NotificationManager posts against the service through this reference.
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

        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        if (ownsSession) {
            LogUtil.w(AppConfig.TAG, "$TAG: already running, ignoring start")
            return START_STICKY
        }
        if (!sessionActive.compareAndSet(false, true)) {
            notifyUi(AppConfig.MSG_STATE_START_FAILURE, "the previous session is still closing")
            stopSelf()
            return START_NOT_STICKY
        }
        ownsSession = true

        val guid = MmkvManager.getRunServer()
        val serverConfig = guid?.let { MmkvManager.decodeServerConfig(it) }
        if (serverConfig == null || serverConfig.configType != EConfigType.AETHER) {
            stopWithFailure("no Aether server selected")
            return START_NOT_STICKY
        }
        if (!NativeAetherBridge.isLoaded) {
            stopWithFailure("the Aether core is not available on this device")
            return START_NOT_STICKY
        }
        NotificationManager.showNotification(serverConfig)

        worker = Thread({ runEngine() }, "aether-engine").apply { start() }
        return START_STICKY
    }

    private fun runEngine() {
        // Connected is the engine's own verdict, tracked here so failure is only
        // ever reported when the data-plane was never confirmed.
        val ready = AtomicBoolean(false)
        try {
            // The protector must be in place before the engine opens any socket,
            // so its endpoint probes and the QUIC carrier go out on the real
            // network rather than being pulled back into the tunnel we create.
            NativeAetherBridge.setSocketProtector(NativeSocketProtector { fd -> protect(fd) })

            // Smart, resilient gateway hunt. Fast path first (turbo), then a
            // thorough scan (balanced) that a high-latency network needs. Each
            // mode tries the transports in `transportOrder()` — the last one that
            // worked first, so a network that blocks UDP does not pay the full h3
            // scan deadline on every connect (only the first). We only declare
            // failure once every plan has come back empty — no premature give-up.
            var chosen: String? = null
            var prepared: PreparedEngine? = null
            val order = transportOrder()
            outer@ for (mode in listOf("turbo", "balanced")) {
                for (t in order) {
                    if (!ownsSession) return
                    val cfg = AetherConfig.tunConfig(this, t, scanMode = mode)
                    val p = NativeAetherBridge.prepare(cfg).getOrNull()
                    if (p != null) {
                        LogUtil.i(AppConfig.TAG, "$TAG: prepared via ${t.wire} ($mode)")
                        rememberTransport(t)
                        chosen = cfg
                        prepared = p
                        break@outer
                    }
                    LogUtil.w(AppConfig.TAG, "$TAG: no gateway on ${t.wire} ($mode)")
                }
            }
            val cfg = chosen
            val engine = prepared
            if (cfg == null || engine == null) {
                stopWithFailure("no working Cloudflare gateway found")
                return
            }

            val fd = establishTunnel(engine.ipv4, engine.ipv6) ?: run {
                stopWithFailure("could not establish the TUN")
                return
            }

            // onNativeReady fires only once the MASQUE data-plane is validated
            // end-to-end, so we report success there rather than optimistically at
            // establish. The UI trusts this and skips its own probe for this core
            // (HomeViewModel.onTunnelUp), which is what stops a slow, high-latency
            // connect from being failed before the tunnel has actually come up.
            val listener = NativeEngineListener {
                if (ready.compareAndSet(false, true)) {
                    coreRunning = true
                    LogUtil.i(AppConfig.TAG, "$TAG: tunnel ready — data confirmed")
                    notifyUi(AppConfig.MSG_STATE_START_SUCCESS, "")
                    // The countdown lives in whichever process holds the
                    // tunnel, so every core has to arm it for itself. This one
                    // did not, and a timed session on Aether ran past zero:
                    // the screen showed 00:00 and the tunnel stayed up,
                    // because nothing in this process was counting.
                    SessionLimit.arm(this@AetherVpnService)
                }
            }

            // Blocks until the tunnel stops or errors.
            val result = NativeAetherBridge.run(cfg, engine.peer, fd, listener)
            if (!ready.get() && ownsSession) {
                // run() ended before any data ever came back — a real failure, not
                // a stop we asked for.
                stopWithFailure("the tunnel carried no data: ${result.error ?: "no gateway response"}")
                return
            }
            if (!result.ok) LogUtil.e(AppConfig.TAG, "$TAG: engine stopped: ${result.error}")
        } catch (t: Throwable) {
            LogUtil.e(AppConfig.TAG, "$TAG: engine crashed", t)
            if (!ready.get() && ownsSession) {
                stopWithFailure("engine error: ${t.message}")
                return
            }
        } finally {
            stopEverything()
        }
    }

    private fun establishTunnel(ipv4: String, ipv6: String): Int? {
        val builder = Builder()
            .setSession("Aether")
            .setMtu(1280)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("1.0.0.1")
        addAddress(builder, ipv4, 32)
        if (ipv6.isNotBlank()) {
            addAddress(builder, ipv6, 128)
            builder.addDnsServer("2606:4700:4700::1111")
            builder.addDnsServer("2606:4700:4700::1001")
            builder.addRoute("::", 0)
        }
        runCatching { builder.addDisallowedApplication(packageName) }
        val pfd = runCatching { builder.establish() }.getOrNull() ?: return null
        // Hand the engine full ownership of the fd. Passing the raw pfd.fd while
        // the ParcelFileDescriptor still owns it makes the engine's own close()
        // trip fdsan and SIGABRT the process — the reference detaches for exactly
        // this reason.
        return runCatching { pfd.detachFd() }.getOrNull()
    }

    private fun addAddress(builder: Builder, address: String, prefix: Int) {
        runCatching { builder.addAddress(InetAddress.getByName(address), prefix) }
    }

    override fun onRevoke() {
        stopEverything()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(messageReceiver) }
            receiverRegistered = false
        }
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        SessionLimit.disarm()
        coreRunning = false
        // stop() unblocks run() and hands the engine the chance to close the tun
        // fd it now owns; we must not close it ourselves (double-close → fdsan).
        runCatching { NativeAetherBridge.stop() }
        runCatching { NativeAetherBridge.setSocketProtector(null) }
        worker = null
        if (ownsSession) {
            notifyUi(AppConfig.MSG_STATE_STOP_SUCCESS, "")
        }
        ownsSession = false
        sessionActive.set(false)
        NotificationManager.cancelNotification()
        runCatching { stopSelf() }
    }

    private fun stopWithFailure(reason: String) {
        LogUtil.e(AppConfig.TAG, "$TAG: $reason")
        notifyUi(AppConfig.MSG_STATE_START_FAILURE, reason)
        SessionLimit.disarm()
        coreRunning = false
        runCatching { NativeAetherBridge.setSocketProtector(null) }
        ownsSession = false
        sessionActive.set(false)
        NotificationManager.cancelNotification()
        runCatching { stopSelf() }
    }

    private fun notifyUi(what: Int, message: String) {
        MessageHelper.sendMsg2UI(this, what, message)
    }

    // ---- ServiceControl ----
    override fun getService(): Service = this

    override fun startService() {
        NotificationManager.ensureForeground()
    }

    override fun stopService() {
        stopEverything()
    }

    override fun vpnProtect(socket: Int): Boolean = protect(socket)

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    /**
     * Transports to try, the last one that connected first. The default order
     * (no memory yet) is h2 before h3 — the same order the reference client
     * probes in, and the reason it connects in a second on a network that blocks
     * UDP: h2 (MASQUE over TLS/TCP) is reachable almost everywhere, while h3's
     * QUIC scan burns its full deadline finding nothing when UDP is blocked.
     * Whatever actually won is remembered and tried first next time, so a network
     * where h3 does work settles onto it.
     */
    private fun transportOrder(): List<AetherConfig.Transport> {
        val all = listOf(AetherConfig.Transport.H2, AetherConfig.Transport.H3)
        val last = runCatching {
            val v = File(filesDir, LAST_TRANSPORT_FILE).takeIf { it.exists() }?.readText()?.trim()
            all.firstOrNull { it.wire == v }
        }.getOrNull() ?: return all
        return listOf(last) + all.filter { it != last }
    }

    private fun rememberTransport(t: AetherConfig.Transport) {
        runCatching { File(filesDir, LAST_TRANSPORT_FILE).writeText(t.wire) }
    }

    companion object {
        private const val TAG = "AetherVpnService"
        private const val LAST_TRANSPORT_FILE = "aether-last-transport"
        const val ACTION_START = "com.rahgozar.app.aether.START"
        const val ACTION_STOP = "com.rahgozar.app.aether.STOP"
        const val EXTRA_CONFIG = "config"
    }
}
