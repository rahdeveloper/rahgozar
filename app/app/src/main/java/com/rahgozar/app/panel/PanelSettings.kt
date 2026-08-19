package com.rahgozar.app.panel

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * Everything the panel's settings screen controls, as the app sees it.
 *
 * The panel serialises `app_settings` wholesale — every row, whatever it is —
 * so this class is a *view* over that map rather than a fixed schema. Two
 * consequences worth keeping in mind before changing it:
 *
 *  - A setting added in the panel reaches every installed device on its next
 *    launch. It only needs code here when the app has to *act* on it; until
 *    then it is readable through [raw], [bool], [int] and [string].
 *  - A setting the panel stops sending falls back to the default below rather
 *    than to null. Defaults are chosen so that a missing value is the safe
 *    direction, not the permissive one — `blockRootedDevices` defaults to true,
 *    `forceUpdate` to false.
 *
 * Nothing here is trusted for security on its own: the panel enforces its own
 * rules server-side. These are the switches the *client* has to honour because
 * only the client can (there is no server-side way to notice a rooted device).
 */
class PanelSettings private constructor(private val json: JsonObject) {

    // ---------------------------------------------------------------- sync --

    /** How often the app should re-read configuration from the panel. */
    val syncIntervalMinutes: Int get() = int("sync_interval_minutes", 360).coerceIn(5, 24 * 60)

    // -------------------------------------------------------------- update --

    /** When true, builds below [minVersionCode] must stop and ask for an update. */
    val forceUpdate: Boolean get() = bool("force_update", false)

    val minVersionCode: Int get() = int("min_version_code", 0)

    // ------------------------------------------------------------ security --

    /**
     * When true the app must not hand a configuration to a rooted device.
     *
     * Defaults to true: if the panel ever stops sending this key, the app keeps
     * the stricter behaviour rather than silently opening up.
     */
    val blockRootedDevices: Boolean get() = bool("block_rooted_devices", true)

    // ------------------------------------------------------------- session --

    /**
     * How long one connection may run before the app cuts it, in minutes.
     *
     * Zero — the default, and what a panel that has never heard of this sends
     * — means no limit at all. That is deliberately the fallback: a device
     * that cannot read this setting must not start disconnecting people.
     */
    val sessionDurationMinutes: Int get() = int("session_duration_minutes", 0).coerceAtLeast(0)

    /**
     * How many times one connection may be extended by watching an ad.
     * Zero means no limit; each extension is worth [sessionDurationMinutes].
     */
    val sessionMaxExtensions: Int get() = int("session_max_extensions", 0).coerceAtLeast(0)

    /** True when the panel is running timed sessions at all. */
    val sessionLimited: Boolean get() = sessionDurationMinutes > 0

    // --------------------------------------------------------------- smart --

    /** ISO-3166-1 alpha-2 codes whose devices route ad traffic through Smart. */
    val smartCountries: List<String>
        get() = stringList("smart_countries").map { it.uppercase() }

    // ----------------------------------------------------------- discovery --

    val dohResolvers: List<String> get() = stringList("doh_resolvers")

    val discoveryTimeoutMs: Int get() = int("discovery_timeout_ms", 4000).coerceIn(1000, 30_000)

    // --------------------------------------------------------------- links --

    /**
     * An empty URL means "hide this item", never "show a dead link" — which is
     * why these are plain strings with an emptiness check at the call site
     * rather than nullable.
     */
    val privacyUrl: String get() = string("privacy_url")
    val termsUrl: String get() = string("terms_url")
    val aboutUrl: String get() = string("about_url")
    val supportUrl: String get() = string("support_url")
    val faqUrl: String get() = string("faq_url")

    /** Every link the app should show, in menu order, skipping the empty ones. */
    fun links(): List<Pair<String, String>> = listOf(
        "privacy" to privacyUrl,
        "terms" to termsUrl,
        "about" to aboutUrl,
        "support" to supportUrl,
        "faq" to faqUrl,
    ).filter { it.second.isNotBlank() }

    // ------------------------------------------------------------- generic --

    /** The untouched map, for settings this build has no opinion about yet. */
    val raw: JsonObject get() = json

    /**
     * The type checks are not pedantry. Gson coerces a JSON string to a
     * boolean by calling [String.toBoolean], so a value of `"yes"` in a bool
     * setting reads as **false** — which for `block_rooted_devices` would
     * silently switch a protection off. Requiring the real JSON type means a
     * mistyped setting falls back to the declared default instead.
     */
    fun bool(key: String, default: Boolean = false): Boolean =
        primitive(key)?.takeIf { it.isBoolean }?.asBoolean ?: default

    fun int(key: String, default: Int = 0): Int =
        primitive(key)?.takeIf { it.isNumber }?.runCatching { asInt }?.getOrNull() ?: default

    fun string(key: String, default: String = ""): String =
        primitive(key)?.takeIf { it.isString }?.asString ?: default

    fun stringList(key: String): List<String> {
        val array = json.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            element.runCatching { asString }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun primitive(key: String): com.google.gson.JsonPrimitive? =
        json.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive

    companion object {
        /** Empty settings: every value falls back to its default. */
        fun empty() = PanelSettings(JsonObject())

        fun from(element: JsonElement?): PanelSettings =
            PanelSettings(element?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject())

        /** Parses the `settings` object out of a bootstrap payload. */
        fun parse(json: String?): PanelSettings = try {
            from(json?.let { JsonParser.parseString(it) })
        } catch (e: JsonSyntaxException) {
            empty()
        }
    }
}
