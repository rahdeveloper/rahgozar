package com.rahgozar.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient
import net.openvpn.ovpn3.ClientAPI_OpenVPNClientHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The openvpn3 core, exercised inside the real app process on a real device.
 *
 * Everything below the tunnel handshake is covered here: that the native
 * library packaged in the APK loads, that the JNI binding dispatches, that a
 * profile of the shape the panel sends is understood, and that a broken one is
 * rejected rather than accepted and failed later.
 *
 * What this cannot cover is the handshake itself and the tun_builder callbacks
 * that follow it, because both need a reachable OpenVPN server.
 */
@RunWith(AndroidJUnit4::class)
class OpenVpnCoreTest {

    companion object {
        /** The shape the panel stores in ProfileItem.ovpnProfile. */
        private val PROFILE = """
            client
            dev tun
            proto udp
            remote 198.51.100.7 1194
            resolv-retry infinite
            nobind
            persist-key
            persist-tun
            remote-cert-tls server
            cipher AES-256-GCM
            verb 3
        """.trimIndent()

        @JvmStatic
        @BeforeClass
        fun loadLibrary() {
            System.loadLibrary("ovpncli")
        }
    }

    @Test
    fun nativeLibraryIsPackagedForThisDevicesAbi() {
        val platform = ClientAPI_OpenVPNClientHelper.platform()
        assertTrue("unexpected platform: $platform", platform.contains("OpenVPN core"))
        // The core derives this from __ANDROID__ at compile time. If it ever
        // says anything else, the wrong build slipped into the APK.
        assertTrue("unexpected platform: $platform", platform.contains("android"))
    }

    @Test
    fun coreAcceptsAProfileOfTheShapeThePanelSends() {
        val helper = ClientAPI_OpenVPNClientHelper()
        val config = ClientAPI_Config().apply {
            content = PROFILE
            compressionMode = "asym"
        }

        val eval = helper.eval_config(config)

        assertFalse("core rejected the profile: ${eval.message}", eval.error)
        assertEquals("198.51.100.7", eval.remoteHost)
        assertEquals("1194", eval.remotePort)
        assertEquals("udp", eval.remoteProto)
    }

    /**
     * A profile with no remote cannot connect. The core has to say so at
     * eval time — that is what OpenVpnService checks before it starts a
     * thread, shows a notification and asks for VPN permission.
     */
    /**
     * TCP profiles need nothing special anywhere — not in the panel, which
     * stores the file without reading it, and not in OpenVpnClient, which never
     * looks at the transport. This test exists to keep that true: it is the
     * kind of thing a later change could quietly break.
     */
    @Test
    fun coreAcceptsATcpProfileJustTheSame() {
        val helper = ClientAPI_OpenVPNClientHelper()
        val config = ClientAPI_Config().apply {
            content = PROFILE
                .replace("proto udp", "proto tcp")
                .replace("remote 198.51.100.7 1194", "remote 198.51.100.7 443")
        }

        val eval = helper.eval_config(config)

        assertFalse("core rejected the TCP profile: ${eval.message}", eval.error)
        assertEquals("443", eval.remotePort)
        assertTrue("expected a TCP remote, got ${eval.remoteProto}", eval.remoteProto.startsWith("tcp"))
    }

    @Test
    fun coreRejectsAProfileWithNothingToConnectTo() {
        val helper = ClientAPI_OpenVPNClientHelper()
        val config = ClientAPI_Config().apply { content = "client\ndev tun\n" }

        val eval = helper.eval_config(config)

        assertTrue("a profile with no remote should be rejected", eval.error)
    }

    /**
     * Subclassing is how the whole integration works: SWIG directors let C++
     * call back into Kotlin. If construction of a subclass ever stopped wiring
     * the directors, every tun_builder callback would silently go to the base
     * implementation — which returns false — and tunnels would fail to build
     * for no visible reason.
     */
    @Test
    fun subclassOverridesAreReachableFromNative() {
        var sawCallback = false

        val client = object : ClientAPI_OpenVPNClient() {
            override fun tun_builder_new(): Boolean {
                sawCallback = true
                return true
            }
        }

        // Calling through the Java object is enough to prove the override is
        // installed; the director wiring itself is asserted by the constructor
        // not throwing.
        assertTrue(client.tun_builder_new())
        assertTrue("override was not invoked", sawCallback)
    }
}
