package com.graphite.app.feature.workspace.geometry

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkToolType
import kotlin.math.hypot
import kotlin.math.pow

data class CubicBezierSegment(
    val startX: Float,
    val startY: Float,
    val cp1X: Float,
    val cp1Y: Float,
    val cp2X: Float,
    val cp2Y: Float,
    val endX: Float,
    val endY: Float
)

object BezierSplineInterpolator {

    /**
     * Computes the Catmull-Rom cubic Bezier segments for a sequence of points.
     */
    fun computeSplineSegments(points: List<InkPoint>): List<CubicBezierSegment> {
        if (points.size < 2) return emptyList()

        if (points.size == 2) {
            val p0 = points[0]
            val p1 = points[1]
            return listOf(
                CubicBezierSegment(
                    startX = p0.x,
                    startY = p0.y,
                    cp1X = p0.x + (p1.x - p0.x) / 3f,
                    cp1Y = p0.y + (p1.y - p0.y) / 3f,
                    cp2X = p0.x + 2f * (p1.x - p0.x) / 3f,
                    cp2Y = p0.y + 2f * (p1.y - p0.y) / 3f,
                    endX = p1.x,
                    endY = p1.y
                )
            )
        }

        val segments = mutableListOf<CubicBezierSegment>()
        val n = points.size
        for (i in 0 until n - 1) {
            val p0 = if (i > 0) points[i - 1] else points[i]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = if (i + 2 < n) points[i + 2] else points[i + 1]

            // Catmull-Rom to Cubic Bezier control points conversion
            val cp1X = p1.x + (p2.x - p0.x) / 6f
            val cp1Y = p1.y + (p2.y - p0.y) / 6f

            val cp2X = p2.x - (p3.x - p1.x) / 6f
            val cp2Y = p2.y - (p3.y - p1.y) / 6f

            segments.add(
                CubicBezierSegment(
                    startX = p1.x,
                    startY = p1.y,
                    cp1X = cp1X,
                    cp1Y = cp1Y,
                    cp2X = cp2X,
                    cp2Y = cp2Y,
                    endX = p2.x,
                    endY = p2.y
                )
            )
        }
        return segments
    }

    /**
     * Converts a sequence of discrete ink points into a smooth Catmull-Rom cubic Bezier path.
     */
    fun createSplinePath(points: List<InkPoint>): Path {
        val path = Path()
        if (points.isEmpty()) return path

        if (points.size == 1) {
            val p = points[0]
            path.addOval(
                androidx.compose.ui.geometry.Rect(
                    p.x - 1f, p.y - 1f, p.x + 1f, p.y + 1f
                )
            )
            return path
        }

        val segments = computeSplineSegments(points)
        if (segments.isEmpty()) return path

        path.moveTo(segments[0].startX, segments[0].startY)
        for (segment in segments) {
            path.cubicTo(
                x1 = segment.cp1X,
                y1 = segment.cp1Y,
                x2 = segment.cp2X,
                y2 = segment.cp2Y,
                x3 = segment.endX,
                y3 = segment.endY
            )
        }

        return path
    }

    /**
     * Calculates the effective stroke width modulated by normalized stylus pressure.
     */
    fun calculateEffectiveWidth(baseWidth: Float, pressure: Float): Float {
        // Taper stroke width with pressure: 40% base width at minimum pressure, up to 130% at max pressure
        val clampedPressure = pressure.coerceIn(0.05f, 1.0f)
        return baseWidth * (0.4f + 0.9f * clampedPressure)
    }

