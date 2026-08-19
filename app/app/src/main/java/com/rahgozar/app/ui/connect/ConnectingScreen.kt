package com.rahgozar.app.ui.connect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Where a connect tap lands.
 *
 * It exists for two reasons, and the second is the one that made it. The first
 * is honesty: a connect is a sequence of real steps that takes seconds — the ad
 * flow's tunnel coming down, the core starting, the verification gate waiting
 * for one real answer — and a modal that says «لطفاً صبر کنید» over the home
 * screen for the whole of it tells the user nothing about which of them is
 * happening.
 *
 * The second is that a full-screen ad has to sit **between two screens**. Under
 * the arrangement this replaces, tapping Connect showed an ad and then put the
 * user back on the screen they tapped from, with a dial that had changed state:
 * no transition, and by Google's placement rules no legitimate place for an
 * interstitial. Tapping the server list was fine for exactly the reason this
 * was not — a screen opened afterwards. So the connect tap now opens one too,
 * and the ad, when there is one, is what the user passes through on the way.
 *
 * Which means the ordering is not cosmetic: this screen must appear **after**
 * the last ad of the tap is dismissed, never before it. A screen already on
 * display when the ad arrives is a screen the ad interrupted. See
 * `MainActivity.beginConnect`.
 */
@Composable
fun ConnectingScreen(
    stage: ConnectStage,
    serverName: String,
    serverCountry: String,
    serverProtocol: String,
    /** Whether an ad was part of this journey, and the note is therefore true. */
    afterAnAd: Boolean,
    onDismiss: () -> Unit,
) {
    val working = stage == ConnectStage.PREPARING || stage == ConnectStage.DIALLING

    TransitionBackdrop(
        // Only while something is still running. Once the verdict is in there
        // is nothing to stay for, and the line would be asking for patience
        // the screen no longer needs.
        footer = { if (afterAnAd && working) AdSupportNote() },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            StageEmblem(
                spinning = working,
                verdict = when (stage) {
                    ConnectStage.DONE -> Verdict.CONNECTED
                    ConnectStage.FAILED -> Verdict.FAILED
                    else -> Verdict.NONE
                },
            )

            // A fixed box, so the two lines swapping does not move the emblem
            // above them or the chip below. The same delayed crossfade the
            // splash uses: without the delay both messages are on screen at
            // once and the text reads as a smear.
            Box(
                Modifier.height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = stage,
                    transitionSpec = {
                        (fadeIn(tween(320, delayMillis = 260)) +
                            slideInVertically(tween(320, delayMillis = 260)) { it / 5 })
                            .togetherWith(fadeOut(tween(240)))
                            .using(SizeTransform(clip = false) { _, _ -> snap() })
                    },
                    label = "connect-stage",
                ) { current ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StageHeadline(headline(current))
                        StageSubtitle(subtitle(current))
                    }
                }
            }

            ServerChip(serverName, serverCountry, serverProtocol)

            // Only the failure is dismissible. The rest of the sequence has
            // nothing to cancel into: the ad tunnel may be half-down and the
            // user's core half-up, and a way out of this screen would hand the
            // user back a dial that contradicts what is still happening behind
            // it. Success needs no button either — it leaves by itself.
            //
            // Which is exactly why the note is there while it is working: the
            // screen cannot be left safely, so it says so rather than letting
            // the user find out.
            when {
                stage == ConnectStage.FAILED ->
                    PillButton("Back", onDismiss)

                working -> StayHereNote()

                else -> Spacer(Modifier.height(1.dp))
            }
        }
    }
}

/**
 * What each stage is called.
 *
 * [ConnectStage.PREPARING] deliberately does not say "connecting": nothing of
 * the user's is connecting yet, the tunnel coming down at that moment is the ad
 * flow's, and the home screen spends the same span reporting itself off. The
 * app does not get to describe that tunnel as the user's on one screen and not
 * on another.
 */
private fun headline(stage: ConnectStage): String = when (stage) {
    ConnectStage.PREPARING -> "Getting ready"
    ConnectStage.DIALLING -> "Connecting"
    ConnectStage.DONE -> "You're connected"
    ConnectStage.FAILED -> "Couldn't connect"
}

private fun subtitle(stage: ConnectStage): String = when (stage) {
    ConnectStage.PREPARING ->
        "One moment…"

    ConnectStage.DIALLING ->
        "Bringing the tunnel up and checking it"

    ConnectStage.DONE ->
        "Your traffic is going through the tunnel"

    ConnectStage.FAILED ->
        "Try another server"
}
