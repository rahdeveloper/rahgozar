package com.rahgozar.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.VpnService
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_EvalConfig
import net.openvpn.ovpn3.ClientAPI_ProvideCreds
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs an OpenVPN profile, using the openvpn3 core.
 *
 * A service of its own rather than a branch inside [CoreVpnService], because
 * Android hands out exactly one tun at a time and the two cores want opposite
 * things from it: Xray wants a descriptor it can pump through tun2socks, while
 * openvpn3 wants to build the interface itself through callbacks. Folding them
 * together would leave both paths harder to follow than either is now.
 *
 * What does carry over: per-app proxy (see [PerAppProxy]), the notification,
 * and the MSG_STATE_* messages the UI already listens for. What does not: the
 * routing presets and the panel's tunnel_* keys, both of which describe Xray
 * and mean nothing here.
 */
@SuppressLint("VpnServicePolicy")
class OpenVpnService : VpnService(), ServiceControl {

    private companion object {
        const val TAG = "OpenVPN-Service"

        /** Matches the Xray path's sampling rate so both tapes move alike. */
        const val TRAFFIC_INTERVAL_MS = 3000L

        /**
         * How long a session may spend trying before it is called a failure.
         *
         * openvpn3 retries transport errors forever, which is right for a
         * tunnel that has been working and hits a blip, and wrong for the first
         * connection on a network that blocks OpenVPN outright: the core keeps
         * trying, no fatal event is ever emitted, and the dial spins with
         * nothing to tell the user. On the networks this app exists for, that
         * is the common case, so the attempt is given a deadline.
         */
        const val CONNECT_DEADLINE_MS = 30_000L

        /**
         * Guards against two openvpn3 clients existing at once.
         *
         * Deliberately on the companion, not the instance. A per-instance guard
         * looks sufficient and is not: when a session stops, Android may create
         * a *new* service instance while the previous one's native client is
         * still unwinding, and the new instance's own fields are empty, so it
         * cheerfully builds a second client. The core is not designed for that,
         * and the result is a native crash rather than an exception.
         */
        private val sessionActive = AtomicBoolean(false)

        /**
         * Loaded explicitly rather than from a static initialiser.
         *
         * SWIG's ovpncliJNI runs `swig_module_init()` — itself a native method —
         * in a static block, so the library has to be in already before any
         * generated class is touched. A companion `init` would be the obvious
         * place, except every member here is a `const val`, which Kotlin inlines,
         * so the companion might never be initialised at all.
         *
         * Failure is reported rather than thrown: a device whose ABI we did not
         * ship should see "this server cannot run here", not a process crash.
         */
        @Volatile
        private var nativeLoaded: Boolean? = null

        @Synchronized
        fun ensureNativeLoaded(): Boolean {
            nativeLoaded?.let { return it }
            val ok = runCatching { System.loadLibrary("ovpncli") }
                .onFailure { LogUtil.e(AppConfig.TAG, "$TAG: cannot load libovpncli", it) }
                .isSuccess
            nativeLoaded = ok
            return ok
        }
    }

    // Written on the main thread in onStartCommand, read from the worker thread
    // and from the broadcast receiver.
    @Volatile
    private var client: OpenVpnClient? = null

    @Volatile
    private var worker: Thread? = null

    private val isStopping = AtomicBoolean(false)
    private var receiverRegistered = false
    private var trafficJob: Job? = null

    /** True once this instance has claimed [sessionActive], so it can release it. */
    @Volatile
    private var ownsSession = false

    private var deadlineJob: Job? = null

    /**
     * The same control channel [CoreVpnService] listens on.
     *
     * The UI does not know which core is running — it broadcasts "stop" and
     * expects whatever holds the tun to let go. Without this the disconnect
     * button would do nothing on an OpenVPN server.
     */
    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                // Answered only when this service is the one carrying
                // traffic: every service hears this, and a "not running" from
                // an idle one would overwrite the answer of the one that is.
                AppConfig.MSG_REGISTER_CLIENT ->
                    if (worker?.isAlive == true) notifyUi(AppConfig.MSG_STATE_RUNNING, "")

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

