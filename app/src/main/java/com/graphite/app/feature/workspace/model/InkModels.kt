package com.graphite.app.feature.workspace.model

import androidx.compose.ui.graphics.Color
import java.util.UUID

enum class InkToolType {
    PEN,
    ERASER,
    SHAPE
}

enum class PaperStyle {
    BLANK,
    RULED,
    DOTTED,
    GRID,
    HEXAGONAL_GRID,
    CORNELL_A,
    CORNELL_B,
    LEGAL,
    ENGINEERING_GRID,
    MUSIC_SCORE,
    GUITAR_TABLATURE,
    GUITAR_SCORE
}


data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 0.5f,
    val timestamp: Long = System.currentTimeMillis()
)

data class InkColorOption(
    val name: String,
    val color: Color
)

object WorkspaceColors {
    val White = Color(0xFFF5F5F5)
    val CoralOrange = Color(0xFFFF7A30)
    val Cyan = Color(0xFF00E5FF)
    val Navy = Color(0xFF3D5AFE)
    val Red = Color(0xFFFF5252)
    val DarkGraphite = Color(0xFF212121)

    val All = listOf(
        InkColorOption("Coral Orange", CoralOrange),
        InkColorOption("White", White),
        InkColorOption("Cyan", Cyan),
        InkColorOption("Navy", Navy),
        InkColorOption("Red", Red),
        InkColorOption("Graphite", DarkGraphite)
    )
}

data class InkStroke(
    val id: String = UUID.randomUUID().toString(),
    val points: List<InkPoint>,
    val baseWidth: Float = 4f,
    val color: Color = WorkspaceColors.CoralOrange,
    val toolType: InkToolType = InkToolType.PEN,
    val pageIndex: Int = 0
) {
    val boundingBox: StrokeBoundingBox by lazy {
        if (points.isEmpty()) {
            StrokeBoundingBox(0f, 0f, 0f, 0f)
        } else {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE
            for (p in points) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            val pad = baseWidth * 2f
            StrokeBoundingBox(minX - pad, minY - pad, maxX + pad, maxY + pad)
        }
    }

    fun intersectsPoint(px: Float, py: Float, radius: Float): Boolean {
        val strokeHalfWidth = (baseWidth * 0.5f).coerceAtLeast(1f)
        val effectiveRadius = radius + strokeHalfWidth
        if (!boundingBox.contains(px, py, effectiveRadius)) return false

        val effRadiusSq = effectiveRadius * effectiveRadius
        if (points.size <= 1) {
            if (points.isEmpty()) return false
            val p = points[0]
            val dx = px - p.x
            val dy = py - p.y
            return (dx * dx + dy * dy) <= effRadiusSq
        }

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            if (distanceSqToSegment(px, py, p1.x, p1.y, p2.x, p2.y) <= effRadiusSq) {
                return true
            }
        }
        return false
    }

    private fun distanceSqToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) {
            val dpx = px - x1
            val dpy = py - y1
            return dpx * dpx + dpy * dpy
        }
        val t = ((px - x1) * dx + (py - y1) * dy) / lenSq
        val clampedT = t.coerceIn(0f, 1f)
        val projX = x1 + clampedT * dx
        val projY = y1 + clampedT * dy
        val dpx = px - projX
        val dpy = py - projY
        return dpx * dpx + dpy * dpy
    }
}

data class StrokeBoundingBox(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float
) {
    fun contains(x: Float, y: Float, pad: Float = 0f): Boolean {
        return x in (minX - pad)..(maxX + pad) && y in (minY - pad)..(maxY + pad)
    }
}
