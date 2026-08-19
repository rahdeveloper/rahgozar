package com.rahgozar.app.service

import android.net.IpPrefix
import android.net.InetAddresses
import android.net.VpnService
import android.os.Build
import com.rahgozar.app.AppConfig
import com.rahgozar.app.util.LogUtil
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_LogInfo
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient
import net.openvpn.ovpn3.DnsOptions
import java.net.InetAddress

/**
 * The bridge between the openvpn3 core and Android's VpnService.
 *
 * This is the inversion that makes OpenVPN different from Xray here. With Xray
 * we build the tun and then push packets into the core. openvpn3 on Android
 * compiles with USE_TUN_BUILDER, so the core drives instead: it works out the
 * addresses, routes and DNS from what the server pushed, calls the methods
 * below one at a time, and finally asks us for a file descriptor.
 *
 * Which is good news. Because we still own the VpnService.Builder, per-app
 * proxy keeps working exactly as it does for Xray.
 *
 * Threading: every callback here arrives on the worker thread running
 * connect(), never the main thread.
 */
class OpenVpnClient(
    private val service: VpnService,
    private val sessionName: String,
    private val onEvent: (name: String, info: String, fatal: Boolean) -> Unit,
) : ClientAPI_OpenVPNClient() {

    private companion object {
        const val TAG = "OpenVPN"
    }

    /** Built up across the callbacks, consumed by [tun_builder_establish]. */
    private var builder: VpnService.Builder? = null

    /** Kept only so a failure can say which server it was talking to. */
    @Volatile
    var remoteAddress: String? = null
        private set

    // ------------------------------------------------------------- tun build --

    override fun tun_builder_new(): Boolean {
        // A fresh Builder per session: openvpn3 calls this again on reconnect
        // when the pushed configuration changed, and carrying over routes from
        // the previous server would silently widen what the tunnel claims.
        builder = service.Builder().apply {
            setSession(sessionName)
            setBlocking(true)
        }
        return true
    }

    override fun tun_builder_set_session_name(name: String): Boolean {
        // Ignored on purpose. The name shown in Android's VPN dialog should be
        // ours, not whatever the server decided to call the session.
        return true
    }

    override fun tun_builder_set_remote_address(address: String, ipv6: Boolean): Boolean {
        remoteAddress = address
        return true
    }

    override fun tun_builder_set_mtu(mtu: Int): Boolean {
        val b = builder ?: return false
        b.setMtu(mtu)
        return true
    }

    override fun tun_builder_add_address(
        address: String,
        prefix_length: Int,
        gateway: String?,
        ipv6: Boolean,
        net30: Boolean,
    ): Boolean {
        val b = builder ?: return false
        return runCatching { b.addAddress(address, prefix_length) }
            .onFailure { LogUtil.e(AppConfig.TAG, "$TAG: bad address $address/$prefix_length", it) }
            .isSuccess
    }

    /**
     * Called once when the server pushes redirect-gateway.
     *
     * The core does not follow this with an explicit 0.0.0.0/0 — adding the
     * default route is this callback's whole job, and forgetting it produces a
     * tunnel that comes up and carries nothing.
     */
    override fun tun_builder_reroute_gw(ipv4: Boolean, ipv6: Boolean, flags: Long): Boolean {
        val b = builder ?: return false
        return runCatching {
            if (ipv4) b.addRoute("0.0.0.0", 0)
            if (ipv6) b.addRoute("::", 0)
        }.onFailure { LogUtil.e(AppConfig.TAG, "$TAG: failed to add default route", it) }
            .isSuccess
    }

    override fun tun_builder_add_route(
        address: String,
        prefix_length: Int,
        metric: Int,
        ipv6: Boolean,
    ): Boolean {
        val b = builder ?: return false
        return runCatching { b.addRoute(address, prefix_length) }
            .onFailure { LogUtil.e(AppConfig.TAG, "$TAG: bad route $address/$prefix_length", it) }
            .isSuccess
    }

    /**
     * Only reachable on Android 13+.
     *
     * Below that, Android cannot punch a hole in a route, so openvpn3's own
     * `enableRouteEmulation` (on by default) converts exclusions into a set of
     * narrower include routes before we ever get here. If this is somehow
     * called on an older device, refusing is the honest answer: returning true
     * would claim traffic is excluded when it is not.
     */
    override fun tun_builder_exclude_route(
        address: String,
        prefix_length: Int,
        metric: Int,
        ipv6: Boolean,
    ): Boolean {
        val b = builder ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            LogUtil.w(AppConfig.TAG, "$TAG: cannot exclude $address/$prefix_length below API 33")
            return false
        }
        return runCatching {
            val addr: InetAddress = InetAddresses.parseNumericAddress(address)
            b.excludeRoute(IpPrefix(addr, prefix_length))
        }.onFailure { LogUtil.e(AppConfig.TAG, "$TAG: bad excluded route $address", it) }
            .isSuccess
    }

    /**
     * Called once, and replaces anything set before it.
     *
     * Only the addresses are taken. openvpn3 also carries search domains and
     * per-server DNSSEC and transport settings, none of which Android's
     * VpnService.Builder can express.
     */
    override fun tun_builder_set_dns_options(dns: DnsOptions): Boolean {
        val b = builder ?: return false
        var added = 0
        dns.servers.values.forEach { server ->
            server.addresses.forEach { entry ->
                val address = entry.address
                if (!address.isNullOrBlank()) {
                    runCatching { b.addDnsServer(address); added++ }
                        .onFailure { LogUtil.e(AppConfig.TAG, "$TAG: bad DNS $address", it) }
                }
            }
        }
        LogUtil.i(AppConfig.TAG, "$TAG: $added DNS server(s) from the server")
        return true
    }

    override fun tun_builder_set_layer(layer: Int): Boolean {
        // 3 is TUN. A TAP tunnel (layer 2) cannot be represented by VpnService
        // at all, so say so here rather than failing later with a confusing
        // establish() error.
        if (layer != 3) {
            LogUtil.e(AppConfig.TAG, "$TAG: server asked for layer $layer; only TUN is supported")
            return false
        }
        return true
    }

    override fun tun_builder_set_route_metric_default(metric: Int): Boolean = true

    override fun tun_builder_set_allow_family(af: Int, allow: Boolean): Boolean = true

    override fun tun_builder_set_allow_local_dns(allow: Boolean): Boolean = true

    /**
     * The last call of the session: hand the core a file descriptor it owns.
     *
     * Per-app rules are applied here, at the end, so they cannot be undone by a
     * later callback. detachFd() is deliberate — the contract says the caller
     * takes ownership, so the core is what closes it, and this side must not.
     */
    override fun tun_builder_establish(): Int {
        val b = builder ?: return -1

        PerAppProxy.apply(b, service, TAG)

        return try {
            val pfd = b.establish()
            if (pfd == null) {
                // Almost always means VPN permission was revoked while connecting.
                LogUtil.e(AppConfig.TAG, "$TAG: establish() returned null")
                -1
            } else {
                val fd = pfd.detachFd()
                LogUtil.i(AppConfig.TAG, "$TAG: tun established, fd=$fd")
                fd
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: failed to establish tun", e)
            -1
        }
    }

    override fun tun_builder_teardown(disconnect: Boolean) {
        LogUtil.i(AppConfig.TAG, "$TAG: tun teardown, disconnect=$disconnect")
        // The descriptor belongs to the core and it closes it. All that is left
        // here is dropping the builder so a reconnect starts from a clean one.
        builder = null
    }

    // ------------------------------------------------------------- transport --

    /**
     * Keeps the tunnel's own socket outside the tunnel.
     *
     * Without this the transport would be routed into the interface it is
     * trying to establish, and the connection dies immediately.
     */
    override fun socket_protect(socket: Int, remote: String?, ipv6: Boolean): Boolean {
        val ok = service.protect(socket)
        if (!ok) {
            LogUtil.e(AppConfig.TAG, "$TAG: failed to protect socket for $remote")
        }
        return ok
    }

    // ---------------------------------------------------------------- events --

    override fun event(ev: ClientAPI_Event) {
        val name = ev.name.orEmpty()
        val info = ev.info.orEmpty()
        if (ev.error || ev.fatal) {
            LogUtil.e(AppConfig.TAG, "$TAG: event $name $info")
        } else {
            LogUtil.i(AppConfig.TAG, "$TAG: event $name $info")
        }
        onEvent(name, info, ev.fatal)
    }

    override fun log(loginfo: ClientAPI_LogInfo) {
        // Core logs can carry the server address, so they follow the same rule
        // as the rest of the app: debug builds only. See LogUtil.
        LogUtil.d(AppConfig.TAG, "$TAG: ${loginfo.text?.trimEnd()}")
    }
}
