package com.rahgozar.app.ui.brand

/**
 * Turns an ISO-3166-1 alpha-2 code into its flag.
 *
 * Regional indicator symbols rather than bundled images: 250-odd SVGs would add
 * more to the APK than the whole rest of this UI, and the system font already
 * draws them. A code the font has no flag for renders as two letters, which is
 * a reasonable thing to see rather than a broken glyph.
 */
fun countryFlag(code: String?): String {
    val cc = code?.trim()?.uppercase().orEmpty()
    if (cc.length != 2 || cc.any { it !in 'A'..'Z' }) return ""
    val base = 0x1F1E6 - 'A'.code
    return String(Character.toChars(base + cc[0].code)) +
        String(Character.toChars(base + cc[1].code))
}
