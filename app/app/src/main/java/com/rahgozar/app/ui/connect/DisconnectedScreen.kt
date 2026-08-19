package com.rahgozar.app.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.rahgozar.app.ui.brand.LocalPalette
import com.rahgozar.app.ui.home.HomeUiState
import com.rahgozar.app.ui.home.formatBytes

/**
 * The session that just ended, as figures.
 *
 * A copy, taken **before** the tunnel is stopped, and that is the whole reason
 * the type exists: every number here is read from the live tunnel state, which
 * resets the instant the service goes down. Reading them when this screen is
 * finally shown — after an ad, possibly half a minute later — would report a
 * session of zero seconds and no traffic.
 */
@Immutable
data class SessionSummary(
    val serverName: String,
    val serverCountry: String,
    val serverProtocol: String,
    /** Seconds the tunnel was up. */
    val seconds: Long,
    val upBytes: Long,
    val downBytes: Long,
) {
    val clock: String
        get() = "%02d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)

    companion object {
        fun of(state: HomeUiState) = SessionSummary(
            serverName = state.serverName,
            serverCountry = state.serverCountry,
            serverProtocol = state.serverProtocol,
            seconds = state.elapsed,
            upBytes = state.sessionUpBytes,
            downBytes = state.sessionDownBytes,
        )
    }
}

/**
 * Where a disconnect tap lands.
 *
 * The mirror of [ConnectingScreen], for the same two reasons. It reports
 * something the app knew and used to throw away — how long the connection
 * lasted and what went through it — and it gives the disconnect slot's ad a
 * screen to be *between*, which a full-screen ad shown over the home screen the
 * user never left does not have.
 *
 * Unlike the connecting screen this one does not leave by itself. It is the
 * last word on a session, there is nothing still happening behind it, and a
 * summary that vanished on a timer would be a toast pretending to be a screen.
 */
@Composable
fun DisconnectedScreen(summary: SessionSummary, onDone: () -> Unit) {
    val palette = LocalPalette.current

    TransitionBackdrop {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StageEmblem(spinning = false, verdict = Verdict.ENDED)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StageHeadline("Disconnected")
                StageSubtitle(
                    "Your traffic is no longer going through the tunnel"
                )
            }

            ServerChip(summary.serverName, summary.serverCountry, summary.serverProtocol)

            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surface)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FigureRow(
                    "Connected for",
                    summary.clock,
                )
                FigureRow(
                    "Downloaded",
                    formatBytes(summary.downBytes),
                )
                FigureRow(
                    "Uploaded",
                    formatBytes(summary.upBytes),
                )
            }

            PillButton("Done", onDone)
        }
    }
}
