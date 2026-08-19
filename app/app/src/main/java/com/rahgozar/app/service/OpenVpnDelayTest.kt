package com.rahgozar.app.service

import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import net.openvpn.ovpn3.ClientAPI_AppCustomControlMessageEvent
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_ExternalPKICertRequest
import net.openvpn.ovpn3.ClientAPI_ExternalPKISignRequest
import net.openvpn.ovpn3.ClientAPI_LogInfo
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient
import net.openvpn.ovpn3.ClientAPI_ProvideCreds
import net.openvpn.ovpn3.ClientAPI_RemoteOverride
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Measures an OpenVPN server the way it will actually be used.
 *
 * A TCP connect proves almost nothing on a censored network. The ports these
 * servers listen on are usually open and answer instantly; what fails is the
 * **handshake** — DPI recognises the OpenVPN TLS exchange and drops the
 * connection a moment later. So a config that "pings" in 30ms and never
 * carries a byte is the normal case, not the exception, and measuring the
 * connect alone reports exactly the wrong thing.
 *
 * This runs the real thing instead: TCP connect, TLS handshake, authentication,
 * and the server accepting us and agreeing to push a configuration. That is
 * every stage censorship interferes with.
 *
 * It stops at `GET_CONFIG`, which the core emits when it sends PUSH_REQUEST —
 * after authentication succeeds and before any tun is configured. Nothing is
 * ever established, so this needs no VPN permission and cannot disturb a
 * tunnel the user already has up.
 */
object OpenVpnDelayTest {

    private const val TAG = "OpenVPN-Delay"

    /** Generous: a handshake over a congested path is slow, not broken. */
    private const val TIMEOUT_MS = 10_000L

    /**
     * openvpn3 is built around one client per process at a time. Testing a list
     * of servers concurrently would be a native-level experiment with the whole
     * app process as the stake, so measurements are taken one at a time.
     */
    private val lock = ReentrantLock()

    /**
     * @return milliseconds to a completed handshake, or -1 if the server did
     *   not get that far.
     */
    fun measure(profile: String, username: String?, password: String?): Long = lock.withLock {
        runCatching { System.loadLibrary("ovpncli") }
            .onFailure { return@withLock -1L }

        var elapsed = -1L
        var client: ClientAPI_OpenVPNClient? = null

        try {
            val started = System.currentTimeMillis()

            val probe = object : ClientAPI_OpenVPNClient() {
                override fun event(ev: ClientAPI_Event) {
                    when {
                        // The server has accepted us and we are asking for a
                        // configuration. Everything censorship blocks is behind
                        // us, so this is the number worth reporting.
                        ev.name == "GET_CONFIG" -> {
                            elapsed = System.currentTimeMillis() - started
                            stop()
                        }

                        ev.error || ev.fatal -> {
                            LogUtil.i(AppConfig.TAG, "$TAG: ${ev.name} ${ev.info}")
                            stop()
                        }
                    }
                }

                override fun log(loginfo: ClientAPI_LogInfo) = Unit

                /**
                 * No tunnel is built, so nothing needs protecting. This is only
                 * reached if the session somehow runs past GET_CONFIG.
                 */
                override fun socket_protect(socket: Int, remote: String?, ipv6: Boolean) = true

                /** Refusing here guarantees no tun is ever created by a probe. */
                override fun tun_builder_new() = false

                // Everything below exists because of how SWIG binds openvpn3's
                // callbacks, not because a probe has anything to say through
                // them.
                //
                // `external_pki_cert_request` and `external_pki_sign_request`
                // are pure virtual in C++. When C++ calls one and the Java
                // subclass has no override, SWIG's director raises
                // "Attempted to invoke pure virtual method" *from native code*
                // — and a Java exception pending across a JNI boundary is a
                // fatal error, so ART aborts the process. Not a hypothetical:
                // it killed :RunSoLibV2RayDaemon with SIGABRT on every server
                // sweep that reached an OpenVPN row, and that process is the
                // one hosting any tunnel the user has up.
                //
                // Failing the request is the honest answer. A probe has no
                // access to a key store, and eval_config already tries to turn
                // external PKI off before we get here; if something still asks,
                // the measurement should end rather than continue with a
                // certificate nobody signed.
                override fun external_pki_cert_request(req: ClientAPI_ExternalPKICertRequest) {
                    req.error = true
                    req.errorText = "a delay probe has no external PKI"
                }

                override fun external_pki_sign_request(req: ClientAPI_ExternalPKISignRequest) {
                    req.error = true
                    req.errorText = "a delay probe has no external PKI"
                }

                // These have C++ defaults today, so they are not what aborted.
                // They are overridden anyway: the cost is four lines, and the
                // failure mode if a future openvpn3 promotes one of them to
                // pure virtual is a native abort that takes the user's tunnel
                // with it — which is not a thing to find out about in the field.
                override fun acc_event(ev: ClientAPI_AppCustomControlMessageEvent) = Unit

                override fun remote_override_enabled() = false

                override fun remote_override(ro: ClientAPI_RemoteOverride) = Unit

                override fun clock_tick() = Unit

                /** One attempt is the measurement; never sit in a pause. */
                override fun pause_on_connection_timeout() = false
            }
            client = probe

            val sanitised = OpenVpnProfile.sanitise(profile)
            val config = ClientAPI_Config().apply {
                content = sanitised.profile
                compressionMode = "asym"
                // Do not sit in a reconnect loop on a dead server: one attempt
                // is the measurement.
                connTimeout = (TIMEOUT_MS / 1000).toInt()
            }

            var eval = probe.eval_config(config)
            if (eval.error) return@withLock -1L
            if (eval.externalPki) {
                config.disableClientCert = true
                eval = probe.eval_config(config)
                if (eval.error) return@withLock -1L
            }

            if (!eval.autologin) {
                if (username.isNullOrBlank() || password.isNullOrBlank()) return@withLock -1L
                val status = probe.provide_creds(
                    ClientAPI_ProvideCreds().apply {
                        this.username = username
                        this.password = password
                    }
                )
                if (status.error) return@withLock -1L
            }

            // connect() blocks. If the handshake never completes — which is what
            // a blocked server looks like — nothing would ever return it, so a
            // watchdog ends the attempt.
            val watchdog = Thread({
                try {
                    Thread.sleep(TIMEOUT_MS)
                    probe.stop()
                } catch (_: InterruptedException) {
                    // Finished in time.
                }
            }, "openvpn3-probe-timeout").apply { isDaemon = true; start() }

            try {
                probe.connect()
            } finally {
                watchdog.interrupt()
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: probe failed", e)
            return@withLock -1L
        } finally {
            runCatching { client?.stop() }
        }

        return@withLock elapsed
    }
}
