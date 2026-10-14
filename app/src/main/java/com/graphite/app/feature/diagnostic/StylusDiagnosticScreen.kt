package com.graphite.app.feature.diagnostic

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.core.theme.*

data class DiagnosticStrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val toolType: StylusToolType
)

data class DiagnosticStroke(
    val points: List<DiagnosticStrokePoint>,
    val color: Color,
    val isEraser: Boolean
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StylusDiagnosticScreen(
    onNavigateBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var latestPacket by remember { mutableStateOf<StylusPacket?>(null) }
    var capabilities by remember { mutableStateOf(HardwareCapabilitySummary()) }
    var inputPolicy by remember { mutableStateOf(InputModePolicy.ACTIVE_PALM_GUARD) }
    val palmFilter = remember { PalmRejectionFilter(inputPolicy) }
    palmFilter.policy = inputPolicy

    val completedStrokes = remember { mutableStateListOf<DiagnosticStroke>() }
    var currentStrokePoints by remember { mutableStateOf<List<DiagnosticStrokePoint>>(emptyList()) }
    var currentStrokeTool by remember { mutableStateOf(StylusToolType.UNKNOWN) }
    var hoverPoint by remember { mutableStateOf<Offset?>(null) }
    var showLogDialog by remember { mutableStateOf(false) }
    val packetHistory = remember { mutableStateListOf<StylusPacket>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GraphiteBlack)
            .statusBarsPadding()
    ) {
        // Top Diagnostic HUD
        DiagnosticHeaderBar(
            packet = latestPacket,
            capabilities = capabilities,
            inputPolicy = inputPolicy,
            onPolicyChange = { newPolicy -> inputPolicy = newPolicy },
            onNavigateBack = onNavigateBack,
            onClear = {
                completedStrokes.clear()
                currentStrokePoints = emptyList()
                hoverPoint = null
            },
            onResetStats = {
                capabilities = HardwareCapabilitySummary()
                packetHistory.clear()
                palmFilter.reset()
            },
            onToggleLogs = { showLogDialog = !showLogDialog }
        )

        // Telemetry strip
        TelemetryStrip(
            packet = latestPacket,
            capabilities = capabilities
        )

        // Interactive Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GraphiteSurface)
                .border(1.dp, GraphiteOutline, RoundedCornerShape(12.dp))
                .pointerInteropFilter { motionEvent ->
                    val packet = StylusEventParser.parse(motionEvent)
                    val accepted = palmFilter.shouldAcceptEvent(packet)

                    capabilities = StylusEventParser.updateCapabilities(
                        current = capabilities,
                        packet = packet,
                        wasPalmRejected = !accepted
                    )
                    latestPacket = packet

                    if (packetHistory.size > 80) {
                        packetHistory.removeAt(0)
                    }
                    packetHistory.add(packet)

                    if (!accepted) {
                        return@pointerInteropFilter true
                    }

                    when (packet.action) {
                        MotionActionType.HOVER_ENTER, MotionActionType.HOVER_MOVE -> {
                            hoverPoint = Offset(packet.x, packet.y)
                        }
                        MotionActionType.HOVER_EXIT -> {
                            hoverPoint = null
                        }
                        MotionActionType.DOWN -> {
                            hoverPoint = null
                            currentStrokeTool = packet.toolType
                            val newPoint = DiagnosticStrokePoint(packet.x, packet.y, packet.normalizedPressure, packet.toolType)
                            currentStrokePoints = listOf(newPoint)
                        }
                        MotionActionType.MOVE -> {
                            hoverPoint = null
                            // Process batched historical coordinates for highest fidelity
                            val pointsToAdd = mutableListOf<DiagnosticStrokePoint>()
                            for (h in 0 until motionEvent.historySize) {
                                val histPressure = motionEvent.getHistoricalPressure(0, h).coerceIn(0f, 1f)
                                pointsToAdd.add(
                                    DiagnosticStrokePoint(
                                        motionEvent.getHistoricalX(0, h),
                                        motionEvent.getHistoricalY(0, h),
                                        histPressure,
                                        packet.toolType
                                    )
                                )
                            }
                            pointsToAdd.add(DiagnosticStrokePoint(packet.x, packet.y, packet.normalizedPressure, packet.toolType))
                            currentStrokePoints = currentStrokePoints + pointsToAdd
                        }
                        MotionActionType.UP, MotionActionType.CANCEL -> {
                            hoverPoint = null
                            if (currentStrokePoints.isNotEmpty()) {
                                val strokeColor = when (currentStrokeTool) {
                                    StylusToolType.STYLUS -> GraphiteOrange
                                    StylusToolType.ERASER -> Color(0xFFFF5252)
                                    StylusToolType.FINGER -> Color(0xFF64B5F6)
                                    else -> Color.White
                                }
                                completedStrokes.add(
                                    DiagnosticStroke(
                                        points = currentStrokePoints,
                                        color = strokeColor,
                                        isEraser = currentStrokeTool == StylusToolType.ERASER || packet.isEraser
                                    )
                                )
                                currentStrokePoints = emptyList()
                            }
                        }
                        else -> {}
                    }
                    true
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Background subtle grid
                val gridSpacing = 40.dp.toPx()
                val width = size.width
                val height = size.height

                var x = 0f
                while (x < width) {
                    drawLine(
                        color = Color(0xFF1F1F1F),
                        start = Offset(x, 0f),
                        end = Offset(x, height),
                        strokeWidth = 0.5f
                    )
                    x += gridSpacing
                }

                var y = 0f
                while (y < height) {
                    drawLine(
                        color = Color(0xFF1F1F1F),
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 0.5f
                    )
                    y += gridSpacing
                }

                // Render completed strokes
                for (stroke in completedStrokes) {
                    if (stroke.points.size < 2) continue
                    val path = Path()
                    path.moveTo(stroke.points[0].x, stroke.points[0].y)
                    for (i in 1 until stroke.points.size) {
                        path.lineTo(stroke.points[i].x, stroke.points[i].y)
                    }
                    val avgPressure = stroke.points.map { it.pressure }.average().toFloat().coerceIn(0.1f, 1.0f)
                    val baseWidth = if (stroke.isEraser) 24.dp.toPx() else 4.dp.toPx()
                    drawPath(
                        path = path,
                        color = stroke.color,
                        style = Stroke(
                            width = baseWidth * (0.4f + 0.9f * avgPressure),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Render in-progress stroke
                if (currentStrokePoints.size >= 2) {
                    val path = Path()
                    path.moveTo(currentStrokePoints[0].x, currentStrokePoints[0].y)
                    for (i in 1 until currentStrokePoints.size) {
                        path.lineTo(currentStrokePoints[i].x, currentStrokePoints[i].y)
                    }
                    val currentPressure = currentStrokePoints.lastOrNull()?.pressure ?: 0.5f
                    val strokeColor = when (currentStrokeTool) {
                        StylusToolType.STYLUS -> GraphiteOrange
                        StylusToolType.ERASER -> Color(0xFFFF5252)
                        StylusToolType.FINGER -> Color(0xFF64B5F6)
                        else -> Color.White
                    }
                    val baseWidth = if (currentStrokeTool == StylusToolType.ERASER) 24.dp.toPx() else 4.dp.toPx()
                    drawPath(
                        path = path,
                        color = strokeColor,
                        style = Stroke(
                            width = baseWidth * (0.4f + 0.9f * currentPressure),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Render hover reticle if stylus is hovering
                hoverPoint?.let { hp ->
                    drawCircle(
                        color = GraphiteOrange.copy(alpha = 0.6f),
                        radius = 16.dp.toPx(),
                        center = hp,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = GraphiteOrange,
                        radius = 2.dp.toPx(),
                        center = hp
                    )
                }
            }

            // Canvas overlay guidance
            if (completedStrokes.isEmpty() && currentStrokePoints.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Draw with Motorola Stylus or Touch\nVerify pressure, tilt, hover, and palm rejection",
                        color = GraphiteOnSurfaceMuted,
                        fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        // Bottom Capabilities Summary Card
        HardwareCapabilitiesCard(capabilities = capabilities)
    }

    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Recent Motion Packets (${packetHistory.size})", color = Color.White) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                ) {
                    items(packetHistory.asReversed()) { packet ->
                        Text(
                            text = "[${packet.action}] tool=${packet.toolType} P=${"%.2f".format(packet.rawPressure)} tilt=${"%.1f".format(packet.tiltDegrees)}° btn=${packet.isPrimaryButtonPressed}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (packet.toolType == StylusToolType.STYLUS) GraphiteOrange else Color.LightGray,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) {
                    Text("Close", color = GraphiteOrange)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }
}

@Composable
private fun DiagnosticHeaderBar(
    packet: StylusPacket?,
    capabilities: HardwareCapabilitySummary,
    inputPolicy: InputModePolicy,
    onPolicyChange: (InputModePolicy) -> Unit,
    onNavigateBack: () -> Unit,
    onClear: () -> Unit,
    onResetStats: () -> Unit,
    onToggleLogs: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Workspace",
                    tint = GraphiteOrange
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column {
                Text(
                    text = "Stylus Diagnostic",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Hardware Telemetry & Sensor Log",
                    color = GraphiteOrange,
                    fontSize = 11.sp
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Input Policy Selector
            AssistChip(
                onClick = {
                    val nextPolicy = when (inputPolicy) {
                        InputModePolicy.ACTIVE_PALM_GUARD -> InputModePolicy.STYLUS_ONLY_FOR_INK
                        InputModePolicy.STYLUS_ONLY_FOR_INK -> InputModePolicy.ALLOW_ALL
                        InputModePolicy.ALLOW_ALL -> InputModePolicy.ACTIVE_PALM_GUARD
                    }
                    onPolicyChange(nextPolicy)
                },
                label = {
                    Text(
                        text = when (inputPolicy) {
                            InputModePolicy.ACTIVE_PALM_GUARD -> "Palm Guard"
                            InputModePolicy.STYLUS_ONLY_FOR_INK -> "Stylus Only"
                            InputModePolicy.ALLOW_ALL -> "Allow All"
                        },
                        fontSize = 11.sp,
                        color = Color.White
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = GraphiteSurfaceVariant
                ),
                border = AssistChipDefaults.assistChipBorder(borderColor = GraphiteOutline, enabled = true)
            )

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(onClick = onClear) {
                Icon(Icons.Default.Delete, contentDescription = "Clear Canvas", tint = Color.LightGray)
            }

            IconButton(onClick = onResetStats) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset Stats", tint = Color.LightGray)
            }

            TextButton(onClick = onToggleLogs) {
                Text("Logs", color = GraphiteOrange, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TelemetryStrip(
    packet: StylusPacket?,
    capabilities: HardwareCapabilitySummary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(GraphiteSurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val toolColor = when (packet?.toolType) {
            StylusToolType.STYLUS -> GraphiteOrange
            StylusToolType.ERASER -> Color(0xFFFF5252)
            StylusToolType.FINGER -> Color(0xFF64B5F6)
            else -> Color.Gray
        }

        Column {
            Text(text = "TOOL", fontSize = 9.sp, color = GraphiteOnSurfaceMuted, fontWeight = FontWeight.SemiBold)
            Text(
                text = packet?.toolType?.name ?: "IDLE",
                color = toolColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column {
            Text(text = "PRESSURE", fontSize = 9.sp, color = GraphiteOnSurfaceMuted, fontWeight = FontWeight.SemiBold)
            Text(
                text = packet?.let { "%.2f".format(it.normalizedPressure) } ?: "--",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Column {
            Text(text = "TILT", fontSize = 9.sp, color = GraphiteOnSurfaceMuted, fontWeight = FontWeight.SemiBold)
            Text(
                text = packet?.let { "${"%.1f".format(it.tiltDegrees)}°" } ?: "--",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Column {
            Text(text = "HOVER", fontSize = 9.sp, color = GraphiteOnSurfaceMuted, fontWeight = FontWeight.SemiBold)
            Text(
                text = packet?.let { "%.1f".format(it.hoverDistance) } ?: "--",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Column {
            Text(text = "BARREL BTN", fontSize = 9.sp, color = GraphiteOnSurfaceMuted, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (packet?.isPrimaryButtonPressed == true) "PRESSED" else "OFF",
                color = if (packet?.isPrimaryButtonPressed == true) GraphiteOrange else Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column {
            Text(text = "PALM BLOCKED", fontSize = 9.sp, color = GraphiteOnSurfaceMuted, fontWeight = FontWeight.SemiBold)
            Text(
                text = capabilities.palmTouchesRejectedCount.toString(),
                color = if (capabilities.palmTouchesRejectedCount > 0) Color(0xFFFF7A30) else Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun HardwareCapabilitiesCard(
    capabilities: HardwareCapabilitySummary
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = GraphiteSurfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CapabilityBadge(
                label = "Stylus",
                active = capabilities.hasStylusInputSeen,
                detail = if (capabilities.hasStylusInputSeen) "Detected" else "Waiting"
            )
            CapabilityBadge(
                label = "Pressure",
                active = capabilities.hasPressureSupport,
                detail = if (capabilities.hasPressureSupport) "${"%.2f".format(capabilities.minPressureSeen)} - ${"%.2f".format(capabilities.maxPressureSeen)}" else "Off/Fixed"
            )
            CapabilityBadge(
                label = "Tilt",
                active = capabilities.hasTiltSupport,
                detail = if (capabilities.hasTiltSupport) "Max ${"%.0f".format(capabilities.maxTiltDegreesSeen)}°" else "Off"
            )
            CapabilityBadge(
                label = "Hover",
                active = capabilities.hasHoverSupport,
                detail = if (capabilities.hasHoverSupport) "Active" else "Off"
            )
            CapabilityBadge(
                label = "Barrel",
                active = capabilities.hasBarrelButtonSupport,
                detail = if (capabilities.hasBarrelButtonSupport) "Supported" else "Off"
            )
        }
    }
}

@Composable
private fun CapabilityBadge(
    label: String,
    active: Boolean,
    detail: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = GraphiteOnSurfaceMuted,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = detail,
            fontSize = 11.sp,
            color = if (active) GraphiteOrange else Color.Gray,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}
