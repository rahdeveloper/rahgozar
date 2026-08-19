package com.rahgozar.app.ui.splash

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rahgozar.app.ui.brand.Brand
import com.rahgozar.app.ui.brand.BrandMark
import com.rahgozar.app.ui.brand.LocalPalette
import kotlin.math.roundToInt

/**
 * The splash, in the two frames the design specifies.
 *
 * Frame 5b is the mark and the wordmark; 5c replaces them with the sync
 * messages. They share a background so the transition is a change of content,
 * not of screen.
 *
 * The design's "V1.0.4 · XRAY-CORE 25.3.1" footer is deliberately absent: it
 * was placeholder text naming a component the user has no reason to know about.
 */
@Composable
fun SplashScreen(
    phase: SplashPhase,
    failureReason: String,
    onRetry: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.00f to palette.skyTop,
                    0.52f to palette.skyMid,
                    1.00f to palette.skyBottom,
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        BreathingGlow()
        Scanlines()

        AnimatedContent(
            targetState = phase == SplashPhase.BRAND,
            transitionSpec = {
                // No SizeTransform: letting the container resize between the
                // two frames squeezed the wordmark mid-fade and broke "رهگذر"
                // across two lines.
                // A plain crossfade, deliberately undelayed: the inner message
                // swap already carries a delay, and two delayed fades landing
                // together leave the screen empty in between.
                (fadeIn(tween(340)) togetherWith fadeOut(tween(340)))
                    .using(SizeTransform(clip = false) { _, _ -> snap() })
            },
            label = "splash-frame",
        ) { isBrand ->
            if (isBrand) BrandFrame() else SyncFrame(phase, failureReason, onRetry)
        }
    }
}

// ------------------------------------------------------------------ 5b --

/** The mark at rest: tick ring, sweeping arc, sphere, wordmark, loading bar. */
@Composable
private fun BrandFrame() {
    val palette = LocalPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Dial(size = 150.dp)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.wrapContentWidth(unbounded = true),
        ) {
            Text(
                "RAHGOZAR",
                style = TextStyle(
                    fontFamily = Brand.JetBrainsMono,
                    fontWeight = FontWeight.Black,
                    fontSize = 32.sp,
                    color = palette.text,
                ),
                maxLines = 1,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "PRIVATE TUNNEL",
                style = TextStyle(
                    fontFamily = Brand.JetBrainsMono,
                    fontSize = 10.sp,
                    letterSpacing = 3.sp, // .3em
                    color = palette.dim,
                ),
                maxLines = 1,
            )
        }

        LoadingBar()
    }
}

/**
 * The 150dp dial: a ring of ticks, a jade arc sweeping over it, and a lit
 * sphere carrying the mark.
 */
@Composable
private fun Dial(size: androidx.compose.ui.unit.Dp) {
    val palette = LocalPalette.current
    val spin by rememberInfiniteTransition(label = "dial").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = androidx.compose.animation.core.LinearEasing)),
        label = "sweep",
    )

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        // Tick ring: 0.7° marks every 6°, as a thin band near the edge.
        Canvas(Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2f
            val band = radius * 0.12f
            var angle = -90f
            while (angle < 270f) {
                val sweep = 0.7f
                drawArc(
                    color = palette.accent.copy(alpha = 0.42f),
                    startAngle = angle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = band),
                    topLeft = Offset(band / 2, band / 2),
                    size = Size(this.size.width - band, this.size.height - band),
                )
                angle += 6f
            }
        }

        // The sweeping arc.
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
                .rotate(spin)
        ) {
            val stroke = this.size.minDimension * 0.045f
            drawArc(
                brush = Brush.sweepGradient(
                    0.0f to palette.accent,
                    0.30f to Color.Transparent,
                    1.0f to Color.Transparent,
                ),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(this.size.width - stroke, this.size.height - stroke),
            )
        }

        // The sphere, lit from upper-left as the design's radial gradient is.
        Box(
            Modifier
                .padding(34.dp)
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(palette.dialInner, palette.dialOuter),
                        center = Offset.Unspecified,
                        radius = Float.POSITIVE_INFINITY,
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            BrandMark(Modifier.size(34.dp), strokeWidth = 3.6f)
        }
    }
}

