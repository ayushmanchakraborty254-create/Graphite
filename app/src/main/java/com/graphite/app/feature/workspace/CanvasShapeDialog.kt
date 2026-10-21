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
import androidx.compose.ui.graphics.vector.ImageVector
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
    RECTANGLE("Rectangle"),
    ROUNDED_RECT("Rounded Rect"),
    CIRCLE("Circle"),
    TRIANGLE("Triangle"),
    STAR("Star"),
    SPEECH_BUBBLE("Speech Bubble"),
    HEXAGON("Hexagon")
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
                toolType = InkToolType.PEN,
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
                val arrowHeadLen = (strokeWidth * 4f).coerceIn(24f, 48f)
                val barbAngle = Math.PI / 6.0 // 30 degrees

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
                val headLen = (strokeWidth * 4f).coerceIn(24f, 48f)
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
                val r = (min(w, h) * 0.2f).coerceAtMost(24f)
                val pts = mutableListOf<Offset>()
                // Top line
                pts.add(Offset(minX + r, minY))
                pts.add(Offset(maxX - r, minY))
                // Top-right corner
                for (a in -90..0 step 15) {
                    val rad = Math.toRadians(a.toDouble())
                    pts.add(Offset((maxX - r + r * cos(rad)).toFloat(), (minY + r + r * sin(rad)).toFloat()))
                }
                // Right line
                pts.add(Offset(maxX, maxY - r))
                // Bottom-right corner
                for (a in 0..90 step 15) {
                    val rad = Math.toRadians(a.toDouble())
                    pts.add(Offset((maxX - r + r * cos(rad)).toFloat(), (maxY - r + r * sin(rad)).toFloat()))
                }
                // Bottom line
                pts.add(Offset(minX + r, maxY))
                // Bottom-left corner
                for (a in 90..180 step 15) {
                    val rad = Math.toRadians(a.toDouble())
                    pts.add(Offset((minX + r + r * cos(rad)).toFloat(), (maxY - r + r * sin(rad)).toFloat()))
                }
                // Left line
                pts.add(Offset(minX, minY + r))
                // Top-left corner
                for (a in 180..270 step 15) {
                    val rad = Math.toRadians(a.toDouble())
                    pts.add(Offset((minX + r + r * cos(rad)).toFloat(), (minY + r + r * sin(rad)).toFloat()))
                }
                pts.add(pts.first())
                listOf(makeStroke(pts))
            }
            CanvasShapeType.CIRCLE -> {
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val rx = w / 2f
                val ry = h / 2f
                val pts = (0..360 step 10).map { deg ->
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
            CanvasShapeType.STAR -> {
                val cx = (minX + maxX) / 2f
                val cy = (minY + maxY) / 2f
                val outerR = min(w, h) / 2f
                val innerR = outerR * 0.42f
                val pts = mutableListOf<Offset>()
                for (i in 0..10) {
                    val angle = Math.toRadians(-90.0 + i * 36.0)
                    val r = if (i % 2 == 0) outerR else innerR
                    pts.add(Offset((cx + r * cos(angle)).toFloat(), (cy + r * sin(angle)).toFloat()))
                }
                listOf(makeStroke(pts))
            }
            CanvasShapeType.SPEECH_BUBBLE -> {
                val r = (min(w, h) * 0.15f).coerceAtMost(20f)
                val bodyBottom = maxY - (h * 0.22f)
                val pts = mutableListOf<Offset>()
                pts.add(Offset(minX + r, minY))
                pts.add(Offset(maxX - r, minY))
                pts.add(Offset(maxX, minY + r))
                pts.add(Offset(maxX, bodyBottom - r))
                pts.add(Offset(maxX - r, bodyBottom))
                // Tail
                pts.add(Offset(minX + w * 0.45f, bodyBottom))
                pts.add(Offset(minX + w * 0.20f, maxY)) // tip of speech pointer
                pts.add(Offset(minX + w * 0.28f, bodyBottom))
                pts.add(Offset(minX + r, bodyBottom))
                pts.add(Offset(minX, bodyBottom - r))
                pts.add(Offset(minX, minY + r))
                pts.add(Offset(minX + r, minY))
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
                            text = "Shapes",
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

                // Grid of shapes (matching Screenshot 2 shape pop-up instead of scrolling list)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(130.dp)
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
        CanvasShapeType.RECTANGLE -> Text("▭", color = tint, fontSize = 20.sp)
        CanvasShapeType.ROUNDED_RECT -> Text("▢", color = tint, fontSize = 20.sp)
        CanvasShapeType.CIRCLE -> Text("○", color = tint, fontSize = 20.sp)
        CanvasShapeType.TRIANGLE -> Text("△", color = tint, fontSize = 18.sp)
        CanvasShapeType.STAR -> Text("★", color = tint, fontSize = 18.sp)
        CanvasShapeType.SPEECH_BUBBLE -> Text("🗨", color = tint, fontSize = 16.sp)
        CanvasShapeType.HEXAGON -> Text("⬡", color = tint, fontSize = 18.sp)
    }
}
