package com.rahgozar.app.ui.splash

import android.content.Context
import android.os.SystemClock
import com.rahgozar.app.service.TunnelState

/**
 * Whether this launch should read the panel again, or open the app on what it
 * already has.
 *
 * The rule is the user's, and it has three parts. Closing the app and opening
 * it again asks the panel afresh. Coming back to an app that is still running —
 * above all one that is connected — must not replay the splash. But an app left
 * sitting in the background for a while is not "still in use": the servers and
 * settings it holds have had time to go stale, so after [STALE_AFTER_MS] away
 * the next look at it starts over.
 *
 * What answers "same run" is a flag in memory, because memory is exactly what a
 * full close throws away. A live tunnel overrides everything: the app is
 * plainly in use, and re-reading the panel under a running connection is both
 * pointless and a chance to pull the server list out from under it.
 */
object SplashSession {

    /**
     * How long the app may sit unused before its configuration is treated as
     * stale. Fifteen minutes: long enough that flicking to another app and back
     * never costs the user a splash, short enough that a phone left in a pocket
     * all afternoon picks up whatever the panel changed meanwhile.
     */
    const val STALE_AFTER_MS = 15 * 60 * 1000L

    /** Cleared by the process dying, which is the definition being used. */
    @Volatile
    private var syncedThisRun = false

    /** When the app was last on screen, on the monotonic clock. */
    @Volatile
    private var lastForegroundAt = 0L

    /** Called once the splash has finished a sync of its own. */
    fun markSynced() {
        syncedThisRun = true
    }

    /**
     * Called while any screen of the app is in front, so "how long since the
     * user last had it open" is measurable. Stamped on both resume and pause:
     * the first keeps it fresh during use, the second records the moment they
     * left.
     */
    fun markForeground() {
        lastForegroundAt = SystemClock.elapsedRealtime()
    }

    /** True when the panel has already been read for this run of the app. */
    fun alreadySynced(context: Context): Boolean = shouldSkipSplash(
        tunnelRunning = TunnelState.isRunning(context),
        syncedThisRun = syncedThisRun,
        awayMillis = SystemClock.elapsedRealtime() - lastForegroundAt,
    )

    /**
     * The decision itself, with nothing Android in it so it can be tested.
     *
     * @param awayMillis time since the app was last on screen. Meaningless when
     *   [syncedThisRun] is false — the process is new and there is nothing to
     *   have been away from.
     */
    internal fun shouldSkipSplash(
        tunnelRunning: Boolean,
        syncedThisRun: Boolean,
        awayMillis: Long,
    ): Boolean = when {
        // Connected: the app is in use whatever the clock says, and a sync
        // here could replace the server the tunnel is running on.
        tunnelRunning -> true

        // A new process. This is what a full close leaves behind.
        !syncedThisRun -> false

        else -> awayMillis < STALE_AFTER_MS
    }
}
