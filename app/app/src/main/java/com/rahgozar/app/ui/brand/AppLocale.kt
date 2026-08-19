package com.rahgozar.app.ui.brand

import com.tencent.mmkv.MMKV

/** Dark, light, or whatever the phone is set to. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * The one appearance choice left to the user, remembered across launches.
 *
 * There used to be a second: a Persian/English switch, with the whole app
 * written in both languages side by side. The app ships in English only now, so
 * the switch, the enum behind it and the right-to-left layout direction it
 * drove are all gone rather than left as a control with one position.
 *
 * Stored in its own MMKV instance alongside the panel's, and deliberately not
 * in the panel's settings — this is the user's choice about their own device,
 * and an operator should not be able to change how someone's phone looks.
 */
object AppPreferences {

    private const val ID = "APPEARANCE"
    private const val KEY_THEME = "theme_mode"

    // Multi-process: the tunnel services run in :RunSoLibV2RayDaemon and read
    // from here too. A single-process handle would let the two disagree.
    private val store: MMKV by lazy { MMKV.mmkvWithID(ID, MMKV.MULTI_PROCESS_MODE) }

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(store.decodeString(KEY_THEME).orEmpty()) }
            .getOrDefault(ThemeMode.SYSTEM)
        set(value) { store.encode(KEY_THEME, value.name) }

    /** Flips between the two explicit modes, taking the current one as the start. */
    fun toggleTheme(currentlyDark: Boolean) {
        themeMode = if (currentlyDark) ThemeMode.LIGHT else ThemeMode.DARK
    }
}
