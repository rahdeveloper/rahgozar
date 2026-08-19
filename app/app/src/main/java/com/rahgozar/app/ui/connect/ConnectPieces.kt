package com.rahgozar.app.ui.connect

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rahgozar.app.ui.brand.Brand
import com.rahgozar.app.ui.brand.BrandMark
import com.rahgozar.app.ui.brand.LocalPalette
import com.rahgozar.app.ui.brand.countryFlag

/**
 * The parts [ConnectingScreen] and [DisconnectedScreen] share.
 *
 * Both are the same screen with different news on it: the app's sky behind a
 * single centred column, a 96dp emblem saying which of four things happened,
 * and the server the news is about. The pieces live here rather than in one
 * screen and imported by the other, which is what keeps the pair from
 * drifting — the only way two screens like this ever go wrong.
 */

/**
 * The sky the whole app sits on, edge to edge with the content inset off it.
 *
 * @param footer drawn against the bottom edge rather than in the centred
 *   column, so a closing line cannot push the emblem off a short screen.
 */
@Composable
internal fun TransitionBackdrop(
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.00f to palette.skyTop,
                    0.46f to palette.skyMid,
                    1.00f to palette.skyBottom,
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
            if (footer != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    footer()
                }
            }
        }
    }
}

/** What the emblem's centre is reporting, if anything yet. */
internal enum class Verdict { NONE, CONNECTED, FAILED, ENDED }

/**
 * The 96dp emblem: the splash's spinner while something is still happening, and
 * a still ring carrying a verdict once it has stopped.
 *
 * The verdict shapes are drawn rather than taken from an icon set for the same
 * reason the rest of this design is — the app ships no glyph for either, and a
 * Material one next to the brand mark reads as somebody else's app.
 */
@Composable
internal fun StageEmblem(spinning: Boolean, verdict: Verdict = Verdict.NONE) {
    val palette = LocalPalette.current
    val color = if (verdict == Verdict.FAILED) palette.danger else palette.accent
    val spin by rememberInfiniteTransition(label = "stage").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "turn",
    )

    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Only the moving state turns. A ring still spinning under a
                // tick would say the app is working on something it has just
                // finished.
                .rotate(if (spinning) spin else 0f)
        ) {
            val stroke = 2.dp.toPx()
            drawCircle(
                color = color.copy(alpha = 0.18f),
                style = Stroke(width = stroke),
                radius = (size.minDimension - stroke) / 2f,
            )
            drawArc(
                color = color,
                startAngle = -90f,
                // A quarter while it turns, the whole ring once it has stopped,
                // so the still state does not read as a stalled spinner.
                sweepAngle = if (spinning) 90f else 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
            )
        }

        Box(
            Modifier
                .padding(22.dp)
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(palette.dialInner, palette.dialOuter))),
            contentAlignment = Alignment.Center,
        ) {
            when (verdict) {
                Verdict.CONNECTED -> Canvas(Modifier.size(26.dp)) {
                    val u = size.minDimension / 24f
                    drawLine(
                        color, Offset(5 * u, 12.5f * u), Offset(10 * u, 17.5f * u),
                        strokeWidth = 2.6f * u, cap = StrokeCap.Round,
                    )
                    drawLine(
                        color, Offset(10 * u, 17.5f * u), Offset(19 * u, 6.5f * u),
                        strokeWidth = 2.6f * u, cap = StrokeCap.Round,
                    )
                }

                Verdict.FAILED -> Canvas(Modifier.size(24.dp)) {
                    val u = size.minDimension / 24f
                    drawLine(
                        color, Offset(6 * u, 6 * u), Offset(18 * u, 18 * u),
                        strokeWidth = 2.6f * u, cap = StrokeCap.Round,
                    )
                    drawLine(
                        color, Offset(18 * u, 6 * u), Offset(6 * u, 18 * u),
                        strokeWidth = 2.6f * u, cap = StrokeCap.Round,
                    )
                }

                // NONE while it is working, and ENDED because a tunnel that is
                // down is the expected outcome of a disconnect, not a failure:
                // the mark, not a red cross.
                else -> BrandMark(
                    Modifier.size(26.dp),
                    strokeWidth = 4.6f,
                    showRoadDashes = false,
                )
            }
        }
    }
}

/** The headline, in the size both screens use. */
@Composable
internal fun StageHeadline(text: String) {
    Text(
        text,
        style = TextStyle(
            fontFamily = Brand.Vazirmatn,
            fontWeight = FontWeight.Black,
            fontSize = 21.sp,
            color = LocalPalette.current.text,
            textAlign = TextAlign.Center,
        ),
    )
}

/** The line under it. */
@Composable
internal fun StageSubtitle(text: String) {
    Text(
        text,
        style = TextStyle(
            fontFamily = Brand.Vazirmatn,
            fontSize = 13.sp,
            color = LocalPalette.current.text2,
            textAlign = TextAlign.Center,
        ),
    )
}

/**
 * Flag, name and protocol of the server the screen is talking about.
 *
 * The address is deliberately absent, for the reason `HomeUiState` gives for
 * holding it and never drawing it: host and port on a screen turn a screenshot
 * into a working config.
 */
@Composable
internal fun ServerChip(name: String, country: String, protocol: String) {
    if (name.isBlank()) return
    val palette = LocalPalette.current
    val flag = countryFlag(country)
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.surface)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (flag.isNotEmpty()) Text(flag, style = TextStyle(fontSize = 15.sp))
        Text(
            name,
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = palette.text,
            ),
            maxLines = 1,
        )
        if (protocol.isNotBlank()) Text(
            protocol.uppercase(),
            style = TextStyle(
                fontFamily = Brand.JetBrainsMono,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = palette.dim,
            ),
            maxLines = 1,
        )
    }
}

/** The splash's retry pill, which is the only button shape this design has. */
@Composable
internal fun PillButton(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .clip(CircleShape)
            .background(palette.accent.copy(alpha = 0.12f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 30.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = palette.accent,
            ),
        )
    }
}

/** A label-and-value line, as the summary's figures are written. */
@Composable
internal fun FigureRow(label: String, value: String, valueColor: Color? = null) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = Brand.Vazirmatn,
                fontSize = 13.sp,
                color = palette.dim,
            ),
        )
        Text(
            value,
            style = TextStyle(
                fontFamily = Brand.JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = valueColor ?: palette.text,
            ),
        )
    }
}

/**
 * Why the user is looking at an ad at all.
 *
 * Standard in this category and, more to the point, true: the ads are what pay
 * for a free tunnel. A line that says so turns an interruption into an
 * exchange the user is party to. Only drawn where an ad is actually part of
 * what is happening — a thank-you for ads that were never shown is worse than
 * silence.
 */
@Composable
internal fun AdSupportNote() {
    val palette = LocalPalette.current
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
        modifier = Modifier.widthIn(max = 320.dp),
    )
}

/**
 * The one thing the user can do wrong here.
 *
 * Leaving is not a neutral act while this screen is up: the flow runs on the
 * Activity's scope, so backing out cancels it mid-way and takes down whatever
 * tunnel it had opened. Every app in this category says some version of this,
 * for the same reason.
 */
@Composable
internal fun StayHereNote() {
    val palette = LocalPalette.current
    Text(
        "Please stay on this screen until it finishes",
        style = TextStyle(
            fontFamily = Brand.Vazirmatn,
            fontSize = 12.5.sp,
            color = palette.text2,
            textAlign = TextAlign.Center,
        ),
        maxLines = 2,
        modifier = Modifier.widthIn(max = 300.dp),
    )
}
