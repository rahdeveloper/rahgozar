package com.rahgozar.app.ads

import android.app.Activity
import android.os.SystemClock
import com.rahgozar.app.AppConfig
import com.rahgozar.app.panel.AdManager
import com.rahgozar.app.panel.AdPlacement
import com.rahgozar.app.panel.AdSlot
import com.rahgozar.app.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The splash ad scenario, end to end.
 *
 * The order is the scenario: Smart tunnel up first (when the panel requires
 * it), then the first ad is loaded and shown right here on the splash, and
 * the moment it fills, the *second* ad starts loading behind it — so it is
 * ready by the time the user reaches the home screen and taps something. If
 * the first ad never fills, the user is not kept waiting: they go straight
 * in, and the second load gets a doubled timeout because nobody is staring
 * at a spinner for it any more.
 *
 * Every early return means the same thing — this launch shows no splash ad —
 * and none of them is an error the user should hear about. The tunnel is
 * deliberately *not* torn down on success: it stays up so the second ad's
 * show on the home screen still has a working path, and the user's first
 * action is what ends it (see [SmartTunnel]).
 */
object SplashAdFlow {

    /** The fail path's extra patience for ad #2, per the scenario. */
    private const val FAILURE_TIMEOUT_FACTOR = 2L

    /**
     * The whole splash's patience for its own ad, measured from the first
     * request — the wait for the tunnel to answer is already over by then, so
     * this is time spent on the ad and nothing else.
     *
     * Two attempts at the panel's 12s, near enough. Past this the user has been
     * looking at a progress indicator long enough that an ad is no longer the
     * thing they are waiting for.
     */
    private const val FIRST_AD_BUDGET_MS = 26_000L

    /**
     * Below this there is not enough left for an attempt to mean anything, and
     * asking anyway would only delay the home screen by the remainder.
     */
    private const val MIN_RETRY_BUDGET_MS = 6_000L

    /**
     * Runs the scenario. Returns when the splash should hand over to the app,
     * whichever way it went.
     *
     * @param onWaiting flips the splash to its waiting message once the flow
     *   is genuinely going to take a moment.
     * @param ensureVpnConsent asks Android for VPN consent (the system sheet)
     *   and answers whether the user granted it. Declining means no smart
     *   tunnel, which means no ads this launch — not a nagging loop.
     */
    suspend fun run(
        activity: Activity,
        onWaiting: () -> Unit,
        ensureVpnConsent: suspend () -> Boolean,
    ) {
        try {
            scenario(activity, onWaiting, ensureVpnConsent)
        } catch (cancelled: CancellationException) {
            // The splash was finished under us — a back press, or the task
            // being swiped away. The scope is gone, so nothing downstream will
            // ever end the session this flow opened, and a smart tunnel with
            // no owner carries the user's own browser through the operator's
            // server for as long as the phone stays on. Ending it here is the
            // only chance left.
            LogUtil.w(AppConfig.TAG, "ads: splash flow cancelled — closing the smart session")
            withContext(NonCancellable) { SmartTunnel.stopNow(activity) }
            throw cancelled
        }
    }

