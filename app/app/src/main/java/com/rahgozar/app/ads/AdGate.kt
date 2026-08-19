package com.rahgozar.app.ads

import android.content.Context
import com.rahgozar.app.AppConfig
import com.rahgozar.app.panel.AdPlacement
import com.rahgozar.app.panel.PanelStore
import com.rahgozar.app.util.LogUtil

/**
 * The one place that answers "may this placement ask Google for an ad right
 * now".
 *
 * There is exactly one rule and it has two halves: does this placement need
 * the smart tunnel, and is this app's traffic actually inside it. Every flow
 * used to answer both halves for itself, and the cost of that was two real
 * defects. The `splash_second` placement's `require_smart` was never read by
 * anyone — the second splash ad simply inherited whatever the first slot had
 * decided, so an operator who set the two differently got the opposite of
 * what the panel showed them. And no flow ever re-asked the second half after
 * bring-up, so a tunnel that died mid-flow took the ad request with it onto
 * the user's real address.
 *
 * Both halves live here now, so a placement added later inherits the rule
 * instead of having to remember it.
 */
object AdGate {

    /**
     * Whether this placement's fills must travel through the smart tunnel.
     *
     * Two flags, both from the panel: the placement's own `require_smart`,
     * and whether this device's country needs a tunnel at all. Either one off
     * means the ad may go out directly — which is the ordinary case in the
     * countries the panel exempts, and is not a leak.
     */
    fun needsSmart(placement: AdPlacement): Boolean =
        placement.requireSmart && PanelStore.smartRequired

    /**
     * Whether the path this placement requires exists *now*, waiting briefly
     * for a tunnel that has just come up.
     *
     * For the moment before a request. False means "do not ask" — never "ask
     * anyway and hope", which is the failure mode this replaces.
     */
    suspend fun pathReady(context: Context, placement: AdPlacement): Boolean {
        if (!needsSmart(placement)) return true
        if (!SmartTunnel.awaitCarrying(context)) {
            LogUtil.w(
                AppConfig.TAG,
                "ads: ${placement.slot.wire} needs the smart tunnel and it is not carrying us — skipping",
            )
            return false
        }

        // Carrying is not the same as working, and the difference is a whole
        // ad. See [SmartTunnel.awaitReachable]: the request that goes out
        // before the tunnel's far end has answered does not fail fast, it
        // hangs — past our budget, and past the point where showing it would
        // still make sense.
        //
        // A "no" here is not a veto. The tunnel demonstrably has this app's
        // packets, which is the leak question and the only one this gate is
        // entitled to refuse on; whether the path is quick today is the ad's
        // own problem. So this only ever costs time, never a fill.
        if (!SmartTunnel.awaitReachable()) {
            LogUtil.w(
                AppConfig.TAG,
                "ads: nothing came back through the smart tunnel yet — asking anyway",
            )
        }
        return true
    }

    /**
     * [pathReady] without the wait, for decisions made while a session is
     * already known to be up — chiefly whether a second ad is worth
     * preloading at all.
     */
    fun pathHolds(context: Context, placement: AdPlacement): Boolean =
        !needsSmart(placement) || SmartTunnel.isCarrying(context)
}
