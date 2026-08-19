package com.rahgozar.app.handler

import com.rahgozar.app.Branding

/**
 * The About/Terms/Privacy links the app shows, as last received from the panel.
 *
 * These are not compiled in. A legal page whose domain gets filtered is worse
 * than no page at all, and correcting one must not require shipping a build and
 * waiting for a store review. The panel owns them; the app caches whatever it
 * was last told.
 *
 * Values arrive with the bootstrap response and are written here by the panel
 * sync. Until that lands, everything is empty and every entry stays hidden —
 * which is the correct behaviour either way, since an empty value means "there
 * is no such page", not "fall back to something".
 */
object AppLinksManager {

    /** One entry the about screen may show. */
    data class Link(val key: String, val url: String)

    /**
     * Replaces the cached set. Keys not present in [links] are cleared, so a
     * link removed in the panel disappears from the app rather than lingering.
     */
    fun store(links: Map<String, String>) {
        for (key in ALL_KEYS) {
            MmkvManager.encodeSettings(prefKey(key), links[key]?.trim().orEmpty())
        }
    }

    /**
     * Returns only the links that actually point somewhere.
     *
     * Empty entries are dropped here rather than at each call site: an about
     * screen that renders a row opening nothing is worse than one row shorter,
     * and Google Play reviewers do click every one of them.
     */
    fun visible(): List<Link> = ALL_KEYS.mapNotNull { key ->
        val url = get(key)
        if (url.isEmpty()) null else Link(key, url)
    }

    fun get(key: String): String =
        MmkvManager.decodeSettingsString(prefKey(key), "").orEmpty().trim()

    private fun prefKey(key: String) = "pref_link_$key"

    /** Order here is the order shown in the about screen. */
    private val ALL_KEYS = listOf(
        Branding.LinkKeys.ABOUT,
        Branding.LinkKeys.FAQ,
        Branding.LinkKeys.SUPPORT,
        Branding.LinkKeys.TERMS,
        Branding.LinkKeys.PRIVACY,
    )
}
