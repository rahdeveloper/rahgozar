package com.rahgozar.app.ads

import android.app.Activity
import com.rahgozar.app.AppConfig
import com.rahgozar.app.panel.AdManager
import com.rahgozar.app.panel.AdSlot
import com.rahgozar.app.util.LogUtil

/**
 * The connect slot's scenario: the same shape as the splash's, wrapped around
 * the user's connect tap.
 *
 * Behind the caller's loading dialog: the Smart tunnel comes up (when the
 * panel requires one, with the same measured failover the splash uses), the
 * interstitial loads through it and is shown, the user dismisses it, and the
 * tunnel comes down — leaving the caller to dial the server the user actually
 * picked.
 *
 * Every failure inside collapses to "no ad this time": the tunnel is torn
 * down and the caller still connects. The one thing this flow must never do
 * is leave the smart tunnel as the connection the user ends up with.
 */
object ConnectAdFlow {

    /**
     * Runs smart-up → load → show → dismissed → smart-down.
     *
     * There is no "hold this ad back, another one just closed" parameter any
     * more, and its absence is the design: a tap with a parked splash ad to
     * show never gets here at all — `MainActivity.beginConnect` spends that
     * tap on the ad and the server list. This flow's ad is always the only one
     * of its tap, so there is nothing to space it out from.
     *
     * @param onScreenFree called exactly once, the moment nothing more of this
     *   flow's will cover the screen — right after the ad is dismissed, or
     *   straight away on every path that shows none. The caller opens its
     *   destination screen on it, and *when* it fires is the point: an ad has
     *   to land between two screens, so the next screen must arrive after the
     *   ad rather than be sitting there when it appears. Deliberately not the
     *   return of this function, which is several seconds later — the smart
     *   tunnel is drained and taken down first, and the user would spend that
     *   time back on the screen they tapped from.
     * @return whether a smart tunnel was brought up, so the caller knows to
     *   let the VPN interface go before starting its own.
     */
    suspend fun run(
        activity: Activity,
        onScreenFree: () -> Unit = {},
    ): Boolean {
        var handedOver = false
        val handOver = {
            if (!handedOver) {
                handedOver = true
                onScreenFree()
            }
        }
        return try {
            scenario(activity, handOver)
        } finally {
            // Every path that did not show an ad, plus every path that threw
            // or was cancelled. One place, rather than a call before each of
            // the seven returns below and a missing one after the next edit.
            handOver()
        }
    }

    private suspend fun scenario(
        activity: Activity,
        handOver: () -> Unit,
    ): Boolean {
        val placement = AdManager.placement(AdSlot.CONNECT)
        if (!placement.enabled || !placement.format.isFullScreen) return false
        if (AdInventory.tooSoon(placement)) {
            LogUtil.i(AppConfig.TAG, "ads: connect slot inside min_interval, skipping")
            return false
        }

        // Fail closed, exactly as the splash does: the panel said fills must
        // go through the Smart tunnel, so no tunnel means no request.
        val needSmart = AdGate.needsSmart(placement)
        if (needSmart) {
            if (!SmartTunnel.available) {
                LogUtil.w(AppConfig.TAG, "ads: connect ad skipped — no smart candidate")
                return false
            }
            if (!SmartTunnel.bringUp(activity)) {
                LogUtil.w(AppConfig.TAG, "ads: connect ad skipped — no smart candidate came up")
                // A failed bring-up can still have opened the interface for a
                // moment, so the caller is told to wait before dialling.
                // Nothing was requested, so there is nothing to drain.
                SmartTunnel.stopNow(activity)
                return true
            }
        }

        // Believing the tunnel came up is not the same as knowing this app is
        // inside it — see [AdGate.pathReady]. Nothing is requested until it is.
        if (!AdGate.pathReady(activity, placement)) {
            handOver()
            SmartTunnel.stop(activity)
            return needSmart
        }

        AdManager.ensureStarted(activity)
        val ad = AdInventory.load(
            activity.applicationContext, placement, placement.loadTimeoutMs.toLong(),
        )
        if (ad == null) {
            LogUtil.i(AppConfig.TAG, "ads: connect ad did not fill, connecting without it")
        } else {
            AdInventory.showAndAwait(activity, ad)
        }
        // The screen is the caller's again *now*, not after the teardown — the
        // drain below takes seconds and has nothing to do with the user's tap.
        // On the ad path this is also the ordering the whole callback exists
        // for: the next screen arrives after the ad, not under it.
        handOver()
        SmartTunnel.stop(activity)
        return needSmart
    }
}
