package com.rahgozar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a connect tap is allowed to believe, and where a selection may be lost.
 *
 * All four rules below were broken at once in 2.4.3, and every one of them
 * compiled, ran, and looked right:
 *
 *  - the VPN consent callback ran a bare start, so the first connect of an
 *    install — the only one that asks for consent — showed no Connecting screen
 *    at all;
 *  - the tap trusted the screen's idea of the selection, which is only as fresh
 *    as its last refresh, and a stale one sent the tap to the service, which
 *    refused it with a message written for the quick-settings tile;
 *  - the Smart candidates were written with `encodeServerConfig`, which adds the
 *    guid to the *visible* list, so a panel tunnel could be shown as a server,
 *    picked, and then deleted by the next sync — taking the selection with it;
 *  - and the deletions of the selection were spread across three functions, so
 *    a release log could not say which of them had done it.
 *
 * Read from the sources rather than the classes, like [com.rahgozar.app.ads.SessionLimitWiringTest]:
 * these are facts about which call sits in which function, and no reflection
 * over a compiled Activity can see that.
 */
class ConnectSelectionWiringTest {

    @Test
    fun `the consent callback runs the connect journey, not a bare start`() {
        val callback = mainActivity().between(
            "private val requestVpnPermission",
            "private val settingsActivityLauncher",
        )
        assertTrue(
            "The VPN consent callback must run the journey — the Connecting screen and the " +
                "wait for a verdict — rather than starting the tunnel behind the home screen:\n$callback",
            callback.contains("dial(withAd = false)"),
        )
        assertTrue(
            "The consent callback calls startV2Ray directly again, which is the bug: that " +
                "path shows no Connecting screen:\n$callback",
            !callback.contains("startV2Ray("),
        )
    }

    @Test
    fun `a connect tap re-reads the selection before it trusts the screen`() {
        val beginConnect = mainActivity().between("private fun beginConnect()", "private fun dial(")
        val refresh = beginConnect.indexOf("homeViewModel.refreshServer()")
        val check = beginConnect.indexOf("hasServer")
        assertTrue("beginConnect no longer re-reads the selection:\n$beginConnect", refresh >= 0)
        assertTrue(
            "beginConnect reads hasServer before refreshing it, so a stale screen decides " +
                "whether the tap connects:\n$beginConnect",
            refresh < check,
        )

        val start = mainActivity().between("private fun startV2Ray(): Boolean", "private fun restartV2Ray")
        assertTrue(
            "startV2Ray must re-read too: an ad or a consent sheet can stand between " +
                "beginConnect's check and this one:\n$start",
            start.contains("homeViewModel.refreshServer()"),
        )
    }

    @Test
    fun `smart candidates are never written into the visible server list`() {
        val applySmart = source("panel/PanelSync.kt").readText().between(
            "private fun applySmart(",
            "private fun clearSmart(",
        )
        assertTrue(
            "applySmart stores its hidden candidates with encodeServerConfig, which also adds " +
                "the guid to the visible server list. They then appear as servers the user can " +
                "pick, and the next sync deletes them again:\n$applySmart",
            !applySmart.contains("encodeServerConfig("),
        )
        assertTrue(
            "applySmart must store the profile directly:\n$applySmart",
            applySmart.contains("encodeProfileDirect("),
        )
    }

    @Test
    fun `the selection is only ever dropped through the one logged path`() {
        val mmkv = source("handler/MmkvManager.kt").readText()
        assertEquals(
            "Every removal of the selection must go through dropSelection(), which says so in " +
                "the log with the caller's stack. A second place that deletes the key is a " +
                "selection that can vanish with nothing to explain it.",
            1,
            mmkv.split("mainStorage.remove(KEY_SELECTED_SERVER)").size - 1,
        )
        val dropSelection = mmkv.between("private fun dropSelection(", "fun getRunServer")
        assertTrue(
            "dropSelection must log at WARN — a release build keeps WARN and drops INFO, and " +
                "this is the line that names the cause:\n$dropSelection",
            dropSelection.contains("LogUtil.w("),
        )
        assertTrue(
            "dropSelection must carry a stack, or the log says a selection went without " +
                "saying which code path took it:\n$dropSelection",
            dropSelection.contains("Throwable("),
        )
    }

    // ------------------------------------------------------------ reading --

    private fun mainActivity() = source("ui/main/MainActivity.kt").readText()

    private fun source(relative: String): File {
        val file = File(sourceRoot(), relative)
        assertTrue(
            "$relative is gone. If it moved, point this test at the new path — quietly " +
                "dropping it is how the check stops checking.",
            file.isFile,
        )
        return file
    }

    /** The text between two markers, for asserting about one function at a time. */
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
        throw AssertionError(
            "Could not find src/main/java/com/rahgozar/app above ${System.getProperty("user.dir")}",
        )
    }
}
