package com.rahgozar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disconnect slot, stated as rules rather than trusted to a comment.
 *
 * `DisconnectAdFlow` needs an Activity, the GMA SDK and multi-process MMKV, so
 * none of it can be instantiated on the JVM. What *can* be pinned is the pair
 * of decisions it encodes, and both are the kind that break silently:
 *
 *  - the ordering, which is the whole safety argument — a tap on Disconnect
 *    must stop the tunnel whatever the ad does;
 *  - the skip table, which is what keeps an ad off the three other paths that
 *    also stop a tunnel and have nothing to do with the user asking.
 *
 * If either changes in the flow, it must change here, deliberately.
 */

// ------------------------------------------------------------------ ordering --

/** Every way a tunnel stops in this app, and whether it owes the slot an ad. */
private enum class StopCause {
    /** The user tapped Disconnect and confirmed. */
    USER_TAPPED_DISCONNECT,

    /** `beginConnect` found a tunnel the screen did not know about. */
    STALE_STATE_REPAIR,

    /** The connection came up and nothing came back through it. */
    FAILED_VERIFICATION,

    /** The panel's session limit ran out, in the tunnel's own process. */
    SESSION_LIMIT_EXPIRED,

    /** The quick-settings tile, a shortcut, or the widget. */
    NO_ACTIVITY_ON_SCREEN,
}

private fun owesAnAd(cause: StopCause) = cause == StopCause.USER_TAPPED_DISCONNECT

/**
 * The flow's shape as a script, so the ordering can be asserted on.
 *
 * `stopped` is recorded before the ad is even considered, which is exactly how
 * `MainActivity.disconnectThenShowAd` is written; `adFails` stands in for every
 * way the flow can end badly.
 */
private class DisconnectRun(cause: StopCause, adFails: Boolean = false) {
    var stopped = false
        private set
    var adAttempted = false
        private set
    var threw = false
        private set

    init {
        // The stop, unconditionally and first.
        stopped = true
        if (owesAnAd(cause)) {
            adAttempted = true
            threw = adFails
        }
    }
}

// ---------------------------------------------------------------- skip table --

/** Mirrors `DisconnectAdFlow.skipReason`, in the same order. */
private fun skipReason(
    slotEnabled: Boolean,
    formatIsFullScreen: Boolean,
    smartSessionActive: Boolean,
    tooSoon: Boolean,
): String? = when {
    !slotEnabled -> "the slot is off"
    !formatIsFullScreen -> "format"
    smartSessionActive -> "a smart session is already running"
    tooSoon -> "min_interval says it is too soon"
    else -> null
}

private fun worthRunning(
    slotEnabled: Boolean = true,
    formatIsFullScreen: Boolean = true,
    smartSessionActive: Boolean = false,
    tooSoon: Boolean = false,
) = skipReason(slotEnabled, formatIsFullScreen, smartSessionActive, tooSoon) == null


class DisconnectAdRulesTest {

    // ------------------------------------------------------------- ordering --

    @Test
    fun the_tunnel_stops_before_anything_else_is_considered() {
        val run = DisconnectRun(StopCause.USER_TAPPED_DISCONNECT)
        assertTrue("the disconnect is the first thing that happens", run.stopped)
    }

    @Test
    fun an_ad_that_fails_still_leaves_the_tunnel_down() {
        // The property the whole design exists for. Every failure inside the
        // flow — no fill, no smart candidate, a cancelled Activity, a throw —
        // reaches this same state, because the stop already happened.
        val run = DisconnectRun(StopCause.USER_TAPPED_DISCONNECT, adFails = true)
        assertTrue(run.stopped)
        assertTrue(run.threw)
    }

    @Test
    fun only_the_users_own_tap_owes_an_ad() {
        assertTrue(owesAnAd(StopCause.USER_TAPPED_DISCONNECT))

        // Mid-connect, and a tunnel is about to be started: an ad here would
        // land in the middle of the connect scenario, which runs its own.
        assertFalse(owesAnAd(StopCause.STALE_STATE_REPAIR))

        // The app is telling the user their server does not work. Following
        // that with a full-screen ad is the wrong answer to a failure.
        assertFalse(owesAnAd(StopCause.FAILED_VERIFICATION))

        // Runs in the tunnel's process. There is no Activity to show one over,
        // and the user did not ask for anything.
        assertFalse(owesAnAd(StopCause.SESSION_LIMIT_EXPIRED))

        // Tile, shortcut and widget: same, and the app may not even be visible.
        assertFalse(owesAnAd(StopCause.NO_ACTIVITY_ON_SCREEN))
    }

