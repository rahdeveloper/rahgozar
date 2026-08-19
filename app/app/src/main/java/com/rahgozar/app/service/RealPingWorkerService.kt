package com.rahgozar.app.service

import android.content.Context
import com.rahgozar.app.core.CoreConfigManager
import com.rahgozar.app.core.CoreNativeManager
import com.rahgozar.app.dto.RealPingEvent
import com.rahgozar.app.enums.EConfigType
import com.rahgozar.app.extension.isComplexType
import com.rahgozar.app.extension.isNotNullEmpty
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.handler.SpeedtestManager
import com.rahgozar.app.util.Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onlyTcp: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()

    /**
     * Measured in parallel, and reported one row at a time.
     *
     * These are not in tension, which is what an earlier attempt got wrong: it
     * dropped to one server at a time to make results appear progressively, and
     * paid for it with a batch that took as long as the sum of every server.
     * The batch was never the problem — the list was, because it greyed out
     * every row for the whole run. With each row carrying its own state, a wide
     * batch still shows each answer the moment it lands.
     *
     * OpenVPN servers are the exception and serialise themselves: their test is
     * a real handshake and openvpn3 allows one client per process, so
     * OpenVpnDelayTest holds a lock. They queue while everything else keeps
     * running in parallel around them.
     */
    private val concurrency = SettingsManager.getRealPingConcurrency().let {
        if (onlyTcp) it * 2 else it
    }
    private val dispatcher = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    // Announced before the work, so the list can show which row
                    // is being measured rather than greying out all of them.
                    onEvent(RealPingEvent.Started(guid))
                    val result = if (onlyTcp) startTcping(guid) else startRealPing(guid)
                    onEvent(RealPingEvent.Result(guid, result))
                } catch (_: Throwable) {
                    // ignore
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    onEvent(RealPingEvent.Progress("$left / $count"))
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                onEvent(RealPingEvent.Finish("0"))
            } catch (_: CancellationException) {
                onEvent(RealPingEvent.Finish("-1"))
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
        // The sing-box test process holds a whole Go runtime; nothing needs it
        // between batches. No-op if it was never used, or if another batch
        // still has a measurement in flight.
        SingBoxDelayBridge.releaseIfIdle(context)
    }

    private fun startRealPing(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure

        // The real-delay test for OpenVPN runs an actual handshake rather than
        // a TCP connect, because on a censored network the connect succeeds on
        // servers that never carry traffic. See OpenVpnDelayTest.
        if (config.configType == EConfigType.OPENVPN) {
            val profile = config.ovpnProfile
            if (profile.isNullOrBlank()) return retFailure
            return OpenVpnDelayTest.measure(profile, config.username, config.password)
        }

        // sing-box gets the same kind of test as Xray, not a TCP connect: a
        // real core with this server's outbound, one real request through it,
        // and the time it took. See SingBoxDelayTest for why the difference
        // matters — and note the connect-time gate trusts this number.
        if (config.configType == EConfigType.SINGBOX) {
            val singboxConfig = config.singboxConfig
            if (singboxConfig.isNullOrBlank()) return retFailure
            return SingBoxDelayBridge.measure(
                context,
                singboxConfig,
                SettingsManager.getDelayTestUrl(),
                // A batch of one is someone waiting: the connect-time gate, or
                // a single row just tapped. It goes ahead of a list sweep, or
                // the gate would time out behind servers nobody is watching.
                urgent = guids.size == 1,
            )
        }

        // A quick reachability check before the real test — but only where it
        // can tell the truth.
        //
        // It dials the server directly, from this process, using the phone's
        // own resolver. Two ways that condemns a healthy server, and this app
        // runs in a country where both happen daily:
        //
        //  - A domain address goes through the system resolver, which is the
        //    one being poisoned. We have watched it hand back the block page's
        //    address for a server that works perfectly when the core resolves
        //    it over DoH. A config fronted by a CDN *has* to be a domain, so
        //    this gate was systematically failing exactly the configs that are
        //    hardest to block. Those skip it entirely: the real test resolves
        //    through the core's own DNS and is the only honest answer.
        //
        //  - One second is not a handshake, it is a coin toss. A dropped SYN
        //    is retransmitted at one second, so a server on a congested mobile
        //    network fails a 1000ms connect and answers fine at 1100ms. That
        //    is how the smart selection reported "none answered" for a server
        //    the user then connected to by hand, twice.
        //
        // A healthy server still passes in a few hundred milliseconds, so the
        // fast path costs nothing; only servers that were about to be called
        // dead wait longer.
        val server = config.server.orEmpty()
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.startsWith("h3") != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
            && Utils.isPureIpAddress(server)
        ) {
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(server, port, REACH_TIMEOUT_MS)
            if (tcpTime <= -1L) {
                return retFailure
            }
        }

        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        return CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
    }

    /** `proto tcp` / `proto tcp-client`, ignoring comments and indentation. */
    private val tcpProfile = Regex("""(?m)^\s*proto\s+tcp""", RegexOption.IGNORE_CASE)

    /**
     * Reachability for an OpenVPN server, measured by a plain TCP connect.
     *
     * What a failure means depends on the profile. If it says `proto tcp`, the
     * tunnel itself would dial exactly this port over exactly this protocol, so
     * a refused connect is a genuine "this server is not answering" and is
     * reported as one. If it is UDP, the same failure proves nothing at all —
     * so it is reported as "not measured" instead of condemning a server that
     * may be perfectly healthy.
     *
     * @return connect time, 0 for "could not be measured", or -1 for a real
     *   failure.
     */
    private fun measureOpenVpnReach(server: String?, serverPort: String?, profile: String?): Long {
        val host = server.orEmpty()
        val port = serverPort?.toIntOrNull()
        if (host.isBlank() || port == null) return 0L

        val tcpTime = SpeedtestManager.socketConnectTime(host, port, REACH_TIMEOUT_MS)
        if (tcpTime > 0L) return tcpTime
        return if (profile != null && tcpProfile.containsMatchIn(profile)) -1L else 0L
    }

    /**
     * Outbound types that ride UDP or QUIC, so a refused TCP connect proves
     * nothing about them. Matched textually rather than by parsing the whole
     * config: the question is only "does this config mention such a type".
     */
    private val udpOutbound =
        Regex(""""type"\s*:\s*"(tuic|hysteria2?|wireguard)"""")

    /**
     * Reachability for a sing-box server, for the TCP-only batch — the same
     * bargain as OpenVPN's: a successful connect is a real number, and a
     * failed one is a real failure only when the config's protocols would
     * themselves dial TCP. If it names a UDP-carried outbound, the failure is
     * reported as "not measured" instead of condemning a healthy server.
     *
     * The address comes from the configuration rather than the panel row's
     * `server` field, because the configuration is what the core dials.
     */
    private fun measureSingBoxReach(server: String?, serverPort: String?, config: String?): Long {
        val endpoint = config?.let { SingBoxTestConfig.endpointOf(it) }
        val host = endpoint?.first ?: server.orEmpty()
        val port = endpoint?.second ?: serverPort?.toIntOrNull()
        if (host.isBlank() || port == null) return 0L

        val tcpTime = SpeedtestManager.socketConnectTime(host, port, REACH_TIMEOUT_MS)
        if (tcpTime > 0L) return tcpTime
        return if (config != null && udpOutbound.containsMatchIn(config)) 0L else -1L
    }

    /**
     * How long a live server is allowed to take to accept a connection.
     *
     * Not a latency budget — the real test measures latency. This only has to
     * outlast the first SYN retransmission, which is where a healthy server on
     * a bad mobile network was being failed.
     */
    private companion object {
        const val REACH_TIMEOUT_MS = 4_000
    }

    private fun startTcping(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure

        if (config.configType == EConfigType.OPENVPN) {
            return measureOpenVpnReach(config.server, config.serverPort, config.ovpnProfile)
        }

        if (config.configType == EConfigType.SINGBOX) {
            return measureSingBoxReach(config.server, config.serverPort, config.singboxConfig)
        }

        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.startsWith("h3") != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, REACH_TIMEOUT_MS)

            return tcpTime
        }

        return retFailure
    }
}