/** The 110×2 track with a 42dp jade segment crossing it. */
@Composable
private fun LoadingBar() {
    val palette = LocalPalette.current
    val offset by rememberInfiniteTransition(label = "bar").animateFloat(
        initialValue = -1f,
        targetValue = 2.6f,
        animationSpec = infiniteRepeatable(tween(1400)),
        label = "slide",
    )
    Box(
        Modifier
            .width(110.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(palette.trackOff)
    ) {
        Box(
            Modifier
                .offset { IntOffset((110.dp.toPx() * offset).roundToInt(), 0) }
                .width(42.dp)
                .fillMaxHeight()
                .background(palette.accent)
        )
    }
}

// ------------------------------------------------------------------ 5c --

/**
 * The sync frame: a small spinner over the mark, the current message, and the
 * tagline.
 */
@Composable
private fun SyncFrame(phase: SplashPhase, failureReason: String, onRetry: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Spinner()

        Box(
            Modifier
                .width(300.dp)
                .height(52.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    // The incoming message waits for the outgoing one to finish
                    // fading. Without the delay both are on screen together and
                    // the text reads as a smear.
                    (fadeIn(tween(380, delayMillis = 320)) +
                        slideInVertically(tween(380, delayMillis = 320)) { it / 5 })
                        .togetherWith(fadeOut(tween(300)))
                        .using(SizeTransform(clip = false) { _, _ -> snap() })
                },
                label = "splash-message",
            ) { current ->
                val message = messageFor(current, failureReason)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (message.showTick) SuccessTick()
                        Text(
                            message.headline,
                            style = TextStyle(
                                fontFamily = Brand.Vazirmatn,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = palette.text,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                    if (message.subtitle.isNotEmpty()) Text(
                        message.subtitle,
                        style = TextStyle(
                            fontFamily = Brand.JetBrainsMono,
                            fontSize = 10.5.sp,
                            letterSpacing = 0.6.sp,
                            color = palette.dim,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }

        when (phase) {
            SplashPhase.FAILED -> RetryButton(onRetry)
            // Said where it is true and nowhere else: this is the only phase
            // that is waiting on an ad. The tagline gives up its place rather
            // than sitting next to it — two closing lines under one message is
            // one more than the design has room for.
            SplashPhase.AD_WAITING -> AdSupportNote()
            else -> Tagline()
        }
    }
}

/**
 * Why the user is looking at an ad at all.
 *
 * Standard practice in this category and, more to the point, the truth: the
 * ads are what pay for a free tunnel. A line that says so turns an
 * interruption into an exchange the user is party to, which is worth more than
 * the two seconds it costs to read.
 */
@Composable
private fun AdSupportNote() {
    val palette = LocalPalette.current
    // Bounded, because it is the only line on this screen long enough to reach
    // the edges of a narrow phone.
    Box(Modifier.width(300.dp), contentAlignment = Alignment.Center) {
        Text(
            "Watching ads keeps this service free and helps us improve it",
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontSize = 12.sp,
                lineHeight = 20.sp,
                color = palette.dim,
                textAlign = TextAlign.Center,
            ),
            maxLines = 2,
        )
    }
}

/** 64dp ring with a jade quarter, turning once a second. */
@Composable
private fun Spinner() {
    val palette = LocalPalette.current
    val spin by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "turn",
    )
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .rotate(spin)
        ) {
            val stroke = 2.dp.toPx()
            drawCircle(
                color = palette.accent.copy(alpha = 0.18f),
                style = Stroke(width = stroke),
                radius = (this.size.minDimension - stroke) / 2f,
            )
            drawArc(
                color = palette.accent,
                startAngle = -90f,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(this.size.width - stroke, this.size.height - stroke),
            )
        }
        Box(
            Modifier
                .padding(16.dp)
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(listOf(palette.dialInner, palette.dialOuter))
                ),
            contentAlignment = Alignment.Center,
        ) {
            BrandMark(Modifier.size(24.dp), strokeWidth = 4.6f, showRoadDashes = false)
        }
    }
}

