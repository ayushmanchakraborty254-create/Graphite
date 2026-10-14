package com.graphite.app.feature.workspace

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.auth.GoogleProfileTopBarIcon
import com.graphite.app.core.drive.DriveUploadState
import com.graphite.app.core.theme.*
import com.graphite.app.data.repository.GraphiteRepository
import com.graphite.app.feature.diagnostic.InputModePolicy
import com.graphite.app.feature.diagnostic.MotionActionType
import com.graphite.app.feature.diagnostic.PalmRejectionFilter
import com.graphite.app.feature.diagnostic.StylusEventParser
import com.graphite.app.feature.diagnostic.StylusToolType
import com.graphite.app.feature.workspace.geometry.BezierSplineInterpolator
import com.graphite.app.feature.workspace.model.*
import kotlinx.coroutines.launch

enum class StudioTool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    LASSO,
    SHAPES,
    TEXT,
    AI
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CanvasEditorScreen(
    notebookId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    workspaceViewModel: WorkspaceViewModel = viewModel()
) {
    val notebook = remember(notebookId) { GraphiteRepository.getNotebook(notebookId) }
    val uiState by workspaceViewModel.uiState.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val driveManager = remember { GraphiteApplication.instance.googleDriveManager }
    val driveAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val authResult = driveManager.handleAuthorizationResult(result.data)
        if (authResult.isSuccess && notebook != null) {
            scope.launch {
                driveManager.startUploadFlow(context, notebook)
            }
        }
    }

    // Load notebook's initial strokes once
    LaunchedEffect(notebookId) {
        notebook?.let { nb ->
            if (uiState.strokes.isEmpty() && nb.initialStrokes.isNotEmpty()) {
                nb.initialStrokes.forEach { stroke -> workspaceViewModel.addStroke(stroke) }
            }
            workspaceViewModel.selectPaperStyle(nb.paperStyle)
        }
    }

    var selectedStudioTool by remember { mutableStateOf(StudioTool.PEN) }
    var showSideSettingsMenu by remember { mutableStateOf(false) }
    var showPageThumbnailsDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var scrollingMode by remember { mutableStateOf("Vertical") }

    val palmFilter = remember { PalmRejectionFilter(uiState.inputPolicy) }
    palmFilter.policy = uiState.inputPolicy

    var activePoints by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var activeEraserCenter by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GraphiteBlack)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Studio Top App Bar matching Page 5
            StudioTopAppBar(
                selectedTool = selectedStudioTool,
                canUndo = uiState.canUndo,
                canRedo = uiState.canRedo,
                onNavigateBack = onNavigateBack,
                onTogglePageThumbnails = { showPageThumbnailsDialog = true },
                onUndo = { workspaceViewModel.undo() },
                onRedo = { workspaceViewModel.redo() },
                onSelectTool = { tool ->
                    selectedStudioTool = tool
                    if (tool == StudioTool.ERASER) {
                        workspaceViewModel.selectTool(InkToolType.ERASER)
                    } else {
                        workspaceViewModel.selectTool(InkToolType.PEN)
                    }
                },
                onAddPage = {
                    val newStroke = InkStroke(
                        points = listOf(InkPoint(120f, 440f), InkPoint(480f, 440f)),
                        baseWidth = 4f,
                        color = uiState.selectedColor
                    )
                    workspaceViewModel.addStroke(newStroke)
                },
                onToggleSettingsMenu = { showSideSettingsMenu = !showSideSettingsMenu }
            )

            // 2. Main Studio Canvas Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A))
                    .pointerInteropFilter { motionEvent ->
                        val packet = StylusEventParser.parse(motionEvent)
                        val accepted = palmFilter.shouldAcceptEvent(packet)

                        workspaceViewModel.setStylusActive(
                            packet.toolType == StylusToolType.STYLUS || packet.toolType == StylusToolType.ERASER
                        )

                        if (!accepted) return@pointerInteropFilter true

                        val isHardwareEraser = packet.isEraser || packet.isPrimaryButtonPressed
                        val isEffectiveEraser = isHardwareEraser || selectedStudioTool == StudioTool.ERASER

                        when (packet.action) {
                            MotionActionType.DOWN -> {
                                if (isEffectiveEraser) {
                                    activeEraserCenter = Offset(packet.x, packet.y)
                                    workspaceViewModel.eraseAt(packet.x, packet.y, 32f)
                                } else {
                                    activeEraserCenter = null
                                    activePoints = listOf(
                                        InkPoint(packet.x, packet.y, packet.normalizedPressure, packet.timestamp)
                                    )
                                }
                            }
                            MotionActionType.MOVE -> {
                                if (isEffectiveEraser) {
                                    activeEraserCenter = Offset(packet.x, packet.y)
                                    for (h in 0 until motionEvent.historySize) {
                                        workspaceViewModel.eraseAt(
                                            motionEvent.getHistoricalX(0, h),
                                            motionEvent.getHistoricalY(0, h),
                                            32f
                                        )
                                    }
                                    workspaceViewModel.eraseAt(packet.x, packet.y, 32f)
                                } else {
                                    activeEraserCenter = null
                                    val batch = mutableListOf<InkPoint>()
                                    for (h in 0 until motionEvent.historySize) {
                                        batch.add(
                                            InkPoint(
                                                motionEvent.getHistoricalX(0, h),
                                                motionEvent.getHistoricalY(0, h),
                                                motionEvent.getHistoricalPressure(0, h).coerceIn(0.05f, 1f),
                                                motionEvent.getHistoricalEventTime(h)
                                            )
                                        )
                                    }
                                    batch.add(
                                        InkPoint(packet.x, packet.y, packet.normalizedPressure, packet.timestamp)
                                    )
                                    activePoints = activePoints + batch
                                }
                            }
                            MotionActionType.UP, MotionActionType.CANCEL -> {
                                if (isEffectiveEraser) {
                                    activeEraserCenter = null
                                } else if (activePoints.isNotEmpty()) {
                                    val strokeColor = if (selectedStudioTool == StudioTool.HIGHLIGHTER) {
                                        uiState.selectedColor.copy(alpha = 0.35f)
                                    } else uiState.selectedColor

                                    val strokeWidth = if (selectedStudioTool == StudioTool.HIGHLIGHTER) {
                                        uiState.selectedStrokeWidth * 3.5f
                                    } else uiState.selectedStrokeWidth

                                    val newStroke = InkStroke(
                                        points = activePoints,
                                        baseWidth = strokeWidth,
                                        color = strokeColor,
                                        toolType = InkToolType.PEN
                                    )
                                    workspaceViewModel.addStroke(newStroke)
                                    notebook?.let {
                                        GraphiteRepository.updateNotebookStrokes(it.id, uiState.strokes + newStroke)
                                    }
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

                    // Paper Background Template
                    when (uiState.paperStyle) {
                        PaperStyle.BLANK -> {}
                        PaperStyle.RULED -> {
                            val lineSpacing = 38.dp.toPx()
                            var y = lineSpacing
                            while (y < canvasHeight) {
                                drawLine(
                                    color = Color(0xFF242424),
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
                                drawLine(color = Color(0xFF222222), start = Offset(x, 0f), end = Offset(x, canvasHeight), strokeWidth = 1.dp.toPx())
                                x += gridSpacing
                            }
                            var y = 0f
                            while (y < canvasHeight) {
                                drawLine(color = Color(0xFF222222), start = Offset(0f, y), end = Offset(canvasWidth, y), strokeWidth = 1.dp.toPx())
                                y += gridSpacing
                            }
                        }
                    }

                    // Render Committed Ink Strokes
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

                    // Render In-Progress Stroke
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

                    // Render Eraser Reticle
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

                // Default assignment exercise notes matching Page 5 of PDF
                if (notebook?.previewLines?.isNotEmpty() == true) {
                    Column(
                        modifier = Modifier
                            .padding(start = 72.dp, top = 48.dp, end = 40.dp)
                    ) {
                        notebook.previewLines.forEach { line ->
                            Text(
                                text = line,
                                color = Color(0xFFCCCCCC),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                        }
                    }
                }

                // 3. Floating Vertical Pen Palette (Left Edge) matching Page 5
                FloatingPenPalette(
                    selectedColor = uiState.selectedColor,
                    selectedWidth = uiState.selectedStrokeWidth,
                    onSelectColor = { workspaceViewModel.selectColor(it) },
                    onSelectWidth = { workspaceViewModel.selectStrokeWidth(it) },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp)
                )
            }
        }

        // 4. Right Slide-out Menu / Settings Sheet matching Page 5
        if (showSideSettingsMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showSideSettingsMenu = false }
            ) {
                SlideOutNotebookSettingsMenu(
                    scrollingMode = scrollingMode,
                    inputPolicy = uiState.inputPolicy,
                    onScrollingChange = { scrollingMode = it },
                    onPolicyChange = { workspaceViewModel.setInputPolicy(it) },
                    onChangeTemplate = { showTemplateDialog = true },
                    onGetInfo = { showInfoDialog = true },
                    onDeletePage = {
                        workspaceViewModel.confirmClearPage()
                        showSideSettingsMenu = false
                    },
                    onUploadToDrive = {
                        showSideSettingsMenu = false
                        if (!driveManager.hasDrivePermission(context)) {
                            val intent = driveManager.createAuthorizationIntent(context)
                            driveAuthLauncher.launch(intent)
                        } else if (notebook != null) {
                            scope.launch {
                                driveManager.startUploadFlow(context, notebook)
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }

    // Change Template Dialog
    if (showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text("Change Page Template", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperStyle.entries.forEach { style ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (uiState.paperStyle == style) GraphiteOrange.copy(alpha = 0.2f) else Color.Transparent)
                                .clickable {
                                    workspaceViewModel.selectPaperStyle(style)
                                    showTemplateDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = style.name.lowercase().replaceFirstChar { it.uppercase() },
                                color = if (uiState.paperStyle == style) GraphiteOrange else Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTemplateDialog = false }) {
                    Text("Close", color = GraphiteOrange)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }

    // Notebook Info Dialog
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Notebook Information", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Title: ${notebook?.title ?: "Quick Note"}", color = Color.White)
                    Text("Last Modified: ${notebook?.updatedAtFormatted ?: "Just now"}", color = Color.Gray)
                    Text("Strokes on Page: ${uiState.strokes.size}", color = Color.Gray)
                    Text("Background: ${uiState.paperStyle.name}", color = Color.Gray)
                    Text("Hardware Digitizer: Motorola Moto Pad 60 Pro", color = GraphiteOrange)
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("OK", color = GraphiteOrange)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }


}

@Composable
private fun StudioTopAppBar(
    selectedTool: StudioTool,
    canUndo: Boolean,
    canRedo: Boolean,
    onNavigateBack: () -> Unit,
    onTogglePageThumbnails: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectTool: (StudioTool) -> Unit,
    onAddPage: () -> Unit,
    onToggleSettingsMenu: () -> Unit
) {
    Surface(
        color = Color(0xFF141414),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Group: Back (<), Grid Pages (⊞), Undo (↶), Redo (↷)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                IconButton(onClick = onTogglePageThumbnails, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.GridView, contentDescription = "Page Thumbnails", tint = Color.White)
                }
                IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo",
                        tint = if (canUndo) Color.White else Color(0xFF555555)
                    )
                }
                IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.Redo,
                        contentDescription = "Redo",
                        tint = if (canRedo) Color.White else Color(0xFF555555)
                    )
                }
            }

            // Center Tool Switcher: Pen, Highlighter, Eraser, Lasso, Shapes, Text, AI
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                StudioToolIcon(
                    tool = StudioTool.PEN,
                    selected = selectedTool == StudioTool.PEN,
                    icon = Icons.Default.Create,
                    onClick = { onSelectTool(StudioTool.PEN) }
                )
                StudioToolIcon(
                    tool = StudioTool.HIGHLIGHTER,
                    selected = selectedTool == StudioTool.HIGHLIGHTER,
                    icon = Icons.Default.BorderColor,
                    onClick = { onSelectTool(StudioTool.HIGHLIGHTER) }
                )
                StudioToolIcon(
                    tool = StudioTool.ERASER,
                    selected = selectedTool == StudioTool.ERASER,
                    icon = Icons.Default.AutoFixHigh,
                    onClick = { onSelectTool(StudioTool.ERASER) }
                )
                StudioToolIcon(
                    tool = StudioTool.LASSO,
                    selected = selectedTool == StudioTool.LASSO,
                    icon = Icons.Default.PanoramaFishEye,
                    onClick = { onSelectTool(StudioTool.LASSO) }
                )
                StudioToolIcon(
                    tool = StudioTool.SHAPES,
                    selected = selectedTool == StudioTool.SHAPES,
                    icon = Icons.Default.Straighten,
                    onClick = { onSelectTool(StudioTool.SHAPES) }
                )
                StudioToolIcon(
                    tool = StudioTool.TEXT,
                    selected = selectedTool == StudioTool.TEXT,
                    icon = Icons.Default.TextFields,
                    onClick = { onSelectTool(StudioTool.TEXT) }
                )
                StudioToolIcon(
                    tool = StudioTool.AI,
                    selected = selectedTool == StudioTool.AI,
                    icon = Icons.Default.AutoAwesome,
                    onClick = { onSelectTool(StudioTool.AI) }
                )
            }

            // Right Group: Add element (+), Zoom/Fit (⊘), Overflow (⋮)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAddPage, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add Page", tint = Color.White)
                }
                IconButton(onClick = { /* Fit to screen */ }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.CropFree, contentDescription = "Fit Page", tint = Color.White)
                }
                IconButton(onClick = onToggleSettingsMenu, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Settings Menu", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(4.dp))
                GoogleProfileTopBarIcon()
            }
        }
    }
}

