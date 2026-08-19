package com.rahgozar.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the quick-settings tile and the widget say while the ad flow holds a
 * tunnel.
 *
 * The home screen has always known the answer: a running smart session is not
 * the user's VPN, so the dial reports disconnected and the tunnel's own events
 * are masked. The tile and the widget did not, and for the few seconds an ad
 * takes they contradicted it — the tile went active and labelled itself with
 * `CoreServiceManager.getRunningServerName()`, which in that moment is the
 * *panel's* smart server: a name the user never chose and cannot find in their
 * list. A tap made it worse, killing a tunnel they did not know existed and
 * taking the ad with it.
 *
 * Neither surface can be built on the JVM — one is a TileService, the other an
 * AppWidgetProvider, and the predicate underneath reaches into multi-process
 * MMKV and ActivityManager. The rule they now share is what is pinned here.
 */

/** Mirrors `SmartTunnel.ownsTheRunningTunnel`. */
private fun adFlowOwnsTheTunnel(sessionFlagSet: Boolean, aTunnelIsRunning: Boolean) =
    sessionFlagSet && aTunnelIsRunning

/**
 * Mirrors `QSTileService.userIsConnected` / `WidgetProvider.userIsConnected`.
 *
 * The parameter is what a tunnel service actually being up looks like to
 * Android, **not** the Xray controller's flag. Both surfaces used to ask that
 * flag, which is per-process and per-core — see [aTileThatCanReachEveryCore].
 */
private fun showsConnected(aTunnelIsRunning: Boolean, sessionFlagSet: Boolean) =
    aTunnelIsRunning && !adFlowOwnsTheTunnel(sessionFlagSet, aTunnelIsRunning)

/** Mirrors the guard at the top of `onClick` / the widget's click branch. */
private fun tapDoesSomething(sessionFlagSet: Boolean, aTunnelIsRunning: Boolean) =
    !adFlowOwnsTheTunnel(sessionFlagSet, aTunnelIsRunning)


class SmartSessionSurfaceRulesTest {

    // ------------------------------------------------------------- display --

    @Test
    fun the_users_own_tunnel_still_reads_as_connected() {
        // The ordinary case, which none of this may disturb.
        assertTrue(showsConnected(aTunnelIsRunning = true, sessionFlagSet = false))
    }

    @Test
    fun nothing_running_reads_as_disconnected() {
        assertFalse(showsConnected(aTunnelIsRunning = false, sessionFlagSet = false))
    }

    @Test
    fun the_ad_flows_tunnel_never_reads_as_connected() {
        // The whole point: the tile agrees with the dial instead of announcing
        // a connection the user did not make, under a server name they have
        // never seen.
        assertFalse(showsConnected(aTunnelIsRunning = true, sessionFlagSet = true))
    }

    /**
     * The tile has to see every core, not just Xray's.
     *
     * `CoreServiceManager.isRunning()` is `coreController.isRunning` — the Xray
     * controller's flag, in whichever process asks. sing-box runs its core in
     * `:SingBoxDaemon` and OpenVPN never touches that controller at all, so
     * with either of them connected the tile read "off" and a tap took the
     * *connect* branch: `SingBox-Service: already running, ignoring start`,
     * seen on the device. The tunnel simply could not be stopped from the tile
     * or the widget. `TunnelState` asks Android, which sees all three.
     */
    @Test
    fun aTileThatCanReachEveryCore() {
        for (core in listOf("xray", "sing-box", "openvpn")) {
            assertTrue(
                core,
                showsConnected(aTunnelIsRunning = true, sessionFlagSet = false),
            )
        }
    }

    // --------------------------------------------------------------- taps --

    @Test
    fun a_tap_during_a_live_ad_session_does_nothing() {
        // The surface reads "off", so a tap means connect — and connecting
        // would reach for the VPN slot the ad flow is holding, leaving two
        // VpnServices racing for it. Waiting is the honest answer; the session
        // is measured in seconds.
        assertFalse(tapDoesSomething(sessionFlagSet = true, aTunnelIsRunning = true))
    }

    @Test
    fun an_ordinary_tap_is_untouched() {
        assertTrue(tapDoesSomething(sessionFlagSet = false, aTunnelIsRunning = true))
        assertTrue(tapDoesSomething(sessionFlagSet = false, aTunnelIsRunning = false))
    }

    /**
     * The half that keeps this from becoming a dead tile.
     *
     * `smartSessionActive` is a stored intention that only the app's own
     * teardown clears, so a session whose tunnel is long gone still reads true
     * — after a crash, a swipe from recents, or the tunnel process being
     * reclaimed. A guard on the flag alone would leave the tile and the widget
     * refusing to work until something reconciled it, which for a user who
     * never reopens the app could be days.
     *
     * Asking Android as well makes a stale flag cost nothing. And the tap that
     * gets through then clears it: `LauncherManager.startContextService` runs
     * `SmartTunnel.clearSession()` for every start that is not the ad flow's
     * own — see [RunOverrideRulesTest].
     */
    @Test
    fun a_stale_flag_with_no_tunnel_blocks_nothing() {
        assertFalse(adFlowOwnsTheTunnel(sessionFlagSet = true, aTunnelIsRunning = false))
        assertTrue(tapDoesSomething(sessionFlagSet = true, aTunnelIsRunning = false))
    }

    @Test
    fun both_halves_are_required() {
        assertTrue(adFlowOwnsTheTunnel(sessionFlagSet = true, aTunnelIsRunning = true))
        assertFalse(adFlowOwnsTheTunnel(sessionFlagSet = true, aTunnelIsRunning = false))
        assertFalse(adFlowOwnsTheTunnel(sessionFlagSet = false, aTunnelIsRunning = true))
        assertFalse(adFlowOwnsTheTunnel(sessionFlagSet = false, aTunnelIsRunning = false))
    }
}
