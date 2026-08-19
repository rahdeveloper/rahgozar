package com.rahgozar.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rahgozar.app.service.SingBoxTestConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delay test is only as truthful as the config it measures through, and
 * that config is built by string surgery on whatever the panel sent. These
 * pin down what comes out, because getting it wrong does not crash — it
 * silently measures the wrong thing, which is the failure this whole test
 * exists to prevent.
 */
class SingBoxTestConfigTest {

    private fun parse(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun JsonObject.route(): JsonObject = getAsJsonObject("route")

    @Test
    fun `replaces the tun inbound with a loopback proxy`() {
        val result = parse(SingBoxTestConfig.forDelayTest(TUN_AND_DIRECT, 1080))

        val inbounds = result.getAsJsonArray("inbounds")
        assertEquals(1, inbounds.size())
        val inbound = inbounds[0].asJsonObject
        assertEquals("mixed", inbound.get("type").asString)
        assertEquals("127.0.0.1", inbound.get("listen").asString)
        assertEquals(1080, inbound.get("listen_port").asInt)
    }

    @Test
    fun `drops everything that could answer the probe from somewhere else`() {
        val result = parse(SingBoxTestConfig.forDelayTest(FULL_CONFIG, 1080))

        assertFalse("experimental would fight the tunnel for its ports", result.has("experimental"))
        assertFalse("a routing rule could send the probe direct", result.route().has("rules"))
        assertFalse(result.route().has("rule_set"))
        assertFalse("a dns rule can redirect too", result.getAsJsonObject("dns").has("rules"))
    }

    @Test
    fun `keeps the dns servers an outbound may name`() {
        // sing-box resolves domain_resolver at construction and fails the whole
        // config on a tag it cannot find, so dropping dns would report a
        // healthy server as dead. See common/dialer/dialer.go.
        val config = """
            {
              "dns": {"servers": [{"type": "udp", "tag": "dns-direct", "server": "1.1.1.1"}]},
              "outbounds": [
                {"type": "vless", "tag": "proxy", "server": "node.example", "server_port": 443,
                 "domain_resolver": "dns-direct"}
              ]
            }
        """.trimIndent()

        val result = parse(SingBoxTestConfig.forDelayTest(config, 1080))

        val servers = result.getAsJsonObject("dns").getAsJsonArray("servers")
        assertEquals("dns-direct", servers[0].asJsonObject.get("tag").asString)
    }

    @Test
    fun `keeps the route-level resolver an outbound falls back to`() {
        val config = """
            {
              "dns": {"servers": [{"type": "local", "tag": "local"}]},
              "outbounds": [{"type": "trojan", "tag": "proxy", "server": "node.example", "server_port": 443}],
              "route": {"default_domain_resolver": {"server": "local"}, "rules": [], "final": "proxy"}
            }
        """.trimIndent()

        val result = parse(SingBoxTestConfig.forDelayTest(config, 1080))

        assertEquals(
            "local",
            result.route().getAsJsonObject("default_domain_resolver").get("server").asString,
        )
    }

    @Test
    fun `keeps a pinned certificate`() {
        val config = """
            {
              "certificate": {"store": "system", "certificate": ["-----BEGIN CERTIFICATE-----"]},
              "outbounds": [{"type": "vless", "tag": "proxy", "server": "node.example", "server_port": 443}]
            }
        """.trimIndent()

        val result = parse(SingBoxTestConfig.forDelayTest(config, 1080))
        assertTrue(result.has("certificate"))
    }

    @Test
    fun `sends everything through the proxy outbound, not direct`() {
        val result = parse(SingBoxTestConfig.forDelayTest(FULL_CONFIG, 1080))

        assertEquals("proxy", result.route().get("final").asString)
        assertFalse(result.route().get("auto_detect_interface").asBoolean)
    }

    @Test
    fun `keeps the outbounds the server needs`() {
        val result = parse(SingBoxTestConfig.forDelayTest(FULL_CONFIG, 1080))

        val types = result.getAsJsonArray("outbounds").map { it.asJsonObject.get("type").asString }
        assertTrue(types.contains("vless"))
        assertTrue("direct must survive: an outbound may detour through it", types.contains("direct"))
    }

    @Test
    fun `prefers a concrete outbound over a selector`() {
        val config = """
            {
              "outbounds": [
                {"type": "selector", "tag": "auto", "outbounds": ["tuic-out"]},
                {"type": "direct", "tag": "direct"},
                {"type": "tuic", "tag": "tuic-out", "server": "a.example", "server_port": 443}
              ]
            }
        """.trimIndent()

        val result = parse(SingBoxTestConfig.forDelayTest(config, 1080))
        assertEquals("tuic-out", result.route().get("final").asString)
    }

    @Test
    fun `falls back to a selector when there is no concrete outbound`() {
        val config = """
            {"outbounds": [{"type": "direct", "tag": "direct"}, {"type": "selector", "tag": "auto"}]}
        """.trimIndent()

        val result = parse(SingBoxTestConfig.forDelayTest(config, 1080))
        assertEquals("auto", result.route().get("final").asString)
    }

    @Test
    fun `names an untagged outbound without colliding with an existing tag`() {
        val config = """
            {
              "outbounds": [
                {"type": "trojan", "server": "a.example", "server_port": 443},
                {"type": "direct", "tag": "rahgozar-test-out"}
              ]
            }
        """.trimIndent()

        val result = parse(SingBoxTestConfig.forDelayTest(config, 1080))
        val finalTag = result.route().get("final").asString

        val tags = result.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }
        assertEquals("every outbound must keep a unique tag", tags.size, tags.toSet().size)
        assertEquals("trojan", result.getAsJsonArray("outbounds")[0].asJsonObject.get("type").asString)
        assertEquals(finalTag, result.getAsJsonArray("outbounds")[0].asJsonObject.get("tag").asString)
    }

