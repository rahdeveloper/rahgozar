package com.rahgozar.app

import com.rahgozar.app.service.OpenVpnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenVpnProfileTest {

    @Test
    fun removesOnlyTheDirectivesTheCoreCannotParse() {
        val result = OpenVpnProfile.sanitise(
            """
            client
            dev tun
            proto tcp
            remote 198.51.100.7 443
            resolv-retry infinite
            nobind
            ping-timer-rem
            explicit-exit-notify
            verb 3
            """.trimIndent()
        )

        assertFalse(result.profile.contains("ping-timer-rem"))
        assertFalse(result.profile.contains("resolv-retry"))
        assertFalse(result.profile.contains("explicit-exit-notify"))
        assertEquals(3, result.removed.size)

        // Everything that decides where the tunnel goes stays exactly as it was.
        assertTrue(result.profile.contains("remote 198.51.100.7 443"))
        assertTrue(result.profile.contains("proto tcp"))
        assertTrue(result.profile.contains("nobind"))
        assertTrue(result.profile.contains("client"))
    }

    /**
     * The important one. Inline blocks are base64; dropping or reordering a
     * single line there produces a key that no longer parses, and the failure
     * would look nothing like its cause.
     */
    @Test
    fun leavesInlineCertificatesAndKeysByteForByte() {
        val block = """
            <ca>
            -----BEGIN CERTIFICATE-----
            resolv-retry
            ping-timer-rem
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()

        val result = OpenVpnProfile.sanitise("client\n$block\n")

        // Those two words appear inside the block as if they were payload, and
        // must survive precisely because the sanitiser is not allowed to read
        // inside a block at all.
        assertTrue(result.profile.contains("resolv-retry"))
        assertTrue(result.profile.contains("ping-timer-rem"))
        assertTrue(result.removed.isEmpty())
        assertTrue(result.profile.contains("-----BEGIN CERTIFICATE-----"))
        assertTrue(result.profile.contains("-----END CERTIFICATE-----"))
    }

    @Test
    fun neverTouchesSecurityRelevantDirectives() {
        val profile = """
            cipher AES-256-GCM
            auth SHA512
            tls-version-min 1.2
            verify-x509-name CN=example.com
            remote-cert-tls server
            auth-user-pass
            redirect-gateway def1
        """.trimIndent()

        val result = OpenVpnProfile.sanitise(profile)

        assertTrue(result.removed.isEmpty())
        profile.lines().forEach { assertTrue("lost: $it", result.profile.contains(it)) }
    }

    @Test
    fun matchesWholeDirectivesRatherThanPrefixes() {
        // "resolv-retry-something" is not "resolv-retry", and a prefix match
        // would silently eat a directive that was never on the list.
        val result = OpenVpnProfile.sanitise("resolv-retry-hypothetical 5\nping-timer-remote 9\n")

        assertTrue(result.removed.isEmpty())
        assertTrue(result.profile.contains("resolv-retry-hypothetical 5"))
        assertTrue(result.profile.contains("ping-timer-remote 9"))
    }

    @Test
    fun leavesAProfileWithNothingToRemoveAlone() {
        val profile = "client\ndev tun\nremote 198.51.100.7 1194\n"

        val result = OpenVpnProfile.sanitise(profile)

        assertTrue(result.removed.isEmpty())
        assertEquals(profile, result.profile)
    }
}
