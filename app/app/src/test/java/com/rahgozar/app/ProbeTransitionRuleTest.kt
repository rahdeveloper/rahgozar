package com.rahgozar.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling "our own tunnel interrupted the measurement" apart from "the server
 * did not answer".
 *
 * `SingBoxDelayTest` runs a whole core in another process, so the rule cannot be
 * exercised here directly — but the rule is the interesting part, and getting it
 * wrong goes both ways. Too narrow and a working server keeps being disconnected
 * the moment its own tun appears. Too wide and a genuinely dead server gets a
 * free second attempt, which is exactly the "slow server hiding inside a retry"
 * that the probe's own client disables retries to prevent.
 */
class ProbeTransitionRuleTest {

    /** Mirrors `SingBoxDelayTest.isTransitionFailure`. */
    private fun isTransition(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return "network changed" in text ||
            "context canceled" in text ||
            "connection reset" in text ||
            "software caused connection abort" in text
    }

    @Test
    fun the_messages_seen_on_the_device_are_recognised() {
        // Both of these were logged while a working sing-box server was being
        // disconnected: the first half a second after the tun came up, the
        // second eight seconds later on the next attempt.
        assertTrue(isTransition("Connection reset"))
        assertTrue(isTransition("open connection to www.gstatic.com:443 using outbound/hysteria2[HY2]: network changed"))
        assertTrue(isTransition("dns: lookup failed for hys.xmsh.space: context canceled"))
    }

    @Test
    fun a_server_that_simply_did_not_answer_gets_no_second_chance() {
        assertFalse(isTransition("timeout"))
        assertFalse(isTransition("Failed to connect to /203.0.113.10:8444"))
        assertFalse(isTransition("Read timed out"))
        assertFalse(isTransition("unexpected end of stream"))
    }

    @Test
    fun a_missing_message_is_not_a_transition() {
        // An exception with nothing to say is not evidence of anything, and
        // treating it as one would make the retry universal by accident.
        assertFalse(isTransition(null))
        assertFalse(isTransition(""))
    }

    @Test
    fun matching_ignores_case() {
        assertTrue(isTransition("NETWORK CHANGED"))
        assertTrue(isTransition("Software caused connection abort"))
    }
}