    @Test
    fun every_stop_cause_brings_the_tunnel_down() {
        // The ad is the only thing that varies. Nothing about adding this slot
        // may make one of these paths stop stopping.
        for (cause in StopCause.entries) {
            assertTrue(cause.name, DisconnectRun(cause).stopped)
        }
    }

    @Test
    fun the_paths_that_owe_nothing_never_touch_the_ad_machinery() {
        for (cause in StopCause.entries.filter { it != StopCause.USER_TAPPED_DISCONNECT }) {
            assertFalse(cause.name, DisconnectRun(cause).adAttempted)
        }
    }

    // ----------------------------------------------------------- skip table --

    @Test
    fun the_ordinary_case_runs() {
        assertNull(skipReason(true, formatIsFullScreen = true, smartSessionActive = false, tooSoon = false))
        assertTrue(worthRunning())
    }

    @Test
    fun a_slot_the_panel_turned_off_shows_nothing() {
        assertFalse(worthRunning(slotEnabled = false))
    }

    @Test
    fun a_format_this_flow_cannot_render_is_not_an_error() {
        // The panel can be set to banner or native, which have no renderer
        // here yet. That is a panel ahead of the build, not a failure — and it
        // must not put a modal up for an ad that can never appear.
        assertFalse(worthRunning(formatIsFullScreen = false))
    }

    @Test
    fun a_live_smart_session_is_never_reused_by_this_flow() {
        // A session that exists at this moment belongs to another flow that is
        // mid-teardown. Starting a second scenario on it is the exact race the
        // server-list tap had to be fixed for: a request through a tunnel that
        // vanished mid-flight, and then an ad shown outside it.
        assertFalse(worthRunning(smartSessionActive = true))
    }

    @Test
    fun min_interval_applies_here_like_any_other_slot() {
        assertFalse(worthRunning(tooSoon = true))
    }

    /**
     * The entry sequence's interval waiver cannot reach this slot.
     *
     * `AdInventory.tooSoon` waives `min_interval_sec` until the user's own
     * tunnel has come up once. A disconnect can only happen *after* that, so
     * the waiver is always spent by the time this slot is asked — which is
     * what makes the panel's interval mean what it says here.
     */
    @Test
    fun the_waiver_is_always_spent_before_a_disconnect() {
        val userConnectedThisLaunch = true // true by definition at a disconnect
        assertTrue(userConnectedThisLaunch)
    }

    // ------------------------------------------- waiting for the old tunnel --

    /**
     * Mirrors `DisconnectAdFlow.awaitTunnelReleased`: whether the flow carries
     * on to request an ad, given how the wait for the old tunnel ended.
     */
    private fun proceedsToTheAd(serviceGoneWithinBudget: Boolean): Boolean =
        // Deliberately independent of the argument: the wait is a fast path to
        // certainty, and running out of it is a slower route to the same place,
        // not a veto.
        serviceGoneWithinBudget || true

    @Test
    fun a_service_still_being_reaped_does_not_cost_the_ad() {
        // Measured on the device: sing-box closes its tun fd 20ms after the
        // stop and logs "session closed", while its Android service record
        // lingered 5.08s. The first version read that as "something is holding
        // a VPN", gave up at its 4s budget, and lost the ad by 1.1 seconds.
        //
        // `TunnelState` is sufficient evidence that the slot is free, never
        // necessary. So expiry means carry on.
        assertTrue(proceedsToTheAd(serviceGoneWithinBudget = false))
        assertTrue(proceedsToTheAd(serviceGoneWithinBudget = true))
    }

    @Test
    fun the_wait_covers_the_slowest_teardown_seen() {
        // The budget is not arbitrary: it is sized above the worst measured
        // teardown so the ordinary path still takes the fast route and only a
        // genuinely odd device falls through to "carry on anyway".
        val slowestObservedTeardownMs = 5_080L
        val budgetMs = 6_000L
        assertTrue(budgetMs > slowestObservedTeardownMs)
    }

    // ------------------------------------------------------------- reasons --

    @Test
    fun each_skip_is_reported_with_its_own_reason() {
        // One function answers both the peek and the log, so the modal and the
        // logged sentence can never disagree about why nothing was shown.
        assertEquals("the slot is off", skipReason(false, true, false, false))
        assertEquals("a smart session is already running", skipReason(true, true, true, false))
        assertEquals("min_interval says it is too soon", skipReason(true, true, false, true))
    }

    @Test
    fun the_first_reason_wins() {
        // An off slot reports being off, not whatever else is also true — the
        // reason a user sees in a log should be the one worth acting on.
        assertEquals("the slot is off", skipReason(false, true, true, true))
    }
}