@Composable
private fun StudioToolIcon(
    tool: StudioTool,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color.White.copy(alpha = 0.15f) else Color.Transparent)
            .border(
                width = if (selected) 1.5.dp else 0.dp,
                color = if (selected) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tool.name,
            tint = if (selected) Color.White else Color(0xFF9E9E9E),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun FloatingPenPalette(
    selectedColor: Color,
    selectedWidth: Float,
    onSelectColor: (Color) -> Unit,
    onSelectWidth: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .width(46.dp)
            .shadow(6.dp, RoundedCornerShape(23.dp)),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Selected nib preview
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(selectedColor)
                    .border(2.dp, Color.White, CircleShape)
            )

            // Preset Favorite Swatches matching Page 5
            val presets = listOf(
                Color(0xFF1A237E), // Dark Navy
                Color(0xFF00BCD4), // Vibrant Cyan
                Color(0xFFD32F2F), // Crimson Red
                Color(0xFFFF7A30)  // Coral Orange
            )
            presets.forEach { color ->
                val isSelected = selectedColor == color
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 2.dp else 0.5.dp,
                            color = if (isSelected) Color.White else Color.Gray,
                            shape = CircleShape
                        )
                        .clickable { onSelectColor(color) }
                )
            }

            HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(vertical = 4.dp))

            // Nib stroke thickness presets (Fine 2.5dp, Medium 5dp, Bold 9dp)
            val widths = listOf(2.5f, 5.0f, 9.0f)
            widths.forEach { w ->
                val isSelected = selectedWidth == w
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onSelectWidth(w) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size((w * 1.5f).dp.coerceIn(4.dp, 14.dp))
                            .clip(CircleShape)
                            .background(if (isSelected) GraphiteOrange else Color.Gray)
                    )
                }
            }
        }
    }
}

