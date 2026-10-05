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

    @Test
    fun `no release build writes a server configuration to the log`() {
        // The panel's tunnel_log_level is also the app's own level, and on
        // "debug" every connect dumped the full decrypted config into logcat.
        val dumps = sourceRoot().walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val logsConfig = Regex("""LogUtil\.[a-z]\(.*(result\.content|configContent)""").containsMatchIn(line)
                if (logsConfig && !line.contains("BuildConfig.DEBUG")) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }.toList()
        assertTrue("A configuration is logged outside a debug build:\n${dumps.joinToString("\n")}", dumps.isEmpty())
    }

    @Test
    fun `the launcher shortcuts see every core's tunnel`() {
        // CoreServiceManager.isRunning() only sees the Xray core of the calling
        // process, so "Stop" did nothing on sing-box, OpenVPN and Aether.
        for (name in listOf("ScStartActivity", "ScStopActivity", "ScSwitchActivity")) {
            val code = source("ui/shortcut/$name.kt").lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
            assertTrue("$name asks CoreServiceManager again:\n$code", !code.contains("CoreServiceManager.isRunning()"))
            assertTrue("$name no longer asks TunnelState:\n$code", code.contains("TunnelState.isRunning("))
        }
    }

    @Test
    fun `an Aether stop during the gateway search is honoured`() {
        val aether = source("service/AetherVpnService.kt")
        val run = aether.between("private fun runEngine(", "private fun establishTunnel(")
        // After the search loop has finished — its last statement is the "no
        // gateway" warning — and before the result is used. The check at the top
        // of each attempt does not count: the stop lands inside an attempt.
        val loopEnds = run.indexOf("no gateway on")
        val searchEnds = run.indexOf("val cfg = chosen")
        val establishes = run.indexOf("establishTunnel(")
        assertTrue("runEngine's search loop has changed shape — re-point this test", loopEnds in 0 until searchEnds)
        assertTrue(
            "runEngine must ask again after the search, before bringing a tunnel up — a stop lands " +
                "inside the blocking search unseen:\n$run",
            run.substring(loopEnds, searchEnds).contains("if (!stillOurs()) return") && searchEnds < establishes,
        )
        assertTrue(
            "a worker that outlived its session must not tear down the next one:\n$run",
            run.contains("if (sessionEpoch.get() == epoch) stopEverything()"),
        )
        val onStart = aether.between("override fun onStartCommand", "private fun runEngine(")
        assertTrue("a new start must wait for the previous search to end:\n$onStart", onStart.contains("worker?.isAlive == true"))
    }

    @Test
    fun `Aether applies the user's per-app choices like every other core`() {
        val establish = source("service/AetherVpnService.kt").between("private fun establishTunnel(", "private fun addAddress(")
        assertTrue(
            "Aether builds its tun without PerAppProxy again — per-app lists ignored, and the " +
                "\"rides the tunnel\" flag left from another core:\n$establish",
            establish.contains("PerAppProxy.apply(builder"),
        )
    }

    @Test
    fun `the Xray runtime stays out of the Aether and sing-box processes`() {
        val setter = source("core/CoreServiceManager.kt").between("var serviceControl:", "fun isRunning()")
        assertTrue(
            "serviceControl brings Xray up in a process that has its own core:\n$setter",
            setter.contains("service is SingBoxService") && setter.contains("service is AetherVpnService"),
        )
    }

    @Test
    fun `asking whether the tunnel runs does not cut its check short`() {
        // The quick-settings tile asks on every pull-down, and the answer was
        // taken as "connected" mid-check; the check's own deadline then tore
        // down a working tunnel.
        val home = source("ui/home/HomeViewModel.kt")
        val events = home.between("private fun onServiceEvent(", "is MainServiceEvent.SpeedUpdate")
        assertTrue(
            "StateRunning sets the link while a check is still deciding:\n$events",
            events.contains("MainServiceEvent.StateRunning ->") && events.contains("if (!verifying()) setLink(LinkState.ON)"),
        )
        assertTrue(
            "a stop or a failure no longer ends the pending check:\n$events",
            Regex("""StateStopSuccess -> \{\s*cancelVerification\(\)""").containsMatchIn(events) &&
                Regex("""StateStartFailure -> \{\s*cancelVerification\(\)""").containsMatchIn(events),
        )
        val openVpn = source("service/OpenVpnService.kt")
        assertTrue(
            "OpenVPN must say it is running only once CONNECTED, not during the handshake",
            openVpn.contains("if (connected) notifyUi(AppConfig.MSG_STATE_RUNNING") &&
                Regex("""name == "CONNECTED" -> \{\s*connected = true""").containsMatchIn(openVpn),
        )
    }

    @Test
    fun `a connect cut off mid-ad does not strand the ad flow's tunnel`() {
        // A rotation or a theme change recreates the Activity and cancels the
        // flow before its own teardown; the smart tunnel stayed up for minutes.
        val main = source("ui/main/MainActivity.kt")
        val dial = main.between("private fun dial(", "private suspend fun runAdBeforeConnect")
        val parked = main.between("private fun showPendingAdThen(", "private suspend fun releaseSmartTunnelIfAbandoned")
        for ((name, body) in listOf("dial" to dial, "showPendingAdThen" to parked)) {
            assertTrue(
                "$name's finally no longer releases an abandoned smart tunnel:\n$body",
                Regex("""finally \{[^}]*releaseSmartTunnelIfAbandoned\(\)""").containsMatchIn(body),
            )
        }
        val release = main.between("private suspend fun releaseSmartTunnelIfAbandoned", "\n    }\n")
        assertTrue("the release must run NonCancellable:\n$release", release.contains("withContext(NonCancellable)"))
    }

    @Test
    fun `every fresh connection starts its own session clock`() {
        // Started only from the connect button, the tile, the widget, the
        // shortcuts and start-on-boot inherited a stale deadline or ran unlimited.
        val start = source("core/LauncherManager.kt").between("private fun startContextService(", "val guid = MmkvManager.getRunServer()")
        assertTrue(
            "LauncherManager must start the session clock for every fresh connection:\n$start",
            Regex("""if \(!honourOverride\) \{[^}]*SessionLimit\.begin\(\)""").containsMatchIn(start),
        )
        val main = source("ui/main/MainActivity.kt").lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        assertTrue("MainActivity starts the clock a second time again", !main.contains("SessionLimit.begin()"))
    }

    @Test
    fun `a process that skipped the splash still has its ad configuration`() {
        val onCreate = source("ui/main/MainActivity.kt").between("override fun onCreate(", "override fun onResume()")
        assertTrue(
            "MainActivity no longer applies the stored configuration in a process that skipped the splash:\n$onCreate",
            onCreate.contains("if (!AdManager.applied)") && onCreate.contains("AdManager.apply(this, stored.ads)"),
        )
    }

    @Test
    fun `the notification offers Restart only on the core that answers it`() {
        val notification = source("handler/NotificationManager.kt")
        val cores = notification.between("CORES_WITHOUT_RESTART = setOf(", ")")
        for (type in listOf("SINGBOX", "OPENVPN", "AETHER")) {
            assertTrue("$type must not be offered a Restart nothing answers: $cores", cores.contains("EConfigType.$type"))
        }
        val actions = notification.between("if (!smart) {", "service.startForeground(")
        assertTrue(
            "the Restart action is added without the core check:\n$actions",
            actions.indexOf("CORES_WITHOUT_RESTART") in 0 until actions.indexOf("title_service_restart"),
        )
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
