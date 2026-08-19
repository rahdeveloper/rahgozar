package com.rahgozar.app.service

/**
 * Makes an OpenVPN 2.x profile digestible by the openvpn3 core.
 *
 * openvpn3 is strict: `cliopt.hpp` collects every directive it did not consume
 * and reports them as `UNKNOWN/UNSUPPORTED OPTIONS`, **fatally**. Some of these
 * are options OpenVPN 2 accepted and openvpn3 simply never implemented, and a
 * provider's file routinely contains one or two of them. Refusing to connect
 * over a directive that tunes a keepalive timer would be a bad trade.
 *
 * So a small, explicit set is removed. The rule for what may appear in that set
 * is deliberately narrow: **it must not be able to change what the tunnel
 * carries, where it goes, or how it is encrypted.** Anything touching ciphers,
 * certificates, routes, DNS or the remote list is left alone, so a profile that
 * would connect insecurely still fails loudly instead of being quietly fixed.
 *
 * Nothing is guessed at runtime: options are dropped only if they are named
 * here. If a new provider's file trips the core, the error names the directive
 * and it can be considered on its merits — which is safer than stripping
 * whatever the core happened to complain about.
 */
object OpenVpnProfile {

    /**
     * Directives openvpn3 does not implement, all of which only affect local
     * timing or bookkeeping.
     *
     * * `ping-timer-rem` — start the ping timer only once a remote is known.
     * * `explicit-exit-notify` — send a goodbye datagram on disconnect.
     * * `resolv-retry` — how long to retry a failed DNS lookup for the remote.
     *
     * openvpn3 handles reconnection and name resolution its own way, so on this
     * core all three describe behaviour that is not configurable rather than
     * behaviour that is being turned off.
     */
    private val UNSUPPORTED = setOf(
        "ping-timer-rem",
        "explicit-exit-notify",
        "resolv-retry",
    )

    /** What [sanitise] did, so the caller can log it. */
    data class Result(val profile: String, val removed: List<String>)

    fun sanitise(profile: String): Result {
        val removed = mutableListOf<String>()
        val kept = mutableListOf<String>()
        // Inline blocks hold certificates and keys. Their contents are base64
        // and comments, never directives, and must be copied through untouched
        // — a single dropped line would corrupt a key.
        var insideInlineBlock = false

        // split(), not lineSequence() with appendLine(): rejoining the kept
        // lines reproduces the original byte for byte, including whether it
        // ended with a newline. Appending as we go quietly added one.
        profile.split('\n').forEach { line ->
            val trimmed = line.trim()

            if (insideInlineBlock) {
                if (trimmed.startsWith("</")) insideInlineBlock = false
                kept += line
                return@forEach
            }
            if (trimmed.startsWith("<") && !trimmed.startsWith("</")) {
                insideInlineBlock = true
                kept += line
                return@forEach
            }

            val directive = trimmed.substringBefore(' ').substringBefore('\t')
            if (directive in UNSUPPORTED) {
                removed += trimmed
            } else {
                kept += line
            }
        }

        return Result(kept.joinToString("\n"), removed)
    }
}
