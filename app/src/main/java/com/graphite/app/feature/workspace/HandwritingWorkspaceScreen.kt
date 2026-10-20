package com.graphite.app.feature.workspace

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.graphite.app.core.theme.*
import com.graphite.app.feature.diagnostic.InputModePolicy
import com.graphite.app.feature.diagnostic.MotionActionType
import com.graphite.app.feature.diagnostic.PalmRejectionFilter
import com.graphite.app.feature.diagnostic.StylusEventParser
import com.graphite.app.feature.diagnostic.StylusToolType
import com.graphite.app.feature.workspace.geometry.BezierSplineInterpolator
import com.graphite.app.feature.workspace.model.*

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HandwritingWorkspaceScreen(
    onNavigateToDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    workspaceViewModel: WorkspaceViewModel = viewModel()
) {
    val uiState by workspaceViewModel.uiState.collectAsState()

    val palmFilter = remember { PalmRejectionFilter(uiState.inputPolicy) }
    palmFilter.policy = uiState.inputPolicy

    // Active in-progress stroke coordinates
    var activePoints by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var activeEraserCenter by remember { mutableStateOf<Offset?>(null) }
    var activeToolType by remember { mutableStateOf(StylusToolType.UNKNOWN) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GraphiteBlack)
            .statusBarsPadding()
    ) {
        // Top App Bar
        WorkspaceTopAppBar(
            paperStyle = uiState.paperStyle,
            isStylusActive = uiState.isStylusActive,
            onPaperStyleChange = { workspaceViewModel.selectPaperStyle(it) },
            onNavigateToDiagnostics = onNavigateToDiagnostics
        )

        // Main Functional Toolbar
        WorkspaceToolbar(
            uiState = uiState,
            onSelectTool = { workspaceViewModel.selectTool(it) },
            onSelectColor = { workspaceViewModel.selectColor(it) },
            onSelectWidth = { workspaceViewModel.selectStrokeWidth(it) },
            onUndo = { workspaceViewModel.undo() },
            onRedo = { workspaceViewModel.redo() },
            onClear = { workspaceViewModel.requestClearPage() }
        )

        // Handwriting Canvas Viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GraphiteBlack)
                .border(1.dp, GraphiteOutline, RoundedCornerShape(12.dp))
                .pointerInteropFilter { motionEvent ->
                    val packet = StylusEventParser.parse(motionEvent)
                    val accepted = palmFilter.shouldAcceptEvent(packet)

                    workspaceViewModel.setStylusActive(
                        packet.toolType == StylusToolType.STYLUS || packet.toolType == StylusToolType.ERASER
                    )

                    if (!accepted) {
                        return@pointerInteropFilter true
                    }

                    val isHardwareEraser = packet.isEraser || packet.isPrimaryButtonPressed
                    val effectiveEraserMode = isHardwareEraser || uiState.selectedTool == InkToolType.ERASER

                    when (packet.action) {
                        MotionActionType.DOWN -> {
                            activeToolType = packet.toolType
                            if (effectiveEraserMode) {
                                activeEraserCenter = Offset(packet.x, packet.y)
                                workspaceViewModel.eraseAt(packet.x, packet.y, 32f)
                            } else {
                                activeEraserCenter = null
                                activePoints = listOf(
                                    InkPoint(
                                        x = packet.x,
                                        y = packet.y,
                                        pressure = packet.normalizedPressure,
                                        timestamp = packet.timestamp
                                    )
                                )
                            }
                        }
                        MotionActionType.MOVE -> {
                            if (effectiveEraserMode) {
                                activeEraserCenter = Offset(packet.x, packet.y)
                                // Erase historical batch points for high-frequency precision
                                for (h in 0 until motionEvent.historySize) {
                                    val hx = motionEvent.getHistoricalX(0, h)
                                    val hy = motionEvent.getHistoricalY(0, h)
                                    workspaceViewModel.eraseAt(hx, hy, 32f)
                                }
                                workspaceViewModel.eraseAt(packet.x, packet.y, 32f)
                            } else {
                                activeEraserCenter = null
                                val batch = mutableListOf<InkPoint>()
                                for (h in 0 until motionEvent.historySize) {
                                    batch.add(
                                        InkPoint(
                                            x = motionEvent.getHistoricalX(0, h),
                                            y = motionEvent.getHistoricalY(0, h),
                                            pressure = motionEvent.getHistoricalPressure(0, h).coerceIn(0.05f, 1f),
                                            timestamp = motionEvent.getHistoricalEventTime(h)
                                        )
                                    )
                                }
                                batch.add(
                                    InkPoint(
                                        x = packet.x,
                                        y = packet.y,
                                        pressure = packet.normalizedPressure,
                                        timestamp = packet.timestamp
                                    )
                                )
                                activePoints = activePoints + batch
                            }
                        }
                        MotionActionType.UP, MotionActionType.CANCEL -> {
                            if (effectiveEraserMode) {
                                activeEraserCenter = null
                            } else if (activePoints.isNotEmpty()) {
                                val completedStroke = InkStroke(
                                    points = activePoints,
                                    baseWidth = uiState.selectedStrokeWidth,
                                    color = uiState.selectedColor,
                                    toolType = InkToolType.PEN
                                )
                                workspaceViewModel.addStroke(completedStroke)
                                activePoints = emptyList()
                            }
                        }
                        else -> {}
                    }
                    true
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                // 1. Draw Page Background Template (never included in erasing/undo)
                when (uiState.paperStyle) {
                    PaperStyle.BLANK -> {
                        // Plain OLED black
                    }
                    PaperStyle.RULED -> {
                        val lineSpacing = 36.dp.toPx()
                        var y = lineSpacing
                        while (y < canvasHeight) {
                            drawLine(
                                color = Color(0xFF262626),
                                start = Offset(0f, y),
                                end = Offset(canvasWidth, y),
                                strokeWidth = 1.dp.toPx()
                            )
                            y += lineSpacing
                        }
                    }
                    PaperStyle.DOTTED -> {
                        val dotSpacing = 32.dp.toPx()
                        var x = dotSpacing
                        while (x < canvasWidth) {
                            var y = dotSpacing
                            while (y < canvasHeight) {
                                drawCircle(
                                    color = Color(0xFF383838),
                                    radius = 1.5.dp.toPx(),
                                    center = Offset(x, y)
                                )
                                y += dotSpacing
                            }
                            x += dotSpacing
                        }
                    }
                    PaperStyle.GRID -> {
                        val gridSpacing = 32.dp.toPx()
                        var x = 0f
                        while (x < canvasWidth) {
                            drawLine(
                                color = Color(0xFF222222),
                                start = Offset(x, 0f),
                                end = Offset(x, canvasHeight),
                                strokeWidth = 1.dp.toPx()
                            )
                            x += gridSpacing
                        }
                        var y = 0f
                        while (y < canvasHeight) {
                            drawLine(
                                color = Color(0xFF222222),
                                start = Offset(0f, y),
                                end = Offset(canvasWidth, y),
                                strokeWidth = 1.dp.toPx()
                            )
                            y += gridSpacing
                        }
                    }
                    else -> {
                        // Templates rendered with specialized rules
                    }
                }

                // 2. Render Committed Strokes with Catmull-Rom to Cubic Bezier Splines
                for (stroke in uiState.strokes) {
                    if (stroke.points.size < 2) continue
                    val path = BezierSplineInterpolator.createSplinePath(stroke.points)
                    val avgPressure = stroke.points.map { it.pressure }.average().toFloat().coerceIn(0.1f, 1f)
                    val effectiveWidth = BezierSplineInterpolator.calculateEffectiveWidth(
                        stroke.baseWidth * density,
                        avgPressure
                    )
                    drawPath(
                        path = path,
                        color = stroke.color,
                        style = Stroke(
                            width = effectiveWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // 3. Render In-Progress Stroke in Real Time
                if (activePoints.size >= 2) {
                    val activePath = BezierSplineInterpolator.createSplinePath(activePoints)
                    val currentPressure = activePoints.lastOrNull()?.pressure ?: 0.5f
                    val effectiveWidth = BezierSplineInterpolator.calculateEffectiveWidth(
                        uiState.selectedStrokeWidth * density,
                        currentPressure
                    )
                    drawPath(
                        path = activePath,
                        color = uiState.selectedColor,
                        style = Stroke(
                            width = effectiveWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // 4. Render Active Eraser Cursor Reticle
                activeEraserCenter?.let { center ->
                    drawCircle(
                        color = Color(0xFFFF5252).copy(alpha = 0.35f),
                        radius = 28.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = Color(0xFFFF5252),
                        radius = 28.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // Empty Canvas Placeholder
            if (uiState.strokes.isEmpty() && activePoints.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Handwriting Workspace\nWrite or sketch with Motorola Stylus or Touch",
                        color = GraphiteOnSurfaceMuted.copy(alpha = 0.5f),
                        fontSize = 15.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }

    // Confirmation Dialog for Destructive Clear Page
    if (uiState.showClearDialog) {
        AlertDialog(
            onDismissRequest = { workspaceViewModel.dismissClearDialog() },
            title = {
                Text(
                    text = "Clear Page?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will remove all handwritten strokes on this page. You can still use Undo to restore them.",
                    color = GraphiteOnSurface
                )
            },
            confirmButton = {
                Button(
                    onClick = { workspaceViewModel.confirmClearPage() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Clear Page", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { workspaceViewModel.dismissClearDialog() }) {
                    Text("Cancel", color = GraphiteOrange)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }
}

@Composable
private fun WorkspaceTopAppBar(
    paperStyle: PaperStyle,
    isStylusActive: Boolean,
    onPaperStyleChange: (PaperStyle) -> Unit,
    onNavigateToDiagnostics: () -> Unit
) {
    var paperMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = null,
                tint = GraphiteOrange,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Quick Note",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Text(
                    text = if (isStylusActive) "Stylus Active" else "Ready (Touch / Pen)",
                    color = if (isStylusActive) GraphiteOrange else GraphiteOnSurfaceMuted,
                    fontSize = 11.sp
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Paper Style Dropdown Trigger
            Box {
                FilterChip(
                    selected = true,
                    onClick = { paperMenuExpanded = true },
                    label = {
                        Text(
                            text = "Paper: ${paperStyle.name}",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.GridOn,
                            contentDescription = "Select Paper Style",
                            tint = GraphiteOrange,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GraphiteSurfaceVariant
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = GraphiteOutline,
                        selectedBorderColor = GraphiteOutline,
                        enabled = true,
                        selected = true
                    )
                )

                DropdownMenu(
                    expanded = paperMenuExpanded,
                    onDismissRequest = { paperMenuExpanded = false },
                    modifier = Modifier.background(GraphiteSurfaceElevated)
                ) {
                    PaperStyle.entries.forEach { style ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = style.name.lowercase().replaceFirstChar { it.uppercase() },
                                    color = if (style == paperStyle) GraphiteOrange else Color.White
                                )
                            },
                            onClick = {
                                onPaperStyleChange(style)
                                paperMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Diagnostic Navigation Button
            FilledTonalIconButton(
                onClick = onNavigateToDiagnostics,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = GraphiteSurfaceVariant,
                    contentColor = GraphiteOrange
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Open Stylus Hardware Diagnostics"
                )
            }
        }
    }
}

@Composable
private fun WorkspaceToolbar(
    uiState: WorkspaceUiState,
    onSelectTool: (InkToolType) -> Unit,
    onSelectColor: (Color) -> Unit,
    onSelectWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = GraphiteSurfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Tool Selection (Pen vs Eraser)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolButton(
                    selected = uiState.selectedTool == InkToolType.PEN,
                    icon = Icons.Default.Create,
                    contentDescription = "Pen Tool",
                    onClick = { onSelectTool(InkToolType.PEN) }
                )

                Spacer(modifier = Modifier.width(4.dp))

                ToolButton(
                    selected = uiState.selectedTool == InkToolType.ERASER,
                    icon = Icons.Default.AutoFixHigh,
                    contentDescription = "Eraser Tool",
                    onClick = { onSelectTool(InkToolType.ERASER) }
                )
            }

            VerticalDivider(
                modifier = Modifier
                    .height(26.dp)
                    .padding(horizontal = 4.dp),
                color = GraphiteOutline
            )

            // 2. Stroke Width Presets
            Row(verticalAlignment = Alignment.CenterVertically) {
                val widths = listOf(
                    2.5f to "Fine",
                    5.0f to "Medium",
                    9.0f to "Bold"
                )
                widths.forEach { (widthValue, label) ->
                    val isSelected = uiState.selectedStrokeWidth == widthValue
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) GraphiteOrange.copy(alpha = 0.25f) else Color.Transparent)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) GraphiteOrange else Color.Transparent,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable { onSelectWidth(widthValue) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            color = if (isSelected) GraphiteOrange else GraphiteOnSurfaceMuted,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            VerticalDivider(
                modifier = Modifier
                    .height(26.dp)
                    .padding(horizontal = 4.dp),
                color = GraphiteOutline
            )

            // 3. Color Palette Swatches
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkspaceColors.All.forEach { colorOption ->
                    val isSelected = uiState.selectedColor == colorOption.color && uiState.selectedTool == InkToolType.PEN
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(colorOption.color)
                            .border(
                                width = if (isSelected) 2.5.dp else 1.dp,
                                color = if (isSelected) Color.White else Color(0xFF424242),
                                shape = CircleShape
                            )
                            .clickable { onSelectColor(colorOption.color) }
                    )
                }
            }

            VerticalDivider(
                modifier = Modifier
                    .height(26.dp)
                    .padding(horizontal = 4.dp),
                color = GraphiteOutline
            )

            // 4. Undo, Redo, Clear
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onUndo,
                    enabled = uiState.canUndo
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo Stroke",
                        tint = if (uiState.canUndo) Color.White else Color(0xFF555555),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onRedo,
                    enabled = uiState.canRedo
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo Stroke",
                        tint = if (uiState.canRedo) Color.White else Color(0xFF555555),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onClear,
                    enabled = uiState.strokes.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Clear Page",
                        tint = if (uiState.strokes.isNotEmpty()) Color(0xFFFF5252) else Color(0xFF555555),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) GraphiteOrange else GraphiteSurfaceElevated)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) Color.Black else Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}
