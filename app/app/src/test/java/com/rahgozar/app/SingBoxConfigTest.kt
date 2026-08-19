package com.rahgozar.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rahgozar.app.service.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the panel may store, and what the core must receive.
 *
 * These exist because the gap between the two is invisible at a glance and
 * expensive in practice: a single outbound handed to sing-box as if it were a
 * configuration fails with `unknown field "type"`, and the shape an operator
 * pastes is exactly that single outbound.
 *
 * [SingBoxConfig.forTunnel] reads app settings, so only [SingBoxConfig.normalize]
 * and the parts that do not touch MMKV are exercised here; the tunnel wrapping
 * is covered on the device.
 */
class SingBoxConfigTest {

    private fun parse(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `wraps a single outbound into a configuration`() {
        val config = SingBoxConfig.normalize(BARE_HYSTERIA)

        val outbounds = config.getAsJsonArray("outbounds")
        assertEquals(1, outbounds.size())
        assertEquals("hysteria2", outbounds[0].asJsonObject.get("type").asString)
    }

    @Test
    fun `wraps a list of outbounds`() {
        val config = SingBoxConfig.normalize(
            """[{"type":"vless","tag":"a","server":"a.example","server_port":443}]"""
        )
        assertEquals(1, config.getAsJsonArray("outbounds").size())
    }

    @Test
    fun `leaves a complete configuration alone`() {
        val source = """
            {"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[{"type":"direct","tag":"direct"}]}
        """.trimIndent()

        val config = SingBoxConfig.normalize(source)

        assertEquals(1, config.getAsJsonArray("inbounds").size())
        assertEquals("direct", config.getAsJsonArray("outbounds")[0].asJsonObject.get("type").asString)
    }

    @Test
    fun `treats a config carrying only a route section as complete`() {
        val config = SingBoxConfig.normalize("""{"route":{"final":"proxy"}}""")
        assertTrue(config.has("route"))
    }

    @Test
    fun `refuses a blob that is neither a configuration nor an outbound`() {
        runCatching { SingBoxConfig.normalize("""{"remarks":"my server","password":"x"}""") }
            .onSuccess { throw AssertionError("an unrecognisable blob must be refused") }
    }

    @Test
    fun `refuses a selector pasted on its own`() {
        // It names outbounds that were never included, so it can select nothing.
        runCatching { SingBoxConfig.normalize("""{"type":"selector","tag":"auto","outbounds":["a"]}""") }
            .onSuccess { throw AssertionError("a lone group must be refused") }
    }

    @Test
    fun `refuses text that is not JSON`() {
        runCatching { SingBoxConfig.normalize("vless://not-a-json-link") }
            .onSuccess { throw AssertionError("a share link is not a sing-box configuration") }
    }

    @Test
    fun `finds the address in a bare outbound`() {
        assertEquals("hys.example" to 8443, SingBoxConfig.endpointOf(BARE_HYSTERIA))
    }

    @Test
    fun `has no address to offer for a direct-only config`() {
        assertNull(
            SingBoxConfig.endpointOf("""{"outbounds":[{"type":"direct","tag":"direct"}]}""")
        )
    }

    private companion object {
        const val BARE_HYSTERIA = """
            {
              "type": "hysteria2",
              "tag": "HY2-XMSH",
              "server": "hys.example",
              "server_port": 8443,
              "password": "secret",
              "tls": {"enabled": true, "server_name": "hys.example", "alpn": ["h3"]}
            }
        """
    }
}
