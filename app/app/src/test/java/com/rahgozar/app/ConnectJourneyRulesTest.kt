package com.rahgozar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the connect and disconnect taps put their screens, stated as rules.
 *
 * The screens themselves are Compose and the flows need an Activity, the GMA
 * SDK and a live tunnel, so none of it runs on the JVM. What can be pinned is
 * the **ordering**, which is the entire reason the two screens exist: a
 * full-screen ad is only allowed to sit *between* two screens, so the screen a
 * tap is heading for must open after the last ad of that tap, never before it.
 * A destination already on display when the ad arrives is a screen the ad
 * interrupted, which is the arrangement these replaced.
 *
 * It is the kind of rule that breaks silently — a hand-over moved a few lines
 * for a tidier state machine still compiles, still works, and quietly puts the
 * app back in breach.
 */

/** The things that happen on one tap, in the order they happen. */
private enum class Step {
    /** A full-screen ad was shown and dismissed. */
    AD,

    /** The tap's destination screen opened. */
    SCREEN,

    /** The ad flow's smart tunnel was drained and taken down. */
    TEARDOWN,

    /** The user's own tunnel was started. */
    DIAL,

    /** The user's own tunnel was stopped. */
    STOP,

    /** The ending session's figures were copied off the live state. */
    CAPTURE,

    /** The server list opened — the parked ad's destination. */
    SERVER_LIST,
}

/** What the connect slot did with its turn. */
private enum class ConnectAd { SHOWN, NO_FILL, SLOT_OFF }

/**
 * A tap on the dial, whole.
 *
 * The parked splash ad takes the tap over completely: it is shown, the server
 * list opens behind it, and the connect slot is not reached at all. That is
 * what keeps every full-screen ad to one user action with one destination —
 * the connect slot gets the *next* tap, when there is no parked ad left.
 */
private fun connectTap(
    parkedAd: Boolean = false,
    connectAd: ConnectAd = ConnectAd.SHOWN,
): List<Step> {
    if (parkedAd) {
        return listOf(Step.AD, Step.SERVER_LIST, Step.TEARDOWN)
    }
    return connectJourney(connectAd = connectAd)
}

/**
 * `MainActivity.beginConnect` and `ConnectAdFlow.run` as a script, for a tap
 * with no parked ad in the way.
 *
 * `handOver` is the idempotent hand-over both of them share: the flow fires it
 * where it knows the screen is free, and the Activity fires it again in case
 * the flow never did.
 */
private fun connectJourney(
    connectAd: ConnectAd = ConnectAd.SHOWN,
): List<Step> = buildList {
    var opened = false
    val handOver = {
        if (!opened) {
            opened = true
            add(Step.SCREEN)
        }
    }

    when (connectAd) {
        // Nothing to show: the flow's `finally` hands over immediately.
        ConnectAd.SLOT_OFF -> handOver()
        // Asked and got nothing. The hand-over still precedes the teardown.
        ConnectAd.NO_FILL -> {
            handOver()
            add(Step.TEARDOWN)
        }
        ConnectAd.SHOWN -> {
            add(Step.AD)
            handOver()
            add(Step.TEARDOWN)
        }
    }

    handOver()
    add(Step.DIAL)
}

/** `MainActivity.disconnectThenShowAd` and `DisconnectAdFlow.run` as a script. */
private fun disconnectJourney(adShown: Boolean = true): List<Step> = buildList {
    add(Step.CAPTURE)
    add(Step.STOP)
    if (adShown) add(Step.AD)
    add(Step.SCREEN)
    add(Step.TEARDOWN)
}

private fun List<Step>.lastIndexOfAd() = lastIndexOf(Step.AD)

class ConnectJourneyRulesTest {

    // ------------------------------------------------- the ordering rule --

    @Test
    fun the_connect_screen_opens_after_the_last_ad() {
        // The screen must come after the ad — that is what makes the ad a
        // transition between two screens rather than an interruption of one.
        val steps = connectTap(parkedAd = false, connectAd = ConnectAd.SHOWN)
        assertTrue("this run should contain an ad", steps.contains(Step.AD))
        assertTrue(
            "the screen opened before the ad in $steps",
            steps.indexOf(Step.SCREEN) > steps.lastIndexOfAd(),
        )
    }

    // ------------------------------- one ad, one action, one destination --

    @Test
    fun a_parked_ad_takes_the_whole_tap() {
        // The rule that keeps two full-screen ads off one tap. The parked ad
        // is shown, the server list is its destination, and the connect slot
        // is not reached — it gets the next tap instead.
        val steps = connectTap(parkedAd = true)
        assertEquals("exactly one ad on this tap: $steps", 1, steps.count { it == Step.AD })
        assertEquals(
            "the server list is the ad's destination",
            steps.indexOf(Step.AD) + 1,
            steps.indexOf(Step.SERVER_LIST),
        )
        assertTrue("the connect slot must not also run: $steps", !steps.contains(Step.SCREEN))
        assertTrue("this tap does not connect: $steps", !steps.contains(Step.DIAL))
    }

