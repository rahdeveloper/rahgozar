package com.rahgozar.app.ui.splash

import com.rahgozar.app.panel.AdsConfig
import com.rahgozar.app.panel.PanelGate
import com.rahgozar.app.panel.PanelSettings
import com.rahgozar.app.panel.PanelSync
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One more try before a first launch's error screen — and never anywhere else.
 *
 * On 2026-10-05 a fresh install from Play hit the error screen although nothing
 * had failed on the server: its first fetch was lost on the way, and the user's
 * own Try again went straight through. See [FirstLaunchRetry].
 */
class FirstLaunchRetryTest {

    private val lost = PanelSync.Result.Unavailable("timeout")

    @Test
    fun `a first launch whose sync was lost on the way tries again`() {
        assertTrue(FirstLaunchRetry.worthIt(lost, hasStoredConfiguration = false))
    }

    @Test
    fun `a refusal read off the wire is still worth one more try`() {
        // A 503 "come back" or a rate limit is answered, not fatal. On a first
        // launch with nothing stored, one more try costs a second and a half.
        val answered = PanelSync.Result.Unavailable("HTTP 503", answered = true)
        assertTrue(FirstLaunchRetry.worthIt(answered, hasStoredConfiguration = false))
    }

    @Test
    fun `a later launch carries on with its saved servers instead`() {
        // Nothing to retry for: the splash goes into the app on what it has.
        assertFalse(FirstLaunchRetry.worthIt(lost, hasStoredConfiguration = true))
    }

    @Test
    fun `a fatal failure fails the same way twice`() {
        val broken = PanelSync.Result.Unavailable("discovery.json is missing from the build", fatal = true)
        assertFalse(FirstLaunchRetry.worthIt(broken, hasStoredConfiguration = false))
    }

    @Test
    fun `an answer is never retried`() {
        val ready = PanelSync.Result.Ready(changed = true, PanelSettings.parse(null), AdsConfig.disabled())
        val blocked = PanelSync.Result.Blocked(PanelGate.Decision.RootBlocked, PanelSettings.parse(null))
        assertFalse(FirstLaunchRetry.worthIt(ready, hasStoredConfiguration = false))
        assertFalse(FirstLaunchRetry.worthIt(blocked, hasStoredConfiguration = false))
    }

    @Test
    fun `the splash sync goes through the retry`() {
        // The rule above is only worth anything if the splash asks it.
        val splash = File(sourceRoot(), "ui/splash/SplashViewModel.kt").readText()
        val start = splash.substringAfter("private fun start()").substringBefore("private suspend fun syncAllowingOneRetry")
        assertTrue(
            "The splash starts its sync without the first-launch retry:\n$start",
            start.contains("syncAllowingOneRetry()"),
        )
        val wrapper = splash.substringAfter("private suspend fun syncAllowingOneRetry").substringBefore("/** Awaits the sync")
        assertTrue(
            "The retry no longer asks FirstLaunchRetry whether to try again:\n$wrapper",
            wrapper.contains("FirstLaunchRetry.worthIt("),
        )
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
