package com.rahgozar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long the splash keeps asking for its ad.
 *
 * The real loop lives in `SplashAdFlow.loadFirst`, which needs an Activity and
 * the ad SDK, so what is pinned here is the arithmetic that decides when it
 * stops. Both directions of getting it wrong are bad in a way nobody reports:
 * stop too early and a launch that would have shown an ad shows none, keep
 * going and the user sits on a progress indicator wondering whether the app is
 * broken.
 */
class SplashAdBudgetTest {

    private val budgetMs = 26_000L
    private val minRetryMs = 6_000L
    private val attemptMs = 12_000L

    /** Mirrors the loop: how many requests a run of this shape would make. */
    private fun attempts(fillsOnAttempt: Int?, tunnelHoldsFor: Int = Int.MAX_VALUE): Int {
        var spent = 0L
        var made = 0
        while (true) {
            made++
            if (fillsOnAttempt == made) return made
            val left = budgetMs - (spent + attemptMs)
            spent += attemptMs
            if (left < minRetryMs) return made
            if (made >= tunnelHoldsFor) return made
        }
    }

    @Test
    fun a_fill_on_the_first_ask_is_one_request() {
        assertEquals(1, attempts(fillsOnAttempt = 1))
    }

    @Test
    fun nothing_at_all_costs_two_asks_and_then_stops() {
        // 26s of budget against 12s attempts: after the second there are 2s
        // left, which is not enough for a third to mean anything.
        assertEquals(2, attempts(fillsOnAttempt = null))
    }

    @Test
    fun the_second_ask_is_what_the_change_is_for() {
        // The case seen on the device: the first request went out before the
        // tunnel's far end had answered and hung past its budget; the next one,
        // on a path that was by then working, filled in four seconds.
        assertEquals(2, attempts(fillsOnAttempt = 2))
    }

    @Test
    fun a_tunnel_that_ends_between_asks_stops_the_loop() {
        // Not patience but safety: a request sent after the session ended goes
        // out on the user's real address.
        assertEquals(1, attempts(fillsOnAttempt = null, tunnelHoldsFor = 1))
    }

    @Test
    fun the_user_reaches_the_home_screen_within_the_budget() {
        // Whatever happens, the total time spent asking is bounded — the point
        // of a budget rather than a retry count.
        val worstCase = attempts(fillsOnAttempt = null) * attemptMs
        assertTrue("worst case $worstCase exceeds the budget", worstCase <= budgetMs)
        assertFalse(worstCase == 0L)
    }
}
