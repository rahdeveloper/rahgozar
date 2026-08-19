package com.rahgozar.app.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.rahgozar.app.AppConfig
import com.rahgozar.app.R
import com.rahgozar.app.ads.SmartTunnel
import com.rahgozar.app.core.CoreServiceManager
import com.rahgozar.app.core.LauncherManager
import com.rahgozar.app.service.TunnelState
import com.rahgozar.app.util.LogUtil

class WidgetProvider : AppWidgetProvider() {
    /**
     * This method is called every time the widget is updated.
     * It updates the widget background based on the V2Ray service running state.
     *
     * @param context The Context in which the receiver is running.
     * @param appWidgetManager The AppWidgetManager instance.
     * @param appWidgetIds The appWidgetIds for which an update is needed.
     */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        updateWidgetBackground(context, appWidgetManager, appWidgetIds, userIsConnected(context))
    }

    /**
     * Whether a tunnel the *user* asked for is up — the same question the
     * quick-settings tile asks, answered the same way and for the same two
     * reasons. See `QSTileService.userIsConnected`: [TunnelState] because the
     * Xray controller's flag cannot see sing-box or OpenVPN, and the smart
     * session because the ad flow's tunnel is not the user's.
     */
    private fun userIsConnected(context: Context): Boolean =
        TunnelState.isRunning(context) && !SmartTunnel.ownsTheRunningTunnel(context)

    /**
     * Updates the widget background based on whether the V2Ray service is running.
     *
     * @param context The Context in which the receiver is running.
     * @param appWidgetManager The AppWidgetManager instance.
     * @param appWidgetIds The appWidgetIds for which an update is needed.
     * @param isRunning Boolean indicating if the V2Ray service is running.
     */
    private fun updateWidgetBackground(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray, isRunning: Boolean) {
        val remoteViews = RemoteViews(context.packageName, R.layout.widget_switch)
        val intent = Intent(context, WidgetProvider::class.java)
        intent.action = AppConfig.BROADCAST_ACTION_WIDGET_CLICK
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            R.id.layout_switch,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        remoteViews.setOnClickPendingIntent(R.id.layout_switch, pendingIntent)
        if (isRunning) {
            remoteViews.setInt(R.id.image_switch, "setImageResource", R.drawable.ic_stop_24dp)
            remoteViews.setInt(R.id.layout_background, "setBackgroundResource", R.drawable.ic_rounded_corner_active)
        } else {
            remoteViews.setInt(R.id.image_switch, "setImageResource", R.drawable.ic_play_24dp)
            remoteViews.setInt(R.id.layout_background, "setBackgroundResource", R.drawable.ic_rounded_corner_inactive)
        }

        for (appWidgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
        }
    }

    /**
     * This method is called when the BroadcastReceiver is receiving an Intent broadcast.
     * It handles widget click actions and updates the widget background based on the V2Ray service state.
     *
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received.
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (AppConfig.BROADCAST_ACTION_WIDGET_CLICK == intent.action) {
            // Nothing while the ad flow holds the tunnel, exactly as the tile
            // does: the widget reads "off" then, so a tap means connect, and
            // connecting would race the ad flow for the one VPN slot. The
            // session lasts seconds.
            if (SmartTunnel.ownsTheRunningTunnel(context)) {
                LogUtil.i(AppConfig.TAG, "widget: ignoring a tap while the ad flow holds the tunnel")
                return
            }
            // Same source as the icon above, or the two disagree: a widget
            // drawn from Android's view of the services and a tap decided by
            // the Xray controller's flag is how "stop" quietly became "start
            // again" on every sing-box connection.
            if (TunnelState.isRunning(context)) {
                LauncherManager.stopService(context)
            } else {
                LauncherManager.startServiceFromToggle(context)
            }
        } else if (AppConfig.BROADCAST_ACTION_ACTIVITY == intent.action) {
            AppWidgetManager.getInstance(context)?.let { manager ->
                when (intent.getIntExtra("key", 0)) {
                    // Not `true`: the broadcasts do not say whose tunnel they
                    // are about, and the ad flow's start sends START_SUCCESS
                    // like any other. Re-asked here instead.
                    AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                        updateWidgetBackground(
                            context, manager, manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java)),
                            userIsConnected(context)
                        )
                    }

                    AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_START_FAILURE, AppConfig.MSG_STATE_STOP_SUCCESS -> {
                        updateWidgetBackground(
                            context, manager, manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java)),
                            false
                        )
                    }
                }
            }
        }
    }
}