        // NotificationManager resolves the service it posts against through
        // this reference. Without claiming it, ensureForeground() would find
        // nothing, silently skip startForeground(), and Android would kill the
        // service for never reaching the foreground.
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

        // Already connecting or connected here: a second tap must be a no-op,
        // not a second attempt.
        if (ownsSession) {
            LogUtil.w(AppConfig.TAG, "$TAG: already running, ignoring start")
            return START_STICKY
        }

        // A previous session's client has not finished unwinding yet. Starting
        // now would put two of them in the process at once.
        if (!sessionActive.compareAndSet(false, true)) {
            LogUtil.w(AppConfig.TAG, "$TAG: a previous session is still closing")
            notifyUi(AppConfig.MSG_STATE_START_FAILURE, "the previous session is still closing")
            stopSelf()
            return START_NOT_STICKY
        }
        ownsSession = true

        if (!ensureNativeLoaded()) {
            stopWithFailure("the OpenVPN core is not available on this device")
            return START_NOT_STICKY
        }

        val profile = loadProfile()
        if (profile == null) {
            stopWithFailure("no OpenVPN profile in the selected server")
            return START_NOT_STICKY
        }

        if (prepare(this) != null) {
            stopWithFailure("VPN permission not granted")
            return START_NOT_STICKY
        }

