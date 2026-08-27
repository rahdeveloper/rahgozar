package com.rahgozar.app.util

import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app's internal broadcasts are its tunnel control channel.
 *
 * `MSG_STATE_STOP` closes the tunnel, `MSG_STATE_RESTART` cycles it, and the UI
 * channel decides what the user is told about their own connection. Every one
 * of the seven registration sites takes its flags from [Utils.receiverFlags],
 * and that returned `RECEIVER_EXPORTED` on API 33 and above — so any installed
 * app, holding no permissions at all, could send
 * `Intent(AppConfig.BROADCAST_ACTION_SERVICE).setPackage("com.rahgozar.app")`
 * and turn a user's VPN off, then follow it with a forged "running" state so
 * the app kept saying it was connected.
 *
 * Setting `intent.package` on the sending side limits who *receives* a
 * broadcast. It never limited who could *send* one. That is the half this test
 * pins, and it is one refactor away from coming back.
 */
class ReceiverFlagsTest {

    @Test
    fun `internal receivers are never exported`() {
        assertEquals(
            "Utils.receiverFlags() must return RECEIVER_NOT_EXPORTED. Exporting these " +
                "hands the tunnel's stop control to every app on the device.",
            ContextCompat.RECEIVER_NOT_EXPORTED,
            Utils.receiverFlags(),
        )
    }
}
