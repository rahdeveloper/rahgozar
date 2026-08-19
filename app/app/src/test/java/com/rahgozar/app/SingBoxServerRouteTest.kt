package com.rahgozar.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rahgozar.app.service.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's own address must stay outside the tun this config builds.
 *
 * Not for the core's sake — libbox protects its sockets by descriptor. For this
 * app's: during a timed session the tun carries us too, and the connection
 * verification runs in a plain Service that cannot protect anything. Without
 * the exclusion its probe dials the server through the tunnel it is measuring,
 * times out, and the app disconnects a server that was working. That was every
 * sing-box server, whenever a session limit was on.
 */
class SingBoxServerRouteTest {

    private val settings = SingBoxConfig.TunnelSettings(
        ipv4Address = "10.10.14.1/30",
        ipv6Address = "fc00::10:10:14:1/126",
        mtu = 1500,
        dnsServer = "1.1.1.1",
        ipv6Enabled = false,
    )

    private fun tun(blob: String): JsonObject =
        JsonParser.parseString(SingBoxConfig.forTunnel(blob, settings))
            .asJsonObject
            .getAsJsonArray("inbounds")
            .get(0).asJsonObject

    private fun JsonObject.excluded(key: String): List<String> =
        (get(key)?.asJsonArray ?: return emptyList()).map { it.asString }

    @Test
    fun an_outbound_server_address_is_excluded_from_the_tun() {
        val inbound = tun(
            """
            {"type": "anytls", "tag": "out", "server": "203.0.113.10", "server_port": 8444}
            """.trimIndent()
        )
        assertEquals(listOf("203.0.113.10/32"), inbound.excluded("route_exclude_address"))
    }

    @Test
    fun a_wireguard_peer_address_is_excluded_too() {
        // The peer is where an AmneziaWG endpoint's address lives; a rule that
        // only looked at the root would leave it inside the tun.
        val inbound = tun(
            """
            {
              "endpoints": [{
                "type": "wireguard",
                "tag": "awg-out",
                "peers": [{"address": "198.18.7.9", "port": 51820}]
              }]
            }
            """.trimIndent()
        )
        assertEquals(listOf("198.18.7.9/32"), inbound.excluded("route_exclude_address"))
    }

    @Test
    fun an_ipv6_server_is_excluded_as_a_v6_prefix() {
        val inbound = tun(
            """
            {"type": "anytls", "tag": "out", "server": "2001:db8::1", "server_port": 443}
            """.trimIndent()
        )
        assertEquals(listOf("2001:db8::1/128"), inbound.excluded("route_exclude_address"))
    }

    @Test
    fun a_config_that_names_no_address_adds_no_empty_lists() {
        // sing-box rejects some empty arrays outright, and an exclusion list
        // with nothing in it says something different from no list at all.
        val inbound = tun("""{"type": "direct", "tag": "out"}""")
        assertFalse(inbound.has("route_exclude_address"))
    }

    @Test
    fun the_legacy_split_fields_are_never_emitted() {
        // sing-box 1.10 deprecated the per-family pair and 1.12 removed it. The
        // core does not ignore what it no longer knows: it refuses the whole
        // configuration, so the tunnel never starts. Seen on the device as
        // "legacy tun address fields are deprecated" and every sing-box server
        // failing to connect.
        val inbound = tun(
            """
            {"type": "anytls", "tag": "out", "server": "203.0.113.10", "server_port": 8444}
            """.trimIndent()
        )
        for (legacy in listOf(
            "inet4_route_exclude_address",
            "inet6_route_exclude_address",
            "inet4_address",
            "inet6_address",
        )) {
            assertFalse("emitted the removed field $legacy", inbound.has(legacy))
        }
    }

    @Test
    fun the_rest_of_the_tun_is_unchanged() {
        val inbound = tun(
            """
            {"type": "anytls", "tag": "out", "server": "203.0.113.10", "server_port": 8444}
            """.trimIndent()
        )
        assertEquals("tun", inbound.get("type").asString)
        assertEquals("gvisor", inbound.get("stack").asString)
        assertTrue(inbound.get("auto_route").asBoolean)
        assertFalse(inbound.get("strict_route").asBoolean)
    }
}

/**
 * The rule that decides whether a failed probe is allowed to disconnect anyone.
 *
 * `HomeViewModel` reaches into MMKV and a Service, so the decision is restated
 * here as the arithmetic it is. It is worth pinning because getting it wrong in
 * either direction is invisible: too low and an idle tunnel is called healthy,
 * too high and a working connection is dropped on a measurement's say-so.
 */
class VerificationTrafficRuleTest {

    private val floor = 64L * 1024L

    private fun keepsTheConnection(baseline: Long, down: Long, up: Long): Boolean =
        (down + up - baseline) >= floor

    @Test
    fun a_tunnel_that_carried_nothing_is_still_a_failure() {
        assertFalse(keepsTheConnection(baseline = 0, down = 0, up = 0))
    }

    @Test
    fun a_refused_handshake_does_not_count_as_carrying() {
        // A few kilobytes is what a TLS exchange that went nowhere costs.
        assertFalse(keepsTheConnection(baseline = 0, down = 4_000, up = 2_000))
    }

    @Test
    fun real_use_overrules_the_probe() {
        assertTrue(keepsTheConnection(baseline = 0, down = 900_000, up = 100_000))
    }

    @Test
    fun the_previous_session_s_bytes_do_not_count() {
        // The counters belong to the service and the UI's copy is whatever the
        // last session left behind, so the baseline is what makes this honest —
        // without it every reconnect would look like it was already carrying.
        assertFalse(keepsTheConnection(baseline = 5_000_000, down = 5_000_000, up = 0))
        assertTrue(keepsTheConnection(baseline = 5_000_000, down = 5_100_000, up = 0))
    }
}
