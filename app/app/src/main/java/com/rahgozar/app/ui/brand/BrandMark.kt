package com.rahgozar.app.ui.brand

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The Rahgozar mark: a road narrowing toward the horizon as it passes through a
 * tunnel, with the traveller as the dot on it.
 *
 * Drawn rather than shipped as a vector asset because it appears at 24, 34 and
 * larger, and the stroke weight in the design is specified per size — a single
 * scaled drawable would either look thin at 24 or heavy at 34.
 *
 * Geometry is the design's 100×100 viewBox, so the path values below can be
 * compared with the source directly.
 */
@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    strokeWidth: Float = 3.6f,
    /** The design drops the road's dashes at small sizes, keeping only the dot. */
    showRoadDashes: Boolean = true,
) {
    val fill = LocalPalette.current.markFill
    Canvas(modifier) {
        val unit = size.minDimension / 100f
        val gradient = Brush.verticalGradient(
            colors = listOf(Brand.AccentBright, Brand.AccentDeep),
            startY = 0f,
            endY = size.minDimension,
        )
        drawMark(unit, gradient, fill, strokeWidth * unit, showRoadDashes)
    }
}

private fun DrawScope.drawMark(
    unit: Float,
    gradient: Brush,
    fill: Color,
    stroke: Float,
    showRoadDashes: Boolean,
) {
    fun x(v: Float) = v * unit
    fun y(v: Float) = v * unit

    // The road: wide at the viewer, narrowing to the tunnel mouth.
    // M32 90 L45.5 22 Q50 18 54.5 22 L68 90 Z
    val road = Path().apply {
        moveTo(x(32f), y(90f))
        lineTo(x(45.5f), y(22f))
        quadraticTo(x(50f), y(18f), x(54.5f), y(22f))
        lineTo(x(68f), y(90f))
        close()
    }
    drawPath(road, fill)
    drawPath(
        road,
        gradient,
        style = Stroke(width = stroke, join = StrokeJoin.Round),
    )

    if (showRoadDashes) {
        // Three centre-line segments, shortening with distance. Drawn as
        // separate paths rather than one dashed line because the design spaces
        // them by perspective, not evenly.
        val segments = listOf(
            Triple(49.3f to 80f, 49f to 70f, 3f),
            Triple(49.7f to 61.5f, 49.55f to 54.5f, 3f),
            Triple(50.05f to 46f, 50.2f to 40f, 3f),
        )
        for ((from, to, w) in segments) {
            drawLine(
                brush = gradient,
                start = Offset(x(from.first), y(from.second)),
                end = Offset(x(to.first), y(to.second)),
                strokeWidth = w * unit,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.cornerPathEffect(0f),
            )
        }
    }

    // The traveller.
    drawCircle(
        brush = gradient,
        radius = (if (showRoadDashes) 7f else 8f) * unit,
        center = Offset(x(49.9f), y(45.5f)),
    )
}
