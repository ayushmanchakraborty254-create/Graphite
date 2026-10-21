package com.graphite.app.feature.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkStroke
import com.graphite.app.feature.workspace.model.InkToolType
import kotlin.math.*

enum class CanvasShapeType(val label: String) {
    LINE("Line"),
    ARROW("Arrow"),
    DOUBLE_ARROW("Double Arrow"),
    CURVED_ARROW("Curved Arrow"),
    RECTANGLE("Rectangle"),
    ROUNDED_RECT("Rounded Rect"),
    CIRCLE("Circle"),
    OVAL("Oval"),
    TRIANGLE("Triangle"),
    RIGHT_TRIANGLE("Right Triangle"),
    DIAMOND("Diamond"),
    PARALLELOGRAM("Parallelogram"),
    TRAPEZOID("Trapezoid"),
    PENTAGON("Pentagon"),
    HEXAGON("Hexagon"),
    OCTAGON("Octagon"),
    STAR("5-Pt Star"),
    STAR_6("6-Pt Star"),
    HEART("Heart"),
    CLOUD("Cloud"),
    SPEECH_BUBBLE("Speech Bubble"),
    THINKING_BUBBLE("Thinking Bubble"),
    CUBE("3D Cube"),
    CYLINDER("3D Cylinder"),
    COORDINATE_2D("2D Axes"),
    COORDINATE_3D("3D Axes")
}

object CanvasShapeGenerator {

