package com.rahgozar.app.ui.settings

import android.app.Application
import com.rahgozar.app.AppConfig
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.SettingsChangeManager
import com.rahgozar.app.ui.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The settings the user actually owns, as the screen needs them. */
data class SettingsUiState(
    val startOnBoot: Boolean = false,
    val perAppProxy: Boolean = false,
    /** How many apps the per-app list names, so the row can say so. */
    val perAppCount: Int = 0,
)

/**
 * Reads and writes the handful of preferences that belong to the phone's owner.
 *
 * Everything else this screen used to hold is the panel's now, so there is no
 * validation left to do here: the values are booleans, and the two that are not
 * are lists the user picks from rather than text they can mistype. That is the
 * point of the split — the settings that can be entered wrongly are the ones
 * the operator sets.
 */
class SettingsViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Re-reads from storage. Called on every resume — the per-app list is
     *  changed on another screen. */
    fun reload() {
        _uiState.value = SettingsUiState(
            startOnBoot = MmkvManager.decodeStartOnBoot(),
            perAppProxy = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false),
            perAppCount = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
                ?.size ?: 0,
        )
    }

    fun setToggle(key: String, value: Boolean) {
        when (key) {
            KEY_START_ON_BOOT -> MmkvManager.encodeStartOnBoot(value)

            KEY_PER_APP_PROXY -> {
                MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, value)
                // Changes which apps the tunnel carries, so a running core has
                // to be rebuilt for it to mean anything.
                SettingsChangeManager.makeRestartService()
            }

            else -> return
        }
        reload()
    }

    companion object {
        const val KEY_START_ON_BOOT = "start_on_boot"
        const val KEY_PER_APP_PROXY = "per_app_proxy"
        const val KEY_CHOOSE_APPS = "choose_apps"
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
    }
}
