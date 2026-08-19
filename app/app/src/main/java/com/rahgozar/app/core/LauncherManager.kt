package com.rahgozar.app.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.rahgozar.app.AppConfig
import com.rahgozar.app.R
import com.rahgozar.app.ads.SmartTunnel
import com.rahgozar.app.enums.EConfigType
import com.rahgozar.app.extension.isComplexType
import com.rahgozar.app.extension.toast
import com.rahgozar.app.extension.toastError
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.helper.MessageHelper
import com.rahgozar.app.root.RootManager
import com.rahgozar.app.service.CoreProxyOnlyService
import com.rahgozar.app.service.CoreRootService
import com.rahgozar.app.service.CoreVpnService
import com.rahgozar.app.service.OpenVpnService
import com.rahgozar.app.service.SingBoxService
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.Utils

object LauncherManager {

    fun startServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        try {
            startContextService(context)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
            return false
        }
        return true
    }

    /**
     * @param quiet suppresses the user-facing toasts. Used by the smart
     *   tunnel, whose start is an implementation detail of the ad flow — a
     *   "connecting" toast over the splash would announce a connection the
     *   user never asked for.
     * @param honourOverride see [startContextService]. Defaults to false, so a
     *   caller that has not thought about the run-guid override cannot be the
     *   one that runs a stale one.
     */
    fun startService(
        context: Context,
        guid: String? = null,
        quiet: Boolean = false,
        honourOverride: Boolean = false,
    ) {
        LogUtil.i(AppConfig.TAG, "LauncherManager: startService from ${context::class.java.simpleName}")

        if (guid != null) {
            MmkvManager.setSelectServer(guid)
        }

        try {
            startContextService(context, quiet, honourOverride)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: ${e.message}", e)
            context.toast(e.message ?: e.javaClass.simpleName)
        }
    }

    fun stopService(context: Context) {
        //context.toast(R.string.toast_services_stop)
        MessageHelper.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /**
     * @param honourOverride whether the smart session's run-guid override may
     *   decide what starts.
     *
     * It may, for exactly two callers: the ad flow itself, which set it, and a
     * restart, which re-establishes whatever was already running. Everything
     * else here is a *fresh intent to connect* — the tile, the widget, both
     * shortcuts, the boot receiver, the connect button — and for those the
     * override can only ever be leftover.
     *
     * Leftover is not hypothetical. Every one of those surfaces stops the
     * tunnel without ending the session, and the watchdog that reaps a
     * stranded one used to clear the flag and leave the override behind. The
     * result was a phone that said "connected" while running the panel's smart
     * profile on a tun scoped to this app alone, so it carried nothing — and
     * on a device with start-on-boot it did that by itself after a reboot.
     *
     * So the default is to clear, and the two exemptions say so at the call
     * site. [SmartTunnel.clearSession] is a no-op when there is nothing stale.
     */
    @Throws(Exception::class)
    private fun startContextService(
        context: Context,
        quiet: Boolean = false,
        honourOverride: Boolean = false,
    ) {
        // Note: isRunning check is removed here to avoid loading Native libraries in the UI process.
        // The check is performed in CoreServiceManager when the service starts in the daemon process.

        if (!honourOverride) SmartTunnel.clearSession()

        // The run server, not the selection: identical except while the smart
        // session's override is in place. See [MmkvManager.getRunServer].
        val guid = MmkvManager.getRunServer()
            ?: run {
                LogUtil.e(AppConfig.TAG, "LauncherManager: No server selected")
                error(context.getString(R.string.app_tile_first_use))
            }

        val config = MmkvManager.decodeServerConfig(guid)
            ?: run {
                LogUtil.e(AppConfig.TAG, "LauncherManager: Failed to decode server config")
                error(context.getString(R.string.toast_config_file_invalid))
            }

        if (!config.configType.isComplexType()
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Invalid server configuration")
            error(context.getString(R.string.toast_config_file_invalid))
        }

        SettingsManager.refreshRuntimeSocksPort()

        // Upstream also copied this whole message to the clipboard and pointed
        // the user at its own GitHub discussion. Neither survives: the server
        // came from the panel, so the fix is the operator's, not the user's.
        if (!quiet && config.insecure == true && config.pinnedCA256.isNullOrEmpty()) {
            context.toastError(R.string.toast_allow_insecure_deprecated)
        }

        if (!quiet) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
                context.toast(R.string.toast_warning_pref_proxysharing_short)
            } else {
                context.toast(R.string.toast_services_start)
            }
        }

        val isRootMode = SettingsManager.isRootMode()
        if (isRootMode && !RootManager.isRootAvailable()) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: root mode requires root but none available")
            error(context.getString(R.string.toast_root_required))
        }

        val intent = if (config.configType == EConfigType.OPENVPN) {
            // Checked before every other mode on purpose. An OpenVPN profile has
            // nothing for the Xray core to run, so root and proxy-only modes are
            // not alternatives here — they would start a service that cannot
            // carry this server at all.
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting OpenVPN service")
            Intent(context.applicationContext, OpenVpnService::class.java)
        } else if (config.configType == EConfigType.SINGBOX) {
            // Before root and proxy-only for the same reason as OpenVPN: only
            // SingBoxService can carry this server.
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting sing-box service")
            Intent(context.applicationContext, SingBoxService::class.java)
        } else if (isRootMode) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Root service")
            Intent(context.applicationContext, CoreRootService::class.java)
        } else if (SettingsManager.isVpnMode()) {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting VPN service")
            Intent(context.applicationContext, CoreVpnService::class.java)
        } else {
            LogUtil.i(AppConfig.TAG, "LauncherManager: Starting Proxy service")
            Intent(context.applicationContext, CoreProxyOnlyService::class.java)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: SecurityException) {
            LogUtil.e(AppConfig.TAG, "LauncherManager: Missing permission to start foreground service", e)
            throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
        } catch (e: RuntimeException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                LogUtil.e(AppConfig.TAG, "LauncherManager: Foreground service start not allowed", e)
                throw IllegalStateException(e.message ?: e.javaClass.simpleName, e)
            }
            throw e
        }
    }
}
