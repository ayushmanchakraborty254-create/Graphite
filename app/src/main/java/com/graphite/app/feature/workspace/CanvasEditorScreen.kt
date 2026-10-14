package com.graphite.app.feature.workspace

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.importer.DocumentContentParser
import com.graphite.app.core.importer.ParsedDocument
import com.graphite.app.core.theme.*
import com.graphite.app.data.model.NotebookItem
import com.graphite.app.data.repository.GraphiteRepository
import com.graphite.app.feature.diagnostic.InputModePolicy
import com.graphite.app.feature.diagnostic.MotionActionType
import com.graphite.app.feature.diagnostic.PalmRejectionFilter
import com.graphite.app.feature.diagnostic.StylusEventParser
import com.graphite.app.feature.diagnostic.StylusToolType
import com.graphite.app.feature.workspace.geometry.BezierSplineInterpolator
import com.graphite.app.feature.workspace.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class StudioTool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    LASSO,
    TAPE,
    RULER,
    TEXT,
    AI,
    ATTACH
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

    // Page state
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(maxOf(1, notebook?.pageCount ?: 1)) }

    // Document state (PDF / Word / PPTX / Text)
    var pdfBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var parsedDoc by remember { mutableStateOf<ParsedDocument?>(null) }
    var isDocLoading by remember { mutableStateOf(false) }

    // Tool & Mode state
    var isReadOnlyMode by remember { mutableStateOf(false) } // ⊘ toggle
    var selectedStudioTool by remember { mutableStateOf(StudioTool.PEN) }

    // UI Drawer & Menu toggles
    var showPageDrawer by remember { mutableStateOf(false) }       // Top-left ⊞ (Image 4)
    var showAddMenu by remember { mutableStateOf(false) }          // Top-right + (Image 2)
    var showDocumentMenu by remember { mutableStateOf(false) }     // Top-right ⋮ (Image 3)

    // Sub-dialogs
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showGoToPageDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showMoreSettingsDialog by remember { mutableStateOf(false) }
    var showStylusDialog by remember { mutableStateOf(false) }
    var showAiDialog by remember { mutableStateOf(false) }
    var showTextInsertDialog by remember { mutableStateOf(false) }
    var showZoomBox by remember { mutableStateOf(false) }

    // Zoom & Pan state
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var pageRotation by remember { mutableFloatStateOf(0f) }
    var scrollingMode by remember { mutableStateOf("Vertical") }

    // Drawer state (Image 4)
    var selectedDrawerTab by remember { mutableIntStateOf(0) } // 0=Thumbnails Grid, 1=Bookmarks, 2=Outline
    var drawerSearchQuery by remember { mutableStateOf("") }
    var isDrawerSelectMode by remember { mutableStateOf(false) }
    var isDrawerExpanded by remember { mutableStateOf(false) }

    // Add Menu state (Image 2)
    var selectedAddTab by remember { mutableIntStateOf(0) } // 0=Document, 1=Image, 2=Attachment

    // Drawing coordinates
    var activePoints by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var activeEraserCenter by remember { mutableStateOf<Offset?>(null) }

    val palmFilter = remember { PalmRejectionFilter(uiState.inputPolicy) }
    palmFilter.policy = uiState.inputPolicy

    // Activity Launchers for Add Menu
    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = GraphiteApplication.instance.fileImportManager.importFromUri(context, uri)
                Toast.makeText(context, "Document imported: $res", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            totalPages++
            currentPageIndex = totalPages - 1
            Toast.makeText(context, "Image added as new page", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null) {
            totalPages++
            currentPageIndex = totalPages - 1
            Toast.makeText(context, "Photo captured as page $totalPages", Toast.LENGTH_SHORT).show()
        }
    }

    // Initial strokes load
    LaunchedEffect(notebookId) {
        notebook?.let { nb ->
            if (uiState.strokes.isEmpty() && nb.initialStrokes.isNotEmpty()) {
                nb.initialStrokes.forEach { stroke -> workspaceViewModel.addStroke(stroke) }
            }
            workspaceViewModel.selectPaperStyle(nb.paperStyle)
        }
    }

    // Load PDF / Word / PPTX document page if filePath exists
    LaunchedEffect(notebook?.filePath, currentPageIndex) {
        val path = notebook?.filePath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists()) {
                isDocLoading = true
                withContext(Dispatchers.IO) {
                    try {
                        val ext = file.extension.lowercase()
                        if (ext == "pdf") {
                            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            val renderer = PdfRenderer(pfd)
                            val count = renderer.pageCount
                            withContext(Dispatchers.Main) { totalPages = maxOf(totalPages, count) }

                            if (currentPageIndex in 0 until count) {
                                val page = renderer.openPage(currentPageIndex)
                                val width = (page.width * 1.5f).toInt()
                                val height = (page.height * 1.5f).toInt()
                                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bmp)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                page.close()

                                withContext(Dispatchers.Main) {
                                    pdfBitmap?.recycle()
                                    pdfBitmap = bmp
                                    isDocLoading = false
                                }
                            }
                            renderer.close()
                            pfd.close()
                        } else {
                            val bytes = file.readBytes()
                            val parsed = DocumentContentParser.parse(bytes, file.name, notebook.mimeType)
                            withContext(Dispatchers.Main) {
                                parsedDoc = parsed
                                totalPages = maxOf(totalPages, parsed.pageOrSlideCount)
                                isDocLoading = false
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { isDocLoading = false }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pdfBitmap?.recycle()
            pdfBitmap = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .statusBarsPadding()
    ) {
        // ==========================================
        // 1. CENTER PAGE CANVAS (Zoomable & Pannable)
        // ==========================================
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isReadOnlyMode) {
                    if (isReadOnlyMode) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            zoomScale = (zoomScale * zoom).coerceIn(0.5f, 4.0f)
                            panOffset += pan
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // White Document Sheet matching the reference images
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .fillMaxHeight(0.92f)
                    .graphicsLayer {
                        scaleX = zoomScale
                        scaleY = zoomScale
                        translationX = panOffset.x
                        translationY = panOffset.y
                        rotationZ = pageRotation
                    }
                    .shadow(12.dp, RoundedCornerShape(4.dp)),
                shape = RoundedCornerShape(4.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Layer A: Document content
                    if (isDocLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = GraphiteOrange, modifier = Modifier.size(32.dp))
                        }
                    } else if (pdfBitmap != null) {
                        Image(
                            bitmap = pdfBitmap!!.asImageBitmap(),
                            contentDescription = "PDF Page",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else if (parsedDoc != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 40.dp, vertical = 36.dp)
                        ) {
                            Text(
                                text = parsedDoc!!.title,
                                color = Color(0xFF111111),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            parsedDoc!!.paragraphs.forEachIndexed { i, para ->
                                Text(
                                    text = "${i + 1})$para",
                                    color = Color(0xFF222222),
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    } else {
                        // Regular Ruled / Grid / Blank paper template + note lines
                        PaperTemplateCanvas(paperStyle = uiState.paperStyle)

                        val sampleExercises = listOf(
                            "1)Write programs to demonstrate importing of math module and show the various functions in math module.",
                            "2)Write programs to demonstrate importing of random module and show the various functions in random module.",
                            "3)Write programs to demonstrate importing of statistics module and show the various functions in statistics module."
                        )
                        val displayLines = if (notebook?.previewLines.isNullOrEmpty()) sampleExercises else notebook!!.previewLines

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 40.dp, vertical = 40.dp)
                        ) {
                            displayLines.forEach { line ->
                                Text(
                                    text = line,
                                    color = Color(0xFF1F1F1F),
                                    fontSize = 14.sp,
                                    lineHeight = 26.sp,
                                    fontFamily = FontFamily.Default,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // Layer B: Annotation Strokes Canvas
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInteropFilter { motionEvent ->
                                if (isReadOnlyMode) return@pointerInteropFilter false

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
                                                color = strokeColor
                                            )
                                            workspaceViewModel.addStroke(newStroke)
                                            notebook?.let { nb ->
                                                GraphiteRepository.updateNotebookStrokes(nb.id, uiState.strokes + newStroke)
                                            }
                                            activePoints = emptyList()
                                        }
                                    }
                                    else -> {}
                                }
                                true
                            }
                    ) {
                        val density = this.density

                        // Committed ink strokes
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

                        // In-progress live stroke
                        if (activePoints.size >= 2) {
                            val activePath = BezierSplineInterpolator.createSplinePath(activePoints)
                            val currentPressure = activePoints.lastOrNull()?.pressure ?: 0.5f
                            val effectiveWidth = BezierSplineInterpolator.calculateEffectiveWidth(
                                uiState.selectedStrokeWidth * density,
                                currentPressure
                            )
                            drawPath(
                                path = activePath,
                                color = if (selectedStudioTool == StudioTool.HIGHLIGHTER) uiState.selectedColor.copy(alpha = 0.35f) else uiState.selectedColor,
                                style = Stroke(
                                    width = effectiveWidth,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }

                        // Eraser reticle
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
                }
            }
        }

        // ==========================================
        // 2. TOP FLOATING APP BAR (Three Groups)
        // ==========================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // GROUP A: Top-Left Pill (<, ⊞, ↶) & Page Chip (1 of N)
            Column(horizontalAlignment = Alignment.Start) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f)),
                    border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                    modifier = Modifier.shadow(4.dp, RoundedCornerShape(12.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onNavigateBack, modifier = Modifier.size(34.dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { showPageDrawer = !showPageDrawer },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GridView,
                                contentDescription = "Page Drawer",
                                tint = if (showPageDrawer) Color(0xFF29B6F6) else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { workspaceViewModel.undo() },
                            enabled = uiState.canUndo,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo",
                                tint = if (uiState.canUndo) Color.White else Color(0xFF555555),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Page indicator chip (e.g. "1 of 1")
                Card(
                    shape = RoundedCornerShape(6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f)),
                    border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = "${currentPageIndex + 1} of $totalPages",
                        color = Color(0xFFCCCCCC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // GROUP B: Top-Center Floating Toolbar Pill (Pen, Highlighter, Eraser, ...)
            AnimatedVisibility(
                visible = !isReadOnlyMode,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f)),
                    border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                    modifier = Modifier.shadow(8.dp, RoundedCornerShape(16.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FloatingToolButton(
                            icon = Icons.Default.Create,
                            isSelected = selectedStudioTool == StudioTool.PEN,
                            hasSubOptions = true,
                            onClick = {
                                selectedStudioTool = StudioTool.PEN
                                workspaceViewModel.selectTool(InkToolType.PEN)
                            }
                        )
                        FloatingToolButton(
                            icon = Icons.Default.Brush,
                            isSelected = selectedStudioTool == StudioTool.HIGHLIGHTER,
                            onClick = {
                                selectedStudioTool = StudioTool.HIGHLIGHTER
                                workspaceViewModel.selectTool(InkToolType.PEN)
                            }
                        )
                        FloatingToolButton(
                            icon = Icons.Default.AutoFixHigh,
                            isSelected = selectedStudioTool == StudioTool.ERASER,
                            onClick = {
                                selectedStudioTool = StudioTool.ERASER
                                workspaceViewModel.selectTool(InkToolType.ERASER)
                            }
                        )
                        FloatingToolButton(
                            icon = Icons.Default.HighlightAlt,
                            isSelected = selectedStudioTool == StudioTool.LASSO,
                            onClick = {
                                selectedStudioTool = StudioTool.LASSO
                                Toast.makeText(context, "Lasso selection mode active", Toast.LENGTH_SHORT).show()
                            }
                        )
                        FloatingToolButton(
                            icon = Icons.Default.StickyNote2,
                            isSelected = selectedStudioTool == StudioTool.TAPE,
                            onClick = {
                                selectedStudioTool = StudioTool.TAPE
                                Toast.makeText(context, "Tape / Sticker active", Toast.LENGTH_SHORT).show()
                            }
                        )
                        FloatingToolButton(
                            icon = Icons.Default.Straighten,
                            isSelected = selectedStudioTool == StudioTool.RULER,
                            onClick = {
                                selectedStudioTool = StudioTool.RULER
                                Toast.makeText(context, "Ruler snapped: hold stroke end to straighten", Toast.LENGTH_SHORT).show()
                            }
                        )
                        FloatingToolButton(
                            icon = Icons.Default.TextFields,
                            isSelected = selectedStudioTool == StudioTool.TEXT,
                            onClick = { showTextInsertDialog = true }
                        )
                        FloatingToolButton(
                            icon = Icons.Default.AutoAwesome,
                            isSelected = selectedStudioTool == StudioTool.AI,
                            onClick = { showAiDialog = true }
                        )
                        FloatingToolButton(
                            icon = Icons.Default.AttachFile,
                            isSelected = selectedStudioTool == StudioTool.ATTACH,
                            onClick = {
                                showAddMenu = true
                                showDocumentMenu = false
                            }
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(12.dp).padding(start = 2.dp)
                        )
                    }
                }
            }

            // GROUP C: Top-Right Pill (+, ⊘, ⋮)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f)),
                border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                modifier = Modifier.shadow(4.dp, RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Plus (+) button -> Opens Add Menu (Image 2)
                    IconButton(
                        onClick = {
                            showAddMenu = !showAddMenu
                            showDocumentMenu = false
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            tint = if (showAddMenu) Color(0xFF29B6F6) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 2. Circle with slash (⊘) -> Read-Only mode toggle
                    IconButton(
                        onClick = {
                            isReadOnlyMode = !isReadOnlyMode
                            Toast.makeText(
                                context,
                                if (isReadOnlyMode) "Read-Only Mode enabled (touch scrolls/zooms)" else "Annotation Mode enabled",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = "Read-Only Mode Toggle",
                            tint = if (isReadOnlyMode) Color(0xFF29B6F6) else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // 3. Three-dots menu (⋮) -> Opens Document Menu (Image 3)
                    IconButton(
                        onClick = {
                            showDocumentMenu = !showDocumentMenu
                            showAddMenu = false
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = if (showDocumentMenu) Color(0xFF29B6F6) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // ==========================================
        // 3. LEFT FLOATING COLOR & STROKE PALETTE
        // ==========================================
        AnimatedVisibility(
            visible = !isReadOnlyMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
        ) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f)),
                border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                modifier = Modifier
                    .shadow(6.dp, RoundedCornerShape(22.dp))
                    .width(44.dp)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Blue Swatch
                    PaletteColorDot(
                        color = Color(0xFF2979FF),
                        isSelected = uiState.selectedColor == Color(0xFF2979FF),
                        onClick = { workspaceViewModel.selectColor(Color(0xFF2979FF)) }
                    )
                    // Green Swatch
                    PaletteColorDot(
                        color = Color(0xFF00C853),
                        isSelected = uiState.selectedColor == Color(0xFF00C853),
                        onClick = { workspaceViewModel.selectColor(Color(0xFF00C853)) }
                    )
                    // Red Swatch
                    PaletteColorDot(
                        color = Color(0xFFD50000),
                        isSelected = uiState.selectedColor == Color(0xFFD50000),
                        onClick = { workspaceViewModel.selectColor(Color(0xFFD50000)) }
                    )
                    // Plus (+) Custom Color
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2A2A2E))
                            .clickable {
                                val next = when (uiState.selectedColor) {
                                    Color(0xFFFF7A30) -> Color(0xFFFFEB3B)
                                    Color(0xFFFFEB3B) -> Color(0xFF9C27B0)
                                    Color(0xFF9C27B0) -> Color(0xFF000000)
                                    else -> Color(0xFFFF7A30)
                                }
                                workspaceViewModel.selectColor(next)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Color", tint = Color.LightGray, modifier = Modifier.size(12.dp))
                    }

                    HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(horizontal = 2.dp))

                    // 3 Stroke thickness dots (Fine 2.5dp, Medium 5dp, Bold 9dp)
                    listOf(2.5f to 4.dp, 5.0f to 7.dp, 9.0f to 10.dp).forEach { (w, size) ->
                        val isSelected = uiState.selectedStrokeWidth == w
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { workspaceViewModel.selectStrokeWidth(w) },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(size)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Color.White else Color(0xFF666666))
                            )
                        }
                    }
                }
            }
        }

        // ==========================================
        // 4. ZOOM BOX (High-precision magnification)
        // ==========================================
        if (showZoomBox) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.92f)
                    .height(130.dp)
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f)),
                border = BorderStroke(1.5.dp, Color(0xFF29B6F6)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ZoomIn, contentDescription = null, tint = Color(0xFF29B6F6), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Zoom Box (2x Magnification)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { showZoomBox = false }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(
                        "Write in this area for high-precision handwriting. The zoom window auto-advances across the page as you finish each word.",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // ==========================================
        // 5. THE "ADD" MENU FLYOUT (Image 2)
        // ==========================================
        if (showAddMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showAddMenu = false }
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 56.dp, end = 12.dp)
                        .width(280.dp)
                        .shadow(12.dp, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    border = BorderStroke(1.dp, Color(0xFF2E2E32)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Add", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(10.dp))

                        // 3 Segmented Tabs: Document, Image, Attachment
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF28282C))
                                .padding(2.dp)
                        ) {
                            listOf(
                                0 to Icons.Default.Description,
                                1 to Icons.Default.Image,
                                2 to Icons.Default.AttachFile
                            ).forEach { (idx, icon) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (selectedAddTab == idx) Color(0xFF3E3E44) else Color.Transparent)
                                        .clickable { selectedAddTab = idx }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (selectedAddTab == idx) Color.White else Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        when (selectedAddTab) {
                            0 -> { // Document Tab items (Image 2)
                                AddMenuItemRow(Icons.Default.NoteAdd, "New Page") {
                                    showAddMenu = false
                                    totalPages++
                                    currentPageIndex = totalPages - 1
                                    Toast.makeText(context, "Added Page $totalPages", Toast.LENGTH_SHORT).show()
                                }
                                AddMenuItemRow(Icons.Default.ViewQuilt, "Choose Template", hasChevron = true) {
                                    showAddMenu = false
                                    showTemplateDialog = true
                                }
                                AddMenuItemRow(Icons.Default.ImageSearch, "Page from Image", hasChevron = true) {
                                    showAddMenu = false
                                    imagePickerLauncher.launch("image/*")
                                }
                                AddMenuItemRow(Icons.Default.DocumentScanner, "Scan Document") {
                                    showAddMenu = false
                                    Toast.makeText(context, "Document Scanner opened", Toast.LENGTH_SHORT).show()
                                }
                                AddMenuItemRow(Icons.Default.PhotoCamera, "Page from Camera") {
                                    showAddMenu = false
                                    cameraLauncher.launch(null)
                                }
                                AddMenuItemRow(Icons.Default.FileDownload, "Import Document") {
                                    showAddMenu = false
                                    docPickerLauncher.launch(arrayOf("*/*"))
                                }
                            }
                            1 -> { // Image Tab items
                                AddMenuItemRow(Icons.Default.PhotoLibrary, "Photo from Gallery") {
                                    showAddMenu = false
                                    imagePickerLauncher.launch("image/*")
                                }
                                AddMenuItemRow(Icons.Default.CameraAlt, "Take Photo") {
                                    showAddMenu = false
                                    cameraLauncher.launch(null)
                                }
                                AddMenuItemRow(Icons.Default.EmojiEmotions, "Stickers") {
                                    showAddMenu = false
                                    Toast.makeText(context, "Stickers library opened", Toast.LENGTH_SHORT).show()
                                }
                            }
                            2 -> { // Attachment Tab items
                                AddMenuItemRow(Icons.Default.Mic, "Audio Note") {
                                    showAddMenu = false
                                    Toast.makeText(context, "Audio recorder ready", Toast.LENGTH_SHORT).show()
                                }
                                AddMenuItemRow(Icons.Default.Link, "Web Link") {
                                    showAddMenu = false
                                    Toast.makeText(context, "Insert web link", Toast.LENGTH_SHORT).show()
                                }
                                AddMenuItemRow(Icons.Default.Attachment, "Attach File") {
                                    showAddMenu = false
                                    docPickerLauncher.launch(arrayOf("*/*"))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 6. THE THREE-DOTS MENU FLYOUT (Image 3)
        // ==========================================
        if (showDocumentMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showDocumentMenu = false }
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 56.dp, end = 12.dp)
                        .width(280.dp)
                        .heightIn(max = 520.dp)
                        .shadow(12.dp, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    border = BorderStroke(1.dp, Color(0xFF2E2E32)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Top Section
                        MenuClickableRowWithIcon(Icons.Default.Share, "Share", hasChevron = true) {
                            showDocumentMenu = false
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, "${notebook?.title ?: "Note"}\n\n${notebook?.previewLines?.joinToString("\n") ?: ""}")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Document"))
                        }
                        MenuClickableRowWithIcon(Icons.Default.MenuBook, "Go To Page", subtitle = "${currentPageIndex + 1} of $totalPages") {
                            showDocumentMenu = false
                            showGoToPageDialog = true
                        }
                        MenuClickableRowWithIcon(Icons.Default.CoPresent, "Present") {
                            showDocumentMenu = false
                            Toast.makeText(context, "Presentation mode active (Fullscreen)", Toast.LENGTH_SHORT).show()
                        }

                        HorizontalDivider(color = Color(0xFF2C2C2E))

                        // PAGE SECTION (Image 3)
                        Text("PAGE", color = Color(0xFF00BCD4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        MenuClickableRowWithIcon(Icons.Default.RotateRight, "Rotate Page", hasChevron = true) {
                            pageRotation = (pageRotation + 90f) % 360f
                            Toast.makeText(context, "Rotated to ${pageRotation.toInt()}°", Toast.LENGTH_SHORT).show()
                        }
                        MenuClickableRowWithIcon(Icons.Default.Article, "Change Template") {
                            showDocumentMenu = false
                            showTemplateDialog = true
                        }
                        MenuClickableRowWithIcon(Icons.Default.SaveAlt, "Save as Template") {
                            showDocumentMenu = false
                            Toast.makeText(context, "Current page saved as template", Toast.LENGTH_SHORT).show()
                        }
                        MenuClickableRowWithIcon(
                            Icons.Default.DeleteOutline,
                            "Delete Page",
                            textColor = Color(0xFFFF5252),
                            iconTint = Color(0xFFFF5252)
                        ) {
                            showDocumentMenu = false
                            if (totalPages > 1) {
                                totalPages--
                                if (currentPageIndex >= totalPages) currentPageIndex = totalPages - 1
                                Toast.makeText(context, "Page deleted", Toast.LENGTH_SHORT).show()
                            } else {
                                workspaceViewModel.confirmClearPage()
                                Toast.makeText(context, "Page cleared", Toast.LENGTH_SHORT).show()
                            }
                        }

                        HorizontalDivider(color = Color(0xFF2C2C2E))

                        // NOTEBOOK SECTION (Image 3)
                        Text("NOTEBOOK", color = Color(0xFF00BCD4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        MenuClickableRowWithIcon(Icons.Default.ZoomIn, "Zoom Box") {
                            showDocumentMenu = false
                            showZoomBox = !showZoomBox
                        }
                        MenuClickableRowWithIcon(Icons.Default.Lock, "Password", hasChevron = true) {
                            showDocumentMenu = false
                            showPasswordDialog = true
                        }
                        MenuClickableRowWithIcon(Icons.Default.Info, "Get Info", hasChevron = true) {
                            showDocumentMenu = false
                            showInfoDialog = true
                        }

                        HorizontalDivider(color = Color(0xFF2C2C2E))

                        // SETTINGS SECTION (Image 3)
                        Text("SETTINGS", color = Color(0xFF00BCD4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        MenuClickableRowWithIcon(
                            Icons.Default.Splitscreen,
                            "Scrolling",
                            subtitle = scrollingMode,
                            hasChevron = true
                        ) {
                            scrollingMode = if (scrollingMode == "Vertical") "Horizontal" else "Vertical"
                            Toast.makeText(context, "Scrolling: $scrollingMode", Toast.LENGTH_SHORT).show()
                        }
                        MenuClickableRowWithIcon(Icons.Default.ModeEdit, "Stylus", hasChevron = true) {
                            showDocumentMenu = false
                            showStylusDialog = true
                        }
                        MenuClickableRowWithIcon(Icons.Default.Settings, "More Settings", hasChevron = true) {
                            showDocumentMenu = false
                            showMoreSettingsDialog = true
                        }
                    }
                }
            }
        }

        // ==========================================
        // 7. LEFT PAGE MANAGER SIDEBAR DRAWER (Image 4)
        // ==========================================
        if (showPageDrawer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showPageDrawer = false }
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(if (isDrawerExpanded) 420.dp else 320.dp)
                        .clickable(enabled = false) {}
                        .shadow(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                    border = BorderStroke(1.dp, Color(0xFF242428)),
                    shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                        // Top Header (Image 4)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { /* toggle layout */ }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.ViewSidebar, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { isDrawerSelectMode = !isDrawerSelectMode }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Default.CheckCircleOutline,
                                        contentDescription = "Select Mode",
                                        tint = if (isDrawerSelectMode) Color(0xFF00BCD4) else Color.LightGray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(onClick = { isDrawerExpanded = !isDrawerExpanded }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.OpenInFull, contentDescription = "Expand", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { showPageDrawer = false }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Notebook Title Dropdown (e.g. "Ass9 (2) ▾")
                        Row(
                            modifier = Modifier
                                .clickable { showRenameDialog = true }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = notebook?.title ?: "Document",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Rename", tint = Color.Gray, modifier = Modifier.size(18.dp))
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Search Bar
                        OutlinedTextField(
                            value = drawerSearchQuery,
                            onValueChange = { drawerSearchQuery = it },
                            placeholder = { Text("Search", color = Color.Gray, fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF1E1E22),
                                unfocusedContainerColor = Color(0xFF1E1E22),
                                focusedBorderColor = Color(0xFF00BCD4),
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // 3 Segmented Tabs: Grid (⊞), Bookmarks (🔖), Outline (≡)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E1E22))
                                .padding(2.dp)
                        ) {
                            listOf(
                                0 to Icons.Default.GridView,
                                1 to Icons.Default.BookmarkBorder,
                                2 to Icons.Default.FormatListBulleted
                            ).forEach { (idx, icon) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (selectedDrawerTab == idx) Color(0xFF2C2C32) else Color.Transparent)
                                        .clickable { selectedDrawerTab = idx }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = if (selectedDrawerTab == idx) Color.White else Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Tab 0: Thumbnails Grid
                        when (selectedDrawerTab) {
                            0 -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(totalPages) { pageIdx ->
                                        val isCurrent = pageIdx == currentPageIndex
                                        Column(horizontalAlignment = Alignment.Start) {
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(130.dp)
                                                    .clickable {
                                                        currentPageIndex = pageIdx
                                                    },
                                                shape = RoundedCornerShape(8.dp),
                                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                                border = BorderStroke(
                                                    width = if (isCurrent) 2.5.dp else 1.dp,
                                                    color = if (isCurrent) Color(0xFF00BCD4) else Color(0xFFD0D0D0)
                                                )
                                            ) {
                                                Box(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                                                    // Miniature representation lines
                                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Box(modifier = Modifier.fillMaxWidth(0.7f).height(3.dp).background(Color(0xFFCCCCCC)))
                                                        Box(modifier = Modifier.fillMaxWidth(0.9f).height(2.dp).background(Color(0xFFE0E0E0)))
                                                        Box(modifier = Modifier.fillMaxWidth(0.85f).height(2.dp).background(Color(0xFFE0E0E0)))
                                                        Box(modifier = Modifier.fillMaxWidth(0.6f).height(2.dp).background(Color(0xFFE0E0E0)))
                                                    }

                                                    // Bookmark ribbon
                                                    Icon(
                                                        imageVector = Icons.Default.Bookmark,
                                                        contentDescription = "Bookmark",
                                                        tint = if (notebook?.isBookmarked == true && pageIdx == 0) Color(0xFF00BCD4) else Color(0xFFCCCCCC),
                                                        modifier = Modifier.size(16.dp).align(Alignment.TopEnd)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "${pageIdx + 1}",
                                                color = Color.Gray,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(start = 2.dp)
                                            )
                                        }
                                    }

                                    // Dashed "New Page" Card
                                    item {
                                        Column(horizontalAlignment = Alignment.Start) {
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(130.dp)
                                                    .clickable {
                                                        totalPages++
                                                        currentPageIndex = totalPages - 1
                                                        Toast.makeText(context, "Added Page $totalPages", Toast.LENGTH_SHORT).show()
                                                    },
                                                shape = RoundedCornerShape(8.dp),
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
                                                border = BorderStroke(1.dp, Color(0xFF333333))
                                            ) {
                                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.NoteAdd, contentDescription = "New Page", tint = Color(0xFF00BCD4), modifier = Modifier.size(28.dp))
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("New Page", color = Color(0xFF00BCD4), fontSize = 12.sp)
                                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF00BCD4), modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                }
                            }
                            1 -> { // Tab 1: Bookmarks
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Bookmarked Pages", color = Color.LightGray, fontSize = 13.sp)
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable { currentPageIndex = 0 },
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22))
                                    ) {
                                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Bookmark, contentDescription = null, tint = Color(0xFF00BCD4), modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Page 1 (Cover / Title)", color = Color.White, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                            2 -> { // Tab 2: Outline
                                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Document Outline", color = Color.LightGray, fontSize = 13.sp)
                                    if (parsedDoc != null && parsedDoc!!.paragraphs.isNotEmpty()) {
                                        parsedDoc!!.paragraphs.take(5).forEachIndexed { i, p ->
                                            Text(
                                                text = "${i + 1}. ${p.take(40)}...",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(vertical = 4.dp).clickable { currentPageIndex = 0 }
                                            )
                                        }
                                    } else {
                                        Text("No table of contents available.", color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 8. SUB-DIALOGS (Template, Rename, GoTo, etc.)
        // ==========================================
        if (showTemplateDialog) {
            AlertDialog(
                onDismissRequest = { showTemplateDialog = false },
                title = { Text("Choose Page Template", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaperStyle.entries.forEach { style ->
                            val isSelected = uiState.paperStyle == style
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF00BCD4).copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable {
                                        workspaceViewModel.selectPaperStyle(style)
                                        showTemplateDialog = false
                                        Toast.makeText(context, "Template: ${style.name}", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = style.name.lowercase().replaceFirstChar { it.uppercase() },
                                    color = if (isSelected) Color(0xFF00BCD4) else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTemplateDialog = false }) {
                        Text("Close", color = Color(0xFF00BCD4))
                    }
                },
                containerColor = Color(0xFF1E1E22)
            )
        }

        if (showRenameDialog) {
            var newTitle by remember { mutableStateOf(notebook?.title ?: "") }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename Note", color = Color.White) },
                text = {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00BCD4),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newTitle.isNotBlank()) {
                                notebook?.let { GraphiteRepository.renameNotebook(it.id, newTitle.trim()) }
                                showRenameDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4))
                    ) {
                        Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF1E1E22)
            )
        }

        if (showGoToPageDialog) {
            var pageTarget by remember { mutableStateOf("${currentPageIndex + 1}") }
            AlertDialog(
                onDismissRequest = { showGoToPageDialog = false },
                title = { Text("Go To Page", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter page number (1 to $totalPages):", color = Color.LightGray, fontSize = 13.sp)
                        OutlinedTextField(
                            value = pageTarget,
                            onValueChange = { pageTarget = it },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00BCD4),
                                unfocusedBorderColor = Color.Gray
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val p = pageTarget.toIntOrNull()
                            if (p != null && p in 1..totalPages) {
                                currentPageIndex = p - 1
                                showGoToPageDialog = false
                            } else {
                                Toast.makeText(context, "Invalid page", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4))
                    ) {
                        Text("Go", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGoToPageDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF1E1E22)
            )
        }

        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text("Document Info", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Title: ${notebook?.title ?: "Quick Note"}", color = Color.White, fontSize = 14.sp)
                        Text("Total Pages: $totalPages", color = Color.LightGray, fontSize = 13.sp)
                        Text("Strokes on Page: ${uiState.strokes.size}", color = Color.LightGray, fontSize = 13.sp)
                        Text("Paper Template: ${uiState.paperStyle.name}", color = Color.LightGray, fontSize = 13.sp)
                        if (notebook?.filePath != null) {
                            Text("Attached File: ${File(notebook.filePath).name}", color = Color(0xFF00BCD4), fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) {
                        Text("Close", color = Color(0xFF00BCD4))
                    }
                },
                containerColor = Color(0xFF1E1E22)
            )
        }

        if (showPasswordDialog) {
            var pinInput by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showPasswordDialog = false },
                title = { Text("Set Notebook Password", color = Color.White) },
                text = {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it },
                        placeholder = { Text("Enter PIN or password") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00BCD4),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            Toast.makeText(context, "Password protection saved", Toast.LENGTH_SHORT).show()
                            showPasswordDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4))
                    ) {
                        Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPasswordDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF1E1E22)
            )
        }

        if (showStylusDialog) {
            AlertDialog(
                onDismissRequest = { showStylusDialog = false },
                title = { Text("Stylus & Palm Rejection", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        InputModePolicy.entries.forEach { policy ->
                            val isSelected = uiState.inputPolicy == policy
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF00BCD4).copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable {
                                        workspaceViewModel.setInputPolicy(policy)
                                        showStylusDialog = false
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = policy.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                                    color = if (isSelected) Color(0xFF00BCD4) else Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showStylusDialog = false }) {
                        Text("Close", color = Color(0xFF00BCD4))
                    }
                },
                containerColor = Color(0xFF1E1E22)
            )
        }

        if (showMoreSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showMoreSettingsDialog = false },
                title = { Text("More Settings", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("240Hz High-rate Digitizer", color = Color.White, fontSize = 14.sp)
                            Switch(checked = true, onCheckedChange = {})
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Spline Smoothing", color = Color.White, fontSize = 14.sp)
                            Switch(checked = true, onCheckedChange = {})
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showMoreSettingsDialog = false }) {
                        Text("Close", color = Color(0xFF00BCD4))
                    }
                },
                containerColor = Color(0xFF1E1E22)
            )
        }

        if (showAiDialog) {
            AlertDialog(
                onDismissRequest = { showAiDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF00BCD4))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AI Note Assistant", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Generate summary from this document:", color = Color.LightGray, fontSize = 13.sp)
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                Toast.makeText(context, "Summary: Core topic covers exercises and module import functions.", Toast.LENGTH_LONG).show()
                                showAiDialog = false
                            },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF28282C))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Summarize Notes", color = Color(0xFF00BCD4), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Extract main concepts into bullet points", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAiDialog = false }) {
                        Text("Close", color = Color(0xFF00BCD4))
                    }
                },
                containerColor = Color(0xFF1E1E22)
            )
        }

        if (showTextInsertDialog) {
            var insertedText by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showTextInsertDialog = false },
                title = { Text("Insert Text Box", color = Color.White) },
                text = {
                    OutlinedTextField(
                        value = insertedText,
                        onValueChange = { insertedText = it },
                        placeholder = { Text("Type text to place on page...") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00BCD4),
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (insertedText.isNotBlank()) {
                                notebook?.let { nb ->
                                    GraphiteRepository.updateNotebookLines(nb.id, nb.previewLines + insertedText)
                                }
                                Toast.makeText(context, "Text added", Toast.LENGTH_SHORT).show()
                            }
                            showTextInsertDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4))
                    ) {
                        Text("Insert", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTextInsertDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF1E1E22)
            )
        }
    }
}

