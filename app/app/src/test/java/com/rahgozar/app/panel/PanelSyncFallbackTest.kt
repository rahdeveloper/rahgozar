package com.rahgozar.app.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app may move on to another panel address.
 *
 * The distinction is easy to get backwards and expensive both ways. Too
 * cautious and a blocked domain ends the app; too eager and a device the panel
 * has refused asks every mirror in turn for the same refusal — slower for the
 * user, and a burst of traffic that makes the mirrors themselves worth
 * blocking.
 */
class PanelSyncFallbackTest {

    @Test
    fun `an address that never answered is replaced`() {
        assertTrue(PanelSync.worthAnotherAddress(PanelSync.Result.Unavailable("panel unreachable")))
        assertTrue(PanelSync.worthAnotherAddress(PanelSync.Result.Unavailable("signature rejected")))
    }

    @Test
    fun `a panel that answered and refused is believed`() {
        // The same panel behind another name would refuse identically.
        assertFalse(
            PanelSync.worthAnotherAddress(
                PanelSync.Result.Unavailable("registration expired", answered = true)
            )
        )
    }

    @Test
    fun `a fatal answer ends the attempt`() {
        assertFalse(
            PanelSync.worthAnotherAddress(
                PanelSync.Result.Unavailable("this device is blocked", fatal = true)
            )
        )
        assertFalse(
            PanelSync.worthAnotherAddress(
                PanelSync.Result.Unavailable("this build is not registered", fatal = true)
            )
        )
    }

    @Test
    fun `a configuration in hand ends the walk`() {
        assertFalse(
            PanelSync.worthAnotherAddress(
                PanelSync.Result.Ready(changed = true, PanelSettings.parse(""), AdsConfig.parse(""))
            )
        )
    }

    @Test
    fun `a gate decision ends the walk`() {
        assertFalse(
            PanelSync.worthAnotherAddress(
                PanelSync.Result.Blocked(PanelGate.Decision.Allow, PanelSettings.parse(""))
            )
        )
    }

    @Test
    fun `a rate-limited sync never walks the other addresses`() {
        // The amplification this exists to prevent, measured before the fix:
        // a 429 read as "unreachable", so the walk asked every other address —
        // the same panel, the same limiter — spending another token of the
        // same carrier-NAT budget per address, then fired discovery on top.
        assertFalse(
            PanelSync.worthAnotherAddress(
                PanelSync.Result.Unavailable("the panel is shedding load", answered = true)
            )
        )
    }
}

/**
 * When a rate-limited launch waits, and when it opens on the stored
 * configuration instead. The arithmetic behind `PanelSync`'s single retry —
 * pinned as a table because every rule in it is anti-stampede: crowds behind
 * one carrier address were refused together, and anything that makes them
 * return together re-creates the refusal.
 */
class RateLimitWaitRulesTest {

    @Test
    fun `waits exactly the named delay plus its jitter`() {
        assertEquals(
            3_400L,
            PanelSync.rateLimitWaitMillis(retryAfterSeconds = 3, alreadyRetried = false, jitterMillis = 400),
        )
    }

    @Test
    fun `only one retry per launch`() {
        // The refill on a shared address is a fixed trickle. A launch that
        // keeps retrying spends it on a user already staring at the splash,
        // instead of on the next user's first attempt.
        assertNull(
            PanelSync.rateLimitWaitMillis(retryAfterSeconds = 1, alreadyRetried = true, jitterMillis = 0),
        )
    }

    @Test
    fun `no named wait means no retry`() {
        // Guessing a wait against an unknown limiter is how storms start.
        assertNull(
            PanelSync.rateLimitWaitMillis(retryAfterSeconds = null, alreadyRetried = false, jitterMillis = 0),
        )
    }

    @Test
    fun `a wait past the cap opens the app on the stored configuration`() {
        // Failure is normal for the sync by design; holding the splash for
        // longer than the cap buys nothing the next launch would not.
        assertNull(
            PanelSync.rateLimitWaitMillis(
                retryAfterSeconds = PanelSync.RATE_LIMIT_WAIT_CAP_SECONDS + 1,
                alreadyRetried = false,
                jitterMillis = 0,
            ),
        )
        assertEquals(
            PanelSync.RATE_LIMIT_WAIT_CAP_SECONDS * 1000,
            PanelSync.rateLimitWaitMillis(
                retryAfterSeconds = PanelSync.RATE_LIMIT_WAIT_CAP_SECONDS,
                alreadyRetried = false,
                jitterMillis = 0,
            ),
        )
    }

    @Test
    fun `a malformed negative wait is refused rather than obeyed`() {
        assertNull(
            PanelSync.rateLimitWaitMillis(retryAfterSeconds = -5, alreadyRetried = false, jitterMillis = 0),
        )
    }
}
