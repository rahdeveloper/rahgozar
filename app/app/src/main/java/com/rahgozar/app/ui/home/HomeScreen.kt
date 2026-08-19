package com.rahgozar.app.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rahgozar.app.ui.brand.Brand
import com.rahgozar.app.ui.brand.LocalPalette
import com.rahgozar.app.ui.brand.countryFlag

/**
 * Frame 5a — the whole app in one screen.
 *
 * The dial is the control: one tap connects, one tap disconnects. Everything
 * below it reports, nothing else acts, which is why there is no edit, no add
 * and no share anywhere on it.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onToggle: () -> Unit,
    onConfirmDisconnect: () -> Unit = {},
    onExtend: () -> Unit = {},
    onSelectServer: () -> Unit,
    onTest: () -> Unit,
    onMenu: () -> Unit = {},
    onToggleTheme: () -> Unit = {},
) {
    val palette = LocalPalette.current

    Box(
        Modifier
            .fillMaxSize()
            // The gradient runs edge to edge behind the system bars; the
            // content below does not, or the dial gets clipped by the status
            // bar.
            .background(
                Brush.verticalGradient(
                    0.00f to palette.skyTop,
                    0.46f to palette.skyMid,
                    1.00f to palette.skyBottom,
                )
            )
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            // Short screens lose whitespace before they lose anything else.
            val compact = maxHeight < COMPACT_BELOW

            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppTopBar(
                    tag = "RAHGOZAR",
                    title = "Rahgozar",
                    onMenu = onMenu,
                    onToggleTheme = onToggleTheme,
                )

                // The dial carries the screen's slack, and that is what keeps
                // this screen off a scrollbar.
                //
                // Everything else here takes the height it needs; the dial
                // takes what is left, capped at the design's 232dp. So a timed
                // session appearing — the extend button, and the warning card a
                // minute before the end — costs the dial its own diameter
                // rather than pushing the throughput tapes underneath the
                // server bar, which is where they used to go. Nothing on this
                // screen can be scrolled out of reach because nothing is ever
                // laid out past the bottom of it.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Dial(state, onToggle)
                }

                StateBlock(state, compact)
                SessionBlock(state, compact, onExtend)
                Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                ThroughputHeader(state)
                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                Tapes(state, compact)
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))

                ServerBar(state, compact, onSelectServer, onTest)
            }
        }
    }
}

/**
 * Below this usable height the layout trades whitespace for content.
 *
 * Set above the common budget-phone size (720×1560 at 2.0 leaves about 730dp
 * once the system bars are gone) on purpose: those are exactly the screens
 * where the dial would otherwise be squeezed to nothing by a timed session.
 */
private val COMPACT_BELOW = 760.dp

/** The design's dial diameter, and the ceiling the slack is capped at. */
private val DIAL_MAX = 232.dp

/** The height [tapeBars] draws into. */
private val TAPE_BAND = 22.dp

// ------------------------------------------------------------------ dial --

/**
 * Hairline rim, tick rings, the progress arc, and the core button.
 *
 * 232dp where there is room for it, and whatever the screen can spare where
 * there is not. Everything inside is a fraction of the diameter rather than a
 * fixed dp, so the whole instrument shrinks in proportion instead of coming
 * apart — the ratios below are the design's own numbers over its 232.
 */
