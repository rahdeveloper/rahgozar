package com.rahgozar.app.ui.brand

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.rahgozar.app.R

/**
 * Wraps a screen in the current palette.
 *
 * It used to carry the language and writing direction too, for an app written
 * in Persian and English at once. The app is English-only now, so the layout
 * direction is simply the platform's default and nothing here has to say so.
 */
@Composable
fun BrandTheme(
    themeMode: ThemeMode = AppPreferences.themeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (dark) BrandPalette.Dark else BrandPalette.Light

    CompositionLocalProvider(
        LocalPalette provides palette,
        content = content,
    )
}

/**
 * Appearance state a screen can both read and change.
 *
 * Held here rather than in each ViewModel because the switches live in the top
 * bar of every screen: a change made on one has to be visible on all of them,
 * and it has to survive the process.
 */
class AppearanceState {
    val themeMode: MutableState<ThemeMode> = mutableStateOf(AppPreferences.themeMode)

    fun toggleTheme(currentlyDark: Boolean) {
        AppPreferences.toggleTheme(currentlyDark)
        themeMode.value = AppPreferences.themeMode
    }

    fun setThemeMode(next: ThemeMode) {
        AppPreferences.themeMode = next
        themeMode.value = next
    }

    /**
     * Steps through all three modes, including the one that follows the phone.
     *
     * The top bar's sun/moon can only ever produce an explicit dark or light —
     * it has two states and no way to draw a third. Once someone has pressed it,
     * "follow the phone" is unreachable, so the settings row cycles instead of
     * toggling and gives it back.
     */
    fun cycleTheme() {
        setThemeMode(
            when (themeMode.value) {
                ThemeMode.SYSTEM -> ThemeMode.DARK
                ThemeMode.DARK -> ThemeMode.LIGHT
                ThemeMode.LIGHT -> ThemeMode.SYSTEM
            }
        )
    }
}

@Composable
fun rememberAppearance(): AppearanceState = remember { AppearanceState() }

/**
 * The typefaces the design specifies.
 *
 * Vazirmatn carries running copy — it was chosen for its Persian, which the app
 * no longer ships, but its Latin is the face the whole design was drawn against
 * and swapping it now would redraw every screen. JetBrains Mono carries labels,
 * numbers and anything tabular, where its wide tracking and fixed-width figures
 * keep a changing value from shifting the layout under it.
 */
object Brand {
    val Vazirmatn = FontFamily(
        Font(R.font.vazirmatn_regular, FontWeight.Normal),
        Font(R.font.vazirmatn_bold, FontWeight.Bold),
        Font(R.font.vazirmatn_black, FontWeight.Black),
    )

    val JetBrainsMono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    )

    // --- splash-specific values, which the design gives only for that screen ---
    val AccentBright = androidx.compose.ui.graphics.Color(0xFF6EF0B8)
    val AccentDeep = androidx.compose.ui.graphics.Color(0xFF22B871)
    val DialInnerLight = androidx.compose.ui.graphics.Color(0xFF14472F)
    val DialInnerDark = androidx.compose.ui.graphics.Color(0xFF07160F)
    val MarkFill = androidx.compose.ui.graphics.Color(0xFF0D2419)
    val TaglineEnd = androidx.compose.ui.graphics.Color(0xFF8FE8BC)
}
