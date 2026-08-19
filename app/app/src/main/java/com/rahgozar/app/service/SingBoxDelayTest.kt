package com.rahgozar.app.service

import android.content.Context
import android.os.SystemClock
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.Utils
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The real-delay test for a sing-box server.
 *
 * The Xray side of this is one call — `Libv2ray.measureOutboundDelay` builds a
 * core with no inbound, sends one request through the outbound and returns how
 * long it took. libbox has no such call, so the same thing is assembled here:
 * a real core with the server's own outbound and a loopback proxy inbound, one
 * HTTP request through that proxy, and the elapsed time.
 *
 * That distinction is the whole point of the test. A TCP connect to the
 * server's port proves only that something is listening — not that the
 * password is right, that the quota is not spent, or that the server can still
 * reach the internet. This measures the answer to all three, and it is the
 * number the connect-time gate then trusts.
 *
 * Runs only in the process that owns [SingBoxTestService].
 */
internal object SingBoxDelayTest {

    private const val TAG = "SingBox-DelayTest"

    /** Directory this process owns under filesDir; see [SingBoxNative]. */
    private const val BASE_DIR = "singbox-test"

    /**
     * Comfortably inside HomeViewModel's 12s verification window, so a server
     * that cannot answer is *reported* as failing rather than leaving the gate
     * to time out with nothing to show.
     */
    private const val CALL_TIMEOUT_MS = 8_000L

    /** Returned when the measurement could not be taken at all. */
    private const val FAILED = -1L

    /**
     * How long to let the device's networks settle before measuring again.
     *
     * Long enough for the tun's arrival to have finished rearranging the
     * default interface, short enough that the retry still fits inside the
     * verification window with its own timeout to spare.
     */
    private const val TRANSITION_SETTLE_MS = 1_200L

    /**
     * One core at a time in this process.
     *
     * Not a limitation of sing-box but of the setup around it: libbox's paths
     * are process-global, so two instances would share a working directory,
     * and the servers being tested would each be measured while the other's
     * traffic was in flight.
     */
    private val lock = Any()

    /**
     * Reused across tests. Creating one per measurement would leave a daemon's
     * worth of Go state behind each time; `closeService` returns it to idle,
     * which is exactly what the next test wants.
     */
    private var commandServer: CommandServer? = null

