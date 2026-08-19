package com.rahgozar.app.ui.settings

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahgozar.app.ui.base.BaseComponentActivity
import com.rahgozar.app.ui.brand.BrandTheme
import com.rahgozar.app.ui.brand.LocalPalette
import com.rahgozar.app.ui.brand.ThemeMode
import com.rahgozar.app.ui.brand.rememberAppearance
import com.rahgozar.app.ui.perappproxy.PerAppProxyActivity

/**
 * The settings screen.
 *
 * Everything it shows is the phone owner's own preference. The core options it
 * used to carry now come down from the panel — see
 * [com.rahgozar.app.panel.TunnelSettings] — which is also why root mode and the
 * VPN/proxy mode picker are gone: the panel refuses rooted devices outright, so
 * a switch offering to run through root was offering something that could not
 * work.
 */
class SettingsActivity : BaseComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // The per-app list is edited on another screen, and this is where
        // the user comes back to.
        viewModel.reload()
    }

    @Composable
    override fun ScreenContent() {
        val appearance = rememberAppearance()
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        BrandTheme(themeMode = appearance.themeMode.value) {
            val dark = LocalPalette.current.isDark
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            SettingsScreen(
                sections = sections(state, appearance.themeMode.value),
                onToggle = viewModel::setToggle,
                onLink = { key ->
                    when (key) {
                        SettingsViewModel.KEY_THEME -> appearance.cycleTheme()
                        SettingsViewModel.KEY_CHOOSE_APPS ->
                            startActivity(Intent(this, PerAppProxyActivity::class.java))
                    }
                },
                onBack = { finish() },
                onToggleTheme = { appearance.toggleTheme(dark) },
            )
        }
    }

    /**
     * Grouped by who the setting belongs to rather than by subsystem.
     *
     * Appearance first because it is the group a user opens this screen to
     * find; the tunnel's own behaviour is last because most people should never
     * need to touch it.
     */
    private fun sections(
        state: SettingsUiState,
        themeMode: ThemeMode,
    ): List<SettingsSection> = listOf(
        SettingsSection(
            title = "APPEARANCE",
            rows = listOf(
                SettingsRow.Link(
                    key = SettingsViewModel.KEY_THEME,
                    label = "Theme",
                    valueLabel = themeLabel(themeMode),
                ),
            ),
        ),
        SettingsSection(
            title = "CONNECTION",
            rows = listOf(
                // Confirming a disconnect is not offered as a choice. The dial
                // is the largest tap target on the home screen and a stray
                // touch exposes traffic the user believed was covered, so it
                // always asks.
                SettingsRow.Toggle(
                    key = SettingsViewModel.KEY_START_ON_BOOT,
                    label = "Connect when the phone starts",
                    value = state.startOnBoot,
                ),
            ),
        ),
        // Routing is no longer in it: the app runs one fixed ruleset, so the
        // only thing left in this group is which apps the tunnel carries.
        SettingsSection(
            title = "PROXY",
            rows = listOf(
                SettingsRow.Toggle(
                    key = SettingsViewModel.KEY_PER_APP_PROXY,
                    label = "Per-app proxy",
                    hint = "Tunnel only the apps you choose instead of the whole phone",
                    value = state.perAppProxy,
                ),
                SettingsRow.Link(
                    key = SettingsViewModel.KEY_CHOOSE_APPS,
                    label = "Choose apps",
                    valueLabel = if (state.perAppCount > 0) state.perAppCount.toString() else "",
                    enabled = state.perAppProxy,
                ),
            ),
        ),
    )

    private fun themeLabel(mode: ThemeMode): String {
        return when (mode) {
            ThemeMode.SYSTEM -> "Follow phone"
            ThemeMode.DARK -> "Dark"
            ThemeMode.LIGHT -> "Light"
        }
    }
}
