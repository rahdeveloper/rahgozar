package com.rahgozar.app.service

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Builds the engine config JSON for the Aether (WARP-MASQUE) core.
 *
 * Ported from WhiteAesther's AppSettings.toNativeJson. The engine registers a
 * free Cloudflare WARP device itself and persists that identity to `configPath`
 * (aether.toml), so there is no panel server or credential involved — this core
 * rides Cloudflare's free WARP MASQUE endpoints. `transport = auto` lets the
 * engine pick H3 (QUIC) and fall back to H2 (TLS-over-TCP) when UDP is blocked.
 */
object AetherConfig {
    /** Transports the engine understands. `auto` resolves to H3/H2 at connect time. */
    enum class Transport(val wire: String) {
        AUTO("auto"),
        H3("h3"),
        H2("h2"),
        WIREGUARD("wg"),
        WARP_IN_WARP("wiw"),
    }

    /**
     * @param transport how the tunnel is carried; leave AUTO unless testing a
     *   specific framing.
     * @param dualStack request IPv6 alongside IPv4.
     * @param fragmentTls / encryptedHello anti-inspection extras for H2; off by
     *   default (they cost a little on a healthy network).
     */
    fun tunConfig(
        context: Context,
        transport: Transport = Transport.H3,
        dualStack: Boolean = true,
        fragmentTls: Boolean = false,
        encryptedHello: Boolean = false,
    ): String = JSONObject()
        .put("mode", "tun")
        .put("configPath", File(context.filesDir, "aether.toml").absolutePath)
        .put("listenPort", 1819)
        // turbo, not balanced: balanced walks the whole 120s scan budget before
        // prepare() returns, which overruns the app's connect timeout and shows
        // a spurious error before the tunnel comes up. turbo returns on the first
        // verified gateway (a few seconds); the engine then caches it (lastconn)
        // so later connects are faster still.
        .put("scanMode", "turbo")
        .put("ipScan", if (dualStack) "both" else "v4")
        // The engine's prepare() rejects "auto" — that is a UI policy the caller
        // resolves to a real framing first. Default here is h3; AetherVpnService
        // walks h3 -> h2 itself.
        .put("transport", if (transport == Transport.AUTO) "h3" else transport.wire)
        .put("noize", "firewall")
        .put("validationEnabled", true)
        .put("peerFallback", false)
        .put("fragmentTls", fragmentTls)
        .put("encryptedHello", encryptedHello)
        .toString()

    /** Path the engine persists its WARP identity to; back this up across reinstalls. */
    fun identityPath(context: Context): String =
        File(context.filesDir, "aether.toml").absolutePath
}
