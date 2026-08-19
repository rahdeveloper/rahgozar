package com.rahgozar.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rahgozar.app.ui.brand.Brand
import com.rahgozar.app.ui.brand.LocalPalette

/**
 * The bar the design puts above every screen: menu, screen tag, theme, language.
 *
 * The tag ("RAHGOZAR", "SERVERS") is in JetBrains Mono with wide tracking — it
 * is a label, not prose, and the design treats it as one. The FA/EN switch that
 * used to sit beside the theme button went with the Persian copy.
 */
@Composable
fun AppTopBar(
    tag: String,
    title: String,
    onMenu: () -> Unit,
    onToggleTheme: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current

    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack ?: onMenu) {
            if (onBack != null) ChevronGlyph(palette.dim) else MenuGlyph(palette.dim)
        }

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
        ) {
            Text(
                title,
                style = TextStyle(
                    fontFamily = Brand.Vazirmatn,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    color = palette.text,
                ),
                maxLines = 1,
            )
            Text(
                tag,
                style = TextStyle(
                    fontFamily = Brand.JetBrainsMono,
                    fontSize = 8.5.sp,
                    letterSpacing = 1.6.sp,
                    color = palette.dim,
                ),
                maxLines = 1,
            )
        }

        IconButton(onClick = onToggleTheme) {
            if (palette.isDark) SunGlyph(palette.dim) else MoonGlyph(palette.dim)
        }
    }
}

@Composable
private fun IconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

// ----------------------------------------------------------------- glyphs --

@Composable
private fun MenuGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val u = size.minDimension / 24f
        val stroke = 2f * u
        listOf(6f, 12f, 18f).forEach { y ->
            drawLine(color, Offset(4 * u, y * u), Offset(20 * u, y * u), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun ChevronGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val u = size.minDimension / 24f
        val stroke = 2f * u
        val x1 = 15 * u
        val x2 = 9 * u
        drawLine(color, Offset(x1, 6 * u), Offset(x2, 12 * u), stroke, StrokeCap.Round)
        drawLine(color, Offset(x2, 12 * u), Offset(x1, 18 * u), stroke, StrokeCap.Round)
    }
}

@Composable
private fun SunGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val u = size.minDimension / 24f
        val stroke = 2f * u
        drawCircle(color, radius = 4 * u, center = Offset(12 * u, 12 * u), style = Stroke(stroke))
        val rays = listOf(
            12f to 1.5f to (12f to 4f), 12f to 20f to (12f to 22.5f),
            1.5f to 12f to (4f to 12f), 20f to 12f to (22.5f to 12f),
            4.5f to 4.5f to (6.3f to 6.3f), 17.7f to 17.7f to (19.5f to 19.5f),
            4.5f to 19.5f to (6.3f to 17.7f), 17.7f to 6.3f to (19.5f to 4.5f),
        )
        rays.forEach { (from, to) ->
            drawLine(
                color,
                Offset(from.first * u, from.second * u),
                Offset(to.first * u, to.second * u),
                stroke,
                StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun MoonGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val u = size.minDimension / 24f
        // M20 14.5A8 8 0 1 1 9.5 4 6.5 6.5 0 0 0 20 14.5Z
        val path = Path().apply {
            moveTo(20 * u, 14.5f * u)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    Offset(1.5f * u, 4f * u), Size(16 * u, 16 * u)
                ),
                startAngleDegrees = 30f,
                sweepAngleDegrees = 300f,
                forceMoveTo = false,
            )
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    Offset(7f * u, 1.5f * u), Size(13 * u, 13 * u)
                ),
                startAngleDegrees = 190f,
                sweepAngleDegrees = -160f,
                forceMoveTo = false,
            )
            close()
        }
        drawPath(path, color, style = Stroke(2f * u))
    }
}
