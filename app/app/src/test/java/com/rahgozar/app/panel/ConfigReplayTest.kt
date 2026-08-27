package com.rahgozar.app.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A signed bootstrap response that is genuinely ours but older than the one
 * already applied is a replay. An intercepting proxy holding a recorded
 * response could serve it back indefinitely — undoing an emergency server
 * rotation, or reversing a forced update — with every signature still valid.
 *
 * `BundleReader` has enforced this for discovery bundles from the start; the
 * bootstrap path never did.
 *
 * The two escapes below matter more than the rule. Each of them, if it were
 * wrong, would brick installs rather than protect them, which is why they are
 * tested first.
 */
class ConfigReplayTest {

    @Test
    fun `an older version is refused`() {
        assertTrue(PanelSync.isReplayedConfig(offered = 40, applied = 155))
        assertTrue(PanelSync.isReplayedConfig(offered = 1, applied = 2))
    }

    @Test
    fun `a newer version is applied`() {
        assertFalse(PanelSync.isReplayedConfig(offered = 156, applied = 155))
        assertFalse(PanelSync.isReplayedConfig(offered = 1_000_000, applied = 1))
    }

    @Test
    fun `the same version is applied, not refused`() {
        // Arrives legitimately whenever a device is made to take the full
        // response again — a schema bump, a cleared ETag. Refusing it would
        // strand exactly the installs that were being repaired.
        assertFalse(PanelSync.isReplayedConfig(offered = 155, applied = 155))
    }

    @Test
    fun `no version at all is applied`() {
        // Zero means the panel did not send one, not that it sent an old one.
        // A newer app meeting an older panel must still be configurable, or a
        // staggered rollout locks those installs out with no way back.
        assertFalse(PanelSync.isReplayedConfig(offered = 0, applied = 155))
        assertFalse(PanelSync.isReplayedConfig(offered = 0, applied = 0))
    }

    @Test
    fun `a fresh install accepts anything`() {
        // Nothing applied yet, so there is no high-water mark to be below.
        assertFalse(PanelSync.isReplayedConfig(offered = 1, applied = 0))
        assertFalse(PanelSync.isReplayedConfig(offered = 155, applied = 0))
    }

    @Test
    fun `a negative version is not treated as newer`() {
        // Not reachable from our own panel, but the field is attacker-adjacent
        // and a signed-integer wrap must not read as "the newest yet".
        assertFalse(PanelSync.isReplayedConfig(offered = -1, applied = 155))
    }
}