    @Test
    fun `a direct-only config measures the direct path`() {
        val result = parse(SingBoxTestConfig.forDelayTest(TUN_AND_DIRECT, 1080))
        assertEquals("direct", result.route().get("final").asString)
    }

    @Test
    fun `carries endpoints over and can target one`() {
        val config = """
            {
              "outbounds": [{"type": "direct", "tag": "direct"}],
              "endpoints": [
                {"type": "wireguard", "tag": "wg", "peers": [{"address": "b.example", "port": 51820}]}
              ]
            }
        """.trimIndent()

        val result = parse(SingBoxTestConfig.forDelayTest(config, 1080))
        assertTrue(result.has("endpoints"))
        assertEquals("wg", result.route().get("final").asString)
    }

    @Test
    fun `finds the address the core will really dial`() {
        assertEquals("a.example" to 8443, SingBoxTestConfig.endpointOf(FULL_CONFIG))
    }

    @Test
    fun `finds a wireguard peer address`() {
        val config = """
            {
              "outbounds": [{"type": "direct", "tag": "direct"}],
              "endpoints": [
                {"type": "wireguard", "tag": "wg", "peers": [{"address": "b.example", "port": 51820}]}
              ]
            }
        """.trimIndent()

        assertEquals("b.example" to 51820, SingBoxTestConfig.endpointOf(config))
    }

    @Test
    fun `has no address to offer for a direct-only config`() {
        assertNull(SingBoxTestConfig.endpointOf(TUN_AND_DIRECT))
    }

    @Test
    fun `rejects a configuration that is not a JSON object`() {
        runCatching { SingBoxTestConfig.forDelayTest("not json at all", 1080) }
            .onSuccess { throw AssertionError("garbage must not produce a config") }
    }

    /**
     * The bug this pins down: a blob with no outbounds used to fall back to a
     * direct one, so the probe measured the phone's own internet and reported
     * a server that had never been contacted as healthy.
     */
    @Test
    fun `refuses a blob with nothing to measure through`() {
        runCatching { SingBoxTestConfig.forDelayTest("""{"log":{"level":"info"},"route":{}}""", 1080) }
            .onSuccess { throw AssertionError("a config with no outbound must not measure as direct") }
    }

    @Test
    fun `measures through a bare outbound the operator pasted`() {
        val result = parse(SingBoxTestConfig.forDelayTest(BARE_HYSTERIA, 1080))

        val outbounds = result.getAsJsonArray("outbounds")
        assertEquals(1, outbounds.size())
        assertEquals("hysteria2", outbounds[0].asJsonObject.get("type").asString)
        assertEquals("HY2-XMSH", result.route().get("final").asString)
    }

    private companion object {
        /**
         * What an operator really pastes: one outbound, exactly as a provider
         * hands it over. This shape reached the core verbatim once and failed
         * with `unknown field "type"`.
         */
        const val BARE_HYSTERIA = """
            {
              "type": "hysteria2",
              "tag": "HY2-XMSH",
              "server": "hys.example",
              "server_port": 8443,
              "password": "secret",
              "obfs": {"type": "salamander", "password": "secret"},
              "tls": {"enabled": true, "server_name": "hys.example", "alpn": ["h3"]}
            }
        """

        /** The production test server: a tun that routes straight out. */
        const val TUN_AND_DIRECT = """
            {
              "log": { "level": "info" },
              "inbounds": [
                {"type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30"], "auto_route": true}
              ],
              "outbounds": [{"type": "direct", "tag": "direct"}],
              "route": {"auto_detect_interface": true}
            }
        """

        /** The shape a real server config takes. */
        const val FULL_CONFIG = """
            {
              "log": {"level": "info"},
              "dns": {"servers": [{"type": "local", "tag": "local"}], "rules": [{"outbound": "any", "server": "local"}]},
              "inbounds": [{"type": "tun", "tag": "tun-in", "auto_route": true}],
              "outbounds": [
                {"type": "vless", "tag": "proxy", "server": "a.example", "server_port": 8443},
                {"type": "direct", "tag": "direct"},
                {"type": "block", "tag": "block"}
              ],
              "route": {
                "rules": [{"rule_set": "geoip-ir", "outbound": "direct"}],
                "rule_set": [{"type": "remote", "tag": "geoip-ir", "url": "https://example.invalid/ir.srs"}],
                "final": "proxy",
                "auto_detect_interface": true
              },
              "experimental": {"clash_api": {"external_controller": "127.0.0.1:9090"}}
            }
        """
    }
}
