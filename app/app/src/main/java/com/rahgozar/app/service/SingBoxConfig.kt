package com.rahgozar.app.service

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rahgozar.app.AppConfig
import com.rahgozar.app.BuildConfig
import com.rahgozar.app.core.CoreRoutePin
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.util.Utils

/**
 * Turns whatever the panel stored into something the sing-box core can run.
 *
 * The panel keeps `singboxConfig` as an opaque blob, and what an operator
 * actually has to hand is usually **one outbound** — that is what a provider
 * gives out and what a share link decodes to. A whole configuration, with a
 * tun inbound and routing, is something they would have to write themselves.
 *
 * So all three shapes are accepted: a complete configuration, a single
 * outbound object, or a list of outbounds. The last two are wrapped in the
 * tunnel this app would have built anyway, which also means the tun address,
 * MTU and DNS keep following the app's own settings instead of being frozen
 * into every server row.
 *
 * Getting this wrong is not a loud failure, which is why it is all in one
 * place with tests: a bare outbound handed to the core straight produces
 * `unknown field "type"`, and — worse — a blob nobody understands used to
 * fall back to a direct outbound, so the delay test measured the phone's own
 * internet and reported a dead server as excellent.
 */
object SingBoxConfig {

    /** Tags the app invents when the blob does not carry its own. */
    const val PROXY_TAG = "proxy"
    private const val TUN_TAG = "tun-in"
    private const val DNS_LOCAL_TAG = "local"
    private const val DNS_REMOTE_TAG = "remote"

    /**
     * The server that answers the proxy server's own hostname, from the
     * address pinned before the tun existed. Distinct from [DNS_LOCAL_TAG]
     * because the local resolver is exactly what cannot be trusted with this
     * one lookup once this app is inside its own tunnel.
     */
    private const val DNS_PINNED_TAG = "pinned"

    /** Types that carry traffic somewhere, but never to a server of ours. */
    val LOCAL_TYPES = setOf("direct", "block", "dns")

    /** Types that stand for other outbounds rather than being one. */
    val GROUP_TYPES = setOf("selector", "urltest")

    /**
     * The blob as a complete configuration, whatever shape it arrived in.
     *
     * @throws IllegalArgumentException if it is neither a configuration nor an
     *   outbound — the caller must fail the connection rather than run
     *   something that only looks like the server's.
     */
    fun normalize(blob: String): JsonObject {
        val parsed = runCatching { JsonParser.parseString(blob) }.getOrNull()
            ?: throw IllegalArgumentException("the sing-box configuration is not valid JSON")

        // A list of outbounds, as some panels and generators emit.
        if (parsed is JsonArray) {
            require(parsed.size() > 0) { "the sing-box configuration has no outbound" }
            return JsonObject().apply { add("outbounds", parsed) }
        }

        val root = parsed as? JsonObject
            ?: throw IllegalArgumentException("the sing-box configuration is not a JSON object")

        // `type` is tested before anything else, and the order matters: a
        // selector carries an `outbounds` field of its own — the tags it
        // chooses between — so a section check would read one as a whole
        // configuration. Only an outbound has `type` at its root.
        root.string("type")?.let { type ->
            if (type in GROUP_TYPES) {
                // A group on its own names outbounds that were never included.
                throw IllegalArgumentException("a $type outbound cannot be a server on its own")
            }
            return JsonObject().apply { add("outbounds", JsonArray().apply { add(root) }) }
        }

        // A complete configuration: anything that carries a section of one.
        if (root.has("outbounds") || root.has("endpoints") || root.has("inbounds") || root.has("route")) {
            return root
        }

        throw IllegalArgumentException("the sing-box configuration has no outbounds and is not an outbound")
    }

    /**
     * The parts of the tunnel that come from the app's own settings rather
     * than from the server.
     *
     * Passed in rather than read here so the shape of the configuration can be
     * tested without a device: MMKV is not available to a plain unit test, and
     * the shape is exactly the part worth pinning down.
     */
    data class TunnelSettings(
        val ipv4Address: String,
        val ipv6Address: String,
        val mtu: Int,
        val dnsServer: String,
        /** False means capture IPv6 and refuse it, so apps fall back to IPv4. */
        val ipv6Enabled: Boolean,
    )

