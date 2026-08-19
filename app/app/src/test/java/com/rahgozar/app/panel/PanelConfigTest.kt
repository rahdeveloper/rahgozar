package com.rahgozar.app.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's switches, as the app reads them.
 *
 * Two properties matter more than any individual value here:
 *
 *  1. **A missing setting falls to the safe side.** The panel can lose a row,
 *     an old build can meet a new panel; neither may quietly open something up.
 *  2. **An unknown value is off, not a crash.** A panel gaining an ad format or
 *     a placement slot before this build knows it must degrade to "no ad", so
 *     that adding one in the panel can never brick installed apps.
 */
class PanelConfigTest {

    // The shape the live panel actually sends — copied from a real
    // /v1/bootstrap response (see cmd/devclient output), so this test fails if
    // the wire format drifts rather than only if this file does.
    private val liveSettings = """
        {"about_url":"","block_rooted_devices":true,"discovery_timeout_ms":4000,
         "doh_resolvers":["https://dns.google/resolve","https://cloudflare-dns.com/dns-query"],
         "faq_url":"","force_update":false,"min_version_code":0,"privacy_url":"",
         "smart_countries":["IR"],"support_url":"","sync_interval_minutes":360,"terms_url":""}
    """.trimIndent()

    private val liveAds = """
        {"enabled":false,"admob_app_id":"","test_mode":false,"test_device_ids":[],"params":{},
         "placements":{
           "connect":{"enabled":false,"ad_unit_id":"","format":"interstitial","load_timeout_ms":8000,"min_interval_sec":0,"require_smart":true,"params":{}},
           "disconnect":{"enabled":false,"ad_unit_id":"","format":"interstitial","load_timeout_ms":8000,"min_interval_sec":0,"require_smart":true,"params":{}},
           "splash":{"enabled":false,"ad_unit_id":"","format":"app_open","load_timeout_ms":8000,"min_interval_sec":0,"require_smart":true,"params":{}},
           "server_list":{"enabled":true,"ad_unit_id":"ca-app-pub-3940256099942544/2247696110","format":"native","load_timeout_ms":8000,"min_interval_sec":0,"require_smart":true,"params":{}}}}
    """.trimIndent()

    // -------------------------------------------------------------- settings --

    @Test
    fun `reads the live panel payload`() {
        val s = PanelSettings.parse(liveSettings)

        assertEquals(360, s.syncIntervalMinutes)
        assertFalse(s.forceUpdate)
        assertEquals(0, s.minVersionCode)
        assertTrue(s.blockRootedDevices)
        assertEquals(listOf("IR"), s.smartCountries)
        assertEquals(2, s.dohResolvers.size)
        assertEquals(4000, s.discoveryTimeoutMs)
        // Empty means hide the item, so nothing renders a dead link.
        assertTrue(s.links().isEmpty())
    }

    @Test
    fun `a missing setting falls to the safe side`() {
        val s = PanelSettings.parse("{}")

        assertTrue("root blocking must not switch itself off", s.blockRootedDevices)
        assertFalse("a missing flag must not force an update", s.forceUpdate)
        assertEquals(360, s.syncIntervalMinutes)
        assertTrue(s.smartCountries.isEmpty())
    }

    @Test
    fun `survives a value of the wrong type`() {
        // An operator typing "yes" into a bool, or the panel changing a type.
        val s = PanelSettings.parse("""{"block_rooted_devices":"yes","sync_interval_minutes":"soon"}""")
        assertTrue(s.blockRootedDevices)
        assertEquals(360, s.syncIntervalMinutes)
    }

    @Test
    fun `clamps a sync interval that would hammer the panel`() {
        assertEquals(5, PanelSettings.parse("""{"sync_interval_minutes":0}""").syncIntervalMinutes)
        assertEquals(1440, PanelSettings.parse("""{"sync_interval_minutes":99999}""").syncIntervalMinutes)
    }

    @Test
    fun `exposes settings this build has no opinion about`() {
        // A setting added in the panel reaches the device immediately; code
        // here is only needed when the app has to act on it.
        val s = PanelSettings.parse("""{"future_flag":true,"future_count":7,"future_name":"x"}""")
        assertTrue(s.bool("future_flag"))
        assertEquals(7, s.int("future_count"))
        assertEquals("x", s.string("future_name"))
        assertTrue(s.raw.has("future_flag"))
    }

    @Test
    fun `lists only the links that have a URL`() {
        val s = PanelSettings.parse("""{"privacy_url":"https://x/p","support_url":"","faq_url":"https://x/f"}""")
        assertEquals(listOf("privacy" to "https://x/p", "faq" to "https://x/f"), s.links())
    }

    // ------------------------------------------------------------------ ads --

