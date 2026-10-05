package com.rahgozar.app.ui.splash

import com.rahgozar.app.panel.PanelSync

/**
 * Whether a failed sync gets one more try before the splash shows its error.
 *
 * Only on a first launch — no configuration stored — because that is the only
 * launch where a failed sync is a dead end: every later one carries on with the
 * servers it already has. And only for a failure that could pass: a fatal one
 * (a broken build) fails the same way twice.
 *
 * Seen on 2026-10-05, on a fresh install from Play: the device registered at
 * 09:05:49, and its first fetch reached the panel at 09:05:58 — after the error
 * screen and a tap on Try again. Nothing failed on the server. The panel calls
 * give up after three seconds, which on a mobile network is one or two lost
 * packets during connection setup; the user's own retry went through at once.
 * An error screen is no way to meet somebody for the first time, when the same
 * request a moment later would have worked.
 */
internal object FirstLaunchRetry {

    /** The pause before the second try: a moment for a stalled path to clear. */
    const val DELAY_MS = 1_500L

    fun worthIt(result: PanelSync.Result, hasStoredConfiguration: Boolean): Boolean =
        result is PanelSync.Result.Unavailable && !result.fatal && !hasStoredConfiguration
}
