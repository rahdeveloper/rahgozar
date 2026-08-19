package com.rahgozar.app.ads

import android.app.Activity
import android.content.Context
import com.rahgozar.app.AppConfig
import com.rahgozar.app.panel.AdManager
import com.rahgozar.app.panel.AdPlacement
import com.rahgozar.app.panel.AdSlot
import com.rahgozar.app.service.TunnelState
import com.rahgozar.app.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The disconnect slot's scenario: [ConnectAdFlow] with the order reversed, and
 * the reversal is the entire design.
 *
 * The user's tunnel is already down before anything here runs. That is not an
 * implementation detail, it is the rule: a tap on Disconnect means "stop
 * carrying my traffic", and an app that held the tunnel open to show an ad
 * first would be answering a different question than the one it was asked. So
 * the caller stops the service unconditionally and only then hands over — if
 * every line below throws, the disconnect has still happened.
 *
 * What is left is an ordinary ad with no tunnel: the smart one comes up (when
 * the panel requires it), the interstitial loads through it and is shown, and
 * it comes down again. Bringing a VPN back up moments after the user turned
 * one off sounds worse than it is — [PerAppProxy][com.rahgozar.app.service.PerAppProxy]
 * builds the smart tunnel as an allow list of this app, Play services, the
 * store and the browsers a tap could open. The user's own traffic is outside
 * it, which it was not a moment ago.
 *
 * Every failure collapses to "no ad this time". Nothing here can put the
 * tunnel back or leave the user connected to something they did not choose.
 */
object DisconnectAdFlow {

    /**
     * Whether this disconnect owes an ad at all, asked before the screen is
     * covered.
     *
     * The caller needs this *first*: the flow puts a modal up for the whole of
     * itself, and a modal that appears after a disconnect and then closes with
     * nothing shown is worse than no ad.
     *
     * It logs, because this is where the answer is usually decided and the
     * caller simply returns. Without a line here a skipped slot looks exactly
     * like a slot nobody wired up — which is the reading a live log run gave it
     * the first time, and it cost a round of testing to tell the two apart.
     */
    fun worthRunning(): Boolean {
        val reason = skipReason(AdManager.placement(AdSlot.DISCONNECT)) ?: return true
        LogUtil.i(AppConfig.TAG, "ads: no disconnect ad — $reason")
        return false
    }

    /**
     * Why this disconnect is not showing an ad, or null when it is.
     *
     * One function so the peek and the authority cannot drift: [worthRunning]
     * reads the answer, [run] reads the sentence.
     */
    private fun skipReason(placement: AdPlacement): String? = when {
        !placement.enabled -> "the slot is off"
        !placement.format.isFullScreen ->
            "${placement.format.wire} is not a format this flow can show"
        // A disconnect cannot reach here during the entry sequence — the home
        // screen reports "disconnected" while a smart session is up, so there
        // is no Disconnect to tap. If one ever did, the session belongs to
        // another flow that is mid-teardown, and starting a second scenario on
        // it is the exact race the connect path already had to be fixed for.
        SmartTunnel.isActive -> "a smart session is already running"
        AdInventory.tooSoon(placement) -> "min_interval says it is too soon"
        else -> null
    }

    /**
     * Runs tunnel-gone → smart-up → load → show → smart-down.
     *
     * The tunnel is *already* being stopped when this is entered; the first
     * thing it does is wait for that to finish, because Android runs one VPN
     * at a time and the smart one cannot take a slot the old core has not let
     * go of yet.
     *
     * Only the preparation is on a budget. A show is never interrupted — the
     * impression has been paid for by then, and cutting an ad off mid-frame
     * would be the one failure that costs the operator money rather than time.
     *
     * @param onScreenFree called exactly once, the moment nothing more of this
     *   flow's will cover the screen — right after the ad is dismissed, or
     *   straight away when there is none to show. The caller opens its session
     *   summary on it, which is what puts this ad *between* two screens rather
     *   than over the one the user never left. Not the return of this
     *   function: that is after the smart tunnel's drain, several seconds of
     *   the user staring at the screen they tapped from.
     */
    suspend fun run(activity: Activity, onScreenFree: () -> Unit = {}) {
        var handedOver = false
        val handOver = {
            if (!handedOver) {
                handedOver = true
                onScreenFree()
            }
        }

        val placement = AdManager.placement(AdSlot.DISCONNECT)
        skipReason(placement)?.let {
            // Only reachable when the answer changed between the caller's peek
            // and this call — a sync landing in that gap, or a session started
            // by something else. Louder than the peek's line, because a modal
            // is already on screen for an ad that is not coming.
            LogUtil.w(AppConfig.TAG, "ads: disconnect ad dropped after the tap — $it")
            handOver()
            return
        }

        try {
            val ad = withTimeoutOrNull(PREPARE_BUDGET_MS) { prepare(activity, placement) }
            if (ad == null) {
                LogUtil.i(AppConfig.TAG, "ads: disconnect ad not shown, the tunnel is already down")
                return
            }
            AdInventory.showAndAwait(activity, ad)
        } finally {
            // Before the teardown, and on every path out of the block above —
            // an expired budget, a throw, a cancelled scope. The summary is
            // what the user has to look at while the tunnel drains.
            handOver()
            // The one line that must run whatever happened above — a budget
            // that expired, a cancelled scope, a throw from the SDK. A smart
            // session left standing carries this app's traffic through the
            // operator's server with no flow left to end it; the watchdog in
            // [SessionLimit] would eventually catch it, three minutes later.
            //
            // NonCancellable because the caller runs this on an Activity scope,
            // and a rotation or a swipe from recents cancels it mid-teardown.
            withContext(NonCancellable) { SmartTunnel.stop(activity) }
        }
    }

