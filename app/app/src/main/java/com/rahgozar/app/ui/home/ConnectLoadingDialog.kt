package com.rahgozar.app.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rahgozar.app.ui.brand.Brand
import com.rahgozar.app.ui.brand.BrandMark
import com.rahgozar.app.ui.brand.LocalPalette

/**
 * Holds the screen while a connection is being made.
 *
 * It exists because the connect tap is no longer one step: the app may bring
 * a tunnel up, show a full-screen ad over it, take it down again and only
 * then dial the server the user chose. Without something covering that, the
 * user watches the dial start spinning, get interrupted, and start spinning
 * again — which reads as a failed attempt followed by a retry.
 *
 * So this is deliberately one dialog for the whole span, and the caller
 * leaves it up until the tunnel is either carrying traffic or has given up.
 * The wording never mentions what is happening behind it, because every one
 * of those steps is the app connecting.
 *
 * @param connecting false when the same hold is needed for something that is
 *   *not* a connection — the server-list tap, which also shows an ad and then
 *   closes the ad tunnel. It needs the screen held for exactly the same
 *   reason, and it would be a lie to call it connecting.
 * @param adNote whether an ad is part of what this wait is for, and the line
 *   about ads is therefore true of it. Off, nothing is said — a thank-you for
 *   an ad nobody is going to see is worse than saying nothing.
 */
@Composable
fun ConnectLoadingDialog(connecting: Boolean = true, adNote: Boolean = false) {
    val palette = LocalPalette.current

    Dialog(
        onDismissRequest = {},
        // Not dismissible, by back press or by a tap outside. There is nothing
        // to cancel into: the smart tunnel may be half-up and the ad about to
        // appear, and a dialog the user can dismiss would leave them on a home
        // screen whose dial contradicts what happens next.
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(palette.drawerBackground)
                .border(1.dp, palette.hair, RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spinner()
            Spacer(Modifier.height(18.dp))
            Text(
                when {
                    connecting -> "Connecting"
                    else -> "One moment"
                },
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    color = palette.text,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(Modifier.height(8.dp))
            // Asking, not just reporting. This dialog covers a span the user
            // can spoil by leaving — the flow runs on the Activity's scope, so
            // backing out cancels it and takes down the tunnel it opened.
            Text(
                "Please stay on this screen until it finishes",
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontSize = 13.sp,
                    color = palette.text2,
                    textAlign = TextAlign.Center,
                ),
            )

            if (adNote) {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.hair)
                )
                Spacer(Modifier.height(14.dp))
                // Why there is an ad in the middle of a connection at all. The
                // ads pay for a free tunnel, and a user told so is in an
                // exchange rather than an interruption.
                Text(
                    "Watching ads keeps this service free and helps us improve it",
                    style = TextStyle(
                        fontFamily = Brand.Vazirmatn,
                        fontSize = 12.sp,
                        lineHeight = 20.sp,
                        color = palette.dim,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

/** The splash's spinner, at dialog scale. */
@Composable
private fun Spinner() {
    val palette = LocalPalette.current
    val spin by rememberInfiniteTransition(label = "connect-spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "turn",
    )
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .rotate(spin)
        ) {
            val stroke = 2.dp.toPx()
            drawCircle(
                color = palette.accent.copy(alpha = 0.18f),
                style = Stroke(width = stroke),
                radius = (size.minDimension - stroke) / 2f,
            )
            drawArc(
                color = palette.accent,
                startAngle = -90f,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
            )
        }
        Box(
            Modifier
                .padding(14.dp)
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(palette.dialInner, palette.dialOuter))),
            contentAlignment = Alignment.Center,
        ) {
            BrandMark(Modifier.size(20.dp), strokeWidth = 4.6f, showRoadDashes = false)
        }
    }
}
