package com.rahgozar.app.ui.shortcut

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.rahgozar.app.ads.SmartTunnel
import com.rahgozar.app.core.LauncherManager
import com.rahgozar.app.service.TunnelState
import com.rahgozar.app.ui.base.BaseComponentActivity

class ScSwitchActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        LaunchedEffect(Unit) {
            moveTaskToBack(true)
            if (userIsConnected()) {
                LauncherManager.stopService(this@ScSwitchActivity)
            } else {
                LauncherManager.startServiceFromToggle(this@ScSwitchActivity)
            }
            finish()
        }
    }

    // Asked of Android, as the tile and widget do. CoreServiceManager.isRunning()
    // only sees the Xray core of this process, so on sing-box, OpenVPN and
    // Aether this shortcut never saw the tunnel. An ad-flow tunnel is not the
    // user's; see [SmartTunnel.ownsTheRunningTunnel].
    private fun userIsConnected(): Boolean =
        TunnelState.isRunning(this) && !SmartTunnel.ownsTheRunningTunnel(this)
}
