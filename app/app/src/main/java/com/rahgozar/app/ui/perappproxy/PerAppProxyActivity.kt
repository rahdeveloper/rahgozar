package com.rahgozar.app.ui.perappproxy

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rahgozar.app.R
import com.rahgozar.app.extension.toastSuccess
import com.rahgozar.app.ui.base.BaseComponentActivity
import com.rahgozar.app.ui.brand.BrandTheme
import com.rahgozar.app.ui.brand.LocalPalette
import com.rahgozar.app.ui.brand.rememberAppearance
import com.rahgozar.app.util.Utils

/**
 * Which apps the tunnel carries.
 *
 * Reachable from the drawer as well as from settings, so it carries its own
 * master switch and mode picker rather than assuming the user arrived past the
 * settings row.
 *
 * The import and export here move a list of **package names** through the
 * clipboard, not a server configuration — that is why they survived the pass
 * that closed every other clipboard path in the app. See docs/SECURITY.md.
 */
class PerAppProxyActivity : BaseComponentActivity() {

    private val viewModel: PerAppProxyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.loadApps(this)
    }

    @Composable
    override fun ScreenContent() {
        val appearance = rememberAppearance()
        val apps by viewModel.displayedApps.collectAsStateWithLifecycle()
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        val blacklist by viewModel.blacklist.collectAsStateWithLifecycle()
        val perAppProxyEnabled by viewModel.perAppProxyEnabled.collectAsStateWithLifecycle()
        val bypassApps by viewModel.bypassApps.collectAsStateWithLifecycle()

        BrandTheme(
            themeMode = appearance.themeMode.value,
        ) {
            val dark = LocalPalette.current.isDark
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            PerAppProxyScreen(
                apps = apps.map {
                    AppRow(
                        packageName = it.packageName,
                        name = it.appName,
                        icon = it.appIcon,
                        checked = blacklist.contains(it.packageName),
                    )
                },
                loading = isLoading,
                enabled = perAppProxyEnabled,
                bypass = bypassApps,
                onEnabled = viewModel::setPerAppProxyEnabled,
                onBypass = viewModel::setBypassAppsEnabled,
                onToggleApp = viewModel::toggle,
                onSearch = viewModel::filterApps,
                onSelectAll = viewModel::selectAll,
                onInvert = viewModel::invertSelection,
                onPaste = {
                    viewModel.importProxyApp(Utils.getClipboard(applicationContext))
                },
                onCopy = {
                    Utils.setClipboard(applicationContext, viewModel.exportProxyApp())
                    toastSuccess(R.string.toast_success)
                },
                onBack = { finish() },
                onToggleTheme = { appearance.toggleTheme(dark) },
            )
        }
    }
}