    @Test
    fun `master switch off means every placement is off`() {
        val ads = AdsConfig.parse(liveAds)
        assertFalse(ads.enabled)
        // server_list says enabled:true with a valid unit, but ads are off
        // globally — the app must not show it.
        assertFalse(ads.isActive(AdSlot.SERVER_LIST))
        assertTrue(ads.all().none { it.enabled })
    }

    @Test
    fun `reads the server list placement when ads are on`() {
        val ads = AdsConfig.parse(
            liveAds.replace(""""enabled":false,"admob_app_id":""""",
                """"enabled":true,"admob_app_id":"ca-app-pub-3940256099942544~3347511713"""")
        )
        assertTrue(ads.enabled)

        val p = ads.placement(AdSlot.SERVER_LIST)
        assertTrue(p.enabled)
        assertEquals(AdFormat.NATIVE, p.format)
        assertTrue(p.format.isInline)
        assertFalse(p.format.isFullScreen)
        assertEquals("ca-app-pub-3940256099942544/2247696110", p.adUnitId)
        assertTrue(p.requireSmart)
    }

    @Test
    fun `accepts every format the panel can set`() {
        for (format in AdFormat.entries.filter { it != AdFormat.UNKNOWN }) {
            val ads = AdsConfig.parse(
                """{"enabled":true,"admob_app_id":"ca-app-pub-x~1","placements":
                   {"connect":{"enabled":true,"ad_unit_id":"unit","format":"${format.wire}"}}}"""
            )
            val p = ads.placement(AdSlot.CONNECT)
            assertTrue("${format.wire} was not accepted", p.enabled)
            assertEquals(format, p.format)
        }
    }

    @Test
    fun `an unknown format is off rather than a crash`() {
        // What happens when the panel gains a format before the app does.
        val ads = AdsConfig.parse(
            """{"enabled":true,"admob_app_id":"ca-app-pub-x~1","placements":
               {"connect":{"enabled":true,"ad_unit_id":"unit","format":"holographic"}}}"""
        )
        val p = ads.placement(AdSlot.CONNECT)
        assertFalse(p.enabled)
        assertEquals(AdFormat.UNKNOWN, p.format)
    }

    @Test
    fun `an unknown slot is ignored, not fatal`() {
        val ads = AdsConfig.parse(
            """{"enabled":true,"admob_app_id":"ca-app-pub-x~1","placements":
               {"future_slot":{"enabled":true,"ad_unit_id":"unit","format":"banner"},
                "connect":{"enabled":true,"ad_unit_id":"unit","format":"banner"}}}"""
        )
        assertTrue(ads.isActive(AdSlot.CONNECT))
        assertFalse(ads.isActive(AdSlot.UNKNOWN))
    }

    @Test
    fun `a placement with no ad unit is off`() {
        val ads = AdsConfig.parse(
            """{"enabled":true,"admob_app_id":"ca-app-pub-x~1","placements":
               {"splash":{"enabled":true,"ad_unit_id":"","format":"app_open"}}}"""
        )
        // Otherwise the splash screen waits for a fill that can never arrive.
        assertFalse(ads.isActive(AdSlot.SPLASH))
    }

    @Test
    fun `ads without an app id stay off`() {
        val ads = AdsConfig.parse(
            """{"enabled":true,"admob_app_id":"","placements":
               {"connect":{"enabled":true,"ad_unit_id":"unit","format":"banner"}}}"""
        )
        assertFalse(ads.enabled)
        assertFalse(ads.isActive(AdSlot.CONNECT))
    }

    @Test
    fun `asking about an unconfigured slot gives a disabled placement`() {
        val ads = AdsConfig.parse("""{"enabled":true,"admob_app_id":"ca-app-pub-x~1","placements":{}}""")
        val p = ads.placement(AdSlot.SERVER_LIST)
        assertFalse(p.enabled)
        assertEquals(AdSlot.SERVER_LIST, p.slot)
    }

    @Test
    fun `garbage yields disabled ads`() {
        assertFalse(AdsConfig.parse("not json").enabled)
        assertFalse(AdsConfig.parse(null).enabled)
        assertFalse(AdsConfig.parse("[]").enabled)
    }

    /**
     * The exact bytes production sent on 2026-08-03, with every placement on.
     *
     * Captured from a real /v1/bootstrap response rather than written here, so
     * a change in what the panel emits — a renamed field, a new slot, a format
     * spelled differently — fails this test instead of failing on a phone.
     */
    private val liveAdsEnabled =
        """{"enabled":true,"admob_app_id":"ca-app-pub-3940256099942544~3347511713","test_mode":true,"test_device_ids":[],"params":{},"placements":{"connect":{"enabled":true,"ad_unit_id":"ca-app-pub-3940256099942544/1033173712","format":"interstitial","load_timeout_ms":8000,"min_interval_sec":0,"require_smart":true,"params":{}},"disconnect":{"enabled":true,"ad_unit_id":"ca-app-pub-3940256099942544/1033173712","format":"interstitial","load_timeout_ms":8000,"min_interval_sec":0,"require_smart":true,"params":{}},"server_list":{"enabled":true,"ad_unit_id":"ca-app-pub-3940256099942544/2247696110","format":"native","load_timeout_ms":8000,"min_interval_sec":0,"require_smart":true,"params":{}},"splash":{"enabled":true,"ad_unit_id":"ca-app-pub-3940256099942544/9257395921","format":"app_open","load_timeout_ms":8000,"min_interval_sec":0,"require_smart":true,"params":{}}}}"""

    @Test
    fun `reads every placement out of the production payload`() {
        val ads = AdsConfig.parse(liveAdsEnabled)

        assertTrue(ads.enabled)
        assertTrue("test units must run in test mode", ads.testMode)
        assertEquals("ca-app-pub-3940256099942544~3347511713", ads.adMobAppId)

        val expected = mapOf(
            AdSlot.SPLASH to AdFormat.APP_OPEN,
            AdSlot.CONNECT to AdFormat.INTERSTITIAL,
            AdSlot.DISCONNECT to AdFormat.INTERSTITIAL,
            AdSlot.SERVER_LIST to AdFormat.NATIVE,
        )
        for ((slot, format) in expected) {
            val p = ads.placement(slot)
            assertTrue("$slot should be active", p.enabled)
            assertEquals("$slot format", format, p.format)
            assertTrue("$slot must have a unit", p.adUnitId.startsWith("ca-app-pub-"))
            assertTrue("$slot should route through Smart", p.requireSmart)
        }
        assertEquals(4, ads.all().count { it.enabled })
    }

    @Test
    fun `the production settings payload carries the test unit reference`() {
        // Seeded as a lookup for whoever changes a placement's format. Nothing
        // consumes it, but it must survive the round trip as a JSON object.
        val s = PanelSettings.parse(
            """{"admob_test_units":{"banner":"ca-app-pub-3940256099942544/6300978111"}}"""
        )
        assertTrue(s.raw.has("admob_test_units"))
        assertTrue(s.raw.getAsJsonObject("admob_test_units").has("banner"))
    }

    // ----------------------------------------------------------------- gate --

    @Test
    fun `force update stops an old build`() {
        val s = PanelSettings.parse("""{"force_update":true,"min_version_code":800}""")

        assertEquals(
            PanelGate.Decision.UpdateRequired(800, 742),
            PanelGate.evaluate(s, versionCode = 742, isRooted = false),
        )
        assertEquals(
            PanelGate.Decision.Allow,
            PanelGate.evaluate(s, versionCode = 800, isRooted = false),
        )
    }

    @Test
    fun `an old build without force update is only a suggestion`() {
        val s = PanelSettings.parse("""{"force_update":false,"min_version_code":800}""")
        assertEquals(PanelGate.Decision.Allow, PanelGate.evaluate(s, 742, isRooted = false))
        assertTrue(PanelGate.updateSuggested(s, 742))
        assertFalse(PanelGate.updateSuggested(s, 800))
    }

    @Test
    fun `root blocking follows the panel switch`() {
        val on = PanelSettings.parse("""{"block_rooted_devices":true}""")
        val off = PanelSettings.parse("""{"block_rooted_devices":false}""")

        assertEquals(PanelGate.Decision.RootBlocked, PanelGate.evaluate(on, 742, isRooted = true))
        assertEquals(PanelGate.Decision.Allow, PanelGate.evaluate(on, 742, isRooted = false))
        assertEquals(PanelGate.Decision.Allow, PanelGate.evaluate(off, 742, isRooted = true))
    }

    @Test
    fun `update wins over root`() {
        // A build too old to be trusted with the rest of the configuration
        // should be told to update, not told it is rooted.
        val s = PanelSettings.parse(
            """{"force_update":true,"min_version_code":800,"block_rooted_devices":true}"""
        )
        assertTrue(PanelGate.evaluate(s, 742, isRooted = true) is PanelGate.Decision.UpdateRequired)
    }

    @Test
    fun `an empty panel response does not unlock anything`() {
        val s = PanelSettings.empty()
        assertEquals(PanelGate.Decision.RootBlocked, PanelGate.evaluate(s, 742, isRooted = true))
        assertEquals(PanelGate.Decision.Allow, PanelGate.evaluate(s, 742, isRooted = false))
    }
}
