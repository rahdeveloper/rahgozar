package com.rahgozar.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rahgozar.app.service.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tunnel configuration the app builds around a bare outbound.
 *
 * The case that matters most is DNS. A tunnel whose lookups are answered by
 * the phone's own resolver is a tunnel that connects, shows green, and carries
 * nothing on a filtered network — the answers come back poisoned and every
 * connection goes to the wrong address. It is invisible from the outside,
 * which is exactly why it is pinned here.
 *
 * [SingBoxConfig.forTunnel] reads MMKV for the tun address and MTU, so these
 * run against the defaults the unit-test JVM returns (null settings), which is
 * the same path a fresh install takes.
 */
class SingBoxTunnelConfigTest {

    private val settings = SingBoxConfig.TunnelSettings(
        ipv4Address = "10.10.14.1/30",
        ipv6Address = "fc00::10:10:14:1/126",
        mtu = 1500,
        dnsServer = "1.1.1.1",
        ipv6Enabled = false,
    )

    private fun tunnel(blob: String, tunnelSettings: SingBoxConfig.TunnelSettings = settings): JsonObject =
        JsonParser.parseString(SingBoxConfig.forTunnel(blob, tunnelSettings)).asJsonObject

    private fun JsonObject.route(): JsonObject = getAsJsonObject("route")

    private fun JsonObject.dns(): JsonObject = getAsJsonObject("dns")

    @Test
    fun `app lookups resolve through the proxy, not the phone`() {
        val config = tunnel(BARE)

        val servers = config.dns().getAsJsonArray("servers")
        val remote = servers[0].asJsonObject
        assertEquals("remote", remote.get("tag").asString)
        assertEquals(
            "the resolver must be reached through the proxy, or answers come from the network being escaped",
            "HY2",
            remote.get("detour").asString,
        )
        assertEquals("remote", config.dns().get("final").asString)
    }

    @Test
    fun `the server's own hostname resolves locally`() {
        // It cannot resolve through a tunnel that does not exist yet.
        val config = tunnel(BARE)

        assertEquals(
            "local",
            config.route().getAsJsonObject("default_domain_resolver").get("server").asString,
        )
        val servers = config.dns().getAsJsonArray("servers")
        assertTrue(servers.any { it.asJsonObject.get("type").asString == "local" })
    }

    @Test
    fun `builds a tun that captures traffic and sends it to the proxy`() {
        val config = tunnel(BARE)

        val tun = config.getAsJsonArray("inbounds")[0].asJsonObject
        assertEquals("tun", tun.get("type").asString)
        assertTrue("without auto_route nothing enters the tunnel", tun.get("auto_route").asBoolean)
        assertEquals("HY2", config.route().get("final").asString)
        assertTrue(config.route().get("auto_detect_interface").asBoolean)
    }

    @Test
    fun `sniffs so the server sees hostnames rather than bare addresses`() {
        val rules = tunnel(BARE).route().getAsJsonArray("rules")
        assertTrue(rules.any { it.asJsonObject.get("action").asString == "sniff" })
    }

    /**
     * The bug behind "only Chrome works": a tun with no IPv6 address captures
     * no IPv6, so apps that prefer it go around the tunnel and into the
     * filtering. Chrome falls back to IPv4 and looks fine; Instagram, YouTube
     * and Telegram do not and simply hang.
     */
    @Test
    fun `captures IPv6 so it cannot go around the tunnel`() {
        val tun = tunnel(BARE).getAsJsonArray("inbounds")[0].asJsonObject
        val addresses = tun.getAsJsonArray("address").map { it.asString }

        assertTrue("the tun must hold an IPv6 address or IPv6 leaks", addresses.any { it.contains(":") })
    }

    @Test
    fun `with IPv6 off, refuses it and stops asking for AAAA records`() {
        val config = tunnel(BARE)

        assertEquals("ipv4_only", config.dns().get("strategy").asString)
        val rejects = config.route().getAsJsonArray("rules").any {
            val rule = it.asJsonObject
            rule.has("ip_version") && rule.get("ip_version").asInt == 6 &&
                rule.get("action").asString == "reject"
        }
        assertTrue("IPv6 must be refused fast so apps fall back to IPv4", rejects)
    }

    @Test
    fun `with IPv6 on, carries it instead of refusing it`() {
        val config = tunnel(BARE, settings.copy(ipv6Enabled = true))

        assertTrue("no strategy override belongs here", !config.dns().has("strategy"))
        val rejects = config.route().getAsJsonArray("rules").any {
            it.asJsonObject.has("ip_version")
        }
        assertTrue("IPv6 must not be refused when the user wants it", !rejects)
    }

    @Test
    fun `leaves a complete configuration untouched`() {
        // The operator wrote it; second-guessing their DNS or routing would
        // make their own settings unpredictable.
        val source = """
            {
              "inbounds": [{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"auto_route":true}],
              "outbounds": [{"type":"direct","tag":"direct"}],
              "route": {"final":"direct"}
            }
        """.trimIndent()

        val config = tunnel(source)

        assertEquals("172.19.0.1/30", config.getAsJsonArray("inbounds")[0].asJsonObject.getAsJsonArray("address")[0].asString)
        assertTrue("their config named no dns; we must not invent one", !config.has("dns"))
        assertEquals("direct", config.route().get("final").asString)
    }

    private companion object {
        const val BARE = """
            {
              "type": "hysteria2",
              "tag": "HY2",
              "server": "hys.example",
              "server_port": 8443,
              "password": "secret",
              "tls": {"enabled": true, "server_name": "hys.example", "alpn": ["h3"]}
            }
        """
    }
}
