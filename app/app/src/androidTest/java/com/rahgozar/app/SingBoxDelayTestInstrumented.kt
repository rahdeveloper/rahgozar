package com.rahgozar.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rahgozar.app.service.SingBoxDelayBridge
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The sing-box delay test, end to end on a real device.
 *
 * This is the only place the whole chain runs for real: the Messenger hop into
 * the :SingBoxTestDaemon process, libbox loading there (and *only* there — the
 * process this test runs in has the Xray runtime), the config surgery, a core
 * started with a loopback proxy, and one real request through it.
 *
 * The two cases are the two answers the rest of the app acts on. A working
 * server must produce a number, because the connect-time gate keeps the tunnel
 * only when it gets one; a dead server must produce -1, because that gate is
 * the only thing standing between the user and a tunnel that carries nothing.
 *
 * Needs a working internet connection on the device, and no VPN of another app
 * holding the default route.
 */
@RunWith(AndroidJUnit4::class)
class SingBoxDelayTestInstrumented {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun measuresAServerThatCarriesTraffic() {
        val delay = SingBoxDelayBridge.measure(context, DIRECT, TEST_URL, urgent = true)

        assertTrue(
            "a direct outbound must produce a real measurement, got $delay",
            delay > 0,
        )
        assertTrue("$delay ms is not a plausible round trip", delay < 20_000)
    }

    @Test
    fun reportsAServerThatCannotBeReached() {
        val delay = SingBoxDelayBridge.measure(context, BLACKHOLE, TEST_URL, urgent = true)

        assertTrue(
            "an unreachable server must be reported as failed, got $delay",
            delay == -1L,
        )
    }

    @Test
    fun reportsAConfigurationTheCoreRefuses() {
        val delay = SingBoxDelayBridge.measure(context, NOT_A_CONFIG, TEST_URL, urgent = true)

        assertTrue("garbage must not measure as healthy, got $delay", delay == -1L)
    }

    /**
     * A blob with no outbound must fail, not quietly measure the phone's own
     * internet through a direct outbound and report a server nobody contacted
     * as healthy. That happened, and it is why this test exists.
     */
    @Test
    fun refusesToMeasureSomethingWithNoServerInIt() {
        val delay = SingBoxDelayBridge.measure(context, NO_OUTBOUND, TEST_URL, urgent = true)

        assertTrue("a config with no outbound must not produce a ping, got $delay", delay == -1L)
    }

    /**
     * The shape an operator actually pastes: one outbound. It has to reach the
     * core wrapped in a configuration, or it fails with `unknown field "type"`.
     * The server here is unreachable, so the answer is -1 — what matters is
     * that it fails at the *connection*, having been understood.
     */
    @Test
    fun understandsABareOutbound() {
        val delay = SingBoxDelayBridge.measure(context, BARE_OUTBOUND, TEST_URL, urgent = true)

        assertTrue("a bare outbound must be understood and then fail to connect, got $delay", delay == -1L)
    }

    /**
     * Measures a configuration supplied on the command line, for diagnosing a
     * server that misbehaves on the device:
     *
     *     adb shell am instrument -w -e configB64 <base64 of the config> \
     *       -e class com.rahgozar.app.SingBoxDelayTestInstrumented#measuresASuppliedConfig \
     *       com.rahgozar.app.test/androidx.test.runner.AndroidJUnitRunner
     *
     * Base64 so quoting cannot mangle it, and an argument rather than a
     * constant so a real server's credentials never enter the source tree.
     * Skipped when nothing is passed.
     */
    @Test
    fun measuresASuppliedConfig() {
        val encoded = InstrumentationRegistry.getArguments().getString("configB64")
        assumeTrue("pass -e configB64 <base64> to run this", !encoded.isNullOrBlank())

        val config = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        val delay = SingBoxDelayBridge.measure(context, config, TEST_URL, urgent = true)

        println("SUPPLIED CONFIG MEASURED: $delay ms")
        assertTrue("the supplied server did not carry the probe, got $delay", delay > 0)
    }

    private companion object {
        const val TEST_URL = AppConfig.DELAY_TEST_URL

        /** The production test server's shape: a tun that routes straight out. */
        const val DIRECT = """
            {
              "log": {"level": "info"},
              "inbounds": [
                {"type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30"], "auto_route": true}
              ],
              "outbounds": [{"type": "direct", "tag": "direct"}],
              "route": {"auto_detect_interface": true}
            }
        """

        /**
         * A syntactically valid server that is not there. RFC 5737 documentation
         * space, so the packets cannot reach anything real even by accident.
         */
        const val BLACKHOLE = """
            {
              "log": {"level": "info"},
              "outbounds": [
                {
                  "type": "trojan",
                  "tag": "proxy",
                  "server": "198.51.100.7",
                  "server_port": 443,
                  "password": "not-a-real-password",
                  "tls": {"enabled": true, "server_name": "example.invalid"}
                },
                {"type": "direct", "tag": "direct"}
              ],
              "route": {"final": "proxy"}
            }
        """

        const val NOT_A_CONFIG = """{"outbounds": [{"type": "no-such-protocol", "tag": "proxy"}]}"""

        /** A configuration with nowhere to go. */
        const val NO_OUTBOUND = """{"log": {"level": "info"}, "route": {}}"""

        /** One outbound, exactly as a provider hands it over. */
        const val BARE_OUTBOUND = """
            {
              "type": "hysteria2",
              "tag": "HY2",
              "server": "198.51.100.7",
              "server_port": 8443,
              "password": "not-a-real-password",
              "tls": {"enabled": true, "server_name": "example.invalid", "alpn": ["h3"]}
            }
        """
    }
}
