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
import com.rahgozar.app.service.TunnelState

/**
 * The home-screen widget.
 *
 * It draws, and nothing else. An `AppWidgetProvider` has to be exported —
 * Android itself sends it `APPWIDGET_UPDATE` — and *exported means reachable by
 * explicit intent from any app on the device*, whatever its intent filter says.
 * So the tap, which stops or starts the user's VPN, is handled by
 * [WidgetActionReceiver] instead, which is not exported.
 */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        render(context, appWidgetManager, appWidgetIds, userIsConnected(context))
    }

    companion object {

        /**
         * Whether a tunnel the *user* asked for is up — the same question the
         * quick-settings tile asks, answered the same way and for the same two
         * reasons. See `QSTileService.userIsConnected`: [TunnelState] because
         * the Xray controller's flag cannot see sing-box or OpenVPN, and the
         * smart session because the ad flow's tunnel is not the user's.
         */
        internal fun userIsConnected(context: Context): Boolean =
            TunnelState.isRunning(context) && !SmartTunnel.ownsTheRunningTunnel(context)

        /** Redraws every placed widget from the current tunnel state. */
        internal fun refresh(context: Context, connected: Boolean = userIsConnected(context)) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, WidgetProvider::class.java))
            if (ids.isEmpty()) return
            render(context, manager, ids, connected)
        }

        internal fun render(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            isRunning: Boolean,
        ) {
            val remoteViews = RemoteViews(context.packageName, R.layout.widget_switch)

            // Aimed at [WidgetActionReceiver], which is not exported. A
            // PendingIntent is fired with *our* identity whichever process is
            // holding it, so the launcher can still deliver this one — while an
            // app that simply broadcasts the same action reaches nothing.
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = AppConfig.BROADCAST_ACTION_WIDGET_CLICK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                R.id.layout_switch,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            remoteViews.setOnClickPendingIntent(R.id.layout_switch, pendingIntent)

            if (isRunning) {
                remoteViews.setInt(R.id.image_switch, "setImageResource", R.drawable.ic_stop_24dp)
                remoteViews.setInt(
                    R.id.layout_background, "setBackgroundResource",
                    R.drawable.ic_rounded_corner_active,
                )
            } else {
                remoteViews.setInt(R.id.image_switch, "setImageResource", R.drawable.ic_play_24dp)
                remoteViews.setInt(
                    R.id.layout_background, "setBackgroundResource",
                    R.drawable.ic_rounded_corner_inactive,
                )
            }

            for (appWidgetId in appWidgetIds) {
                appWidgetManager.updateAppWidget(appWidgetId, remoteViews)
            }
        }
    }
}
