package com.rahgozar.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.rahgozar.app.AppConfig
import com.rahgozar.app.R
import com.rahgozar.app.ads.SmartTunnel
import com.rahgozar.app.core.CoreServiceManager
import com.rahgozar.app.core.LauncherManager
import com.rahgozar.app.helper.MessageHelper
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.Utils
import java.lang.ref.SoftReference

class QSTileService : TileService() {

    /**
     * Sets the state of the tile.
     * @param state The state to set.
     */
    fun setState(state: Int) {
        qsTile?.icon = Icon.createWithResource(applicationContext, R.drawable.ic_stat_name)
        if (state == Tile.STATE_INACTIVE) {
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.label = getString(R.string.app_name)
        } else if (state == Tile.STATE_ACTIVE) {
            qsTile?.state = Tile.STATE_ACTIVE
            qsTile?.label = CoreServiceManager.getRunningServerName()
        }

        qsTile?.updateTile()
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    /**
     * Whether a tunnel the *user* asked for is up.
     *
     * Two things this must get right, and it used to get both wrong.
     *
     * **Which tunnels count.** This asked `CoreServiceManager.isRunning()`,
     * which is the Xray core controller's flag in whichever process asked —
     * and sing-box runs its core in `:SingBoxDaemon` while OpenVPN never
     * touches that controller at all. So for two of the app's three cores the
     * tile read "off" while the user was connected, and a tap took the connect
     * branch: the service answered "already running, ignoring start" and the
     * tunnel could not be turned off from here at all. [TunnelState] asks
     * Android instead, which is the only way to see every process.
     *
     * **Whose tunnel it is.** For the few seconds an ad needs, the one that is
     * up belongs to the ad flow; the home screen deliberately reports
     * disconnected, and this tile has to say the same or the two halves of one
     * app disagree — with this one naming the panel's hidden smart server as
     * though the user had chosen it. See [SmartTunnel.ownsTheRunningTunnel].
     */
    private fun userIsConnected(): Boolean =
        TunnelState.isRunning(this) && !SmartTunnel.ownsTheRunningTunnel(this)

    /**
     * Applies a state the tunnel broadcast a moment ago, through the same mask.
     *
     * The broadcasts do not say whose tunnel they are about, and the ad flow's
     * start sends MSG_STATE_START_SUCCESS like any other. Taken at face value
     * they would light this tile up in the middle of an ad, which is exactly
     * what [userIsConnected] exists to prevent — so every route to "active"
     * goes through here.
     */
    private fun applyState(active: Boolean) = setState(
        if (active && !SmartTunnel.ownsTheRunningTunnel(this)) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
    )

    override fun onStartListening() {
        super.onStartListening()

        if (userIsConnected()) {
            setState(Tile.STATE_ACTIVE)
        } else {
            setState(Tile.STATE_INACTIVE)
        }
        mMsgReceive = ReceiveMessageHandler(this)
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(applicationContext, mMsgReceive, mFilter, Utils.receiverFlags())
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /**
     * Called when the tile stops listening.
     */
    override fun onStopListening() {
        super.onStopListening()

        try {
            applicationContext.unregisterReceiver(mMsgReceive)
            mMsgReceive = null
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to unregister receiver", e)
        }

    }

    /**
     * Called when the tile is clicked.
     */
    override fun onClick() {
        super.onClick()

        // Nothing, for the seconds an ad is being fetched.
        //
        // The tile reads inactive then (see [userIsConnected]), so a tap means
        // "connect" — and connecting would reach for a VPN slot the ad flow is
        // holding, with two VpnServices racing for it and Android picking the
        // winner. Stopping is no better: it kills a tunnel the user does not
        // know exists and takes the ad with it. The session is measured in
        // seconds and ends on its own, so the honest answer is to wait.
        if (SmartTunnel.ownsTheRunningTunnel(this)) {
            LogUtil.i(AppConfig.TAG, "tile: ignoring a tap while the ad flow holds the tunnel")
            return
        }

        when (qsTile.state) {
            Tile.STATE_INACTIVE -> {
                LauncherManager.startServiceFromToggle(this)
            }

            Tile.STATE_ACTIVE -> {
                LauncherManager.stopService(this)
            }
        }
    }

    private var mMsgReceive: BroadcastReceiver? = null

    private class ReceiveMessageHandler(context: QSTileService) : BroadcastReceiver() {
        var mReference: SoftReference<QSTileService> = SoftReference(context)
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val context = mReference.get()
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    context?.applyState(true)
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    context?.applyState(false)
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    context?.applyState(true)
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    context?.applyState(false)
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    context?.applyState(false)
                }
            }
        }
    }
}