        start(profile)
        return START_STICKY
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "$TAG: permission revoked")
        stopVpn()
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

    private fun loadProfile(): String? {
        val guid = MmkvManager.getRunServer() ?: return null
        val config = MmkvManager.decodeServerConfig(guid) ?: return null
        return config.ovpnProfile?.takeIf { it.isNotBlank() }
    }

    private fun start(profile: String) {
        val guid = MmkvManager.getRunServer()
        val serverConfig = guid?.let { MmkvManager.decodeServerConfig(it) }
        val sessionName = serverConfig?.remarks?.takeIf { it.isNotBlank() } ?: "Rahgozar"

        val vpnClient = OpenVpnClient(
            service = this,
            sessionName = sessionName,
            onEvent = ::onCoreEvent,
        )
        client = vpnClient

        // openvpn3 treats any directive it does not implement as fatal, and
        // provider profiles routinely carry one or two OpenVPN 2.x leftovers.
        // See OpenVpnProfile for what may be dropped and why.
        val sanitised = OpenVpnProfile.sanitise(profile)
        if (sanitised.removed.isNotEmpty()) {
            LogUtil.i(
                AppConfig.TAG,
                "$TAG: dropped ${sanitised.removed.size} directive(s) openvpn3 does not implement: " +
                    sanitised.removed.joinToString(", "),
            )
        }

        val config = ClientAPI_Config().apply {
            content = sanitised.profile
            // The tunnel carries everything the phone sends, so a server that
            // pushes compression is a server we follow. "asym" mirrors what the
            // core defaults to for modern profiles.
            compressionMode = "asym"
        }

        var eval: ClientAPI_EvalConfig = vpnClient.eval_config(config)
        if (eval.error) {
            stopWithFailure(eval.message.orEmpty().ifBlank { "profile rejected by the core" })
            return
        }

        // "Missing External PKI alias".
        //
        // A profile with no inline <cert>/<key> makes the core assume the
        // client certificate is going to come from somewhere outside it — the
        // Android keystore, say — and it refuses to continue without an alias
        // naming which one. But profiles like this usually have no client
        // certificate at all: they authenticate with a username and password,
        // and the CA plus tls-crypt key in the file are all the server expects.
        //
        // disableClientCert says exactly that: do not send a client cert. It is
        // set only in this case, because switching it on for a profile that
        // does carry a certificate would stop that certificate being sent.
        //
        // The re-evaluation is not redundant: the core copies these settings
        // out of Config during eval_config (parse_extras), so a flag set
        // afterwards would never be read.
        if (eval.externalPki) {
            LogUtil.i(AppConfig.TAG, "$TAG: no client certificate in profile; authenticating by credentials")
            config.disableClientCert = true
            eval = vpnClient.eval_config(config)
            if (eval.error) {
                stopWithFailure(eval.message.orEmpty().ifBlank { "profile rejected by the core" })
                return
            }
        }

        // A profile carrying `auth-user-pass` cannot connect on its certificate
        // alone. The core tells us which kind this is, so the credentials are
        // supplied only when they are actually wanted — handing them to an
        // autologin profile would be rejected.
        if (!eval.autologin) {
            val user = serverConfig?.username.orEmpty()
            val pass = serverConfig?.password.orEmpty()
            if (user.isBlank() || pass.isBlank()) {
                stopWithFailure("this profile needs a username and password, and the panel sent none")
                return
            }
            val creds = ClientAPI_ProvideCreds().apply {
                username = user
                password = pass
            }
            val credStatus = vpnClient.provide_creds(creds)
            if (credStatus.error) {
                stopWithFailure(credStatus.message.orEmpty().ifBlank { "credentials rejected" })
                return
            }
        }

        NotificationManager.showNotification(serverConfig)

        armConnectDeadline()

        // connect() blocks until the session ends, so it gets a thread of its
        // own. Every callback in OpenVpnClient arrives on this thread.
        worker = Thread({
            try {
                val status = vpnClient.connect()
                if (status.error) {
                    LogUtil.e(AppConfig.TAG, "$TAG: connect failed: ${status.message}")
                    notifyUi(AppConfig.MSG_STATE_START_FAILURE, status.message.orEmpty())
                } else {
                    LogUtil.i(AppConfig.TAG, "$TAG: session ended")
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "$TAG: connect threw", e)
                notifyUi(AppConfig.MSG_STATE_START_FAILURE, e.message.orEmpty())
            } finally {
                // connect() has returned, so the native client is finished and
                // the next session may safely build its own. Released here
                // rather than in stopVpn(), which can run while this thread is
                // still inside the core.
                releaseSession()
                // Whether it ended cleanly or not, the tunnel is gone and the
                // UI must not be left showing "connected".
                stopSelf()
            }
        }, "openvpn3-connect").also { it.start() }
    }

    /**
     * Fails the attempt if it has not connected in time.
     *
     * Cancelled the moment CONNECTED arrives, so it only ever fires on a
     * connection that was never going to complete.
     */
    private fun armConnectDeadline() {
        deadlineJob?.cancel()
        deadlineJob = CoroutineScope(Dispatchers.IO).launch {
            delay(CONNECT_DEADLINE_MS)
            LogUtil.w(AppConfig.TAG, "$TAG: no handshake within ${CONNECT_DEADLINE_MS / 1000}s, giving up")
            notifyUi(
                AppConfig.MSG_STATE_START_FAILURE,
                "could not reach the server — the network may be blocking OpenVPN",
            )
            stopVpn()
            stopSelf()
        }
    }

    private fun releaseSession() {
        if (ownsSession) {
            ownsSession = false
            sessionActive.set(false)
        }
    }

    /**
     * Core events, on the worker thread.
     *
     * Only two matter to the rest of the app: the moment the tunnel is usable,
     * and the moment it has failed for good. The rest are progress detail that
     * already goes to the log.
     */
    private fun onCoreEvent(name: String, info: String, fatal: Boolean) {
        when {
            name == "CONNECTED" -> {
                deadlineJob?.cancel()
                deadlineJob = null
                notifyUi(AppConfig.MSG_STATE_START_SUCCESS, "")
                SessionLimit.arm(this)
                startTrafficLoop()
            }

            fatal -> {
                notifyUi(AppConfig.MSG_STATE_START_FAILURE, info.ifBlank { name })
                stopVpn()
                stopSelf()
            }

            // Non-fatal by design: the core will keep retrying. Logged rather
            // than surfaced, because a single dropped packet on a working
            // tunnel produces one of these too. The deadline decides when a
            // run of them means the network is blocking us.
            name == "TRANSPORT_ERROR" || name == "RECONNECTING" -> {
                LogUtil.i(AppConfig.TAG, "$TAG: $name ${info}")
            }
        }
    }

    // ----------------------------------------------------------------- stop --

    /**
     * Ends an attempt that never got as far as starting the core.
     *
     * Releases the session explicitly rather than leaving it to onDestroy: if
     * teardown were delayed, the claim would outlive the attempt and every
     * later start would be refused with "still closing".
     */
    private fun stopWithFailure(reason: String) {
        LogUtil.e(AppConfig.TAG, "$TAG: $reason")
        notifyUi(AppConfig.MSG_STATE_START_FAILURE, reason)
        NotificationManager.cancelNotification()
        releaseSession()
        stopSelf()
    }

    /**
     * Brings the tunnel down. Safe to call more than once, and safe to call
     * from the worker thread itself.
     *
     * The latch is never released: once a session is stopping it is over, and
     * a fresh session arrives as a new service instance with a fresh flag.
     * Releasing it would let a late onDestroy send a second "stopped" to the
     * UI after the screen had already moved on.
     */
    private fun stopVpn() {
        if (!isStopping.compareAndSet(false, true)) return
        try {
            stopTrafficLoop()
            deadlineJob?.cancel()
            deadlineJob = null
            // stop() is what makes the blocked connect() return; the worker
            // then unwinds and stops the service itself.
            client?.stop()

            // A fatal event is delivered on the worker thread, so this can be
            // reached from inside the very thread being waited on. Joining
            // yourself is not an error — it just blocks until the timeout, so
            // the tunnel would appear to hang for three seconds on every fatal
            // failure.
            val running = worker
            if (running != null && running !== Thread.currentThread()) {
                running.join(3_000)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: error while stopping", e)
        } finally {
            // If no worker ever ran, nothing else will release the session, so
            // it has to happen here or the next start is refused forever.
            if (worker == null) releaseSession()
            worker = null
            client = null
            SessionLimit.disarm()
            notifyUi(AppConfig.MSG_STATE_STOP_SUCCESS, "")
        }
    }

    private fun notifyUi(what: Int, message: String) {
        MessageHelper.sendMsg2UI(this, what, message)
    }

    // -------------------------------------------------------------- traffic --

    /**
     * Feeds the home screen's throughput tapes.
     *
     * The Xray path gets these numbers from NotificationManager's own loop,
     * which reads the core's per-outbound stats and bails when that core is not
     * running — so on an OpenVPN session it never produces anything. The
     * message it publishes is just "up,down,sessionUp,sessionDown", so the same
     * tapes are fed here from openvpn3's transport counters instead.
     *
     * The counters are cumulative, hence the deltas.
     */
    private fun startTrafficLoop() {
        if (trafficJob != null) return
        var lastIn = 0L
        var lastOut = 0L
        var sessionUp = 0L
        var sessionDown = 0L

        trafficJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(TRAFFIC_INTERVAL_MS)
                val stats = runCatching { client?.transport_stats() }.getOrNull() ?: continue

                val bytesIn = stats.bytesIn
                val bytesOut = stats.bytesOut
                // A reconnect resets the core's counters; without this the
                // delta would go negative and the tape would draw a spike.
                val deltaIn = (bytesIn - lastIn).coerceAtLeast(0L)
                val deltaOut = (bytesOut - lastOut).coerceAtLeast(0L)
                lastIn = bytesIn
                lastOut = bytesOut

                sessionUp += deltaOut
                sessionDown += deltaIn
                val perSec = TRAFFIC_INTERVAL_MS / 1000.0
                val upPerSec = (deltaOut / perSec).toLong()
                val downPerSec = (deltaIn / perSec).toLong()

                notifyUi(
                    AppConfig.MSG_SPEED_UPDATE,
                    "$upPerSec,$downPerSec,$sessionUp,$sessionDown",
                )
            }
        }
    }

    private fun stopTrafficLoop() {
        trafficJob?.cancel()
        trafficJob = null
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