    fun generateShapeStrokes(
        shape: CanvasShapeType,
        start: Offset,
        end: Offset,
        strokeWidth: Float,
        color: Color,
        pageIndex: Int
    ): List<InkStroke> {
        val minX = min(start.x, end.x)
        val maxX = max(start.x, end.x)
        val minY = min(start.y, end.y)
        val maxY = max(start.y, end.y)
        val w = (maxX - minX).coerceAtLeast(10f)
        val h = (maxY - minY).coerceAtLeast(10f)
        val time = System.currentTimeMillis()

        fun makeStroke(points: List<Offset>): InkStroke {
            return InkStroke(
                points = points.map { InkPoint(it.x, it.y, 1.0f, time) },
                baseWidth = strokeWidth,
                color = color,
                toolType = InkToolType.SHAPE,
                pageIndex = pageIndex
            )
        }

        return when (shape) {
            CanvasShapeType.LINE -> {
                listOf(makeStroke(listOf(start, end)))
            }
            CanvasShapeType.ARROW -> {
                val dx = end.x - start.x
                val dy = end.y - start.y
                val angle = atan2(dy, dx)
                val arrowHeadLen = (strokeWidth * 4f).coerceIn(20f, 44f)
                val barbAngle = Math.PI / 6.0

                val p1 = Offset(
                    (end.x - arrowHeadLen * cos(angle - barbAngle)).toFloat(),
                    (end.y - arrowHeadLen * sin(angle - barbAngle)).toFloat()
                )
                val p2 = Offset(
                    (end.x - arrowHeadLen * cos(angle + barbAngle)).toFloat(),
                    (end.y - arrowHeadLen * sin(angle + barbAngle)).toFloat()
                )
                listOf(
                    makeStroke(listOf(start, end)),
                    makeStroke(listOf(p1, end, p2))
                )
            }
            CanvasShapeType.DOUBLE_ARROW -> {
                val dx = end.x - start.x
                val dy = end.y - start.y
                val angle = atan2(dy, dx)
                val headLen = (strokeWidth * 4f).coerceIn(20f, 44f)
                val barb = Math.PI / 6.0

                val endP1 = Offset((end.x - headLen * cos(angle - barb)).toFloat(), (end.y - headLen * sin(angle - barb)).toFloat())
                val endP2 = Offset((end.x - headLen * cos(angle + barb)).toFloat(), (end.y - headLen * sin(angle + barb)).toFloat())

                val startP1 = Offset((start.x + headLen * cos(angle - barb)).toFloat(), (start.y + headLen * sin(angle - barb)).toFloat())
                val startP2 = Offset((start.x + headLen * cos(angle + barb)).toFloat(), (start.y + headLen * sin(angle + barb)).toFloat())

                listOf(
                    makeStroke(listOf(start, end)),
                    makeStroke(listOf(endP1, end, endP2)),
                    makeStroke(listOf(startP1, start, startP2))
                )
            }
            CanvasShapeType.CURVED_ARROW -> {
                val midX = (start.x + end.x) / 2f
                val midY = min(start.y, end.y) - (h * 0.4f)
                val curvePts = (0..20).map { i ->
                    val t = i / 20f
                    val x = (1 - t) * (1 - t) * start.x + 2 * (1 - t) * t * midX + t * t * end.x
                    val y = (1 - t) * (1 - t) * start.y + 2 * (1 - t) * t * midY + t * t * end.y
                    Offset(x, y)
                }
                val last = curvePts.last()
                val prev = curvePts[curvePts.size - 2]
                val angle = atan2(last.y - prev.y, last.x - prev.x)
                val headLen = (strokeWidth * 4f).coerceIn(20f, 40f)
                val barb = Math.PI / 6.0
                val p1 = Offset((last.x - headLen * cos(angle - barb)).toFloat(), (last.y - headLen * sin(angle - barb)).toFloat())
                val p2 = Offset((last.x - headLen * cos(angle + barb)).toFloat(), (last.y - headLen * sin(angle + barb)).toFloat())
                listOf(makeStroke(curvePts), makeStroke(listOf(p1, last, p2)))
            }
            CanvasShapeType.RECTANGLE -> {
                val pts = listOf(
                    Offset(minX, minY),
                    Offset(maxX, minY),
                    Offset(maxX, maxY),
                    Offset(minX, maxY),
                    Offset(minX, minY)
                )
                listOf(makeStroke(pts))
            }
            CanvasShapeType.ROUNDED_RECT -> {
                val r = (min(w, h) * 0.16f).coerceIn(12f, 32f)
                val pts = mutableListOf<Offset>()
                // Top edge
                pts.add(Offset(minX + r, minY))
                pts.add(Offset(maxX - r, minY))
                // Top-right corner
                for (a in -90..0 step 15) {
                    val rad = Math.toRadians(a.toDouble())
                    pts.add(Offset((maxX - r + r * cos(rad)).toFloat(), (minY + r + r * sin(rad)).toFloat()))
                }
                // Right edge
                pts.add(Offset(maxX, maxY - r))
                // Bottom-right corner
                for (a in 0..90 step 15) {
                    val rad = Math.toRadians(a.toDouble())
                    pts.add(Offset((maxX - r + r * cos(rad)).toFloat(), (maxY - r + r * sin(rad)).toFloat()))
                }
                // Bottom edge
                pts.add(Offset(minX + r, maxY))
                // Bottom-left corner
                for (a in 90..180 step 15) {
                    val rad = Math.toRadians(a.toDouble())
                    pts.add(Offset((minX + r + r * cos(rad)).toFloat(), (maxY - r + r * sin(rad)).toFloat()))
                }
                // Left edge
                pts.add(Offset(minX, minY + r))
                // Top-left corner
                for (a in 180..270 step 15) {
                    val rad = Math.toRadians(a.toDouble())
                    pts.add(Offset((minX + r + r * cos(rad)).toFloat(), (minY + r + r * sin(rad)).toFloat()))
                }
                pts.add(Offset(minX + r, minY))
                listOf(makeStroke(pts))
            }
            CanvasShapeType.CIRCLE -> {
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val r = min(w, h) / 2f
                val pts = (0..360 step 6).map { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    Offset((cx + r * cos(rad)).toFloat(), (cy + r * sin(rad)).toFloat())
                }
                listOf(makeStroke(pts))
            }
            CanvasShapeType.OVAL -> {
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val rx = w / 2f
                val ry = h / 2f
                val pts = (0..360 step 6).map { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    Offset((cx + rx * cos(rad)).toFloat(), (cy + ry * sin(rad)).toFloat())
                }
                listOf(makeStroke(pts))
            }
            CanvasShapeType.TRIANGLE -> {
                val pts = listOf(
                    Offset((minX + maxX) / 2f, minY),
                    Offset(maxX, maxY),
                    Offset(minX, maxY),
                    Offset((minX + maxX) / 2f, minY)
                )
                listOf(makeStroke(pts))
            }
            CanvasShapeType.RIGHT_TRIANGLE -> {
                val pts = listOf(
                    Offset(minX, minY),
                    Offset(minX, maxY),
                    Offset(maxX, maxY),
                    Offset(minX, minY)
                )
                listOf(makeStroke(pts))
            }
            CanvasShapeType.DIAMOND -> {
                val midX = (minX + maxX) / 2f
                val midY = (minY + maxY) / 2f
                val pts = listOf(
                    Offset(midX, minY),
                    Offset(maxX, midY),
                    Offset(midX, maxY),
                    Offset(minX, midY),
                    Offset(midX, minY)
                )
                listOf(makeStroke(pts))
            }
            CanvasShapeType.PARALLELOGRAM -> {
                val shift = w * 0.22f
                val pts = listOf(
                    Offset(minX + shift, minY),
                    Offset(maxX, minY),
                    Offset(maxX - shift, maxY),
                    Offset(minX, maxY),
                    Offset(minX + shift, minY)
                )
                listOf(makeStroke(pts))
            }
            CanvasShapeType.TRAPEZOID -> {
                val inset = w * 0.20f
                val pts = listOf(
                    Offset(minX + inset, minY),
                    Offset(maxX - inset, minY),
                    Offset(maxX, maxY),
                    Offset(minX, maxY),
                    Offset(minX + inset, minY)
                )
                listOf(makeStroke(pts))
            }
            CanvasShapeType.PENTAGON -> {
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val rx = w / 2f
                val ry = h / 2f
                val pts = (0..5).map { i ->
                    val angle = Math.toRadians(-90.0 + i * 72.0)
                    Offset((cx + rx * cos(angle)).toFloat(), (cy + ry * sin(angle)).toFloat())
                }
                listOf(makeStroke(pts))
            }
            CanvasShapeType.HEXAGON -> {
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val rx = w / 2f
                val ry = h / 2f
                val pts = (0..6).map { i ->
                    val angle = Math.toRadians(30.0 + i * 60.0)
                    Offset((cx + rx * cos(angle)).toFloat(), (cy + ry * sin(angle)).toFloat())
                }
                listOf(makeStroke(pts))
            }
            CanvasShapeType.OCTAGON -> {
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val rx = w / 2f
                val ry = h / 2f
                val pts = (0..8).map { i ->
                    val angle = Math.toRadians(22.5 + i * 45.0)
                    Offset((cx + rx * cos(angle)).toFloat(), (cy + ry * sin(angle)).toFloat())
                }
                listOf(makeStroke(pts))
            }
            CanvasShapeType.STAR -> {
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val outerR = min(w, h) / 2f
                val innerR = outerR * 0.40f
                val pts = mutableListOf<Offset>()
                for (i in 0..10) {
                    val angle = Math.toRadians(-90.0 + i * 36.0)
                    val r = if (i % 2 == 0) outerR else innerR
                    pts.add(Offset((cx + r * cos(angle)).toFloat(), (cy + r * sin(angle)).toFloat()))
                }
                listOf(makeStroke(pts))
            }
            CanvasShapeType.STAR_6 -> {
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val outerR = min(w, h) / 2f
                val innerR = outerR * 0.52f
                val pts = mutableListOf<Offset>()
                for (i in 0..12) {
                    val angle = Math.toRadians(-90.0 + i * 30.0)
                    val r = if (i % 2 == 0) outerR else innerR
                    pts.add(Offset((cx + r * cos(angle)).toFloat(), (cy + r * sin(angle)).toFloat()))
                }
                listOf(makeStroke(pts))
            }
            CanvasShapeType.HEART -> {
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val scale = min(w, h) / 36f
                val pts = (0..360 step 6).map { deg ->
                    val t = Math.toRadians(deg.toDouble())
                    val x = 16 * sin(t).pow(3.0)
                    val y = -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))
                    Offset((cx + x * scale).toFloat(), (cy + y * scale).toFloat())
                }
                listOf(makeStroke(pts))
            }
            CanvasShapeType.CLOUD -> {
                val cy = minY + h * 0.55f
                val base = minY + h * 0.78f
                val pts = mutableListOf<Offset>()
                // Flat bottom
                pts.add(Offset(minX + w * 0.15f, base))
                pts.add(Offset(maxX - w * 0.15f, base))
                // Right puff
                for (a in 90 downTo -45 step 15) {
                    val rad = Math.toRadians(a.toDouble())
                    pts.add(Offset((maxX - w * 0.20f + (w * 0.18f) * cos(rad)).toFloat(), (base - h * 0.22f + (h * 0.22f) * sin(rad)).toFloat()))
                }
                // Top puff
                for (a in 0 downTo -180 step 15) {
                    val rad = Math.toRadians(a.toDouble())
                    pts.add(Offset(((minX + maxX) / 2f + (w * 0.25f) * cos(rad)).toFloat(), (minY + h * 0.32f + (h * 0.30f) * sin(rad)).toFloat()))
                }
                // Left puff
                for (a in -135 downTo -270 step 15) {
                    val rad = Math.toRadians(a.toDouble())
                    pts.add(Offset((minX + w * 0.20f + (w * 0.18f) * cos(rad)).toFloat(), (base - h * 0.22f + (h * 0.22f) * sin(rad)).toFloat()))
                }
                pts.add(Offset(minX + w * 0.15f, base))
                listOf(makeStroke(pts))
            }
            CanvasShapeType.SPEECH_BUBBLE -> {
                val r = (min(w, h) * 0.15f).coerceIn(8f, 24f)
                val bodyBottom = maxY - (h * 0.24f)
                val pts = mutableListOf<Offset>()
                pts.add(Offset(minX + r, minY))
                pts.add(Offset(maxX - r, minY))
                pts.add(Offset(maxX, minY + r))
                pts.add(Offset(maxX, bodyBottom - r))
                pts.add(Offset(maxX - r, bodyBottom))
                // Tail
                pts.add(Offset(minX + w * 0.45f, bodyBottom))
                pts.add(Offset(minX + w * 0.18f, maxY)) // pointer tip
                pts.add(Offset(minX + w * 0.28f, bodyBottom))
                pts.add(Offset(minX + r, bodyBottom))
                pts.add(Offset(minX, bodyBottom - r))
                pts.add(Offset(minX, minY + r))
                pts.add(Offset(minX + r, minY))
                listOf(makeStroke(pts))
            }
            CanvasShapeType.THINKING_BUBBLE -> {
                val bodyBottom = maxY - (h * 0.30f)
                val cx = (minX + maxX) / 2f
                val cy = (minY + bodyBottom) / 2f
                val rx = w / 2f
                val ry = (bodyBottom - minY) / 2f
                val cloudPts = (0..360 step 8).map { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    Offset((cx + rx * cos(rad)).toFloat(), (cy + ry * sin(rad)).toFloat())
                }
                // Small thought bubbles below
                val dot1 = Offset(minX + w * 0.28f, maxY - h * 0.16f)
                val dot2 = Offset(minX + w * 0.18f, maxY - h * 0.05f)
                val smallR1 = (min(w, h) * 0.05f)
                val smallR2 = (min(w, h) * 0.03f)
                val ptsDot1 = (0..360 step 30).map { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    Offset((dot1.x + smallR1 * cos(rad)).toFloat(), (dot1.y + smallR1 * sin(rad)).toFloat())
                }
                val ptsDot2 = (0..360 step 30).map { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    Offset((dot2.x + smallR2 * cos(rad)).toFloat(), (dot2.y + smallR2 * sin(rad)).toFloat())
                }
                listOf(makeStroke(cloudPts), makeStroke(ptsDot1), makeStroke(ptsDot2))
            }
            CanvasShapeType.CUBE -> {
                val dx = w * 0.28f
                val dy = h * 0.28f
                // Front rect
                val front = listOf(
                    Offset(minX, minY + dy),
                    Offset(maxX - dx, minY + dy),
                    Offset(maxX - dx, maxY),
                    Offset(minX, maxY),
                    Offset(minX, minY + dy)
                )
                // Back rect
                val back = listOf(
                    Offset(minX + dx, minY),
                    Offset(maxX, minY),
                    Offset(maxX, maxY - dy),
                    Offset(minX + dx, maxY - dy),
                    Offset(minX + dx, minY)
                )
                // Connecting edges
                val c1 = listOf(Offset(minX, minY + dy), Offset(minX + dx, minY))
                val c2 = listOf(Offset(maxX - dx, minY + dy), Offset(maxX, minY))
                val c3 = listOf(Offset(maxX - dx, maxY), Offset(maxX, maxY - dy))
                val c4 = listOf(Offset(minX, maxY), Offset(minX + dx, maxY - dy))
                listOf(makeStroke(front), makeStroke(back), makeStroke(c1), makeStroke(c2), makeStroke(c3), makeStroke(c4))
            }
            CanvasShapeType.CYLINDER -> {
                val ellipseH = h * 0.22f
                val cx = (minX + maxX) / 2f
                val rx = w / 2f
                val ry = ellipseH / 2f
                // Top ellipse
                val topEllipse = (0..360 step 10).map { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    Offset((cx + rx * cos(rad)).toFloat(), (minY + ry + ry * sin(rad)).toFloat())
                }
                // Bottom arc (front half)
                val botArc = (0..180 step 10).map { deg ->
                    val rad = Math.toRadians(deg.toDouble())
                    Offset((cx + rx * cos(rad)).toFloat(), (maxY - ry + ry * sin(rad)).toFloat())
                }
                val side1 = listOf(Offset(minX, minY + ry), Offset(minX, maxY - ry))
                val side2 = listOf(Offset(maxX, minY + ry), Offset(maxX, maxY - ry))
                listOf(makeStroke(topEllipse), makeStroke(botArc), makeStroke(side1), makeStroke(side2))
            }
            CanvasShapeType.COORDINATE_2D -> {
                val origin = Offset(minX + w * 0.15f, maxY - h * 0.15f)
                val topY = Offset(origin.x, minY + 10f)
                val rightX = Offset(maxX - 10f, origin.y)
                // Y-axis with arrow
                val yAxis = listOf(origin, topY)
                val yArrow = listOf(Offset(topY.x - 12f, topY.y + 16f), topY, Offset(topY.x + 12f, topY.y + 16f))
                // X-axis with arrow
                val xAxis = listOf(origin, rightX)
                val xArrow = listOf(Offset(rightX.x - 16f, rightX.y - 12f), rightX, Offset(rightX.x - 16f, rightX.y + 12f))
                listOf(makeStroke(yAxis), makeStroke(yArrow), makeStroke(xAxis), makeStroke(xArrow))
            }
            CanvasShapeType.COORDINATE_3D -> {
                val origin = Offset(minX + w * 0.35f, maxY - h * 0.35f)
                val topZ = Offset(origin.x, minY + 10f)
                val rightX = Offset(maxX - 10f, origin.y)
                val diagY = Offset(minX + 10f, maxY - 10f)
                val zAxis = listOf(origin, topZ)
                val xAxis = listOf(origin, rightX)
                val yAxis = listOf(origin, diagY)
                listOf(makeStroke(zAxis), makeStroke(xAxis), makeStroke(yAxis))
            }
        }
    }
}