    /**
     * @return the round trip in milliseconds, or -1 if the server could not
     *   carry the request
     */
    fun measure(context: Context, config: String, testUrl: String): Long = synchronized(lock) {
        if (!SingBoxNative.ensureLoaded()) return FAILED
        SingBoxNative.ensureSetUp(context, BASE_DIR, "rahgozar-test")

        val port = runCatching { Utils.findRandomFreePort() }.getOrNull() ?: return FAILED
        val testConfig = try {
            SingBoxTestConfig.forDelayTest(config, port)
        } catch (e: RuntimeException) {
            LogUtil.e(AppConfig.TAG, "$TAG: server configuration is not usable", e)
            return FAILED
        }

        val server = try {
            server(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: cannot create the test core", e)
            return FAILED
        }

        return try {
            val first = attempt(server, testConfig, port, testUrl)
            // One retry, and only for the failure this app causes itself.
            //
            // This measurement usually runs while a tunnel is coming up, and
            // the tun appearing moves the device's default interface. The
            // core in *this* process notices, decides its route is stale and
            // cancels whatever it had in flight — "network changed" in its own
            // log, surfacing here as a reset or a timeout depending on where it
            // was. Nothing about the server was measured; the answer is noise.
            //
            // A general retry would be wrong (see the OkHttp client below: a
            // slow server must not be able to hide inside a second attempt), so
            // this one is deliberately narrow — it fires only for a transition,
            // and only once.
            if (first == FAILED && lastFailureWasTransition) {
                LogUtil.i(AppConfig.TAG, "$TAG: the network moved under the probe — measuring again")
                Thread.sleep(TRANSITION_SETTLE_MS)
                attempt(server, testConfig, port, testUrl)
            } else {
                first
            }
        } finally {
            runCatching { server.closeService() }
                .onFailure { LogUtil.e(AppConfig.TAG, "$TAG: error closing the test core", it) }
        }
    }

    /**
     * One measurement: a core built against the network as it stands right now,
     * then one request through it.
     *
     * The core is started here rather than once per [measure] because a
     * sing-box instance that was running while the device's default interface
     * moved does not recover. Its DNS transport stays cancelled, and every
     * later lookup through that instance fails with "context canceled" however
     * long you wait — so a retry on the same core repeats the first answer
     * exactly. Seen on the device: the first attempt died in 17ms with
     * "network changed", the retry sat on a cancelled resolver for the full
     * eight seconds and reported a timeout, and a working server was
     * disconnected on the strength of it.
     */
    private fun attempt(
        server: CommandServer,
        testConfig: String,
        port: Int,
        testUrl: String,
    ): Long {
        try {
            server.startOrReloadService(testConfig, OverrideOptions())
        } catch (e: Exception) {
            // A configuration the core refuses is a dead server as far as the
            // list is concerned, and the reason belongs in the log.
            LogUtil.e(AppConfig.TAG, "$TAG: core refused the configuration", e)
            lastFailureWasTransition = false
            return FAILED
        }
        return probe(port, testUrl)
    }

    /**
     * Whether the last [probe] failed because the device changed networks
     * rather than because the server did not answer.
     *
     * Held here rather than returned because [probe]'s result is a duration and
     * -1 already means "no measurement"; widening that to carry a reason would
     * touch every caller for the sake of one retry.
     */
    private var lastFailureWasTransition = false

    /**
     * The words the core and OkHttp use when our own tun appearing killed the
     * attempt. Matched on text because they arrive as different exception types
     * from different layers — the core cancels the context, the socket resets,
     * the call times out with nothing behind it.
     */
    private fun isTransitionFailure(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return "network changed" in text ||
            "context canceled" in text ||
            "connection reset" in text ||
            "software caused connection abort" in text
    }

    private fun server(context: Context): CommandServer {
        commandServer?.let { return it }
        // No start(): that only opens the command socket for clients, and this
        // core has none. Skipping it also keeps the test off the socket path
        // the running tunnel owns.
        return Libbox.newCommandServer(Handler(), SingBoxPlatform(context, tunnel = null))
            .also { commandServer = it }
    }

    /**
     * One request through the core's loopback proxy.
     *
     * An HTTP proxy rather than SOCKS, though the inbound speaks both: through
     * HTTP the hostname travels to the core and is resolved *there*, so the
     * measurement includes the server's own DNS. Resolving it on the device
     * first would measure a name the server may not even be able to reach.
     */
    private fun probe(port: Int, testUrl: String): Long {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Nothing is retried: a retry would be timed as part of the same
            // measurement and quietly turn a bad server into a slow one.
            .retryOnConnectionFailure(false)
            .build()

        val started = SystemClock.elapsedRealtime()
        lastFailureWasTransition = false
        return try {
            client.newCall(Request.Builder().url(testUrl).get().build()).execute().use { response ->
                if (response.isSuccessful) {
                    SystemClock.elapsedRealtime() - started
                } else {
                    LogUtil.i(AppConfig.TAG, "$TAG: probe answered ${response.code}")
                    FAILED
                }
            }
        } catch (e: Exception) {
            lastFailureWasTransition = isTransitionFailure(e.message)
            LogUtil.i(AppConfig.TAG, "$TAG: probe failed: ${e.message}")
            FAILED
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** Nothing here is reachable: this core has no command client. */
    private class Handler : CommandServerHandler {
        override fun serviceStop() = Unit
        override fun serviceReload() = throw Exception("not supported")
        override fun getSystemProxyStatus(): SystemProxyStatus =
            SystemProxyStatus().apply {
                available = false
                enabled = false
            }

        override fun setSystemProxyEnabled(enabled: Boolean) = throw Exception("not supported")
        override fun connectSSHAgent(): Int = throw Exception("not supported")
        override fun triggerNativeCrash() = throw Exception("refused")
        override fun writeDebugMessage(message: String?) {
            LogUtil.d(AppConfig.TAG, "$TAG: $message")
        }
    }
}