    /**
     * Calculates dynamic width at each point of a stroke, incorporating:
     * 1. Stylus pressure response with natural power curve
     * 2. Velocity-based speed tapering (fast strokes naturally thin out)
     * 3. Tip entry & exit tapering over the first and last few points
     * 4. 3-point moving average smoothing to prevent sudden width jumps
     */
    fun computeDynamicPointWidths(
        points: List<InkPoint>,
        baseWidth: Float,
        minScale: Float = 0.35f,
        maxScale: Float = 1.65f
    ): List<Float> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) {
            val p = points[0].pressure.coerceIn(0.05f, 1.0f)
            val pMult = 0.40f + 1.05f * p.toDouble().pow(0.75).toFloat()
            return listOf((baseWidth * pMult).coerceIn(baseWidth * minScale, baseWidth * maxScale))
        }

        val n = points.size
        val rawWidths = FloatArray(n)

        for (i in 0 until n) {
            val pt = points[i]
            // 1. Stylus pressure factor
            val p = pt.pressure.coerceIn(0.05f, 1.0f)
            val pressureFactor = 0.40f + 1.10f * p.toDouble().pow(0.75).toFloat()

            // 2. Velocity factor (speed tapering)
            var velocityFactor = 1.0f
            if (i > 0) {
                val prev = points[i - 1]
                val dt = (pt.timestamp - prev.timestamp).coerceIn(1L, 100L)
                val dist = kotlin.math.hypot(pt.x - prev.x, pt.y - prev.y)
                val speed = dist / dt // pixels / ms
                velocityFactor = (1.18f - speed * 0.14f).coerceIn(0.60f, 1.30f)
            } else if (n > 1) {
                val next = points[1]
                val dt = (next.timestamp - pt.timestamp).coerceIn(1L, 100L)
                val dist = kotlin.math.hypot(next.x - pt.x, next.y - pt.y)
                val speed = dist / dt
                velocityFactor = (1.18f - speed * 0.14f).coerceIn(0.60f, 1.30f)
            }

            // 3. Tip entry & exit tapering
            var taperFactor = 1.0f
            if (n >= 4) {
                if (i == 0) taperFactor = 0.40f
                else if (i == 1) taperFactor = 0.70f
                else if (i == n - 1) taperFactor = 0.35f
                else if (i == n - 2) taperFactor = 0.65f
            }

            val w = baseWidth * pressureFactor * velocityFactor * taperFactor
            rawWidths[i] = w.coerceIn(baseWidth * minScale, baseWidth * maxScale)
        }

        // 4. Smooth widths using 3-point moving average
        val smoothed = ArrayList<Float>(n)
        for (i in 0 until n) {
            val prev = if (i > 0) rawWidths[i - 1] else rawWidths[i]
            val curr = rawWidths[i]
            val next = if (i < n - 1) rawWidths[i + 1] else rawWidths[i]
            smoothed.add((prev + curr * 2f + next) / 4f)
        }
        return smoothed
    }

    /**
     * Renders a stroke with rich dynamic size variations and seamless Catmull-Rom spline segments.
     */
    fun DrawScope.drawDynamicStroke(
        points: List<InkPoint>,
        baseWidth: Float,
        color: Color,
        toolType: InkToolType = InkToolType.PEN,
        alpha: Float = 1.0f
    ) {
        if (points.isEmpty()) return
        val drawColor = if (alpha < 1.0f) color.copy(alpha = color.alpha * alpha) else color

        if (points.size == 1) {
            val p = points[0]
            val w = calculateEffectiveWidth(baseWidth, p.pressure)
            drawCircle(
                color = drawColor,
                radius = w / 2f,
                center = Offset(p.x, p.y)
            )
            return
        }

        if (toolType == InkToolType.SHAPE) {
            // Precise straight or geometric path
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    lineTo(points[i].x, points[i].y)
                }
            }
            drawPath(
                path = path,
                color = drawColor,
                style = Stroke(
                    width = baseWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            return
        }

        if (toolType == InkToolType.ERASER) {
            return
        }

        val splinePath = createSplinePath(points)
        val avgPressure = points.map { it.pressure }.average().toFloat().coerceIn(0.1f, 1f)
        val effectiveWidth = calculateEffectiveWidth(baseWidth, avgPressure)
        drawPath(
            path = splinePath,
            color = drawColor,
            style = Stroke(
                width = effectiveWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