    @Test
    fun the_destination_opens_before_the_ad_tunnel_comes_down() {
        // Same rule as the connect path's, in the other flow: the drain is
        // seconds, and spending them back on the home screen makes the ad look
        // like it led nowhere.
        val steps = connectTap(parkedAd = true)
        assertTrue(
            "the teardown came first in $steps",
            steps.indexOf(Step.SERVER_LIST) < steps.indexOf(Step.TEARDOWN),
        )
    }

    @Test
    fun no_tap_ever_shows_two_full_screen_ads() {
        listOf(
            connectTap(parkedAd = true),
            connectTap(parkedAd = false, connectAd = ConnectAd.SHOWN),
            connectTap(parkedAd = false, connectAd = ConnectAd.NO_FILL),
            connectTap(parkedAd = false, connectAd = ConnectAd.SLOT_OFF),
            disconnectJourney(adShown = true),
            disconnectJourney(adShown = false),
        ).forEach { steps ->
            assertTrue("more than one ad in $steps", steps.count { it == Step.AD } <= 1)
        }
    }

    @Test
    fun the_disconnect_screen_opens_after_the_ad() {
        val steps = disconnectJourney(adShown = true)
        assertTrue(
            "the summary was already up when the ad arrived: $steps",
            steps.indexOf(Step.SCREEN) > steps.lastIndexOfAd(),
        )
    }

    @Test
    fun the_screen_opens_exactly_once() {
        // The hand-over is fired from several places on purpose, because no
        // single one of them is on every path. Two screens for one tap would
        // be the cost of getting that wrong.
        listOf(
            connectJourney(connectAd = ConnectAd.SHOWN),
            connectJourney(connectAd = ConnectAd.NO_FILL),
            connectJourney(connectAd = ConnectAd.SLOT_OFF),
        ).forEach { steps ->
            assertEquals("in $steps", 1, steps.count { it == Step.SCREEN })
        }
    }

    // ------------------------------------------- the screens are the app's --

    @Test
    fun the_screens_open_even_when_no_ad_runs() {
        // They are the connect and disconnect journeys, not containers for an
        // ad. A tap that behaved differently depending on whether an ad
        // happened to fill would be a worse app, and would make the screens
        // exactly the wrapper the placement rules are meant to prevent.
        val noAd = connectJourney(connectAd = ConnectAd.SLOT_OFF)
        assertTrue(!noAd.contains(Step.AD))
        assertTrue("no ad, still a screen: $noAd", noAd.contains(Step.SCREEN))

        val noAdDisconnect = disconnectJourney(adShown = false)
        assertTrue(!noAdDisconnect.contains(Step.AD))
        assertTrue(noAdDisconnect.contains(Step.SCREEN))
    }

    // ------------------------------------------------ what waits for what --

    @Test
    fun the_screen_does_not_wait_for_the_ad_tunnel_to_come_down() {
        // The drain is bounded at several seconds. Opening the screen after it
        // would leave the user on the screen they tapped from for the whole of
        // that — which is what happened before, and reads as the ad having
        // done nothing.
        val steps = connectJourney(connectAd = ConnectAd.SHOWN)
        assertTrue(
            "the teardown came first in $steps",
            steps.indexOf(Step.SCREEN) < steps.indexOf(Step.TEARDOWN),
        )
    }

    @Test
    fun the_users_tunnel_is_dialled_after_the_screen_is_up() {
        // Not a policy rule but a truthfulness one: the dial starts spinning
        // when the core starts, and it must not do that behind an ad.
        listOf(
            connectJourney(connectAd = ConnectAd.SHOWN),
            connectJourney(connectAd = ConnectAd.SLOT_OFF),
        ).forEach { steps ->
            assertTrue(
                "in $steps",
                steps.indexOf(Step.DIAL) > steps.indexOf(Step.SCREEN),
            )
            assertTrue(steps.indexOf(Step.DIAL) > steps.lastIndexOfAd())
        }
    }

    // ------------------------------------------------------- the summary --

    @Test
    fun the_session_figures_are_read_before_the_tunnel_stops() {
        // Every number on the summary comes from the live tunnel state, which
        // resets when the service goes down. Reading them when the screen is
        // finally shown — after an ad, half a minute later — reports a session
        // of zero seconds and no traffic.
        val steps = disconnectJourney()
        assertTrue(
            "the figures were read after the stop in $steps",
            steps.indexOf(Step.CAPTURE) < steps.indexOf(Step.STOP),
        )
    }

    @Test
    fun the_disconnect_still_happens_before_any_ad() {
        // The rule `DisconnectAdRulesTest` guards, restated here because the
        // summary added a step in front of it: capturing figures must not have
        // pushed the stop behind the ad.
        val steps = disconnectJourney()
        assertTrue(steps.indexOf(Step.STOP) < steps.lastIndexOfAd())
    }
}
