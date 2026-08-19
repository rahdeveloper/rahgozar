package com.rahgozar.app

import com.rahgozar.app.ui.splash.SplashSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When opening the app replays the splash, and when it does not.
 *
 * Every branch here is a rule the user stated, and each one is invisible in
 * normal use until it is wrong: too eager and a connected user watches the
 * panel being re-read over a live tunnel; too reluctant and a phone left in a
 * pocket all afternoon opens on servers that were retired hours ago.
 */
class SplashSessionTest {

    private val minute = 60_000L

    @Test
    fun `a fresh process reads the panel`() {
        // What a full close leaves behind: nothing in memory.
        assertFalse(
            SplashSession.shouldSkipSplash(
                tunnelRunning = false, syncedThisRun = false, awayMillis = 0,
            )
        )
    }

    @Test
    fun `coming straight back does not replay it`() {
        assertTrue(
            SplashSession.shouldSkipSplash(
                tunnelRunning = false, syncedThisRun = true, awayMillis = 5 * minute,
            )
        )
    }

    @Test
    fun `after fifteen minutes away it starts over`() {
        assertFalse(
            SplashSession.shouldSkipSplash(
                tunnelRunning = false, syncedThisRun = true, awayMillis = 15 * minute,
            )
        )
        assertFalse(
            SplashSession.shouldSkipSplash(
                tunnelRunning = false, syncedThisRun = true, awayMillis = 60 * minute,
            )
        )
    }

    @Test
    fun `a connected app is never interrupted, however long it sat`() {
        // Re-reading the panel here could replace the very server the tunnel
        // is running on, and the user did not ask for anything.
        assertTrue(
            SplashSession.shouldSkipSplash(
                tunnelRunning = true, syncedThisRun = true, awayMillis = 24 * 60 * minute,
            )
        )
    }

    @Test
    fun `a connected app whose UI process was reclaimed is still the same run`() {
        // Android kills the screen's process while the service lives on; to the
        // user nothing closed, so nothing should look like a cold start.
        assertTrue(
            SplashSession.shouldSkipSplash(
                tunnelRunning = true, syncedThisRun = false, awayMillis = 0,
            )
        )
    }

    @Test
    fun `the boundary belongs to the shorter side`() {
        val justUnder = SplashSession.STALE_AFTER_MS - 1
        assertTrue(
            SplashSession.shouldSkipSplash(
                tunnelRunning = false, syncedThisRun = true, awayMillis = justUnder,
            )
        )
        assertFalse(
            SplashSession.shouldSkipSplash(
                tunnelRunning = false, syncedThisRun = true, awayMillis = SplashSession.STALE_AFTER_MS,
            )
        )
    }
}