@Composable
private fun Dial(state: HomeUiState, onToggle: () -> Unit) {
    val palette = LocalPalette.current

    // The arc fills as the link comes up. Animated rather than snapped because
    // the design gives it a .7s ease-out — it is the screen's main feedback
    // that a tap did something.
    val arcSweep by animateFloatAsState(
        targetValue = if (state.isOn) 360f else 0f,
        animationSpec = tween(700),
        label = "arc",
    )

    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, maxHeight, DIAL_MAX)

        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            val tick = if (state.isOn) palette.accent.copy(alpha = 0.45f) else palette.hair
            val tickMajor = if (state.isOn) palette.accent else palette.dim

            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(palette.hair, radius = r - 0.5f, style = Stroke(1.dp.toPx()))

                // Minor ticks every 5°, in a band near the rim.
                ring(tick, count = 72, sweep = 0.55f, inner = 0.88f, outer = 1f)
                // Major ticks every 45°, reaching further in.
                ring(tickMajor, count = 8, sweep = 1.1f, inner = 0.82f, outer = 1f)

                // The single mark at twelve o'clock the design puts outside the rim.
                drawLine(
                    color = tickMajor,
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.minDimension * 0.047f),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Progress arc.
            Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(diameter * 0.069f)
            ) {
                if (arcSweep <= 0f) return@Canvas
                val stroke = size.minDimension * 0.055f
                drawArc(
                    color = palette.accent,
                    startAngle = -90f,
                    sweepAngle = arcSweep,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                )
            }

            if (state.isConnecting) ConnectingSweep(inset = diameter * 0.078f)
            if (state.isOn) {
                PulseRing(periodMs = 3200, delayMs = 0, width = 1.dp, inset = diameter * 0.172f)
            }
            if (state.isIdle) {
                PulseRing(periodMs = 2200, delayMs = 0, width = 1.5.dp, inset = diameter * 0.172f)
                PulseRing(periodMs = 2200, delayMs = 1100, width = 1.5.dp, inset = diameter * 0.172f)
            }

            Core(state, onToggle, diameter = diameter * 0.586f)
        }
    }
}

/** Evenly spaced tick marks, drawn as short arcs in a radial band. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.ring(
    color: Color,
    count: Int,
    sweep: Float,
    inner: Float,
    outer: Float,
) {
    val r = size.minDimension / 2f
    val band = r * (outer - inner)
    val inset = r * (1f - outer) + band / 2f
    val step = 360f / count
    var angle = -90f
    repeat(count) {
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width = band),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
        )
        angle += step
    }
}

@Composable
private fun ConnectingSweep(inset: Dp) {
    val palette = LocalPalette.current
    val spin by rememberInfiniteTransition(label = "connect").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "spin",
    )
    Canvas(
        Modifier
            .fillMaxSize()
            .padding(inset)
            .rotate(spin)
    ) {
        val stroke = size.minDimension * 0.05f
        drawArc(
            brush = Brush.sweepGradient(
                0.0f to palette.accent,
                0.28f to Color.Transparent,
                1.0f to Color.Transparent,
            ),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
        )
    }
}

/** The expanding ring the design uses to say "live" and "waiting" alike. */
@Composable
private fun PulseRing(periodMs: Int, delayMs: Int, width: Dp, inset: Dp) {
    val palette = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "pulse$delayMs")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, delayMillis = delayMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "grow",
    )
    // 0.92 → 1.16 with the opacity gone by 75%, as the design's keyframes have it.
    val scale = 0.92f + 0.24f * progress
    val alpha = if (progress < 0.75f) 0.5f * (1f - progress / 0.75f) else 0f
    Box(
        Modifier
            .padding(inset)
            .fillMaxSize()
            .scale(scale)
            .border(width, palette.accent.copy(alpha = alpha), CircleShape)
    )
}