@Composable
fun ShapeSelectorDialog(
    selectedShape: CanvasShapeType,
    strokeWidth: Float,
    selectedColor: Color,
    onSelectShape: (CanvasShapeType) -> Unit,
    onSelectStrokeWidth: (Float) -> Unit,
    onSelectColor: (Color) -> Unit,
    onInsertShapeToPage: (CanvasShapeType, Float, Color) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
            border = BorderStroke(1.dp, Color(0xFF33333D)),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with title and close icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Category,
                            contentDescription = null,
                            tint = Color(0xFF00BCD4),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Shapes (${CanvasShapeType.values().size})",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Grid of shapes with fixed height and scrolling
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(200.dp)
                ) {
                    items(CanvasShapeType.values()) { shape ->
                        val isChosen = selectedShape == shape
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isChosen) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color(0xFF282830))
                                .border(
                                    width = if (isChosen) 2.dp else 1.dp,
                                    color = if (isChosen) Color(0xFF00BCD4) else Color(0xFF3E3E48),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    onSelectShape(shape)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            ShapeMiniIcon(shape = shape, tint = if (isChosen) Color(0xFF00BCD4) else Color.White)
                        }
                    }
                }

                // Stroke Width presets
                Column {
                    Text("STROKE THICKNESS", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(2f to "Fine", 4f to "Normal", 8f to "Thick", 14f to "Bold").forEach { (width, label) ->
                            val isSelected = strokeWidth == width
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF00BCD4) else Color(0xFF282830))
                                    .clickable { onSelectStrokeWidth(width) }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // Quick Color row
                Column {
                    Text("COLOR", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    val colors = listOf(
                        Color(0xFF000000), Color(0xFFFFFFFF), Color(0xFF00BCD4), Color(0xFFE91E63),
                        Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFF2196F3), Color(0xFF9C27B0)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        colors.forEach { col ->
                            val isColSelected = selectedColor == col
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(col)
                                    .border(
                                        width = if (isColSelected) 2.5.dp else 1.dp,
                                        color = if (isColSelected) Color(0xFF00BCD4) else Color.Gray.copy(alpha = 0.5f),
                                        shape = CircleShape
                                    )
                                    .clickable { onSelectColor(col) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isColSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (col == Color.White) Color.Black else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom button: Insert to page
                Button(
                    onClick = {
                        onInsertShapeToPage(selectedShape, strokeWidth, selectedColor)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Insert ${selectedShape.label} to Page",
                        color = Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ShapeMiniIcon(shape: CanvasShapeType, tint: Color) {
    when (shape) {
        CanvasShapeType.LINE -> Text("/", color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        CanvasShapeType.ARROW -> Text("↗", color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        CanvasShapeType.DOUBLE_ARROW -> Text("⤢", color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        CanvasShapeType.CURVED_ARROW -> Text("⤳", color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        CanvasShapeType.RECTANGLE -> Text("▭", color = tint, fontSize = 20.sp)
        CanvasShapeType.ROUNDED_RECT -> Text("▢", color = tint, fontSize = 20.sp)
        CanvasShapeType.CIRCLE -> Text("○", color = tint, fontSize = 20.sp)
        CanvasShapeType.OVAL -> Text("⬭", color = tint, fontSize = 20.sp)
        CanvasShapeType.TRIANGLE -> Text("△", color = tint, fontSize = 18.sp)
        CanvasShapeType.RIGHT_TRIANGLE -> Text("⊿", color = tint, fontSize = 18.sp)
        CanvasShapeType.DIAMOND -> Text("◇", color = tint, fontSize = 18.sp)
        CanvasShapeType.PARALLELOGRAM -> Text("▱", color = tint, fontSize = 18.sp)
        CanvasShapeType.TRAPEZOID -> Text("⏢", color = tint, fontSize = 18.sp)
        CanvasShapeType.PENTAGON -> Text("⬠", color = tint, fontSize = 18.sp)
        CanvasShapeType.HEXAGON -> Text("⬡", color = tint, fontSize = 18.sp)
        CanvasShapeType.OCTAGON -> Text("🛑", color = tint, fontSize = 14.sp)
        CanvasShapeType.STAR -> Text("★", color = tint, fontSize = 18.sp)
        CanvasShapeType.STAR_6 -> Text("✡", color = tint, fontSize = 18.sp)
        CanvasShapeType.HEART -> Text("♥", color = tint, fontSize = 18.sp)
        CanvasShapeType.CLOUD -> Text("☁", color = tint, fontSize = 18.sp)
        CanvasShapeType.SPEECH_BUBBLE -> Text("🗨", color = tint, fontSize = 16.sp)
        CanvasShapeType.THINKING_BUBBLE -> Text("💭", color = tint, fontSize = 16.sp)
        CanvasShapeType.CUBE -> Text("🧊", color = tint, fontSize = 14.sp)
        CanvasShapeType.CYLINDER -> Text("🛢", color = tint, fontSize = 14.sp)
        CanvasShapeType.COORDINATE_2D -> Text("📈", color = tint, fontSize = 14.sp)
        CanvasShapeType.COORDINATE_3D -> Text("📐", color = tint, fontSize = 14.sp)
    }
}