    /** Everything up to having an ad in hand. Null at the first thing missing. */
    private suspend fun prepare(activity: Activity, placement: AdPlacement): LoadedAd? {
        awaitTunnelReleased(activity)

        if (AdGate.needsSmart(placement)) {
            if (!SmartTunnel.available) {
                LogUtil.w(AppConfig.TAG, "ads: disconnect ad skipped — no smart candidate")
                return null
            }
            if (!SmartTunnel.bringUp(activity)) {
                LogUtil.w(AppConfig.TAG, "ads: disconnect ad skipped — no smart candidate came up")
                // A failed bring-up can still leave a half-open session behind.
                // Nothing was requested, so there is nothing to drain.
                SmartTunnel.stopNow(activity)
                return null
            }
        }

        // Believing the tunnel came up is not the same as knowing this app is
        // inside it — see [AdGate.pathReady]. Nothing is requested until it is.
        if (!AdGate.pathReady(activity, placement)) return null

        AdManager.ensureStarted(activity)
        return AdInventory.load(
            activity.applicationContext, placement, placement.loadTimeoutMs.toLong(),
        )
    }

    /**
     * Waits for the tunnel the user just ended to let go of the VPN slot.
     *
     * Asked of Android rather than of a broadcast, for the same reason
     * [TunnelState] exists at all: the stop is a message, and the service is
     * gone some time after it is delivered. Starting the smart tunnel into that
     * gap means two VpnServices reaching for one slot.
     *
     * **A timeout here is not a veto**, and that distinction cost an ad on the
     * device before it was understood. `TunnelState` answers "is one of our
     * services in `getRunningServices`", which is *sufficient* evidence that
     * the slot is free but not *necessary*: sing-box closes its tun fd inside
     * `stopVpn` and logs "session closed" twenty milliseconds after the stop,
     * while its Android service record lingered for a further five seconds on
     * the device — a foreground service being reaped, nothing to do with the
     * tun. The first version treated that lingering as "something is holding a
     * VPN" and dropped the ad, missing by 1.1s. Nor can the leftover block the
     * start: the only lock in the way, `CoreVpnService.tryLockStart`, is an
     * AtomicBoolean private to that class in a different process.
     *
     * So the wait is a fast path, not a gate. If it expires the flow carries
     * on and lets [SmartTunnel.bringUp] be the authority on whether a tunnel
     * can actually come up, which it already is for every other caller.
     *
     * Off the main thread, unlike most of this file. The question underneath is
     * `ActivityManager.getRunningServices`, a binder round trip over a list the
     * system assembles each time, and it is asked several times a second — with
     * a spinner animating over the screen for the whole of it.
     */
    private suspend fun awaitTunnelReleased(context: Context) {
        val gone = withContext(Dispatchers.Default) {
            withTimeoutOrNull(TUNNEL_DOWN_WAIT_MS) {
                while (TunnelState.isRunning(context)) delay(TUNNEL_DOWN_POLL_MS)
                true
            }
        } ?: false

        if (!gone) {
            LogUtil.w(
                AppConfig.TAG,
                "ads: the old tunnel's service is still being reaped — bringing the ad tunnel up anyway",
            )
            return
        }
        // The service being gone and the VPN interface being released are not
        // the same instant; the connect path already allows for the same gap
        // in the other direction.
        delay(INTERFACE_RELEASE_MS)
    }

    /**
     * How long the screen may be held before an ad is on it.
     *
     * Shorter than the connect path's budget on purpose. A user waiting to be
     * connected is waiting for the thing they asked for; a user who has just
     * disconnected is waiting for nothing, and their patience is the smaller
     * of the two. Past this the flow gives up quietly and hands the screen
     * back — the disconnect itself happened long before.
     */
    private const val PREPARE_BUDGET_MS = 20_000L

    /**
     * How long to wait for certainty that the old tunnel is gone.
     *
     * Sized from the device: Xray's service is reaped in 0.15s, sing-box's took
     * 0.74s once and 5.08s another time, and it is the Android teardown that
     * varies, not the tun. Six seconds covers the slow case, and going over is
     * cheap now that expiry means "carry on" rather than "no ad".
     */
    private const val TUNNEL_DOWN_WAIT_MS = 6_000L

    /** Poll interval while waiting for that. */
    private const val TUNNEL_DOWN_POLL_MS = 150L

    /** Long enough for a stopped core to hand the VPN interface back. */
    private const val INTERFACE_RELEASE_MS = 600L
}
