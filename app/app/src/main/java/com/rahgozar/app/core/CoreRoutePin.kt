package com.rahgozar.app.core

import com.rahgozar.app.AppConfig
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.util.HttpUtil
import com.rahgozar.app.util.Utils
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * The addresses the running core will actually dial, by server name.
 *
 * Two pieces of the app have to agree on one set of IPs. The config
 * generator resolves a domain server once and pins the answer into the
 * core's DNS, so the core dials those addresses and no others. The tun
 * builder must exclude the core's own connection from the tun it is
 * building — and a route is an address, so for a domain server it can only
 * exclude what the config pinned. If the two resolved independently, a CDN
 * that rotates answers would hand them different IPs, and the core's uplink
 * would land inside its own tunnel.
 *
 * So the resolution happens once, in the config generator, and is
 * remembered here for everyone else. Process-local on purpose: the config
 * and the tun are both built in the daemon process, and no other process
 * has any business with these addresses.
 */
object CoreRoutePin {

    private val pins = ConcurrentHashMap<String, List<String>>()

    /**
     * True while this app itself is being carried by the running tun.
     *
     * The config generator checks it before resolving: a lookup made from
     * inside the tunnel goes through a resolver that may still be coming
     * up — and worse, a *fresh* answer would name IPs the tun's exclusions
     * have never heard of. While inside, the remembered pin is the only
     * answer that stays consistent with the routes.
     */
    @Volatile
    var insideTun: Boolean = false

    /** Records what [domain] resolved to. Latest resolution wins. */
    fun remember(domain: String, ips: List<String>) {
        val addresses = ips.filter { Utils.isPureIpAddress(it) && isReachablePublicAddress(it) }
        if (addresses.isNotEmpty()) pins[domain] = addresses
    }

    /**
     * Whether an address can plausibly be a server on the internet.
     *
     * This resolution comes from the phone's own resolver, and in the country
     * this app is for that resolver is the one being tampered with: asked for
     * a blocked host it answers with the block page, which lives on a private
     * address. Pinning that is worse than pinning nothing — the tun would
     * exclude a route to nowhere and the core would dial the censor instead of
     * the server. Seen on the device: `excluded 10.10.34.35 from the tun`,
     * followed by a connection that could not be verified.
     *
     * A public server is never a private, loopback, link-local or multicast
     * address, so rejecting those costs nothing real and catches the poisoned
     * answer exactly. With nothing left to pin, this app stays outside the tun
     * and the core resolves the name over its own DNS, which is not poisoned —
     * so the connection works; only the extend offer is withheld.
     */
    private fun isReachablePublicAddress(address: String): Boolean = runCatching {
        val parsed = InetAddress.getByName(address)
        !parsed.isSiteLocalAddress &&
            !parsed.isLoopbackAddress &&
            !parsed.isLinkLocalAddress &&
            !parsed.isAnyLocalAddress &&
            !parsed.isMulticastAddress
    }.getOrDefault(false)

    /** What [domain] was pinned to, or empty when it never resolved. */
    fun recall(domain: String): List<String> = pins[domain].orEmpty()

    /**
     * Every address the core would dial for [server] — the address itself,
     * or the pinned resolution of it. Empty means nobody knows, and a tun
     * that cannot exclude the core's uplink must not carry this app.
     */
    fun dialAddresses(server: String?): List<String> {
        if (server.isNullOrEmpty()) return emptyList()
        return if (Utils.isPureIpAddress(server)) listOf(server) else recall(server)
    }

    /**
     * [dialAddresses], resolving on the spot when nothing is pinned yet.
     *
     * The tun is built *before* the core's configuration is generated, so at
     * the moment the exclusions are decided there is usually no pin to read —
     * waiting for the config generator's resolution would mean deciding
     * blind, which is exactly the bug this replaces. The tun does not exist
     * yet, so this lookup rides the real network like any other.
     *
     * Idempotent by design: the first caller in the build resolves, every
     * later caller — the per-app rules moments later, the config generator
     * once the tun is up — reads the same answer back. That sameness is the
     * contract: the addresses excluded from the tun and the addresses the
     * core dials must be one list.
     *
     * Outside a tun the pin is refreshed rather than trusted, because a pin
     * left from an old sweep can outlive a CDN's rotation; the old answer is
     * kept only as a fallback when the fresh lookup fails outright.
     */
    fun ensurePinned(server: String?): List<String> {
        if (server.isNullOrEmpty()) return emptyList()
        if (Utils.isPureIpAddress(server)) return listOf(server)
        if (insideTun) {
            recall(server).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val preferIpv6 =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true
        val fresh = HttpUtil.resolveHostToIP(server, preferIpv6).orEmpty()
        remember(server, fresh)
        return recall(server)
    }
}