    /** What the app is currently set to. */
    fun tunnelSettings(): TunnelSettings {
        val vpn = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        return TunnelSettings(
            ipv4Address = "${vpn.ipv4Client}/30",
            ipv6Address = "${vpn.ipv6Client}/126",
            mtu = SettingsManager.getVpnMtu(),
            dnsServer = SettingsManager.getVpnDnsServers().firstOrNull() ?: AppConfig.DNS_VPN,
            ipv6Enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true,
        )
    }

    /**
     * The configuration the tunnel runs.
     *
     * A blob that was already a full configuration is left alone — the
     * operator wrote it, and second-guessing it would make their tun settings
     * unpredictable. Only a wrapped outbound gets the tunnel built around it.
     */
    fun forTunnel(blob: String, settings: TunnelSettings = tunnelSettings()): String {
        val config = normalize(blob)

        // Nothing subscribes to the core's log, so on a debug build it goes to
        // a file: a tunnel that comes up and then carries nothing looks
        // identical from the outside to one that works, and this is the only
        // place the difference is written down. Never on a release build — it
        // would record every hostname the user visits.
        if (BuildConfig.DEBUG && !config.has("log")) {
            config.add(
                "log",
                JsonObject().apply {
                    addProperty("level", "debug")
                    addProperty("output", SingBoxNative.tunnelLogPath())
                },
            )
        }

        if (config.has("inbounds")) return config.toString()

        val outbounds = config.getAsJsonArray("outbounds") ?: JsonArray()
        val endpoints = config.getAsJsonArray("endpoints")
        val target = chooseTarget(outbounds, endpoints)

        // IPv6 is always given an address, even when the user has IPv6 off.
        //
        // The tun only captures what it has an address for, so a v4-only tun
        // leaves every IPv6 connection to go out over the phone's own network
        // — around the tunnel, straight into whatever is filtering it. The
        // symptom is peculiar and was reported exactly so: web pages load in
        // Chrome (it falls back to IPv4) while Instagram, YouTube and Telegram
        // do nothing at all, because they prefer IPv6 and never came back.
        //
        // With IPv6 off it is captured and refused below rather than carried,
        // which is what makes those apps fall back to IPv4 instead of waiting.
        val addresses = JsonArray().apply {
            add(settings.ipv4Address)
            add(settings.ipv6Address)
        }

        config.add(
            "inbounds",
            JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "tun")
                    addProperty("tag", TUN_TAG)
                    add("address", addresses)
                    addProperty("mtu", settings.mtu)
                    // Named rather than left to the default, and this is the
                    // difference between a tunnel that carries everything and
                    // one that carries only QUIC.
                    //
                    // The default on Android resolves to "mixed": UDP through
                    // gVisor, TCP through the system stack's NAT. On this
                    // device that TCP half never delivered a byte — the log
                    // showed 68 UDP connections proxied and not one TCP, with
                    // every TCP connection stuck waiting for data that the
                    // stack never handed over. Instagram, Telegram and YouTube
                    // simply hung; Chrome looked fine because it falls back to
                    // QUIC. gVisor handles both halves in userspace.
                    addProperty("stack", "gvisor")
                    // The core asks us for the interface either way (OpenTun),
                    // so this decides what we are asked to route, not who
                    // builds it. Per-app choices are applied on the builder.
                    addProperty("auto_route", true)
                    // Off deliberately: strict_route drops traffic that does
                    // not fit the tunnel, and on a phone that shows up as
                    // "some apps have no internet" with nothing to explain it.
                    addProperty("strict_route", false)
                    excludeServerRoute(this, blob)
                })
            },
        )

        // Names must be resolved *inside* the tunnel.
        //
        // This is the difference between a tunnel that works and one that only
        // looks like it does. The tun hijacks every DNS query the phone makes
        // (`protocol/tun/inbound.go`, hijack is the default mode) and hands it
        // to whatever `dns.final` names. Point that at the local resolver and
        // every lookup is answered by the network the user is trying to escape
        // — on a filtered one that means poisoned answers, so the tunnel comes
        // up, the dial goes green, and nothing loads.
        //
        // So app lookups go to a resolver reached *through* the proxy, and the
        // local resolver is kept for exactly one job: turning the proxy
        // server's own hostname into an address, which cannot go through the
        // tunnel that does not exist yet. That is what default_domain_resolver
        // below is for.
        if (!config.has("dns") && target != null) {
            val remote = settings.dnsServer
            config.add(
                "dns",
                JsonObject().apply {
                    add(
                        "servers",
                        JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("type", "udp")
                                addProperty("tag", DNS_REMOTE_TAG)
                                addProperty("server", remote)
                                addProperty("detour", target)
                            })
                            add(JsonObject().apply {
                                addProperty("type", "local")
                                addProperty("tag", DNS_LOCAL_TAG)
                            })
                            pinnedServerDns(blob)?.let { add(it) }
                        },
                    )
                    addProperty("final", DNS_REMOTE_TAG)
                    // With IPv6 off, answers must not carry AAAA records at
                    // all: an app that gets one will try it, and the reject
                    // rule below is a second of wasted time on every
                    // connection. Asking for A records only avoids the round.
                    if (!settings.ipv6Enabled) addProperty("strategy", "ipv4_only")
                },
            )
        }

        val route = config.getAsJsonObject("route") ?: JsonObject().also { config.add("route", it) }
        if (!route.has("final")) target?.let { route.addProperty("final", it) }
        if (!route.has("auto_detect_interface")) route.addProperty("auto_detect_interface", true)
        if (!route.has("rules")) {
            route.add(
                "rules",
                JsonArray().apply {
                    // Read the hostname out of the first packet. Nothing here
                    // routes on domains, but the server on the other end often
                    // does — and without sniffing it only ever sees an address.
                    add(JsonObject().apply { addProperty("action", "sniff") })
                    if (!settings.ipv6Enabled) {
                        // Refused, not carried: the tun captures IPv6 so it
                        // cannot leak around the tunnel, and refusing it at
                        // once is what makes an app fall back to IPv4 rather
                        // than sit waiting for a connection nobody will answer.
                        add(JsonObject().apply {
                            addProperty("ip_version", 6)
                            addProperty("action", "reject")
                        })
                    }
                },
            )
        }
        if (!route.has("default_domain_resolver")) {
            // The server's own name is answered from the configuration, never
            // from the network — when there is an answer to put there.
            //
            // This is the one lookup the core cannot afford to get wrong,
            // because everything else waits on it. Sending it to the local
            // resolver works right up until this app is *inside* the tun, which
            // is what a timed session does: the query then leaves the app, is
            // hijacked by the tun it just entered, and is handed to the core —
            // which cannot answer it without the outbound that is waiting for
            // this very lookup. The core's log says `dns: lookup failed for
            // <server>: context canceled`, every outbound fails, and the tunnel
            // sits there carrying nothing while looking perfectly connected.
            //
            // The same query on a censored network is also the one most worth
            // poisoning: the log showed the core dialling `10.10.34.36`, which
            // is a block page, not a server.
            //
            // Both problems have the same answer. The address was already
            // resolved on the way in (see [CoreRoutePin]) while there was still
            // no tunnel in the way, so it is written into the configuration and
            // the core reads it from there.
            val resolver = if (hasPinnedServerDns(config)) DNS_PINNED_TAG else DNS_LOCAL_TAG
            route.add(
                "default_domain_resolver",
                JsonObject().apply { addProperty("server", resolver) },
            )
        }

        return config.toString()
    }

    /** Whether [pinnedServerDns] actually added its server to this config. */
    private fun hasPinnedServerDns(config: JsonObject): Boolean =
        config.getAsJsonObject("dns")
            ?.getAsJsonArray("servers")
            ?.any { (it as? JsonObject)?.string("tag") == DNS_PINNED_TAG } == true

    /**
     * A DNS server that answers exactly one name — the proxy server's — from
     * the address resolved before the tun existed.
     *
     * `hosts` is a real sing-box transport, so this needs no network and cannot
     * be intercepted: the answer is in the configuration the core is handed.
     * Null when the server is already an address, or when the name could not be
     * resolved at all — in which case the local resolver stays in charge and
     * behaves exactly as it did before.
     */
    private fun pinnedServerDns(blob: String): JsonObject? {
        val host = endpointOf(blob)?.first ?: return null
        if (Utils.isPureIpAddress(host)) return null
        val addresses = runCatching { CoreRoutePin.ensurePinned(host) }.getOrDefault(emptyList())
        if (addresses.isEmpty()) return null
        return JsonObject().apply {
            addProperty("type", "hosts")
            addProperty("tag", DNS_PINNED_TAG)
            add(
                "predefined",
                JsonObject().apply {
                    add(host, JsonArray().apply { addresses.forEach { add(it) } })
                },
            )
        }
    }

    /**
     * Where traffic must go: the server's own outbound.
     *
     * A concrete proxy outbound is preferred over a `selector`/`urltest` group,
     * which would fold its own probing into anything measured through it. If
     * the configuration has neither — a direct-only config, say — the first
     * outbound is used, and using it is then the honest answer.
     *
     * Tags an untagged outbound in place, so `final` has something to name.
     */
    fun chooseTarget(outbounds: JsonArray, endpoints: JsonArray?): String? {
        val carriers = carrierTags(outbounds, endpoints)
        var group: String? = null
        for (element in outbounds) {
            val outbound = element as? JsonObject ?: continue
            val type = outbound.string("type").orEmpty()
            if (type in LOCAL_TYPES) continue
            if (outbound.string("tag") in carriers) continue
            if (type in GROUP_TYPES) {
                if (group == null) group = outbound.string("tag")
                continue
            }
            return tagOf(outbound, outbounds, endpoints)
        }

        endpoints?.firstOrNull { it is JsonObject } ?.let {
            return tagOf(it as JsonObject, outbounds, endpoints)
        }

        return group ?: (outbounds.firstOrNull() as? JsonObject)?.let { tagOf(it, outbounds, endpoints) }
    }

    /**
     * Tags that exist to carry someone else's traffic rather than to receive it.
     *
     * A node named as another node's `detour` is a hop, not a destination. The
     * case this exists for is an AmneziaWG endpoint whose UDP rides an ordinary
     * outbound so it leaves the phone as TCP: the configuration then holds two
     * servers, and the first one in the file is the wrong answer. Pointing
     * `route.final` at the carrier builds the AmneziaWG tunnel and then routes
     * around it — the user is connected, everything works, and their traffic
     * exits at a server nobody chose. There is no symptom to notice, which is
     * why this is decided structurally rather than by ordering.
     */
    private fun carrierTags(outbounds: JsonArray, endpoints: JsonArray?): Set<String> = buildSet {
        outbounds.forEach { (it as? JsonObject)?.string("detour")?.let(::add) }
        endpoints?.forEach { (it as? JsonObject)?.string("detour")?.let(::add) }
    }

    /**
     * The tag `route.final` will name, inventing one if the outbound has none.
     *
     * The invented tag is checked against every other tag in the file:
     * sing-box refuses a configuration with duplicates, so borrowing a name
     * that is already taken would turn a working server into a broken one.
     */
    private fun tagOf(outbound: JsonObject, outbounds: JsonArray, endpoints: JsonArray?): String {
        outbound.string("tag")?.let { return it }

        val taken = buildSet {
            addAll(outbounds.mapNotNull { (it as? JsonObject)?.string("tag") })
            endpoints?.let { addAll(it.mapNotNull { e -> (e as? JsonObject)?.string("tag") }) }
        }
        var tag = PROXY_TAG
        var suffix = 2
        while (tag in taken) tag = "$PROXY_TAG-${suffix++}"

        outbound.addProperty("tag", tag)
        return tag
    }

    /**
     * Keeps the server's own address outside the tun this configuration builds.
     *
     * The core does not need this — libbox protects its own sockets by file
     * descriptor. What needs it is *this app*, and only during a timed session,
     * because that is when the tun carries us too (see [PerAppProxy], where the
     * extend ad's traffic is the reason we stay inside). The connection
     * verification then runs in `SingBoxTestService`, which is an ordinary
     * Service in its own process: it cannot call `VpnService.protect`, so its
     * probe to the server is captured by the very tunnel it is measuring. Seen
     * on the device as `SingBox-DelayTest: probe failed: timeout` nine seconds
     * after connecting, followed by the app disconnecting a server that was
     * working — for every sing-box server, whenever a session limit is on.
     *
     * The Xray path has always excluded this address (`CoreVpnService`), and
     * `HomeViewModel` says in as many words that the verification relies on it.
     * This restores that promise for sing-box, using the tun inbound's own
     * exclude lists rather than `excludeRoute` on the builder: libbox folds them
     * into the route ranges it hands back through `openTun`, so it works on
     * every API level instead of only on 33 and up.
     */
    private fun excludeServerRoute(tun: JsonObject, blob: String) {
        val host = endpointOf(blob)?.first ?: return
        val addresses = runCatching { CoreRoutePin.ensurePinned(host) }.getOrDefault(emptyList())
        // One list for both families. The split `inet4_route_exclude_address` /
        // `inet6_route_exclude_address` pair is a legacy field: sing-box 1.10
        // deprecated it and 1.12 removed it outright, and the core does not
        // ignore what it no longer knows — it refuses the whole configuration
        // with "legacy tun address fields are deprecated", so the tunnel never
        // starts at all.
        val excluded = JsonArray()
        for (address in addresses) {
            excluded.add(if (address.contains(':')) "$address/128" else "$address/32")
        }
        if (excluded.size() > 0) tun.add("route_exclude_address", excluded)
    }

    /**
     * The address the server is actually dialled at, for the TCP-only test.
     *
     * The panel row carries `server`/`serverPort` fields too, but they are
     * whatever the operator typed; the configuration is what the core obeys.
     */
    fun endpointOf(blob: String): Pair<String, Int>? {
        val config = runCatching { normalize(blob) }.getOrNull() ?: return null

        config.getAsJsonArray("outbounds")?.forEach { element ->
            val outbound = element as? JsonObject ?: return@forEach
            if (outbound.string("type").orEmpty() in LOCAL_TYPES) return@forEach
            val host = outbound.string("server") ?: return@forEach
            val port = outbound.int("server_port")?.takeIf { it in 1..65535 } ?: return@forEach
            return host to port
        }

        // Endpoints (wireguard and friends) name their address on the peer.
        config.getAsJsonArray("endpoints")?.forEach { element ->
            val peers = (element as? JsonObject)?.getAsJsonArray("peers") ?: return@forEach
            peers.forEach { peerElement ->
                val peer = peerElement as? JsonObject ?: return@forEach
                val host = peer.string("address") ?: return@forEach
                val port = peer.int("port")?.takeIf { it in 1..65535 } ?: return@forEach
                return host to port
            }
        }
        return null
    }

    // -------------------------------------------------------------- helpers --

    internal fun JsonObject.getAsJsonArray(name: String): JsonArray? = get(name) as? JsonArray

    internal fun JsonObject.getAsJsonObject(name: String): JsonObject? = get(name) as? JsonObject

    internal fun JsonObject.string(name: String): String? =
        runCatching { get(name)?.asString }.getOrNull()?.takeIf { it.isNotBlank() }

    internal fun JsonObject.int(name: String): Int? = runCatching { get(name)?.asInt }.getOrNull()

    private fun JsonArray.firstOrNull(predicate: (JsonElement) -> Boolean = { true }): JsonElement? {
        for (element in this) if (predicate(element)) return element
        return null
    }
}
