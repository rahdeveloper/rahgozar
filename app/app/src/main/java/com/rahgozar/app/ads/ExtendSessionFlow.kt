package com.rahgozar.app.ads

import android.app.Activity
import com.rahgozar.app.AppConfig
import com.rahgozar.app.panel.AdManager
import com.rahgozar.app.panel.AdSlot
import com.rahgozar.app.util.LogUtil

/**
 * Buys the user more connection time in exchange for a rewarded ad.
 *
 * The one ad in this app the user asks for. That changes the rules: it never
 * interrupts, it is only offered while a connection is running, and the time
 * is granted only if the SDK says the ad was actually watched — a user who
 * closes it early gets nothing, and is told so rather than silently ignored.
 *
 * It also never touches the smart tunnel. Android runs one VPN at a time, so
 * bringing that up would drop the very connection being extended; the request
 * rides the user's own tunnel instead, which [PerAppProxy] keeps this app
 * inside while timed sessions are on.
 */
object ExtendSessionFlow {

    /** What came of an extend attempt, in the terms the screen has to explain. */
    enum class Result {
        /** Time was added. */
        EXTENDED,

        /** The panel's cap for this connection is used up. */
        CAPPED,

        /** No ad to show — none filled, or the placement is off. */
        UNAVAILABLE,

        /** The ad was shown but closed before it was earned. */
        NOT_EARNED,
    }

    /**
     * Whether the button should be on screen at all.
     *
     * Includes the condition that this app can actually reach Google over the
     * user's tunnel — see [SessionLimit.ridesUserTunnel]. Offering an
     * extension the device has no way to fetch would be a button that does
     * nothing, which is worse than no button.
     */
    fun offered(): Boolean {
        val placement = AdManager.placement(AdSlot.EXTEND_SESSION)
        return SessionLimit.ridesUserTunnel && placement.enabled && placement.format.isRewarded
    }

    /** Minutes one ad is worth, for the button's label. */
    fun minutes(): Int = SessionLimit.durationMinutes

    suspend fun run(activity: Activity): Result {
        if (!offered()) return Result.UNAVAILABLE
        if (!SessionLimit.canExtend()) return Result.CAPPED

        // The countdown must not cut the tunnel from under this.
        //
        // The ad travels through the user's own connection, which is the one
        // about to expire — and the moment someone actually taps this button
        // is the moment it is nearly gone. Without the hold the tap destroyed
        // its own request: deadline, tunnel down, "Internal error", no ad and
        // no extra time. Released in `finally` so no path can leave the
        // connection running on a hold nobody is using.
        SessionLimit.holdForExtend()
        try {
            val placement = AdManager.placement(AdSlot.EXTEND_SESSION)
            AdManager.ensureStarted(activity)

            val ad = AdInventory.load(
                activity.applicationContext, placement, placement.loadTimeoutMs.toLong(),
            ) ?: return Result.UNAVAILABLE

            val earned = AdInventory.showForReward(activity, ad)
            if (!earned) {
                LogUtil.i(AppConfig.TAG, "ads: rewarded closed early — no time granted")
                return Result.NOT_EARNED
            }

            // Re-checked after the ad, not before it only: the cap could have
            // been reached by another extension while this one was on screen.
            return if (SessionLimit.extend()) Result.EXTENDED else Result.CAPPED
        } finally {
            SessionLimit.releaseExtendHold()
        }
    }
}
