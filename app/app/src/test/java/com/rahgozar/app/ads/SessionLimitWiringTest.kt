package com.rahgozar.app.ads

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every core that can carry a user's connection must arm the session countdown.
 *
 * [SessionLimit] deliberately runs the countdown inside the tunnel's own
 * process — there is no alarm, because exact alarms need a permission Google
 * does not grant VPN apps. The consequence is that the wiring cannot live in
 * one place: each service has to arm it for itself once its tunnel is up, and
 * disarm it on the way down.
 *
 * Which makes it exactly the kind of thing a new core forgets. Aether did:
 * it shipped as the fifth service with no `SessionLimit` reference at all, so
 * a timed session on it counted down to 00:00 on screen, showed the "less than
 * a minute left" warning, and then kept the tunnel up indefinitely — the
 * enforcement simply was not running in that process. Nothing failed loudly;
 * the user just got free time.
 *
 * This test reads the sources rather than the classes on purpose. The call has
 * to be *present in that file*, and no reflection over a compiled service can
 * see whether a method it never invokes contains a particular call.
 */
class SessionLimitWiringTest {

    /**
     * The services `LauncherManager.startService` can start for a user's
     * connection. `CoreProxyOnlyService` is absent deliberately: it opens no
     * tun, so there is no tunnel for a deadline to cut.
     */
    private val tunnelServices = listOf(
        "core/CoreServiceManager.kt",   // where CoreVpnService and CoreRootService both land
        "service/OpenVpnService.kt",
        "service/SingBoxService.kt",
        "service/AetherVpnService.kt",
    )

    @Test
    fun `every tunnel service arms and disarms the session countdown`() {
        val root = sourceRoot()
        val missing = mutableListOf<String>()

        for (relative in tunnelServices) {
            val file = File(root, relative)
            assertTrue(
                "$relative is gone. If a service was renamed, rename it here too — " +
                    "silently dropping it from this list is how the check stops checking.",
                file.isFile,
            )
            val source = file.readText()
            if (!source.contains("SessionLimit.arm(")) missing += "$relative: no SessionLimit.arm(...)"
            if (!source.contains("SessionLimit.disarm(")) missing += "$relative: no SessionLimit.disarm()"
        }

        if (missing.isNotEmpty()) {
            fail(
                "A tunnel service is not enforcing the session limit, so a timed " +
                    "connection on that core runs past its deadline:\n  " +
                    missing.joinToString("\n  "),
            )
        }
    }

    /** Walks up from wherever the test runner started to `.../com/rahgozar/app`. */
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