/** The button at the centre: power glyph over the session clock. */
@Composable
private fun Core(state: HomeUiState, onToggle: () -> Unit, diameter: Dp) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val on = state.isOn

    Box(
        Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(
                if (on) {
                    Brush.radialGradient(
                        listOf(palette.accent.copy(alpha = 0.20f), palette.surface)
                    )
                } else {
                    Brush.radialGradient(listOf(palette.surface, palette.surface))
                }
            )
            .border(1.dp, palette.hair, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(diameter * 0.037f),
        ) {
            PowerGlyph(if (on) palette.accent else palette.dim, diameter * 0.235f)
            Text(
                state.dialClock,
                style = TextStyle(
                    fontFamily = Brand.JetBrainsMono,
                    fontWeight = FontWeight.SemiBold,
                    // Proportional, but never past the point of being read: on
                    // a short screen this clock is a countdown someone is
                    // watching.
                    fontSize = (diameter.value * 0.125f).coerceAtLeast(11f).sp,
                    letterSpacing = 0.3.sp,
                    color = when {
                        state.dialClockEnding -> palette.danger
                        on -> palette.accent
                        else -> palette.dim
                    },
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PowerGlyph(color: Color, glyphSize: Dp) {
    Canvas(Modifier.size(glyphSize)) {
        val u = size.minDimension / 100f
        val stroke = 7f * u
        // The stem.
        drawLine(
            color, Offset(50 * u, 20 * u), Offset(50 * u, 46 * u),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
        // The open arc beneath it: M32 34 A32 32 0 1 0 68 34
        val path = Path().apply {
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    Offset(18 * u, 20 * u), Size(64 * u, 64 * u)
                ),
                startAngleDegrees = 143f,
                sweepAngleDegrees = 254f,
                forceMoveTo = true,
            )
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

// ----------------------------------------------------------------- state --

@Composable
private fun StateBlock(state: HomeUiState, compact: Boolean) {
    val palette = LocalPalette.current

    val label = when (state.link) {
        LinkState.ON -> "Connected"
        LinkState.CONNECTING -> "Connecting…"
        LinkState.OFF -> "Disconnected"
    }
    val quality = when {
        state.pingMs < 0L -> "NO REPLY"
        !state.isOn -> "READY"
        state.pingMs == 0L -> "READY"
        state.pingMs <= PING_GOOD_MS -> "EXCELLENT"
        state.pingMs <= PING_FAIR_MS -> "FAIR"
        else -> "POOR"
    }
    // A failed test is worth colouring even while disconnected: it is the one
    // thing on this screen telling the user this server will not work.
    val qualityColor = if (state.isOn || state.pingMs < 0L) {
        pingColor(state.pingMs, palette)
    } else {
        palette.dim
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 24.sp,
                    color = palette.text,
                ),
                maxLines = 1,
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, qualityColor.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    quality,
                    style = chipStyle(qualityColor),
                    maxLines = 1,
                )
            }
        }

        // Where the design showed «IP · mode». The address is gone; the mode is
        // the part that answers the question the line was really asking.
        Text(
            state.routeToken,
            style = TextStyle(
                fontFamily = Brand.JetBrainsMono,
                fontSize = 10.5.sp,
                letterSpacing = 1.1.sp,
                color = if (state.isOn) palette.accent else palette.dim,
            ),
            maxLines = 1,
        )

        if (state.notice != null) NoticeCard(state.notice)
    }
}

/**
 * The offer of more time.
 *
 * How much is left is not here — it is the dial's own clock, which a limited
 * session turns into a countdown. This is only the button, because every dp
 * this block spends is a dp the throughput tapes lose to the server bar below.
 * It is deliberately quiet: it offers something, it does not demand anything.
 */
@Composable
private fun SessionBlock(state: HomeUiState, compact: Boolean, onExtend: () -> Unit) {
    val palette = LocalPalette.current
    if (!state.canExtend) return

    val label = when {
        state.extending -> "Getting the ad ready…"
        else -> "${state.extendMinutes} more minutes"
    }

    Spacer(Modifier.height(if (compact) 7.dp else 10.dp))
    Box(
        Modifier
            .clip(CircleShape)
            .background(palette.accent.copy(alpha = if (state.extending) 0.06f else 0.12f))
            .border(1.dp, palette.accent.copy(alpha = 0.35f), CircleShape)
            .clickable(enabled = !state.extending, onClick = onExtend)
            .padding(horizontal = 20.dp, vertical = if (compact) 7.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = palette.accent,
            ),
            maxLines = 1,
        )
    }
}

/**
 * The one case where "disconnected" is not the whole story.
 *
 * A thin red line under the status read as decoration and got skipped, which
 * defeats the point: this is the only thing on the screen telling someone why
 * a server they just chose did nothing. So it is a card, it says what happened
 * and what to do about it, and it is written here in both languages like every
 * other screen in the redesign rather than resolved from a string resource.
 */
@Composable
private fun NoticeCard(notice: HomeNotice) {
    val palette = LocalPalette.current

    val title = when (notice) {
        HomeNotice.SERVER_NOT_RESPONDING ->
            "This server isn't responding"

        HomeNotice.SESSION_ENDING ->
            "Less than a minute of connection left"

        HomeNotice.REWARD_NOT_EARNED ->
            "The ad wasn't watched through"

        HomeNotice.EXTENSIONS_USED_UP ->
            "No extensions left on this connection"

        HomeNotice.AD_NOT_AVAILABLE ->
            "No ad was available"
    }
    // Says only what the user saw. The connection never completed from their
    // side — the dial went from connecting to failed — so any wording about a
    // tunnel that "came up" describes an internal state and reads as a
    // contradiction of what is on screen.
    val body = when (notice) {
        HomeNotice.SERVER_NOT_RESPONDING -> "Pick another server from the list."

        HomeNotice.SESSION_ENDING -> "Tap the extend button to keep going."

        HomeNotice.REWARD_NOT_EARNED -> "Watch it to the end to get the extra time."

        HomeNotice.EXTENSIONS_USED_UP -> "You can connect again once the time runs out."

        HomeNotice.AD_NOT_AVAILABLE -> "Try again in a moment."
    }

    Row(
        Modifier
            .padding(horizontal = 22.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.danger.copy(alpha = 0.10f))
            .border(1.dp, palette.danger.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        WarnGlyph(palette.danger)
        Column {
            Text(
                title,
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp,
                    color = palette.danger,
                ),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                body,
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = palette.text2,
                ),
            )
        }
    }
}