@Composable
private fun SuccessTick() {
    val palette = LocalPalette.current
    Canvas(Modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        drawCircle(
            color = palette.accent,
            style = Stroke(width = stroke),
            radius = (size.minDimension - stroke) / 2f,
        )
        val u = size.minDimension / 24f
        drawLine(
            palette.accent, Offset(7 * u, 12.5f * u), Offset(10 * u, 15.5f * u),
            strokeWidth = 2.2f * u, cap = StrokeCap.Round,
        )
        drawLine(
            palette.accent, Offset(10 * u, 15.5f * u), Offset(17 * u, 8.5f * u),
            strokeWidth = 2.2f * u, cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun Tagline() {
    val palette = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Entering a safe, open world",
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 19.sp,
                brush = Brush.horizontalGradient(
                    listOf(palette.text, Brand.TaglineEnd)
                ),
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
    }
}

/**
 * The design has no failure state. Rather than inventing a screen, the message
 * area says what happened in the same two-line shape and offers the one action
 * that can help.
 */
@Composable
private fun RetryButton(onRetry: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(CircleShape)
            .background(palette.accent.copy(alpha = 0.12f))
            .padding(horizontal = 26.dp, vertical = 11.dp)
            .clickableNoRipple(onRetry),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Try again",
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = palette.accent,
            ),
        )
    }
}

/**
 * Copy for a phase, in both languages.
 *
 * The wording is the design's own, from its `DICT` table where it had one. The
 * Persian half of every pair went when the app became English-only.
 */
private fun messageFor(
    phase: SplashPhase,
    failureReason: String,
): SplashMessage {
    return when (phase) {
        SplashPhase.FETCHING, SplashPhase.BRAND -> SplashMessage(
            headline = "Fetching servers",
            subtitle = "",
        )

        SplashPhase.PATIENCE -> SplashMessage(
            headline = "Please wait",
            subtitle = "",
        )

        // The one wait the user can spoil by leaving: the flow lives on this
        // Activity's scope, so backing out cancels it and takes the tunnel it
        // opened down with it. So this phase asks, rather than just reporting.
        SplashPhase.AD_WAITING -> SplashMessage(
            headline = "Please stay on this screen",
            subtitle = "",
        )

        SplashPhase.SUCCESS -> SplashMessage(
            headline = "Servers loaded",
            subtitle = "",
            showTick = true,
        )

        // No tick. The app is opening, which is the good news, but nothing
        // was fetched and the tick is this screen's word for "fetched".
        SplashPhase.CACHED -> SplashMessage(
            headline = "Using the saved servers",
            subtitle = "Could not reach the panel",
        )

        SplashPhase.FAILED -> SplashMessage(
            headline = "Could not reach the panel",
            // The reason is a diagnostic, always in English: it comes from the
            // network layer and is what a support conversation quotes.
            subtitle = failureReason,
        )
    }
}

// ------------------------------------------------------------ background --

/** The slow jade bloom behind everything. */
@Composable
private fun BreathingGlow() {
    val palette = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "glow")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(4000), RepeatMode.Reverse),
        label = "scale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(4000), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier
            .size(400.dp)
            .scale(scale)
            .blur(52.dp)
            .background(
                Brush.radialGradient(
                    listOf(palette.glow.copy(alpha = alpha * 0.20f), Color.Transparent)
                ),
                CircleShape,
            )
    )
}

/** The 3%-opacity horizontal rule pattern that gives the panel its texture. */
@Composable
private fun Scanlines() {
    val palette = LocalPalette.current
    if (palette.scanlineAlpha <= 0f) return
    Canvas(Modifier.fillMaxSize()) {
        var y = 0f
        val step = 4.dp.toPx()
        val thickness = 1.dp.toPx()
        while (y < size.height) {
            drawLine(
                color = Color.White.copy(alpha = palette.scanlineAlpha),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = thickness,
            )
            y += step
        }
    }
}

/** A tap target without the material ripple, which the design does not use. */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

@Composable
private fun Text(text: String, style: TextStyle, maxLines: Int = Int.MAX_VALUE) {
    androidx.compose.material3.Text(
        text = text,
        style = style,
        maxLines = maxLines,
        softWrap = maxLines > 1,
    )
}
