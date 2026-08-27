package com.rahgozar.app

import com.rahgozar.app.service.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An openconnect endpoint's gateway must be findable, or the tunnel deadlocks.
 *
 * `endpointOf` used to know two shapes: an outbound with `server` +
 * `server_port`, and a wireguard-style endpoint with `peers[].address`. An
 * AnyConnect gateway is neither — it is one string in `server`, port optional.
 *
 * The cost of not finding it is not a missing optimisation. `excludeServerRoute`
 * gives up, the tun gets no `route_exclude_address` for the gateway, and the
 * endpoint's own connection to that gateway is routed into the endpoint — which
 * cannot become ready until that connection completes. Seen on the device:
 * every packet answered with "endpoint is not ready yet", no authentication
 * attempt, and nothing in the core log mentioning the server at all.
 */
class SingBoxOpenConnectRouteTest {

    private fun config(server: String) = """
        {
          "endpoints": [
            {
              "type": "openconnect",
              "tag": "cisco",
              "server": "$server",
              "flavor": "anyconnect",
              "username": "u",
              "password": "p"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun the_gateway_is_found_with_an_explicit_port() {
        assertEquals(
            "service01.example.net" to 443,
            SingBoxConfig.endpointOf(config("service01.example.net:443")),
        )
    }

    @Test
    fun a_bare_hostname_defaults_to_443() {
        // What an AnyConnect gateway listens on, and what sing-box assumes when
        // the port is left off.
        assertEquals(
            "vpn.example.net" to 443,
            SingBoxConfig.endpointOf(config("vpn.example.net")),
        )
    }

    @Test
    fun a_url_form_is_reduced_to_host_and_port() {
        assertEquals(
            "vpn.example.net" to 8443,
            SingBoxConfig.endpointOf(config("https://vpn.example.net:8443/group")),
        )
    }

    @Test
    fun a_non_default_port_survives() {
        assertEquals(
            "vpn.example.net" to 10443,
            SingBoxConfig.endpointOf(config("vpn.example.net:10443")),
        )
    }

    // ------------------------------------------------------------ literals --

    @Test
    fun a_bracketed_ipv6_literal_keeps_its_port() {
        assertEquals(
            "2001:db8::1" to 8443,
            SingBoxConfig.endpointOf(config("[2001:db8::1]:8443")),
        )
    }

    @Test
    fun a_bare_ipv6_literal_is_not_mistaken_for_host_and_port() {
        // The last colon in `2001:db8::1` separates nothing. Reading it as a
        // port would exclude the wrong address from the tun, which fails the
        // same silent way as excluding none.
        assertEquals(
            "2001:db8::1" to 443,
            SingBoxConfig.endpointOf(config("2001:db8::1")),
        )
    }

    // ------------------------------------------------------- the old shapes --

    @Test
    fun outbounds_and_wireguard_peers_still_win() {
        val outbound = """
            {"outbounds":[{"type":"vless","server":"a.example","server_port":8443}]}
        """.trimIndent()
        assertEquals("a.example" to 8443, SingBoxConfig.endpointOf(outbound))

        val wireguard = """
            {"endpoints":[{"type":"wireguard","peers":[{"address":"b.example","port":51820}]}]}
        """.trimIndent()
        assertEquals("b.example" to 51820, SingBoxConfig.endpointOf(wireguard))
    }

    @Test
    fun a_config_with_no_address_at_all_still_answers_null() {
        assertNull(SingBoxConfig.endpointOf("""{"endpoints":[{"type":"openconnect"}]}"""))
        assertNull(SingBoxConfig.endpointOf("""{"endpoints":[{"type":"openconnect","server":""}]}"""))
    }
}

/**
 * The shape an operator pastes into the panel, and what the app makes of it.
 *
 * Worth pinning because openconnect is the one protocol here that **cannot**
 * be entered the way every other one can. The panel's field takes a whole
 * configuration, a single outbound, or a list of them — and an AnyConnect
 * gateway is an *endpoint*, not an outbound. Pasting the bare object the way
 * you would paste a VLESS one wraps it in `outbounds`, where sing-box does not
 * know the type at all.
 *
 * So the contract is: `{"endpoints":[ ... ]}`, and nothing else is required —
 * no inbound, no DNS, no `route`. Those come from the app's own settings, and
 * a server row that froze them would stop following them.
 */
class SingBoxOpenConnectPanelFormatTest {

    /** Exactly what goes in the panel's `singboxConfig` field. */
    private val panelBlob = """
        {
          "endpoints": [
            {
              "type": "openconnect",
              "tag": "cisco",
              "server": "vpn.example.net:443",
              "flavor": "anyconnect",
              "username": "u",
              "password": "p",
              "tls": { "peer_fingerprint": ["sha256:AA"] }
            }
          ]
        }
    """.trimIndent()

    private val settings = SingBoxConfig.TunnelSettings(
        ipv4Address = "10.10.14.1/30",
        ipv6Address = "fc00::10:10:14:1/126",
        mtu = 1500,
        dnsServer = "1.1.1.1",
        ipv6Enabled = false,
    )

    @Test
    fun the_endpoints_only_form_becomes_a_runnable_tunnel() {
        val built = com.google.gson.JsonParser
            .parseString(SingBoxConfig.forTunnel(panelBlob, settings)).asJsonObject

        // The endpoint survives as an endpoint, not folded into outbounds.
        val endpoints = built.getAsJsonArray("endpoints")
        assertEquals(1, endpoints.size())
        assertEquals(
            "openconnect",
            endpoints[0].asJsonObject.get("type").asString,
        )

        // And the app names it as the destination itself — the operator never
        // has to write a `route` section to say "send traffic through this".
        assertEquals(
            "cisco",
            built.getAsJsonObject("route").get("final").asString,
        )

        // Keeping the gateway out of the tun is the other half of this, and it
        // is asserted in the class above — from `endpointOf`, which does not
        // need the name to resolve. Repeating it here against a real hostname
        // would make this test depend on the DNS of whoever runs it.
    }

    @Test
    fun an_untagged_endpoint_still_gets_a_destination() {
        // Operators leave `tag` off; the app invents one rather than building a
        // configuration whose `final` names nothing.
        val untagged = panelBlob.replace("\"tag\": \"cisco\",\n", "")
        val built = com.google.gson.JsonParser
            .parseString(SingBoxConfig.forTunnel(untagged, settings)).asJsonObject
        val finalTag = built.getAsJsonObject("route").get("final").asString
        assertEquals(
            finalTag,
            built.getAsJsonArray("endpoints")[0].asJsonObject.get("tag").asString,
        )
    }
}

/**
 * Which servers hold a session, rather than just answering connections.
 *
 * The delay test is a whole second core opening its own connection to the
 * server. For a stateless protocol that is just another connection. For a
 * gateway whose tunnel is a login it can be a fight over the session already in
 * use, and it was observed to have both possible endings: the second login
 * refused with `tunnel session was rejected`, and the *first* one closed with
 * `CSTP session closed during startup` — a connection the app had just made,
 * broken by the app measuring it.
 *
 * Three behaviours hang off this predicate: the connect-time gate verifies by
 * watching traffic instead of logging in again, the server-list test skips the
 * login only for the server currently connected, and the probe waits for the
 * gateway to finish authenticating before it starts timing.
 *
 * So it has to be right about the ordinary protocols too. A false positive here
 * would make every VLESS server in the list wait four seconds it does not need
 * and verify by a route built for something else.
 */
class SingBoxSessionLoginTest {

    @org.junit.Test
    fun an_openconnect_endpoint_holds_a_session() {
        val config = """
            {"endpoints":[{"type":"openconnect","server":"vpn.example.net:443"}]}
        """.trimIndent()
        org.junit.Assert.assertTrue(
            com.rahgozar.app.service.SingBoxConfig.usesSessionLogin(config)
        )
    }

    @org.junit.Test
    fun the_ordinary_protocols_do_not() {
        // These are stateless: a second connection costs the first one nothing,
        // so they keep the real measurement.
        listOf(
            """{"outbounds":[{"type":"vless","server":"a","server_port":443}]}""",
            """{"endpoints":[{"type":"wireguard","peers":[{"address":"a","port":51820}]}]}""",
            """{"outbounds":[{"type":"shadowsocks","server":"a","server_port":8388}]}""",
        ).forEach {
            org.junit.Assert.assertFalse(
                it, com.rahgozar.app.service.SingBoxConfig.usesSessionLogin(it)
            )
        }
    }

    @org.junit.Test
    fun rubbish_holds_nothing() {
        org.junit.Assert.assertFalse(com.rahgozar.app.service.SingBoxConfig.usesSessionLogin("{"))
        org.junit.Assert.assertFalse(com.rahgozar.app.service.SingBoxConfig.usesSessionLogin("{}"))
        org.junit.Assert.assertFalse(com.rahgozar.app.service.SingBoxConfig.usesSessionLogin(""))
    }
}