/** A circled exclamation, drawn to match the other glyphs on this screen. */
@Composable
private fun WarnGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val u = size.minDimension / 24f
        val stroke = 2f * u
        drawCircle(color, radius = 10f * u, center = Offset(12 * u, 12 * u), style = Stroke(stroke))
        drawLine(color, Offset(12 * u, 7 * u), Offset(12 * u, 13.5f * u), stroke, StrokeCap.Round)
        drawCircle(color, radius = 1.2f * u, center = Offset(12 * u, 16.8f * u))
    }
}

@Composable
private fun chipStyle(color: Color) = TextStyle(
    fontFamily = Brand.JetBrainsMono,
    fontWeight = FontWeight.Bold,
    fontSize = 8.5.sp,
    letterSpacing = 1.4.sp,
    color = color,
)

// ------------------------------------------------------------ throughput --

@Composable
private fun ThroughputHeader(state: HomeUiState) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "THROUGHPUT",
            style = TextStyle(
                fontFamily = Brand.JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.8.sp,
                color = palette.accent,
            ),
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(palette.hair)
        )
        Text(
            formatSession(state.sessionTotalBytes),
            style = TextStyle(
                fontFamily = Brand.JetBrainsMono,
                fontWeight = FontWeight.Medium,
                fontSize = 10.5.sp,
                letterSpacing = 1.1.sp,
                color = palette.faint,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun Tapes(state: HomeUiState, compact: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        Tape(
            label = "DOWNLOAD",
            value = formatSpeed(state.downBytesPerSec),
            total = formatBytes(state.sessionDownBytes),
            history = state.downHistory,
            live = state.isOn,
            downward = true,
            compact = compact,
        )
        Tape(
            label = "UPLOAD",
            value = formatSpeed(state.upBytesPerSec),
            total = formatBytes(state.sessionUpBytes),
            history = state.upHistory,
            live = state.isOn,
            downward = false,
            compact = compact,
        )
    }
}

@Composable
private fun Tape(
    label: String,
    value: String,
    total: String,
    history: List<Long>,
    live: Boolean,
    downward: Boolean,
    compact: Boolean,
) {
    val palette = LocalPalette.current

    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Arrow(downward, if (live) palette.accent else palette.dim)
            Text(
                label,
                style = TextStyle(
                    fontFamily = Brand.JetBrainsMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    letterSpacing = 1.7.sp,
                    color = palette.dim,
                ),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                value,
                style = TextStyle(
                    fontFamily = Brand.JetBrainsMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = palette.text,
                ),
                maxLines = 1,
            )
            Text(
                "MB/S",
                style = TextStyle(
                    fontFamily = Brand.JetBrainsMono,
                    fontSize = 9.sp,
                    color = palette.dim,
                ),
                maxLines = 1,
            )
        }

        // The cumulative figure for this direction, which one "SESSION" number
        // could not give.
        Text(
            total,
            style = TextStyle(
                fontFamily = Brand.JetBrainsMono,
                fontSize = 9.sp,
                letterSpacing = 0.8.sp,
                color = palette.faint,
            ),
            maxLines = 1,
        )

        val bars = remember(history) { tapeBars(history) }
        // tapeBars works in the design's 22dp band; a shorter tape rescales it
        // rather than letting the tall bars saturate against the ceiling.
        val band = if (compact) 16.dp else TAPE_BAND
        val scale = band / TAPE_BAND
        Row(
            Modifier
                .fillMaxWidth()
                .height(band),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEachIndexed { i, h ->
                val barColor = when {
                    !live -> palette.hair
                    // The newest samples are the bright end, so the eye reads
                    // the tape as old-on-the-left, now-on-the-right.
                    i > TAPE_SAMPLES - 5 -> palette.accent
                    else -> palette.accent.copy(alpha = 0.34f)
                }
                val animated by animateFloatAsState(
                    targetValue = h,
                    animationSpec = tween(450),
                    label = "bar",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(animated.dp * scale)
                        .background(barColor)
                )
            }
        }
    }
}

