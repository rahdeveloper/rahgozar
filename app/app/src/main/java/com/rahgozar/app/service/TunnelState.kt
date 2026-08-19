package com.rahgozar.app.service

import android.app.ActivityManager
import android.content.Context

/**
 * Whether a tunnel of this app is running, asked of Android rather than of the
 * app's own state.
 *
 * The screen's idea of "connected" is assembled from broadcasts, and broadcasts
 * can be missed, can arrive out of order, and are answered by every service
 * that hears them — including one left over from a core that is no longer the
 * one carrying traffic. This is the fact underneath all of that.
 *
 * `getRunningServices` stopped reporting other apps' services years ago but
 * still reports our own, which is exactly and only what is being asked here.
 */
object TunnelState {

    private val TUNNEL_SERVICES = setOf(
        CoreVpnService::class.java.name,
        OpenVpnService::class.java.name,
        SingBoxService::class.java.name,
        CoreProxyOnlyService::class.java.name,
        CoreRootService::class.java.name,
    )

    fun isRunning(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val running = runCatching {
            @Suppress("DEPRECATION")
            manager.getRunningServices(Int.MAX_VALUE)
        }.getOrNull() ?: return false

        return running.any { it.service.className in TUNNEL_SERVICES }
    }
}
