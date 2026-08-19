package com.rahgozar.app.ui.perappproxy

import android.app.Application
import android.content.Context
import com.rahgozar.app.AppConfig
import com.rahgozar.app.dto.AppInfo
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.SettingsChangeManager
import com.rahgozar.app.ui.base.BaseViewModel
import com.rahgozar.app.util.AppManagerUtil
import com.rahgozar.app.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * ViewModel for PerAppProxy screen.
 * Holds all UI state and business logic.
 */
class PerAppProxyViewModel(application: Application) : BaseViewModel(application) {

    // Blacklist (apps to be proxied or bypassed)
    private val _blacklist = MutableStateFlow(loadBlacklist())
    val blacklist: StateFlow<Set<String>> = _blacklist.asStateFlow()

    // UI states
    private val _displayedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val displayedApps: StateFlow<List<AppInfo>> = _displayedApps.asStateFlow()

    private val _perAppProxyEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)
    )
    val perAppProxyEnabled: StateFlow<Boolean> = _perAppProxyEnabled.asStateFlow()

    private val _bypassApps = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false)
    )
    val bypassApps: StateFlow<Boolean> = _bypassApps.asStateFlow()

    // Cached full list for filtering
    private var appsAll: List<AppInfo>? = null

    // Blacklist operations
    fun contains(packageName: String): Boolean = _blacklist.value.contains(packageName)

    fun add(packageName: String) {
        val newSet = _blacklist.value + packageName
        if (newSet != _blacklist.value) {
            _blacklist.value = newSet
            saveBlacklist()
        }
    }

    fun remove(packageName: String) {
        val newSet = _blacklist.value - packageName
        if (newSet != _blacklist.value) {
            _blacklist.value = newSet
            saveBlacklist()
        }
    }

    fun toggle(packageName: String) {
        if (_blacklist.value.contains(packageName)) {
            remove(packageName)
        } else {
            add(packageName)
        }
    }

    fun addAll(packages: Collection<String>) {
        val newSet = _blacklist.value + packages
        if (newSet != _blacklist.value) {
            _blacklist.value = newSet
            saveBlacklist()
        }
    }

    fun removeAll(packages: Collection<String>) {
        val newSet = _blacklist.value - packages.toSet()
        if (newSet != _blacklist.value) {
            _blacklist.value = newSet
            saveBlacklist()
        }
    }

    fun clear() {
        if (_blacklist.value.isNotEmpty()) {
            _blacklist.value = emptySet()
            saveBlacklist()
        }
    }

    private fun loadBlacklist(): Set<String> {
        return MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)?.toSet() ?: emptySet()
    }

    private fun saveBlacklist() {
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, _blacklist.value.toMutableSet())
        SettingsChangeManager.makeRestartService()
    }

    // Per‑app proxy switch
    fun setPerAppProxyEnabled(enabled: Boolean) {
        if (_perAppProxyEnabled.value != enabled) {
            _perAppProxyEnabled.value = enabled
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, enabled)
        }
    }

    fun setBypassAppsEnabled(enabled: Boolean) {
        if (_bypassApps.value != enabled) {
            _bypassApps.value = enabled
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, enabled)
        }
    }

    // Load and filter apps
    fun loadApps(context: Context) {
        launchLoading {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val list = AppManagerUtil.loadNetworkAppList(context)
                    sortApps(list)
                }
                appsAll = apps
                _displayedApps.value = apps
            } catch (e: Exception) {
                LogUtil.e(AppConfig.ANG_PACKAGE, "Error loading apps", e)
            }
        }
    }

    fun filterApps(query: String) {
        val key = query.uppercase()
        _displayedApps.value = if (key.isNotEmpty()) {
            appsAll?.filter {
                it.appName.uppercase().contains(key) || it.packageName.uppercase().contains(key)
            } ?: emptyList()
        } else {
            appsAll ?: emptyList()
        }
    }

    private fun sortApps(apps: List<AppInfo>): List<AppInfo> {
        val collator = Collator.getInstance()
        return apps.sortedWith { p1, p2 ->
            val s1 = contains(p1.packageName)
            val s2 = contains(p2.packageName)
            when {
                s1 && !s2 -> -1
                !s1 && s2 -> 1
                p1.isSystemApp && !p2.isSystemApp -> 1
                !p1.isSystemApp && p2.isSystemApp -> -1
                else -> collator.compare(p1.appName, p2.appName)
            }
        }
    }

    // Bulk actions
    fun selectAll() {
        val pkgNames = _displayedApps.value.map { it.packageName }
        val allSelected = pkgNames.all { contains(it) }
        if (allSelected) removeAll(pkgNames) else addAll(pkgNames)
        // Enable per‑app proxy after selection
        enablePerAppProxyAndRestart()
    }

    fun invertSelection() {
        _displayedApps.value.forEach { toggle(it.packageName) }
        enablePerAppProxyAndRestart()
    }

    fun importProxyApp(content: String?) {
        if (content.isNullOrEmpty()) return
        val success = selectProxyApp(content)
        if (success) {
            enablePerAppProxyAndRestart()
        }
    }

    fun exportProxyApp(): String {
        var result = _bypassApps.value.toString()
        _blacklist.value.forEach { pkg ->
            result = result + System.lineSeparator() + pkg
        }
        return result
    }

    /**
     * Applies a pasted list of package names to the current selection.
     *
     * The list is only ever one the user pasted themselves. The variant that
     * downloaded it from a community repository is gone, along with its special
     * case that swept in every `com.google.*` package.
     */
    private fun selectProxyApp(proxyApps: String): Boolean {
        try {
            clear()
            val apps = _displayedApps.value
            if (_bypassApps.value) {
                apps.forEach { app ->
                    if (!proxyApps.contains(app.packageName)) {
                        add(app.packageName)
                    }
                }
            } else {
                apps.forEach { app ->
                    if (proxyApps.contains(app.packageName)) {
                        add(app.packageName)
                    }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error selecting proxy app", e)
            return false
        }
        return true
    }

    private fun enablePerAppProxyAndRestart() {
        setPerAppProxyEnabled(true)
        SettingsChangeManager.makeRestartService()
    }
}