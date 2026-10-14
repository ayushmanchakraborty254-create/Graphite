package com.graphite.app.feature.workspace.geometry

import androidx.compose.ui.graphics.Path
import com.graphite.app.feature.workspace.model.InkPoint

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
}
