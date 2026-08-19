package com.rahgozar.app

/**
 * Every piece of brand identity in one place.
 *
 * The upstream project scattered its name, repository links and policy URLs
 * across a dozen files, which is how a fork ends up shipping with someone
 * else's issue tracker in its about screen. Anything brand-specific belongs
 * here so a rename is one file, not a search-and-replace across the tree.
 *
 * Note what is *not* here: the panel address, the About and Terms links, and
 * anything else an operator might need to change after release. Those come
 * from the panel at runtime — a legal page on a filtered domain is worse than
 * no page at all, and reaching it must not require shipping a new build.
 */
object Branding {

    /** Shown in logs and crash reports. The display name lives in strings.xml. */
    const val NAME = "Rahgozar"

    /**
     * The package. Read from BuildConfig rather than written out, so it can
     * never drift from what the build actually produced.
     *
     * This value is permanent once the app is on Google Play: the store keys
     * every install off it and it cannot be changed afterwards.
     */
    val PACKAGE: String = BuildConfig.APPLICATION_ID

    /**
     * Where the source for this build is published.
     *
     * The app is a v2rayNG fork and therefore GPLv3, which obliges us to give
     * every recipient the *corresponding source* — the source this exact
     * binary was built from. So unlike every other link in the app, this one
     * is compiled in rather than supplied by the panel: an operator-editable
     * link could be pointed elsewhere or emptied, and the obligation would
     * lapse without anyone noticing.
     *
     * The release build refuses to run while this is still the placeholder —
     * see the guard in `app/build.gradle.kts`. Changing it means regenerating
     * `assets/open_source_licenses.html`, which repeats the same URL.
     */
    const val SOURCE_URL = "https://github.com/rahdeveloper/rahgozar"

    /**
     * Keys under which runtime links arrive from the panel's app settings.
     *
     * An empty value means the corresponding menu entry is hidden. That is a
     * real state, not a fallback: showing an item that opens nothing is worse
     * than not showing it, and Google Play reviewers do click these.
     */
    object LinkKeys {
        /** Required for a Play listing; a VPN app will not pass review without it. */
        const val PRIVACY = "privacy_url"

        const val TERMS = "terms_url"
        const val ABOUT = "about_url"
        const val SUPPORT = "support_url"
        const val FAQ = "faq_url"
    }
}
