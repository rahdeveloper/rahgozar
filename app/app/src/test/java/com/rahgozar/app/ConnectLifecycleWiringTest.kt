package com.rahgozar.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rules of the connection lifecycle that compile, run and look right when they
 * are broken — found by the whole-app review before 2.4.5.
 *
 * Read from the sources, like [ConnectSelectionWiringTest]: each rule is a fact
 * about which value or call sits in which function, and no reflection over a
 * compiled ViewModel or Service can see that.
 */
class ConnectLifecycleWiringTest {

    @Test
    fun `the traffic check counts this session from zero`() {
        // Every core counts from zero for each session, so the screen's copy of
        // the counters is the previous session's total. As the baseline it made
        // "bytes since the check began" negative: from the second connection of
        // a run, AnyConnect servers were torn down as "not responding".
        val onTunnelUp = source("ui/home/HomeViewModel.kt").between("private fun onTunnelUp()", "private suspend fun verifyByTraffic")
        assertTrue(
            "onTunnelUp must start the traffic baselines at zero:\n$onTunnelUp",
            onTunnelUp.contains("verifyBaselineBytes = 0") && onTunnelUp.contains("verifyBaselineDownBytes = 0"),
        )
        assertTrue(
            "onTunnelUp takes a baseline from the screen's copy of the counters again:\n$onTunnelUp",
            !onTunnelUp.contains("verifyBaselineDownBytes = _uiState"),
        )
    }

    @Test
    fun `every VPN service the manifest declares is a tunnel TunnelState can see`() {
        // Aether was declared, ran, and was invisible: the splash replayed over
        // it, its ad flow replaced Aether's tunnel and then stopped it, and the
        // widget could never stop it. Checked against the manifest so the next
        // core cannot be forgotten the same way.
        val manifest = File(sourceRoot(), "../../../../AndroidManifest.xml").canonicalFile.readText()
        val vpnServices = Regex("""<service\b([^>]*)>""").findAll(manifest)
            .map { it.groupValues[1] }
            .filter { it.contains("android.permission.BIND_VPN_SERVICE") }
            .mapNotNull { Regex("""android:name="([^"]+)"""").find(it)?.groupValues?.get(1) }
            .map { it.substringAfterLast('.') }
            .toList()
        assertTrue("found no VPN services in the manifest — has its layout changed?", vpnServices.size >= 4)

        val tunnelState = source("service/TunnelState.kt").between("TUNNEL_SERVICES = setOf(", ")\n")
        val missing = vpnServices.filterNot { tunnelState.contains("$it::class.java.name") }
        assertTrue(
            "These VPN services are missing from TunnelState, so a tunnel on them is invisible to " +
                "the splash, the widget, the tile and the connect button: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `a system start of the Xray service never runs another core's server`() {
        // Always-on VPN and sticky restarts start CoreVpnService directly. Run
        // as Xray, a sing-box/OpenVPN/Aether profile produced no proxy outbound
        // and `direct` carried everything under a "connected" tunnel.
        val onStart = source("service/CoreVpnService.kt").between("override fun onStartCommand", "override fun getService")
        val handsOff = onStart.indexOf("LauncherManager.startService(")
        val checksType = onStart.indexOf("in NON_XRAY_TYPES")
        val startsXray = onStart.indexOf("if (!setupVpnService())")
        assertTrue("the system-start path no longer checks the server's core:\n$onStart", checksType >= 0 && handsOff >= 0)
        assertTrue("the check comes after Xray has already been set up:\n$onStart", handsOff < startsXray)

        val types = source("service/CoreVpnService.kt").between("NON_XRAY_TYPES = setOf(", ")")
        for (type in listOf("SINGBOX", "OPENVPN", "AETHER")) {
            assertTrue("$type is missing from NON_XRAY_TYPES: $types", types.contains("EConfigType.$type"))
        }
    }

    @Test
    fun `the Xray config refuses to fall back to direct when its server cannot be built`() {
        val build = source("core/CoreConfigManager.kt").between("private fun buildUnifiedConfig(", "private fun buildOutbounds(")
        assertTrue(
            "buildUnifiedConfig no longer refuses a primary outbound it could not build; the template's " +
                "`direct` would become the default and carry everything:\n$build",
            build.contains("primaryResolvedOutbound.tag in existingTags") && build.contains("check("),
        )
    }

    @Test
    fun `a session that lands on an old service object can still be stopped`() {
        // Android keeps a VPN service object alive while the system is bound to
        // its interface and delivers the next start to it. With the stop latch
        // left set from the previous session, the new tunnel's stop returned at
        // once — a tunnel nothing could bring down.
        for (service in listOf("service/SingBoxService.kt", "service/OpenVpnService.kt")) {
            val onStart = source(service).between("override fun onStartCommand", "\n    override fun ")
            val claims = onStart.indexOf("ownsSession = true")
            val clears = onStart.indexOf("isStopping.set(false)")
            assertTrue("$service no longer clears its stop latch for a new session:\n$onStart", clears >= 0)
            assertTrue(
                "$service clears the stop latch before the session is claimed, while the old stop may " +
                    "still be running:\n$onStart",
                claims in 0 until clears && onStart.indexOf("sessionActive.compareAndSet") in 0 until clears,
            )
        }
    }

    // ------------------------------------------------------------ reading --

    private fun source(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("$relative is gone — point this test at its new path", file.isFile)
        return file.readText()
    }

    private fun String.between(start: String, end: String): String {
        val from = indexOf(start)
        assertTrue("\"$start\" is gone from the source", from >= 0)
        val to = indexOf(end, from + start.length)
        return if (to < 0) substring(from) else substring(from, to)
    }

    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/java/com/rahgozar/app")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Could not find src/main/java/com/rahgozar/app above ${System.getProperty("user.dir")}")
    }
}
