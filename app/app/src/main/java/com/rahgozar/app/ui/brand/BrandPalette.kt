package com.rahgozar.app.ui.brand

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The two palettes, transcribed from the design's own `DARKC` and `LIGHTC`
 * tables rather than eyeballed from screenshots.
 *
 * They are a pair on purpose: every role below exists in both, so a screen
 * written against roles cannot be "dark-only by accident". If a new colour is
 * needed, it gets a role here and a value in each palette, or it does not get
 * used.
 */
@Immutable
data class BrandPalette(
    val isDark: Boolean,

    val background: Color,
    val skyTop: Color,
    val skyMid: Color,
    val skyBottom: Color,
    val glow: Color,
    /** Opacity of the horizontal rule texture. Zero in light — it reads as dirt. */
    val scanlineAlpha: Float,
    val floorShadow: Color,

    val surface: Color,
    /** One-pixel dividers. The design leans on these instead of card borders. */
    val hair: Color,
    val hoverWash: Color,
    val drawerBackground: Color,

    val text: Color,
    val text2: Color,
    val dim: Color,
    val faint: Color,

    val accent: Color,
    /** Text drawn *on* the accent. */
    val onAccent: Color,
    val danger: Color,

    val trackOff: Color,
    val knobOff: Color,
    val selectionWash: Color,

    val barBackground: Color,
    val barHighlight: Color,

    // The dial. The design only draws it on the dark splash, so the light
    // values are derived: the same lit-sphere idea, but reading as a raised
    // pale disc instead of a black hole punched in a white page.
    val dialInner: Color,
    val dialOuter: Color,
    val markFill: Color,
) {
    companion object {
        val Dark = BrandPalette(
            isDark = true,
            background = Color(0xFF0A0C0B),
            skyTop = Color(0xFF0E1613),
            skyMid = Color(0xFF0A0D0C),
            skyBottom = Color(0xFF070908),
            glow = Color(0x293DDC91),        // rgba(61,220,145,.16)
            scanlineAlpha = 0.022f,
            floorShadow = Color(0x99000000),
            surface = Color(0x0DFFFFFF),
            hair = Color(0x13FFFFFF),
            hoverWash = Color(0x08FFFFFF),
            drawerBackground = Color(0xFF101413),
            text = Color(0xFFEAF0EA),
            text2 = Color(0xFFB9C3BA),
            dim = Color(0xFF7C867D),
            faint = Color(0xFF8B948C),
            accent = Color(0xFF3DDC91),
            onAccent = Color(0xFF04170E),
            danger = Color(0xFFE3846C),
            trackOff = Color(0x0FFFFFFF),
            knobOff = Color(0xFF6D766E),
            selectionWash = Color(0x123DDC91),
            barBackground = Color(0xB80A0C0B),
            barHighlight = Color(0x0DFFFFFF),
            dialInner = Color(0xFF14472F),
            dialOuter = Color(0xFF07160F),
            markFill = Color(0xFF0D2419),
        )

        val Light = BrandPalette(
            isDark = false,
            background = Color(0xFFF4F6F2),
            skyTop = Color(0xFFFBFCFA),
            skyMid = Color(0xFFF2F5F1),
            skyBottom = Color(0xFFECEFE9),
            glow = Color(0x2412A361),        // rgba(18,163,97,.14)
            scanlineAlpha = 0f,
            floorShadow = Color(0x1A5A6E5F),
            surface = Color(0x0F12281C),
            hair = Color(0x1C12281C),
            hoverWash = Color(0x0812281C),
            drawerBackground = Color(0xFFFFFFFF),
            text = Color(0xFF101512),
            text2 = Color(0xFF3B453D),
            dim = Color(0xFF616B62),
            faint = Color(0xFF6D766E),
            accent = Color(0xFF12A361),
            onAccent = Color(0xFFFFFFFF),
            danger = Color(0xFFC2543A),
            trackOff = Color(0x1A12281C),
            knobOff = Color(0xFFFFFFFF),
            selectionWash = Color(0x1212A361),
            barBackground = Color(0xC7FFFFFF),
            barHighlight = Color(0xE6FFFFFF),
            dialInner = Color(0xFFFFFFFF),
            dialOuter = Color(0xFFDCEDE3),
            markFill = Color(0xFFEAF5EF),
        )
    }
}

/**
 * The palette in force.
 *
 * Defaults to dark because that is what the design leads with and what a VPN
 * app is opened into at night; the real value is supplied by [BrandTheme].
 */
val LocalPalette = compositionLocalOf { BrandPalette.Dark }