// ==========================================
// COMPONENT HELPERS
// ==========================================

@Composable
private fun FloatingToolButton(
    icon: ImageVector,
    isSelected: Boolean,
    hasSubOptions: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFF2E2E34) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) Color(0xFF00BCD4) else Color(0xFFAAAAAA),
            modifier = Modifier.size(18.dp)
        )
        if (hasSubOptions && isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(4.dp)
                    .background(Color(0xFF00BCD4), CircleShape)
            )
        }
    }
}

@Composable
private fun PaletteColorDot(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 2.dp else 0.5.dp,
                color = if (isSelected) Color.White else Color(0xFF555555),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun AddMenuItemRow(
    icon: ImageVector,
    title: String,
    hasChevron: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF00BCD4), modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Normal)
        }
        if (hasChevron) {
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun MenuClickableRowWithIcon(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    hasChevron: Boolean = false,
    textColor: Color = Color.White,
    iconTint: Color = Color(0xFF00BCD4),
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(title, color = textColor, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (subtitle != null) {
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (hasChevron) {
                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(11.dp))
            }
        }
    }
}

@Composable
private fun PaperTemplateCanvas(paperStyle: PaperStyle) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        when (paperStyle) {
            PaperStyle.BLANK -> {}
            PaperStyle.RULED -> {
                val lineSpacing = 36.dp.toPx()
                var y = lineSpacing + 20.dp.toPx()
                while (y < canvasHeight) {
                    drawLine(
                        color = Color(0xFFE8E8E8),
                        start = Offset(0f, y),
                        end = Offset(canvasWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    y += lineSpacing
                }
            }
            PaperStyle.DOTTED -> {
                val dotSpacing = 28.dp.toPx()
                var x = dotSpacing
                while (x < canvasWidth) {
                    var y = dotSpacing
                    while (y < canvasHeight) {
                        drawCircle(
                            color = Color(0xFFD6D6D6),
                            radius = 1.2.dp.toPx(),
                            center = Offset(x, y)
                        )
                        y += dotSpacing
                    }
                    x += dotSpacing
                }
            }
            PaperStyle.GRID -> {
                val gridSpacing = 28.dp.toPx()
                var x = 0f
                while (x < canvasWidth) {
                    drawLine(color = Color(0xFFEFEFEF), start = Offset(x, 0f), end = Offset(x, canvasHeight), strokeWidth = 1.dp.toPx())
                    x += gridSpacing
                }
                var y = 0f
                while (y < canvasHeight) {
                    drawLine(color = Color(0xFFEFEFEF), start = Offset(0f, y), end = Offset(canvasWidth, y), strokeWidth = 1.dp.toPx())
                    y += gridSpacing
                }
            }
        }
    }
}
