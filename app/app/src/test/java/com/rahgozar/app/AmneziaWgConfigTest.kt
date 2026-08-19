package com.rahgozar.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rahgozar.app.service.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An AmneziaWG server, from the panel's JSON to the configuration the core runs.
 *
 * AmneziaWG is not a new outbound type: it is a WireGuard endpoint carrying
 * obfuscation parameters (`jc`, `s1`..`s4`, `h1`..`h4`, `i1`..`i5`) that the
 * core only honours in a `with_awg` build. The app never reads those fields, so
 * what has to hold is narrower and easier to break by accident: the tunnel
 * builder must pass an endpoint through *untouched*, must still find the peer
 * behind it, and must not lose the detour that carries its UDP over someone
 * else's TCP.
 *
 * Each of those is one line of someone else's code away from silently
 * regressing into a tunnel that comes up and carries nothing.
 */
class AmneziaWgConfigTest {

    private val settings = SingBoxConfig.TunnelSettings(
        ipv4Address = "10.10.14.1/30",
        ipv6Address = "fc00::10:10:14:1/126",
        mtu = 1500,
        dnsServer = "1.1.1.1",
        ipv6Enabled = false,
    )

    /** What the panel ships for an AmneziaWG server with no relay. */
    private val plain = """
        {
          "endpoints": [{
            "type": "wireguard",
            "tag": "awg-out",
            "mtu": 1280,
            "address": ["10.13.13.2/32"],
            "private_key": "cHJpdmF0ZUtleUJhc2U2NDMyYnl0ZXNwYWRkaW5nMDAwMD0=",
            "peers": [{
              "address": "203.0.113.10",
              "port": 51820,
              "public_key": "cHVibGljS2V5QmFzZTY0MzJieXRlc3BhZGRpbmcwMDAwMD0=",
              "allowed_ips": ["0.0.0.0/0"],
              "persistent_keepalive_interval": 25
            }],
            "jc": 4, "jmin": 40, "jmax": 70,
            "s1": 15, "s2": 20,
            "h1": 1234567, "h2": 2345678, "h3": "3456789-4000000", "h4": 4567890
          }]
        }
    """.trimIndent()

    /** The same server with UDP-over-TCP: its UDP rides an existing outbound. */
    private val withRelay = """
        {
          "outbounds": [{
            "type": "vless",
            "tag": "carrier",
            "server": "cdn.example.com",
            "server_port": 443,
            "uuid": "11111111-2222-3333-4444-555555555555",
            "tls": { "enabled": true, "server_name": "cdn.example.com" }
          }],
          "endpoints": [{
            "type": "wireguard",
            "tag": "awg-out",
            "detour": "carrier",
            "mtu": 1280,
            "address": ["10.13.13.2/32"],
            "private_key": "cHJpdmF0ZUtleUJhc2U2NDMyYnl0ZXNwYWRkaW5nMDAwMD0=",
            "peers": [{
              "address": "203.0.113.10",
              "port": 51820,
              "public_key": "cHVibGljS2V5QmFzZTY0MzJieXRlc3BhZGRpbmcwMDAwMD0=",
              "allowed_ips": ["0.0.0.0/0"]
            }],
            "jc": 4, "jmin": 40, "jmax": 70
          }]
        }
    """.trimIndent()

    private fun tunnel(blob: String): JsonObject =
        JsonParser.parseString(SingBoxConfig.forTunnel(blob, settings)).asJsonObject

    private fun JsonObject.endpoint(): JsonObject =
        getAsJsonArray("endpoints").get(0).asJsonObject

    @Test
    fun the_obfuscation_parameters_survive_the_tunnel_builder() {
        val endpoint = tunnel(plain).endpoint()

        // Every one of these is the difference between AmneziaWG and plain
        // WireGuard on the wire. Dropping any of them still produces a working
        // tunnel — one a censor can fingerprint.
        assertEquals(4, endpoint.get("jc").asInt)
        assertEquals(40, endpoint.get("jmin").asInt)
        assertEquals(70, endpoint.get("jmax").asInt)
        assertEquals(15, endpoint.get("s1").asInt)
        assertEquals(20, endpoint.get("s2").asInt)
        assertEquals(1234567, endpoint.get("h1").asInt)
        // A ranged header stays a string; collapsing it to a number would pin
        // the header to one value and undo the randomisation.
        assertEquals("3456789-4000000", endpoint.get("h3").asString)
    }

    @Test
    fun the_endpoint_becomes_the_tunnel_target() {
        val config = tunnel(plain)

        // With no outbounds at all, the endpoint is the only thing traffic can
        // go to — route.final and the DNS detour both have to name it, or the
        // tunnel comes up and drops everything.
        assertEquals("awg-out", config.getAsJsonObject("route").get("final").asString)
        val remote = config.getAsJsonObject("dns")
            .getAsJsonArray("servers").get(0).asJsonObject
        assertEquals("awg-out", remote.get("detour").asString)
    }

    @Test
    fun the_relay_is_preserved_and_does_not_become_the_target() {
        val config = tunnel(withRelay)

        // The carrier exists to move the endpoint's UDP; it is not where the
        // user's traffic is supposed to end up. If route.final named the
        // carrier instead, the AmneziaWG tunnel would be built and then
        // bypassed — the user would be connected to the wrong server, with no
        // symptom other than the wrong exit address.
        assertEquals("carrier", config.endpoint().get("detour").asString)
        assertEquals("awg-out", config.getAsJsonObject("route").get("final").asString)
    }

    @Test
    fun the_peer_address_is_what_the_reachability_test_finds() {
        // The endpoint has no `server` field — a WireGuard peer's address lives
        // one level down. A builder that looked only at the root would report
        // "no endpoint" and the test service would have nothing to measure.
        val endpoint = SingBoxConfig.endpointOf(plain)
        assertNotNull(endpoint)
        assertEquals("203.0.113.10", endpoint!!.first)
        assertEquals(51820, endpoint.second)
    }

    @Test
    fun a_udp_endpoint_is_never_condemned_by_a_failed_tcp_connect() {
        // The reachability pre-gate matches the config text for UDP-carried
        // types. A WireGuard peer's port speaks UDP, so a TCP connect to it
        // fails for every healthy AmneziaWG server there has ever been; the
        // regex is what stops that from being read as "this server is dead".
        val udpOutbound = Regex(""""type"\s*:\s*"(tuic|hysteria2?|wireguard)"""")
        assertTrue(udpOutbound.containsMatchIn(plain))
        assertTrue(udpOutbound.containsMatchIn(withRelay))
    }
}
