package com.rahgozar.app.service

import android.content.Context
import android.os.ParcelFileDescriptor
import com.rahgozar.app.AppConfig
import com.rahgozar.app.contracts.Tun2SocksControl
import com.rahgozar.app.handler.MmkvManager
import com.rahgozar.app.handler.SettingsManager
import com.rahgozar.app.util.LogUtil
import java.io.File

/**
 * Manages the tun2socks process that handles VPN traffic
 */
class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val isRunningProvider: () -> Boolean,
    private val restartCallback: () -> Unit
) : Tun2SocksControl {
    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        /**
         * Whether `libhev-socks5-tunnel.so` is actually here.
         *
         * The load used to be bare, in this same initialiser. A missing or
         * unloadable library raises `UnsatisfiedLinkError` — an **Error**, so
         * nothing below catches it — and raising it from a class initialiser is
         * the worst place for it: the failure becomes
         * `ExceptionInInitializerError` on first touch and `NoClassDefFoundError`
         * on every touch after, from whichever thread happened to get there
         * first. The app dies, and the crash names a class rather than a
         * missing file.
         *
         * It is not a hypothetical for a shipped app: ABI splits mean an
         * install can arrive without the slice this device needs, and this path
         * is only taken when the panel turns the hev tun on — so the first
         * device to meet it would be a user's, not ours. Not being able to
         * start a tunnel is a bad afternoon; not being able to open the app is
         * a bad release.
         */
        private val available: Boolean = runCatching { System.loadLibrary("hev-socks5-tunnel") }
            .onFailure { LogUtil.e(AppConfig.TAG, "hev-socks5-tunnel is not loadable", it) }
            .isSuccess
    }

    /**
     * Starts the tun2socks process with the appropriate parameters.
     */
    override fun startTun2Socks() {
//        LogUtil.i(AppConfig.TAG, "Starting HevSocks5Tunnel via JNI")

        if (!available) {
            LogUtil.e(AppConfig.TAG, "hev-socks5-tunnel is missing; this tunnel cannot start")
            return
        }

        val configContent = buildConfig()
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(configContent)
        }
//        LogUtil.i(AppConfig.TAG, "Config file created: ${configFile.absolutePath}")
        LogUtil.d(AppConfig.TAG, "HevSocks5Tunnel Config content:\n$configContent")

        try {
//            LogUtil.i(AppConfig.TAG, "TProxyStartService...")
            TProxyStartService(configFile.absolutePath, vpnInterface.fd)
        } catch (t: Throwable) {
            // Throwable, not Exception: these are JNI calls, and a native
            // method that did not link raises UnsatisfiedLinkError, which is an
            // Error. An `Exception` catch reads as careful and steps straight
            // over the one failure this wrapper exists to survive.
            LogUtil.e(AppConfig.TAG, "HevSocks5Tunnel could not start", t)
        }
    }

    private fun buildConfig(): String {
        val socksPort = SettingsManager.getSocksPort()
        val socksUsername = SettingsManager.getSocksUsername()
        val socksPassword = SettingsManager.getSocksPassword()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val escapedSocksUsername = socksUsername?.replace("'", "''")
        val escapedSocksPassword = socksPassword?.replace("'", "''")
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${SettingsManager.getVpnMtu()}")
            appendLine("  ipv4: ${vpnConfig.ipv4Client}")

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED)) {
                appendLine("  ipv6: '${vpnConfig.ipv6Client}'")
            }

            appendLine("socks5:")
            appendLine("  port: ${socksPort}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")
            if (escapedSocksUsername != null && escapedSocksPassword != null) {
                appendLine("  username: '${escapedSocksUsername}'")
                appendLine("  password: '${escapedSocksPassword}'")
            }

            // Read-write timeout settings
            val timeoutSetting = MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) ?: AppConfig.HEVTUN_RW_TIMEOUT
            val parts = timeoutSetting.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val tcpTimeout = parts.getOrNull(0)?.toIntOrNull() ?: 300
            val udpTimeout = parts.getOrNull(1)?.toIntOrNull() ?: 60

            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: ${tcpTimeout * 1000}")
            appendLine("  udp-read-write-timeout: ${udpTimeout * 1000}")
            appendLine("  log-level: ${MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL) ?: "warn"}")
        }
    }

    /**
     * Stops the tun2socks process
     */
    override fun stopTun2Socks() {
        if (!available) return
        try {
            LogUtil.i(AppConfig.TAG, "TProxyStopService...")
            TProxyStopService()
        } catch (t: Throwable) {
            LogUtil.e(AppConfig.TAG, "Failed to stop hev-socks5-tunnel", t)
        }
    }
}