    private suspend fun scenario(
        activity: Activity,
        onWaiting: () -> Unit,
        ensureVpnConsent: suspend () -> Boolean,
    ) {
        val placement = AdManager.placement(AdSlot.SPLASH)
        if (!placement.enabled || !placement.format.isFullScreen) return
        if (AdInventory.tooSoon(placement)) {
            LogUtil.i(AppConfig.TAG, "ads: splash slot inside min_interval, skipping")
            return
        }

        if (AdGate.needsSmart(placement)) {
            // Fail closed: the panel said fills from here must go through the
            // Smart tunnel. No tunnel, no request — asking anyway is exactly
            // the traffic the flag exists to prevent.
            if (!SmartTunnel.available) {
                LogUtil.w(AppConfig.TAG, "ads: smart required but no smart profile, skipping")
                return
            }
            if (!ensureVpnConsent()) {
                LogUtil.i(AppConfig.TAG, "ads: VPN consent declined, skipping")
                return
            }
            onWaiting()
            if (!SmartTunnel.bringUp(activity)) {
                LogUtil.w(AppConfig.TAG, "ads: no smart candidate came up, skipping")
                SmartTunnel.stopNow(activity)
                return
            }
        } else {
            onWaiting()
        }

        // The tunnel says it started; this asks Android whether this app's
        // packets are actually inside it. A core can start on a server that
        // carries nothing, and the answer here is per-uid, so it is the
        // difference between believing and knowing.
        if (!AdGate.pathReady(activity, placement)) {
            endSession(activity)
            return
        }

        // Only now: the SDK's first network activity happens on a path that
        // works. See [AdManager.ensureStarted].
        AdManager.ensureStarted(activity)

        val app = activity.applicationContext
        val timeout = placement.loadTimeoutMs.toLong()

        // The second ad is its own placement, with its own switch. Off means
        // the splash owes the home screen nothing, so the session ends with
        // the first ad rather than being held open for one that will never be
        // asked for.
        val second = AdManager.placement(AdSlot.SPLASH_SECOND)
        val wantSecond = second.enabled &&
            second.format.isFullScreen &&
            !AdInventory.tooSoon(second) &&
            // Its own `require_smart`, which nothing used to read. This ad is
            // requested from here but shown on the home screen, so if it
            // needs a tunnel and this launch has none, the honest answer is
            // not to fetch it at all.
            AdGate.pathHolds(activity, second)

        val first = loadFirst(activity, placement, timeout)

        if (first == null) {
            // The scenario's fail path: the user has waited long enough for
            // an ad they never saw, so they go straight in — and ad #2 loads
            // behind the home screen with double the time to fill.
            if (wantSecond) {
                AdInventory.preload(app, second, second.loadTimeoutMs * FAILURE_TIMEOUT_FACTOR)
            } else {
                endSession(activity)
            }
            return
        }

        // Ad #2 starts loading the moment ad #1 fills, exactly so it loads
        // *while the user is watching* ad #1.
        if (wantSecond) AdInventory.preload(app, second, second.loadTimeoutMs.toLong())
        AdInventory.showAndAwait(activity, first)
        if (!wantSecond) endSession(activity)
    }

    /**
     * The first ad, asked for more than once if the first answer was nothing.
     *
     * "No fill" and "the network was not ready" arrive identically at this
     * layer, and only one of them is worth asking about again — but the second
     * is common enough on the path this app runs over that giving up after one
     * request means routinely showing nothing to a user who would have seen an
     * ad a few seconds later. So the slot gets a budget rather than a single
     * attempt, and the budget is the thing that decides when to stop.
     *
     * It is bounded, deliberately and visibly: whatever happens, the user is
     * on the home screen within [FIRST_AD_BUDGET_MS] of the first request. An
     * ad nobody waits for is worth more than an ad shown to somebody who has
     * given up on the app.
     *
     * Each retry re-asks the gate. The tunnel can end between attempts — the
     * watchdog, the user, another VPN app — and a second request sent after
     * that would go out on the real address, which is the one thing no amount
     * of patience is worth.
     */
    private suspend fun loadFirst(
        activity: Activity,
        placement: AdPlacement,
        timeoutMs: Long,
    ): LoadedAd? {
        val app = activity.applicationContext
        val deadline = SystemClock.elapsedRealtime() + FIRST_AD_BUDGET_MS
        while (true) {
            AdInventory.load(app, placement, timeoutMs)?.let { return it }

            val left = deadline - SystemClock.elapsedRealtime()
            if (left < MIN_RETRY_BUDGET_MS) return null
            if (!AdGate.pathHolds(activity, placement)) {
                LogUtil.w(AppConfig.TAG, "ads: not asking again — the tunnel is gone")
                return null
            }
            LogUtil.i(AppConfig.TAG, "ads: asking again — ${left}ms of the splash budget left")
        }
    }

    /**
     * Closes the smart session the splash opened.
     *
     * Only for the paths where nothing downstream will use it. When the second
     * ad is expected, the session is deliberately left up — the home screen's
     * first action is what ends it. Suspending because the teardown drains the
     * requests and pings still in flight; see [SmartTunnel.stop].
     */
    private suspend fun endSession(activity: Activity) {
        if (!SmartTunnel.isActive) return
        LogUtil.i(AppConfig.TAG, "ads: no second ad wanted — closing the smart session")
        SmartTunnel.stop(activity)
    }
}
