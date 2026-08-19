package com.rahgozar.app.panel

import com.rahgozar.app.AppConfig
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.util.LogUtil

/**
 * The core options the panel owns, written into the preferences the core reads.
 *
 * These used to be rows on the settings screen. They are the ones where a wrong
 * value produces a connection that half works — fragment lengths that no longer
 * evade, a MUX concurrency that stalls, a DNS server that resolves nothing —
 * and the operator watching the servers is in a far better position to choose
 * them than the person holding the phone. So the screen dropped them and this
 * took over.
 *
 * Two rules make that safe:
 *
 *  - **Absent is not false.** Only keys the panel actually sent are written.
 *    A panel that says nothing about fragmentation leaves whatever the device
 *    has, rather than switching it off, which is what reading a missing key as
 *    its default would do on every launch.
 *  - **The core still reads MMKV.** Nothing downstream knows the panel exists;
 *    it reads the same keys it always did. That keeps this a single place to
 *    look when a device behaves differently from the panel's intent.
 */
object TunnelSettings {

    private const val TAG = AppConfig.TAG

    /** Panel key to preference key, for the values that are plain booleans. */
    private val BOOLEANS = mapOf(
        "tunnel_mux_enabled" to AppConfig.PREF_MUX_ENABLED,
        "tunnel_fragment_enabled" to AppConfig.PREF_FRAGMENT_ENABLED,
        "tunnel_ipv6_enabled" to AppConfig.PREF_IPV6_ENABLED,
        "tunnel_prefer_ipv6" to AppConfig.PREF_PREFER_IPV6,
        "tunnel_sniffing_enabled" to AppConfig.PREF_SNIFFING_ENABLED,
        "tunnel_route_only" to AppConfig.PREF_ROUTE_ONLY_ENABLED,
        "tunnel_local_dns_enabled" to AppConfig.PREF_LOCAL_DNS_ENABLED,
        "tunnel_fake_dns_enabled" to AppConfig.PREF_FAKE_DNS_ENABLED,
    )

    /**
     * Panel key to preference key, for values the core wants as a string.
     *
     * MUX concurrency is here rather than in [NUMBERS] because the core parses
     * it back out of a string; sending it as a JSON number and storing "8.0"
     * would throw where it is read.
     */
    private val STRINGS = mapOf(
        "tunnel_fragment_packets" to AppConfig.PREF_FRAGMENT_PACKETS,
        "tunnel_fragment_length" to AppConfig.PREF_FRAGMENT_LENGTH,
        "tunnel_fragment_interval" to AppConfig.PREF_FRAGMENT_INTERVAL,
        "tunnel_fragment_maxsplit" to AppConfig.PREF_FRAGMENT_MAXSPLIT,
        "tunnel_log_level" to AppConfig.PREF_LOGLEVEL,
        "tunnel_remote_dns" to AppConfig.PREF_REMOTE_DNS,
        "tunnel_domestic_dns" to AppConfig.PREF_DOMESTIC_DNS,
        "tunnel_dns_hosts" to AppConfig.PREF_DNS_HOSTS,
        // The routing screen's domain strategy is deliberately absent: the user
        // cycles it there, and a panel key writing the same preference would
        // undo their choice on the next launch.
        "tunnel_mtu" to AppConfig.PREF_VPN_MTU,
    )

    /** Numbers the panel sends as numbers and the core reads back as strings. */
    private val NUMBERS = mapOf(
        "tunnel_mux_concurrency" to AppConfig.PREF_MUX_CONCURRENCY,
        "tunnel_mux_xudp_concurrency" to AppConfig.PREF_MUX_XUDP_CONCURRENCY,
    )

    /**
     * @return how many values were written, for the log line — a panel change
     *   that reaches no device looks identical to one that was never made.
     */
    fun apply(settings: PanelSettings): Int {
        val json = settings.raw
        var written = 0

        for ((panelKey, prefKey) in BOOLEANS) {
            val value = json.get(panelKey)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isBoolean }?.asBoolean ?: continue
            MmkvManager.encodeSettings(prefKey, value)
            written++
        }

        for ((panelKey, prefKey) in STRINGS) {
            val value = json.get(panelKey)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isString }?.asString ?: continue
            // An empty string is a real instruction — "use no explicit value" —
            // so it is written rather than skipped.
            MmkvManager.encodeSettings(prefKey, value)
            written++
        }

        for ((panelKey, prefKey) in NUMBERS) {
            val value = json.get(panelKey)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isNumber }?.runCatching { asInt }?.getOrNull() ?: continue
            MmkvManager.encodeSettings(prefKey, value.toString())
            written++
        }

        if (written > 0) LogUtil.i(TAG, "panel: $written tunnel settings applied")
        return written
    }
}