@Composable
private fun SlideOutNotebookSettingsMenu(
    scrollingMode: String,
    inputPolicy: InputModePolicy,
    onScrollingChange: (String) -> Unit,
    onPolicyChange: (InputModePolicy) -> Unit,
    onChangeTemplate: () -> Unit,
    onGetInfo: () -> Unit,
    onDeletePage: () -> Unit,
    onUploadToDrive: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .width(280.dp)
            .fillMaxHeight(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181818)),
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(text = "Share", color = Color.White, fontSize = 14.sp)
            Text(text = "Go To Page (1 of 1)", color = Color.White, fontSize = 14.sp)
            Text(text = "Present", color = Color.White, fontSize = 14.sp)

            HorizontalDivider(color = Color(0xFF2A2A2A))

            // PAGE SECTION
            Text(text = "PAGE", color = Color(0xFF00BCD4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            MenuClickableRow("Rotate Page", onClick = {})
            MenuClickableRow("Change Template", onClick = onChangeTemplate)
            MenuClickableRow("Save as Template", onClick = {})
            MenuClickableRow("Delete Page", textColor = Color(0xFFFF5252), onClick = onDeletePage)

            HorizontalDivider(color = Color(0xFF2A2A2A))

            // NOTEBOOK SECTION
            Text(text = "NOTEBOOK", color = Color(0xFF00BCD4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            MenuClickableRow("Upload to Google Drive", textColor = GraphiteOrange, onClick = onUploadToDrive)
            MenuClickableRow("Zoom Box", onClick = {})
            MenuClickableRow("Password", onClick = {})
            MenuClickableRow("Get Info", onClick = onGetInfo)

            HorizontalDivider(color = Color(0xFF2A2A2A))

            // SETTINGS SECTION
            Text(text = "SETTINGS", color = Color(0xFF00BCD4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    onScrollingChange(if (scrollingMode == "Vertical") "Horizontal" else "Vertical")
                },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Scrolling", color = Color.White, fontSize = 14.sp)
                Text(text = scrollingMode, color = Color(0xFF00BCD4), fontSize = 13.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    val nextPolicy = when (inputPolicy) {
                        InputModePolicy.ACTIVE_PALM_GUARD -> InputModePolicy.STYLUS_ONLY_FOR_INK
                        InputModePolicy.STYLUS_ONLY_FOR_INK -> InputModePolicy.ALLOW_ALL
                        InputModePolicy.ALLOW_ALL -> InputModePolicy.ACTIVE_PALM_GUARD
                    }
                    onPolicyChange(nextPolicy)
                },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Stylus Policy", color = Color.White, fontSize = 14.sp)
                Text(
                    text = when (inputPolicy) {
                        InputModePolicy.ACTIVE_PALM_GUARD -> "Palm Guard"
                        InputModePolicy.STYLUS_ONLY_FOR_INK -> "Stylus Only"
                        InputModePolicy.ALLOW_ALL -> "Allow All"
                    },
                    color = GraphiteOrange,
                    fontSize = 13.sp
                )
            }
            MenuClickableRow("More Settings", onClick = {})
            MenuClickableRow("Customize Toolbar", onClick = {})
        }
    }
}

@Composable
private fun MenuClickableRow(
    title: String,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Text(
        text = title,
        color = textColor,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    )
}
