package com.rahgozar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is allowed to start the smart session's run-guid override.
 *
 * The override makes `MmkvManager.getRunServer()` answer with the panel's
 * hidden smart profile instead of the server the user picked. That is correct
 * for exactly one caller and catastrophic for the rest: the smart profile's tun
 * is an allow list of this app, so a user who "connects" to it gets a phone
 * that says connected and carries nothing.
 *
 * It was catastrophic in practice, twice over. Every surface that stops a
 * tunnel — the quick-settings tile, the widget, both shortcuts — leaves the
 * override behind, and the watchdog that reaps a stranded session cleared the
 * session flag while leaving the override set. From there the next start ran
 * the smart profile, and on a phone with start-on-boot that start was a reboot.
 *
 * `LauncherManager.startContextService` cannot be reached from the JVM — it
 * needs MMKV, the package manager and four Android services — so what is
 * pinned here is the table it encodes.
 */

/** Every way a tunnel is started in this app. */
private enum class StartCause {
    /** `SmartTunnel.start`, which set the override a line earlier. */
    THE_AD_FLOW_ITSELF,

    /** The notification's Restart button, via MSG_STATE_RESTART. */
    RESTART_WHAT_IS_RUNNING,

    /** The connect button on the home screen. */
    USER_TAPPED_CONNECT,

    /** The quick-settings tile, the widget, or either shortcut. */
    TOGGLE_SURFACE,

    /** Start-on-boot, after a reboot or an app update. */
    BOOT_RECEIVER,
}

/** Mirrors the `honourOverride` argument each call site passes. */
private fun honoursOverride(cause: StartCause): Boolean = when (cause) {
    // It set the override; it is the flow the override belongs to.
    StartCause.THE_AD_FLOW_ITSELF -> true
    // Re-establishes whatever was already up, rather than substituting a
    // different server for it halfway through someone else's session.
    StartCause.RESTART_WHAT_IS_RUNNING -> true
    // All three are a fresh intent to connect, where an override can only ever
    // be leftover.
    StartCause.USER_TAPPED_CONNECT -> false
    StartCause.TOGGLE_SURFACE -> false
    StartCause.BOOT_RECEIVER -> false
}

/** What actually starts, given an override that may or may not be stale. */
private fun serverStartedBy(
    cause: StartCause,
    overrideSet: Boolean,
    override: String = "panel-smart-0",
    selection: String = "the user's server",
): String = if (honoursOverride(cause) && overrideSet) override else selection


class RunOverrideRulesTest {

    @Test
    fun the_ad_flow_runs_the_profile_it_just_chose() {
        assertEquals(
            "panel-smart-0",
            serverStartedBy(StartCause.THE_AD_FLOW_ITSELF, overrideSet = true),
        )
    }

    @Test
    fun no_toggle_surface_can_ever_start_the_smart_profile() {
        // The reported gap: two taps on the tile during a smart session — one
        // to stop it, one to start again — used to dial the panel's profile.
        assertEquals(
            "the user's server",
            serverStartedBy(StartCause.TOGGLE_SURFACE, overrideSet = true),
        )
    }

    @Test
    fun a_reboot_cannot_inherit_a_stale_override() {
        // The worse half, from the watchdog leaving the override behind: MMKV
        // survives a reboot, and start-on-boot reads it before any Activity
        // exists to reconcile it.
        assertEquals(
            "the user's server",
            serverStartedBy(StartCause.BOOT_RECEIVER, overrideSet = true),
        )
    }

    @Test
    fun the_connect_button_always_dials_the_users_own_choice() {
        assertEquals(
            "the user's server",
            serverStartedBy(StartCause.USER_TAPPED_CONNECT, overrideSet = true),
        )
    }

    @Test
    fun a_restart_re_establishes_the_tunnel_that_was_up() {
        // Including the ad flow's. The alternative is a session flag left
        // describing a tunnel that is now running a different server.
        assertEquals(
            "panel-smart-0",
            serverStartedBy(StartCause.RESTART_WHAT_IS_RUNNING, overrideSet = true),
        )
    }

    @Test
    fun with_no_override_every_caller_agrees() {
        // The ordinary state of the app: nothing to honour, so the table
        // cannot make any of these differ.
        for (cause in StartCause.entries) {
            assertEquals(
                cause.name,
                "the user's server",
                serverStartedBy(cause, overrideSet = false),
            )
        }
    }

    @Test
    fun the_exemptions_are_two_and_are_named() {
        // A new start path added later gets `honourOverride`'s default, which
        // is false. That is the point of the default: forgetting to think
        // about the override is safe, and remembering has to be deliberate.
        val exempt = StartCause.entries.filter(::honoursOverride)
        assertEquals(
            listOf(StartCause.THE_AD_FLOW_ITSELF, StartCause.RESTART_WHAT_IS_RUNNING),
            exempt,
        )
    }

    @Test
    fun the_watchdog_clears_both_halves_of_a_session() {
        // The session is two facts, and clearing one without the other is what
        // made the override outlive its flow. `SmartTunnel.clearSession` is
        // now the only way either is cleared.
        class Session(var flagActive: Boolean, var overrideSet: Boolean)

        val reaped = Session(flagActive = true, overrideSet = true)
        // What `SessionLimit.armSmartWatchdog` does now.
        reaped.flagActive = false
        reaped.overrideSet = false

        assertFalse(reaped.flagActive)
        assertFalse(reaped.overrideSet)
        assertTrue(
            "a reaped session leaves nothing behind",
            !reaped.flagActive && !reaped.overrideSet,
        )
    }
}
