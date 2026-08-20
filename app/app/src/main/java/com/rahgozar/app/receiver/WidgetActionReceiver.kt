package com.rahgozar.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rahgozar.app.AppConfig
import com.rahgozar.app.ads.SmartTunnel
import com.rahgozar.app.core.LauncherManager
import com.rahgozar.app.service.TunnelState
import com.rahgozar.app.util.LogUtil

/**
 * The widget's tap, and the state broadcasts that redraw it.
 *
 * A second receiver purely so this one can be **not exported**, and that is the
 * whole point of it. [WidgetProvider] has no choice: an `AppWidgetProvider`
 * must be exported for Android to send it `APPWIDGET_UPDATE`, and an exported
 * receiver can be reached by explicit intent from any app on the device — the
 * intent filter is about implicit delivery, it is not a lock. While the tap
 * lived there, any installed app could run
 *
 *     am broadcast -a com.rahgozar.app.action.widget.click \
 *                  -n com.rahgozar.app/.receiver.WidgetProvider
 *
 * and toggle the user's VPN. For a tunnel, being switched off silently is the
 * failure that matters: traffic the user believes is covered goes out in the
 * clear, and the widget redraws itself as "off" afterwards so nothing even
 * looks wrong.
 *
 * Both actions still arrive: the tap comes from a `PendingIntent` this app
 * created, which fires with this app's identity however far it has travelled,
 * and the state broadcasts come from our own services in another process of the
 * same app. Neither needs the door open to anybody else.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppConfig.BROADCAST_ACTION_WIDGET_CLICK -> onTapped(context)
            AppConfig.BROADCAST_ACTION_ACTIVITY -> onTunnelState(context, intent)
        }
    }

    private fun onTapped(context: Context) {
        // Nothing while the ad flow holds the tunnel, exactly as the tile does:
        // the widget reads "off" then, so a tap means connect, and connecting
        // would race the ad flow for the one VPN slot. The session lasts
        // seconds.
        if (SmartTunnel.ownsTheRunningTunnel(context)) {
            LogUtil.i(AppConfig.TAG, "widget: ignoring a tap while the ad flow holds the tunnel")
            return
        }
        // Same source as the icon, or the two disagree: a widget drawn from
        // Android's view of the services and a tap decided by the Xray
        // controller's flag is how "stop" quietly became "start again" on every
        // sing-box connection.
        if (TunnelState.isRunning(context)) {
            LauncherManager.stopService(context)
        } else {
            LauncherManager.startServiceFromToggle(context)
        }
    }

    private fun onTunnelState(context: Context, intent: Intent) {
        when (intent.getIntExtra("key", 0)) {
            // Not `true`: the broadcasts do not say whose tunnel they are
            // about, and the ad flow's start sends START_SUCCESS like any
            // other. Re-asked here instead.
            AppConfig.MSG_STATE_RUNNING,
            AppConfig.MSG_STATE_START_SUCCESS,
            -> WidgetProvider.refresh(context)

            AppConfig.MSG_STATE_NOT_RUNNING,
            AppConfig.MSG_STATE_START_FAILURE,
            AppConfig.MSG_STATE_STOP_SUCCESS,
            -> WidgetProvider.refresh(context, connected = false)
        }
    }
}
