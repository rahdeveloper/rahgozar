package com.rahgozar.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rahgozar.app.service.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one lookup the core cannot afford to get wrong: its own server's name.
 *
 * Everything the tunnel does waits on it, and there are two ways it fails. On a
 * censored network it is the query most worth poisoning — the core was seen
 * dialling `10.10.34.36`, a block page. And once this app is inside its own tun
 * (which a timed session does, so the extend ad can ride it), the query leaves
 * the app, is hijacked by the tun it just entered, and is handed back to the
 * core — which cannot answer it without the outbound that is waiting for this
 * very lookup. `dns: lookup failed for <server>: context canceled`, every
 * outbound failing, and a tunnel that looks connected and carries nothing.
 *
 * Writing the address into the configuration removes the question. The core
 * reads it from a `hosts` server that needs no network and cannot be
 * intercepted.
 */
class SingBoxPinnedDnsTest {

    private val settings = SingBoxConfig.TunnelSettings(
        ipv4Address = "10.10.14.1/30",
        ipv6Address = "fc00::10:10:14:1/126",
        mtu = 1500,
        dnsServer = "1.1.1.1",
        ipv6Enabled = false,
    )

    private fun config(blob: String): JsonObject =
        JsonParser.parseString(SingBoxConfig.forTunnel(blob, settings)).asJsonObject

    private fun JsonObject.dnsServer(tag: String): JsonObject? =
        getAsJsonObject("dns")?.getAsJsonArray("servers")
            ?.map { it.asJsonObject }
            ?.firstOrNull { it.get("tag")?.asString == tag }

    private fun JsonObject.resolverTag(): String? =
        getAsJsonObject("route")?.getAsJsonObject("default_domain_resolver")
            ?.get("server")?.asString

    /** An IP-literal server: nothing to resolve, so nothing to pin. */
    private val literal = """
        {"type": "anytls", "tag": "out", "server": "203.0.113.10", "server_port": 8444}
    """.trimIndent()

    @Test
    fun a_literal_server_needs_no_pinned_resolver() {
        val built = config(literal)
        assertNull("nothing to pin, so no hosts server", built.dnsServer("pinned"))
        // And the resolver stays exactly what it was before any of this.
        assertEquals("local", built.resolverTag())
    }

    @Test
    fun the_local_resolver_is_still_present_for_everything_else() {
        // The pinned server answers one name. Every other name the core has to
        // resolve outside the tunnel still goes to the local resolver, so it
        // must not have been replaced.
        val built = config(literal)
        assertEquals("local", built.dnsServer("local")?.get("tag")?.asString)
    }

    @Test
    fun the_remote_resolver_still_goes_through_the_proxy() {
        // App lookups must be answered *inside* the tunnel or a filtered
        // network answers them instead — the tunnel would come up, go green,
        // and load nothing.
        val remote = config(literal).dnsServer("remote")
        assertEquals("out", remote?.get("detour")?.asString)
        assertEquals("1.1.1.1", remote?.get("server")?.asString)
    }

    @Test
    fun a_config_that_brings_its_own_dns_is_left_alone() {
        // An operator who wrote a whole configuration meant it; second-guessing
        // their DNS would make their tunnel behave differently here than it
        // does anywhere else.
        val built = config(
            """
            {
              "dns": { "servers": [{"type": "udp", "tag": "mine", "server": "8.8.8.8"}], "final": "mine" },
              "outbounds": [{"type": "anytls", "tag": "out", "server": "203.0.113.10", "server_port": 8444}]
            }
            """.trimIndent()
        )
        val servers = built.getAsJsonObject("dns").getAsJsonArray("servers")
        assertEquals(1, servers.size())
        assertEquals("mine", servers.get(0).asJsonObject.get("tag").asString)
    }

    @Test
    fun the_tun_is_still_built_around_the_outbound() {
        // The pinning must not have disturbed the rest of the wrapper.
        val built = config(literal)
        val tun = built.getAsJsonArray("inbounds").get(0).asJsonObject
        assertEquals("tun", tun.get("type").asString)
        assertEquals("out", built.getAsJsonObject("route").get("final").asString)
        assertTrue(built.getAsJsonObject("route").get("auto_detect_interface").asBoolean)
    }
}