@Composable
private fun Arrow(downward: Boolean, color: Color) {
    Canvas(Modifier.size(13.dp)) {
        val u = size.minDimension / 24f
        val stroke = 2f * u
        if (downward) {
            drawLine(color, Offset(12 * u, 5 * u), Offset(12 * u, 19 * u), stroke, StrokeCap.Round)
            drawLine(color, Offset(6 * u, 13 * u), Offset(12 * u, 19 * u), stroke, StrokeCap.Round)
            drawLine(color, Offset(18 * u, 13 * u), Offset(12 * u, 19 * u), stroke, StrokeCap.Round)
        } else {
            drawLine(color, Offset(12 * u, 19 * u), Offset(12 * u, 5 * u), stroke, StrokeCap.Round)
            drawLine(color, Offset(6 * u, 11 * u), Offset(12 * u, 5 * u), stroke, StrokeCap.Round)
            drawLine(color, Offset(18 * u, 11 * u), Offset(12 * u, 5 * u), stroke, StrokeCap.Round)
        }
    }
}

// ------------------------------------------------------------ server bar --

/**
 * The card at the foot: which server, and the two actions that survive.
 *
 * Deliberately loud. This is the only control on the screen besides the dial,
 * and in testing it read as a status line rather than a button — so it now has
 * a filled tile, an accent "change" affordance and a label that says what
 * tapping does, not just what is currently true.
 *
 * The design's third action was "Logs". It is not here — see docs/SECURITY.md.
 */
