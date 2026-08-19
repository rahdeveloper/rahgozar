package com.rahgozar.app.panel

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * Every ad format the panel can select.
 *
 * The app has to accept whatever the panel is set to, so this list mirrors the
 * `ad_format` enum in the database exactly. [UNKNOWN] is the important one: a
 * panel that gains a format before this build knows about it must produce a
 * placement that is quietly *off*, never a crash and never a wrong ad type in
 * the wrong place.
 */
enum class AdFormat(val wire: String) {
    APP_OPEN("app_open"),
    INTERSTITIAL("interstitial"),
    REWARDED("rewarded"),
    REWARDED_INTERSTITIAL("rewarded_interstitial"),
    BANNER("banner"),
    NATIVE("native"),
    UNKNOWN("");

    /** True for formats that take over the screen and need a foreground Activity. */
    val isFullScreen: Boolean
        get() = this == APP_OPEN || this == INTERSTITIAL ||
            this == REWARDED || this == REWARDED_INTERSTITIAL

    /** True for formats that are laid out inside existing content. */
    val isInline: Boolean get() = this == BANNER || this == NATIVE

    /** True for formats that hand the user something in exchange. */
    val isRewarded: Boolean get() = this == REWARDED || this == REWARDED_INTERSTITIAL

    companion object {
        fun from(wire: String?): AdFormat =
            entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/** Where an ad can appear. Mirrors the `ad_slot` enum. */
enum class AdSlot(val wire: String) {
    SPLASH("splash"),

    /**
     * The ad the splash loads *behind* the first one and the home screen shows
     * on the user's first action.
     *
     * Its own placement because it is its own decision: an operator may want
     * the splash ad and not a second one. Switched off, the splash closes its
     * session as soon as the first ad is dismissed instead of holding the
     * tunnel open for an ad that will never be asked for.
     */
    SPLASH_SECOND("splash_second"),

    CONNECT("connect"),
    DISCONNECT("disconnect"),

    /** Inside the server list. Exact position is a UI decision, still open. */
    SERVER_LIST("server_list"),

    /**
     * The rewarded ad that buys the user more connection time.
     *
     * The only placement the user chooses to see. It is shown over their own
     * live tunnel, so its `require_smart` must stay off — Android runs one VPN
     * at a time, and bringing the smart tunnel up here would drop the
     * connection being extended.
     */
    EXTEND_SESSION("extend_session"),

    UNKNOWN("");

    companion object {
        fun from(wire: String?): AdSlot =
            entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/**
 * One placement as configured in the panel.
 *
 * [enabled] already folds in every reason not to show: the panel refuses to
 * enable a placement without an ad unit, and [AdsConfig.placement] switches off
 * anything whose format this build does not understand. A caller can treat
 * `enabled` as the whole answer.
 */
data class AdPlacement(
    val slot: AdSlot,
    val enabled: Boolean,
    val adUnitId: String,
    val format: AdFormat,
    val loadTimeoutMs: Int,
    val minIntervalSec: Int,
    /** Bring up the Smart tunnel before requesting a fill. */
    val requireSmart: Boolean,
    /** Per-placement extras the panel may add later; unread by this build. */
    val params: JsonObject,
) {
    companion object {
        fun disabled(slot: AdSlot) = AdPlacement(
            slot = slot,
            enabled = false,
            adUnitId = "",
            format = AdFormat.UNKNOWN,
            loadTimeoutMs = 8000,
            minIntervalSec = 0,
            requireSmart = true,
            params = JsonObject(),
        )
    }
}

/**
 * The ad configuration for this device, exactly as the panel set it.
 *
 * Nothing here is decided in the app. Which formats, which units, which
 * placements, test mode, even whether ads exist at all — all of it is panel
 * state, so a placement can be turned off, re-formatted or re-pointed without
 * a release. The app's job is to honour it and to fail closed when it cannot.
 */
class AdsConfig private constructor(
    val enabled: Boolean,
    val adMobAppId: String,
    val testMode: Boolean,
    val testDeviceIds: List<String>,
    val params: JsonObject,
    private val placements: Map<AdSlot, AdPlacement>,
) {
    /**
     * The placement for a slot, or a disabled one.
     *
     * Never null: a caller asking "should I show an ad here" gets an answer,
     * not a branch to forget.
     */
    fun placement(slot: AdSlot): AdPlacement =
        placements[slot]?.takeIf { enabled } ?: AdPlacement.disabled(slot)

    /** True when this slot should show an ad on this device right now. */
    fun isActive(slot: AdSlot): Boolean = placement(slot).enabled

    /** Every configured placement, for diagnostics and the settings screen. */
    fun all(): List<AdPlacement> = AdSlot.entries
        .filter { it != AdSlot.UNKNOWN }
        .map { placement(it) }

    companion object {
        fun disabled() = AdsConfig(
            enabled = false,
            adMobAppId = "",
            testMode = false,
            testDeviceIds = emptyList(),
            params = JsonObject(),
            placements = emptyMap(),
        )

        fun parse(json: String?): AdsConfig = try {
            from(json?.let { JsonParser.parseString(it) })
        } catch (e: JsonSyntaxException) {
            disabled()
        }

        fun from(element: JsonElement?): AdsConfig {
            val root = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return disabled()

            val appId = root.stringOr("admob_app_id")
            // The panel applies this rule too, but it is cheap to repeat and
            // the consequence of getting it wrong is the app waiting on an SDK
            // that never initialised.
            val enabled = root.boolOr("enabled") && appId.isNotBlank()

            val placements = mutableMapOf<AdSlot, AdPlacement>()
            root.get("placements")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
                ?.forEach { (key, value) ->
                    val slot = AdSlot.from(key)
                    // A slot this build does not know is not an error — it is a
                    // panel that moved ahead. Skip it and carry on.
                    if (slot == AdSlot.UNKNOWN) return@forEach
                    val obj = value?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach

                    val format = AdFormat.from(obj.stringOr("format"))
                    val unit = obj.stringOr("ad_unit_id")
                    placements[slot] = AdPlacement(
                        slot = slot,
                        // Three independent reasons to stay off, folded here so
                        // no call site has to remember them.
                        enabled = obj.boolOr("enabled") &&
                            unit.isNotBlank() &&
                            format != AdFormat.UNKNOWN,
                        adUnitId = unit,
                        format = format,
                        loadTimeoutMs = obj.intOr("load_timeout_ms", 8000).coerceIn(1000, 60_000),
                        minIntervalSec = obj.intOr("min_interval_sec", 0).coerceAtLeast(0),
                        requireSmart = obj.boolOr("require_smart", true),
                        params = obj.objectOr("params"),
                    )
                }

            return AdsConfig(
                enabled = enabled,
                adMobAppId = appId,
                testMode = root.boolOr("test_mode"),
                testDeviceIds = root.stringListOr("test_device_ids"),
                params = root.objectOr("params"),
                placements = placements,
            )
        }

        // --- lenient readers -------------------------------------------------
        // Everything below returns a default rather than throwing. This payload
        // is verified by signature before it gets here, so a surprising shape
        // means a panel newer than this build, not an attacker.

        // The exact JSON type is required, not coerced: Gson turns the string
        // "yes" into the boolean false, which would quietly clear
        // require_smart and send ad traffic outside the tunnel.
        private fun JsonObject.boolOr(key: String, default: Boolean = false) =
            get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isBoolean }?.asBoolean ?: default

        private fun JsonObject.intOr(key: String, default: Int = 0) =
            get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isNumber }?.runCatching { asInt }?.getOrNull() ?: default

        private fun JsonObject.stringOr(key: String, default: String = "") =
            get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isString }?.asString ?: default

        private fun JsonObject.objectOr(key: String) =
            get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

        private fun JsonObject.stringListOr(key: String): List<String> =
            get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.runCatching { asString }.getOrNull()?.takeIf(String::isNotBlank) }
                ?: emptyList()
    }
}
