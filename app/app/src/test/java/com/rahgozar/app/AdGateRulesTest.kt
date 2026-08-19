package com.rahgozar.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate's rule, stated once as pure logic and checked here.
 *
 * `AdGate` itself reaches into `PanelStore` (multi-process MMKV) and
 * `ConnectivityManager`, neither of which exists on the JVM, so the object
 * cannot be instantiated in a host test. What *can* be pinned down is the
 * decision it encodes — and that decision is the whole leak surface, so it is
 * worth a table rather than a comment.
 *
 * If [AdGate.needsSmart] or [AdGate.pathReady] is ever changed, this table
 * must change with it, deliberately.
 */
private object GateRule {

    /** Mirrors `AdGate.needsSmart`. */
    fun needsSmart(placementRequiresSmart: Boolean, countryRequiresSmart: Boolean): Boolean =
        placementRequiresSmart && countryRequiresSmart

    /** Mirrors `AdGate.pathReady` / `pathHolds`. */
    fun mayRequest(
        placementRequiresSmart: Boolean,
        countryRequiresSmart: Boolean,
        tunnelIsCarrying: Boolean,
    ): Boolean = !needsSmart(placementRequiresSmart, countryRequiresSmart) || tunnelIsCarrying
}


class AdGateRulesTest {

    @Test
    fun a_placement_that_needs_the_tunnel_may_not_ask_without_one() {
        assertFalse(
            GateRule.mayRequest(
                placementRequiresSmart = true,
                countryRequiresSmart = true,
                tunnelIsCarrying = false,
            )
        )
    }

    @Test
    fun the_same_placement_may_ask_once_the_tunnel_carries_us() {
        assertTrue(
            GateRule.mayRequest(
                placementRequiresSmart = true,
                countryRequiresSmart = true,
                tunnelIsCarrying = true,
            )
        )
    }

    @Test
    fun an_exempt_country_never_waits_for_a_tunnel() {
        // The panel exempts this device: ads go out directly and that is not
        // a leak. Nothing here may depend on a tunnel that will never exist.
        assertTrue(
            GateRule.mayRequest(
                placementRequiresSmart = true,
                countryRequiresSmart = false,
                tunnelIsCarrying = false,
            )
        )
    }

    @Test
    fun a_placement_the_operator_marked_direct_never_waits_either() {
        assertTrue(
            GateRule.mayRequest(
                placementRequiresSmart = false,
                countryRequiresSmart = true,
                tunnelIsCarrying = false,
            )
        )
    }

    @Test
    fun both_flags_are_needed_before_a_tunnel_is_required() {
        assertTrue(GateRule.needsSmart(placementRequiresSmart = true, countryRequiresSmart = true))
        assertFalse(GateRule.needsSmart(placementRequiresSmart = true, countryRequiresSmart = false))
        assertFalse(GateRule.needsSmart(placementRequiresSmart = false, countryRequiresSmart = true))
        assertFalse(GateRule.needsSmart(placementRequiresSmart = false, countryRequiresSmart = false))
    }

    /**
     * The defect this gate was written for: the second splash ad's own
     * `require_smart` was never read, so it inherited the first slot's
     * decision. The rule must be evaluated per placement, which means the
     * same country flag with a different placement flag gives a different
     * answer.
     */
    @Test
    fun each_placement_is_judged_on_its_own_flag() {
        val country = true
        val first = GateRule.mayRequest(false, country, tunnelIsCarrying = false)
        val second = GateRule.mayRequest(true, country, tunnelIsCarrying = false)
        assertTrue(first)
        assertFalse(second)
    }
}