@Composable
private fun ServerBar(
    state: HomeUiState,
    compact: Boolean,
    onSelectServer: () -> Unit,
    onTest: () -> Unit,
) {
    val palette = LocalPalette.current

    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.barBackground)
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.hair))

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelectServer)
                .padding(
                    start = 22.dp,
                    end = 22.dp,
                    top = if (compact) 10.dp else 16.dp,
                    bottom = if (compact) 7.dp else 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    !state.hasServer -> "Pick a server to get started"
                    else -> "Current server"
                },
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = palette.dim,
                ),
                modifier = Modifier.weight(1f),
            )
            // The word that tells a first-time user this row is a control.
            Text(
                when {
                    !state.hasServer -> "Choose"
                    else -> "Change"
                },
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = palette.accent,
                ),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (state.hasServer) palette.surface else palette.accent.copy(alpha = 0.12f)
                )
                .border(
                    if (state.hasServer) 1.dp else 1.5.dp,
                    palette.accent.copy(alpha = if (state.hasServer) 0.35f else 0.8f),
                    RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onSelectServer)
                .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FlagTile(state.serverCountry)

            Column(Modifier.weight(1f)) {
                Text(
                    state.serverName.ifEmpty {
                        "Select a server"
                    },
                    style = TextStyle(
                        fontFamily = Brand.Vazirmatn,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = if (state.hasServer) palette.text else palette.accent,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!state.hasServer) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Choose one from the list before you can connect",
                        style = TextStyle(
                            fontFamily = Brand.Vazirmatn,
                            fontSize = 11.sp,
                            color = palette.text2,
                        ),
                        maxLines = 2,
                    )
                }
                if (state.serverProtocol.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        state.serverProtocol.uppercase(),
                        style = TextStyle(
                            fontFamily = Brand.JetBrainsMono,
                            fontWeight = FontWeight.Medium,
                            fontSize = 9.5.sp,
                            letterSpacing = 0.9.sp,
                            color = palette.accent,
                        ),
                        maxLines = 1,
                    )
                }
            }

            if (state.pingMs != 0L) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (state.pingMs < 0L) "×" else state.pingMs.toString(),
                        style = TextStyle(
                            fontFamily = Brand.JetBrainsMono,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = pingColor(state.pingMs, palette),
                        ),
                    )
                    if (state.pingMs > 0L) {
                        Text(
                            " MS",
                            style = TextStyle(
                                fontFamily = Brand.JetBrainsMono,
                                fontSize = 9.sp,
                                color = palette.dim,
                            ),
                        )
                    }
                }
            }

            // A filled disc, not a bare chevron: at a glance it is a button.
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Chevron(palette.accent)
            }
        }

        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))

        // One action, where the design had two. The routing button went with
        // the routing screen: the app now runs a single fixed ruleset — Iran
        // direct, everything else tunnelled — so there is nothing on the other
        // side of that tap to look at or change.
        Row(Modifier.fillMaxWidth()) {
            QuickAction(
                label = "Test",
                active = state.testing,
                compact = compact,
                onClick = onTest,
                modifier = Modifier.weight(1f),
            ) { color -> GaugeIcon(color) }
        }
    }
}

/** The country flag, or a neutral tile when the panel did not say. */
@Composable
private fun FlagTile(country: String) {
    val palette = LocalPalette.current
    val flag = countryFlag(country)
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.hoverWash),
        contentAlignment = Alignment.Center,
    ) {
        if (flag.isNotEmpty()) {
            Text(flag, style = TextStyle(fontSize = 24.sp))
        } else {
            Text(
                country.take(2).uppercase().ifEmpty { "—" },
                style = TextStyle(
                    fontFamily = Brand.JetBrainsMono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    color = palette.dim,
                ),
            )
        }
    }
}

@Composable
private fun QuickAction(
    label: String,
    active: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val palette = LocalPalette.current
    val color = if (active) palette.accent else palette.text2
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(
                top = if (compact) 10.dp else 15.dp,
                bottom = if (compact) 12.dp else 17.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
    ) {
        icon(color)
        Text(
            label,
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontWeight = FontWeight.Medium,
                fontSize = 9.5.sp,
                color = color,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun GaugeIcon(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val u = size.minDimension / 24f
        val stroke = 2f * u
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            style = Stroke(stroke, cap = StrokeCap.Round),
            topLeft = Offset(4 * u, 7 * u),
            size = Size(16 * u, 16 * u),
        )
        drawLine(color, Offset(12 * u, 15 * u), Offset(15.5f * u, 10.5f * u), stroke, StrokeCap.Round)
    }
}


@Composable
private fun Chevron(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val u = size.minDimension / 24f
        val stroke = 2f * u
        val x1 = 9 * u
        val x2 = 15 * u
        drawLine(color, Offset(x1, 6 * u), Offset(x2, 12 * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(x2, 12 * u), Offset(x1, 18 * u), stroke, StrokeCap.Round)
    }
}
