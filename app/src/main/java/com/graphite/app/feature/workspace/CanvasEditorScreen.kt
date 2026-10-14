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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.hypot
import kotlin.math.roundToInt
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
    LASSO
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
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    // Page state
    var totalPages by remember { mutableIntStateOf(maxOf(1, notebook?.pageCount ?: 1)) }
    val listState = rememberLazyListState()
    val currentPageIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, totalPages - 1) }
    }

    // Document state (PDF / Word / PPTX / Text)
    val pdfBitmaps = remember { mutableStateMapOf<Int, Bitmap>() }
    val pageThumbnails = remember { mutableStateMapOf<Int, Bitmap>() }
    var parsedDoc by remember { mutableStateOf<ParsedDocument?>(null) }
    var pdfPageCount by remember { mutableIntStateOf(0) }
    var pdfAspectRatio by remember { mutableFloatStateOf(1.414f) }

    // Tool & Mode state
    var isReadOnlyMode by remember { mutableStateOf(false) } // ⊘ button
    var allowFingerDrawing by remember { mutableStateOf(false) } // Hand drawing toggle
    var selectedStudioTool by remember { mutableStateOf(StudioTool.PEN) }

    // UI Drawer & Menu toggles
    var showPageDrawer by remember { mutableStateOf(false) }       // Top-left ⊞ (Image 4)
    var showAddMenu by remember { mutableStateOf(false) }          // Top-right + (Image 2)
    var showDocumentMenu by remember { mutableStateOf(false) }     // Top-right ⋮ (Image 3)
    var showColorPaletteDialog by remember { mutableStateOf(false) } // Left palette +
    var showStrokeWidthDialog by remember { mutableStateOf(false) } // Left palette size slider

    // Sub-dialogs
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showGoToPageDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showMoreSettingsDialog by remember { mutableStateOf(false) }
    var showStylusDialog by remember { mutableStateOf(false) }
    var showZoomBox by remember { mutableStateOf(false) }

    // Zoom & Pan state
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var pageRotation by remember { mutableFloatStateOf(0f) }
    var scrollingMode by remember { mutableStateOf("Vertical") }
    
    // Dedicated two-finger gesture tracking (pinch vs two-finger scroll)
    var pinchStartDist by remember { mutableFloatStateOf(0f) }
    var lastPinchDist by remember { mutableFloatStateOf(0f) }
    var twoFingerStartY by remember { mutableFloatStateOf(0f) }
    var lastTwoFingerY by remember { mutableFloatStateOf(0f) }
    var twoFingerStartX by remember { mutableFloatStateOf(0f) }
    var lastTwoFingerX by remember { mutableFloatStateOf(0f) }
    var isTwoFingerActive by remember { mutableStateOf(false) }
    var twoFingerGestureMode by remember { mutableIntStateOf(0) } // 0=Undecided, 1=Zooming, 2=Scrolling

    // Drawer state (Image 4)
    var selectedDrawerTab by remember { mutableIntStateOf(0) } // 0=Grid, 1=Bookmarks, 2=Outline
    var drawerSearchQuery by remember { mutableStateOf("") }
    var isDrawerSelectMode by remember { mutableStateOf(false) }
    var isDrawerExpanded by remember { mutableStateOf(false) }

    // Add Menu state (Image 2)
    var selectedAddTab by remember { mutableIntStateOf(0) } // 0=Document, 1=Image, 2=Attachment

    // Drawing state
    var activePageForStroke by remember { mutableIntStateOf(0) }
    var activePoints by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var activeEraserCenter by remember { mutableStateOf<Offset?>(null) }

    // Lasso selection state
    var lassoPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var selectedStrokeIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var lassoBounds by remember { mutableStateOf<Rect?>(null) }

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
            scope.launch { listState.animateScrollToItem(totalPages - 1) }
            Toast.makeText(context, "Image added as new page", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null) {
            totalPages++
            scope.launch { listState.animateScrollToItem(totalPages - 1) }
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
    LaunchedEffect(notebook?.filePath) {
        val path = notebook?.filePath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists()) {
                withContext(Dispatchers.IO) {
                    try {
                        val ext = file.extension.lowercase()
                        if (ext == "pdf") {
                            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            val renderer = PdfRenderer(pfd)
                            val count = renderer.pageCount
                            withContext(Dispatchers.Main) {
                                pdfPageCount = count
                                totalPages = maxOf(totalPages, count)
                            }

                            // Render first few pages immediately for instant sharp viewing
                            for (p in 0 until minOf(count, 5)) {
                                val page = renderer.openPage(p)
                                val w = (page.width * 1.5f).toInt()
                                val h = (page.height * 1.5f).toInt()
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bmp)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val ar = h.toFloat() / w.toFloat()
                                page.close()

                                withContext(Dispatchers.Main) {
                                    pdfBitmaps[p] = bmp
                                    pdfAspectRatio = ar
                                }
                            }

                            // Pre-render small thumbnails for all pages in Page Drawer
                            for (p in 0 until count) {
                                val page = renderer.openPage(p)
                                val thumbW = 160
                                val thumbH = ((thumbW.toFloat() / page.width) * page.height).toInt().coerceIn(180, 240)
                                val thumbBmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(thumbBmp)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                page.render(thumbBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                page.close()

                                withContext(Dispatchers.Main) {
                                    pageThumbnails[p] = thumbBmp
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
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // Lazy load on-demand PDF page when user scrolls to it
    LaunchedEffect(currentPageIndex) {
        val path = notebook?.filePath
        if (!path.isNullOrBlank() && File(path).extension.equals("pdf", ignoreCase = true)) {
            val file = File(path)
            if (file.exists() && currentPageIndex < pdfPageCount && !pdfBitmaps.containsKey(currentPageIndex)) {
                withContext(Dispatchers.IO) {
                    try {
                        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(pfd)
                        if (currentPageIndex < renderer.pageCount) {
                            val page = renderer.openPage(currentPageIndex)
                            val w = (page.width * 1.5f).toInt()
                            val h = (page.height * 1.5f).toInt()
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bmp)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()

                            withContext(Dispatchers.Main) {
                                pdfBitmaps[currentPageIndex] = bmp
                            }
                        }
                        renderer.close()
                        pfd.close()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // Ensure thumbnails are rendered whenever Page Drawer is opened
    LaunchedEffect(showPageDrawer) {
        if (showPageDrawer && pageThumbnails.isEmpty() && !notebook?.filePath.isNullOrBlank()) {
            val path = notebook?.filePath ?: return@LaunchedEffect
            val file = File(path)
            if (file.exists() && file.extension.equals("pdf", ignoreCase = true)) {
                withContext(Dispatchers.IO) {
                    try {
                        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(pfd)
                        val count = renderer.pageCount
                        for (p in 0 until count) {
                            val page = renderer.openPage(p)
                            val thumbW = 160
                            val thumbH = ((thumbW.toFloat() / page.width) * page.height).toInt().coerceIn(180, 240)
                            val thumbBmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(thumbBmp)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            page.render(thumbBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            withContext(Dispatchers.Main) {
                                pageThumbnails[p] = thumbBmp
                            }
                        }
                        renderer.close()
                        pfd.close()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pdfBitmaps.values.forEach { it.recycle() }
            pdfBitmaps.clear()
            pageThumbnails.values.forEach { it.recycle() }
            pageThumbnails.clear()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .statusBarsPadding()
    ) {
        // ==========================================
        // 1. CONTINUOUS MULTI-PAGE DOCUMENT VIEWPORT
        // ==========================================
        val basePageWidth = (screenWidth - 24.dp).coerceAtLeast(320.dp)
        val scaledPageWidth = basePageWidth * zoomScale
        val scaledPageHeight = scaledPageWidth * pdfAspectRatio
        val maxPanX = with(density) {
            ((scaledPageWidth - screenWidth).toPx() / 2f).coerceAtLeast(0f)
        }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 58.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(totalPages) { pageIdx ->
                    // A single page sheet inside the vertical continuous document
                    Card(
                        modifier = Modifier
                            .offset { IntOffset(panX.roundToInt(), 0) }
                            .width(scaledPageWidth)
                            .height(scaledPageHeight)
                            .shadow(6.dp, RoundedCornerShape(3.dp)),
                        shape = RoundedCornerShape(3.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Layer A: Document content
                            val pageBmp = pdfBitmaps[pageIdx]
                            if (pageBmp != null) {
                                Image(
                                    bitmap = pageBmp.asImageBitmap(),
                                    contentDescription = "PDF Page ${pageIdx + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else if (pageIdx < pdfPageCount) {
                                // PDF page loading placeholder
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Color(0xFF00BCD4), modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                                }
                            } else if (parsedDoc != null) {
                                // Word / PPTX text
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 36.dp, vertical = 32.dp)
                                ) {
                                    Text(
                                        text = parsedDoc!!.title,
                                        color = Color(0xFF111111),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    parsedDoc!!.paragraphs.forEachIndexed { i, para ->
                                        Text(
                                            text = "${i + 1})$para",
                                            color = Color(0xFF222222),
                                            fontSize = 13.sp,
                                            lineHeight = 20.sp,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            } else {
                                // Notebook Paper Template (Ruled, Grid, Dotted, Blank)
                                PaperTemplateCanvas(paperStyle = uiState.paperStyle)

                                val sampleExercises = listOf(
                                    "1)Write programs to demonstrate importing of math module and show the various functions in math module.",
                                    "2)Write programs to demonstrate importing of random module and show the various functions in random module.",
                                    "3)Write programs to demonstrate importing of statistics module and show the various functions in statistics module."
                                )
                                val displayLines = if (notebook?.previewLines.isNullOrEmpty()) sampleExercises else notebook!!.previewLines

                                if (pageIdx == 0) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 36.dp, vertical = 36.dp)
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
                            }

                            // Layer B: Ink Strokes Canvas
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInteropFilter { motionEvent ->
                                        val pointerCount = motionEvent.pointerCount
                                        val action = motionEvent.actionMasked

                                        // Check if a hardware stylus is in contact
                                        val hasHardwareStylus = (0 until pointerCount).any { idx ->
                                            val t = motionEvent.getToolType(idx)
                                            t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
                                        }

                                        // ================================================================
                                        // CASE A: TWO (OR MORE) FINGERS -> ZOOM OR TWO-FINGER SCROLL ONLY
                                        // Page scrolling ONLY works with two finger swipe.
                                        // When zooming in or out, pages will NEVER scroll!
                                        // ================================================================
                                        if (pointerCount >= 2 && !hasHardwareStylus) {
                                            activePoints = emptyList()
                                            lassoPoints = emptyList()
                                            activeEraserCenter = null

                                            val p0x = motionEvent.getX(0)
                                            val p0y = motionEvent.getY(0)
                                            val p1x = motionEvent.getX(1)
                                            val p1y = motionEvent.getY(1)
                                            val curDist = kotlin.math.hypot((p1x - p0x).toDouble(), (p1y - p0y).toDouble()).toFloat()
                                            val curCenterY = (p0y + p1y) / 2f
                                            val curCenterX = (p0x + p1x) / 2f

                                            when (action) {
                                                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                                                    isTwoFingerActive = true
                                                    twoFingerGestureMode = 0 // Undecided
                                                    pinchStartDist = curDist
                                                    lastPinchDist = curDist
                                                    twoFingerStartY = curCenterY
                                                    lastTwoFingerY = curCenterY
                                                    twoFingerStartX = curCenterX
                                                    lastTwoFingerX = curCenterX
                                                }
                                                MotionEvent.ACTION_MOVE -> {
                                                    isTwoFingerActive = true
                                                    val distDelta = kotlin.math.abs(curDist - pinchStartDist)
                                                    val yDelta = kotlin.math.abs(curCenterY - twoFingerStartY)

                                                    if (twoFingerGestureMode == 0) {
                                                        if (distDelta > 18f) {
                                                            twoFingerGestureMode = 1 // PINCH ZOOM
                                                        } else if (yDelta > 14f) {
                                                            twoFingerGestureMode = 2 // TWO-FINGER SCROLL
                                                        }
                                                    }

                                                    if (twoFingerGestureMode == 1) {
                                                        // ZOOMING: do NOT scroll listState while zooming!
                                                        if (lastPinchDist > 10f && curDist > 10f) {
                                                            val factor = curDist / lastPinchDist
                                                            if (factor in 0.6f..1.6f) {
                                                                zoomScale = (zoomScale * factor).coerceIn(0.25f, 4.0f)
                                                            }
                                                        }
                                                        lastPinchDist = curDist
                                                    } else if (twoFingerGestureMode == 2) {
                                                        // SCROLLING: Two finger vertical swipe
                                                        val dy = curCenterY - lastTwoFingerY
                                                        lastTwoFingerY = curCenterY
                                                        listState.dispatchRawDelta(dy)

                                                        val dx = curCenterX - lastTwoFingerX
                                                        lastTwoFingerX = curCenterX
                                                        if (zoomScale > 1f) {
                                                            panX = (panX + dx).coerceIn(-maxPanX, maxPanX)
                                                        }
                                                    }
                                                }
                                                MotionEvent.ACTION_POINTER_UP -> {}
                                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                                    isTwoFingerActive = false
                                                    twoFingerGestureMode = 0
                                                }
                                            }
                                            return@pointerInteropFilter true
                                        }

                                        // Ignore trailing finger release after a two-finger gesture
                                        if (isTwoFingerActive) {
                                            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                                                isTwoFingerActive = false
                                                twoFingerGestureMode = 0
                                            }
                                            return@pointerInteropFilter true
                                        }

                                        // ================================================================
                                        // CASE B: SINGLE TOUCH (Stylus OR Single Finger)
                                        // Single touch NEVER scrolls the page! Pages never move while drawing!
                                        // ================================================================
                                        if (isReadOnlyMode) {
                                            return@pointerInteropFilter true
                                        }

                                        val isStylus = hasHardwareStylus ||
                                                motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                                                motionEvent.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

                                        // If finger drawing is OFF and this is NOT a stylus: do not draw and do not scroll
                                        if (!allowFingerDrawing && !isStylus) {
                                            return@pointerInteropFilter true
                                        }

                                        val targetPointerIdx = if (hasHardwareStylus) {
                                            (0 until pointerCount).firstOrNull { idx ->
                                                val t = motionEvent.getToolType(idx)
                                                t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
                                            } ?: 0
                                        } else 0

                                        val packet = StylusEventParser.parse(motionEvent, targetPointerIdx)
                                        activePageForStroke = pageIdx

                                        val isHardwareEraser = packet.isEraser || packet.isPrimaryButtonPressed
                                        val isEffectiveEraser = isHardwareEraser || selectedStudioTool == StudioTool.ERASER

                                        when (packet.action) {
                                            MotionActionType.DOWN -> {
                                                if (selectedStudioTool == StudioTool.LASSO) {
                                                    lassoPoints = listOf(Offset(packet.x, packet.y))
                                                    selectedStrokeIndices = emptySet()
                                                    lassoBounds = null
                                                } else if (isEffectiveEraser) {
                                                    activeEraserCenter = Offset(packet.x, packet.y)
                                                    workspaceViewModel.eraseAt(packet.x, packet.y, 32f, pageIndex = pageIdx)
                                                } else {
                                                    activeEraserCenter = null
                                                    activePoints = listOf(
                                                        InkPoint(packet.x, packet.y, packet.normalizedPressure, packet.timestamp)
                                                    )
                                                }
                                            }
                                            MotionActionType.MOVE -> {
                                                if (selectedStudioTool == StudioTool.LASSO) {
                                                    lassoPoints = lassoPoints + Offset(packet.x, packet.y)
                                                } else if (isEffectiveEraser) {
                                                    activeEraserCenter = Offset(packet.x, packet.y)
                                                    for (h in 0 until motionEvent.historySize) {
                                                        if (targetPointerIdx < motionEvent.pointerCount) {
                                                            workspaceViewModel.eraseAt(
                                                                motionEvent.getHistoricalX(targetPointerIdx, h),
                                                                motionEvent.getHistoricalY(targetPointerIdx, h),
                                                                32f,
                                                                pageIndex = pageIdx
                                                            )
                                                        }
                                                    }
                                                    workspaceViewModel.eraseAt(packet.x, packet.y, 32f, pageIndex = pageIdx)
                                                } else {
                                                    activeEraserCenter = null
                                                    val batch = ArrayList<InkPoint>(motionEvent.historySize + 1)
                                                    for (h in 0 until motionEvent.historySize) {
                                                        if (targetPointerIdx < motionEvent.pointerCount) {
                                                            batch.add(
                                                                InkPoint(
                                                                    motionEvent.getHistoricalX(targetPointerIdx, h),
                                                                    motionEvent.getHistoricalY(targetPointerIdx, h),
                                                                    motionEvent.getHistoricalPressure(targetPointerIdx, h).coerceIn(0.05f, 1f),
                                                                    motionEvent.getHistoricalEventTime(h)
                                                                )
                                                            )
                                                        }
                                                    }
                                                    batch.add(
                                                        InkPoint(packet.x, packet.y, packet.normalizedPressure, packet.timestamp)
                                                    )
                                                    activePoints = activePoints + batch
                                                }
                                            }
                                            MotionActionType.UP, MotionActionType.CANCEL -> {
                                                if (selectedStudioTool == StudioTool.LASSO) {
                                                    if (lassoPoints.size > 2) {
                                                        val minX = lassoPoints.minOf { it.x }
                                                        val maxX = lassoPoints.maxOf { it.x }
                                                        val minY = lassoPoints.minOf { it.y }
                                                        val maxY = lassoPoints.maxOf { it.y }
                                                        val rect = Rect(minX, minY, maxX, maxY)
                                                        lassoBounds = rect

                                                        val selected = mutableSetOf<Int>()
                                                        uiState.strokes.forEachIndexed { idx, s ->
                                                            if (s.pageIndex == pageIdx) {
                                                                val intersects = s.points.any { p -> rect.contains(Offset(p.x, p.y)) }
                                                                if (intersects) selected.add(idx)
                                                            }
                                                        }
                                                        selectedStrokeIndices = selected
                                                    }
                                                } else if (isEffectiveEraser) {
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
                                                        toolType = InkToolType.PEN,
                                                        pageIndex = pageIdx
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
                                val d = this.density

                                // Committed ink strokes for this specific page only
                                uiState.strokes.forEachIndexed { idx, stroke ->
                                    if (stroke.pageIndex == pageIdx && stroke.points.size >= 2) {
                                        val path = BezierSplineInterpolator.createSplinePath(stroke.points)
                                        val isSelectedByLasso = selectedStrokeIndices.contains(idx)
                                        val strokeColor = if (isSelectedByLasso) Color(0xFF00BCD4) else stroke.color
                                        drawPath(
                                            path = path,
                                            color = strokeColor,
                                            style = Stroke(
                                                width = stroke.baseWidth * d,
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            )
                                        )
                                    }
                                }

                                // Live in-progress stroke
                                if (activePoints.size >= 2 && activePageForStroke == pageIdx) {
                                    val activePath = BezierSplineInterpolator.createSplinePath(activePoints)
                                    drawPath(
                                        path = activePath,
                                        color = if (selectedStudioTool == StudioTool.HIGHLIGHTER) uiState.selectedColor.copy(alpha = 0.35f) else uiState.selectedColor,
                                        style = Stroke(
                                            width = uiState.selectedStrokeWidth * d,
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                }

                                // Live Lasso selection loop
                                if (lassoPoints.size >= 2 && activePageForStroke == pageIdx) {
                                    val lassoPath = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(lassoPoints[0].x, lassoPoints[0].y)
                                        for (i in 1 until lassoPoints.size) {
                                            lineTo(lassoPoints[i].x, lassoPoints[i].y)
                                        }
                                    }
                                    drawPath(
                                        path = lassoPath,
                                        color = Color(0xFF00BCD4),
                                        style = Stroke(
                                            width = 2.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                                        )
                                    )
                                }

                                // Lasso Bounding Box
                                lassoBounds?.let { b ->
                                    if (activePageForStroke == pageIdx && selectedStrokeIndices.isNotEmpty()) {
                                        drawRect(
                                            color = Color(0xFF00BCD4).copy(alpha = 0.1f),
                                            topLeft = Offset(b.left, b.top),
                                            size = androidx.compose.ui.geometry.Size(b.width, b.height)
                                        )
                                        drawRect(
                                            color = Color(0xFF00BCD4),
                                            topLeft = Offset(b.left, b.top),
                                            size = androidx.compose.ui.geometry.Size(b.width, b.height),
                                            style = Stroke(
                                                width = 1.5.dp.toPx(),
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                                            )
                                        )
                                    }
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

                            // Lasso Action Popup
                            if (selectedStrokeIndices.isNotEmpty() && lassoBounds != null && activePageForStroke == pageIdx) {
                                Card(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = (lassoBounds!!.top - 46f).coerceAtLeast(10f).dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
                                    border = BorderStroke(1.dp, Color(0xFF00BCD4))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(onClick = {
                                            val remaining = uiState.strokes.filterIndexed { i, _ -> !selectedStrokeIndices.contains(i) }
                                            workspaceViewModel.confirmClearPage()
                                            remaining.forEach { workspaceViewModel.addStroke(it) }
                                            selectedStrokeIndices = emptySet()
                                            lassoBounds = null
                                            Toast.makeText(context, "Deleted selected strokes", Toast.LENGTH_SHORT).show()
                                        }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(onClick = {
                                            selectedStrokeIndices = emptySet()
                                            lassoBounds = null
                                        }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = "Deselect", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Floating Zoom Control Pill (Bottom Right)
            Card(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 24.dp)
                    .shadow(6.dp, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22).copy(alpha = 0.92f)),
                border = BorderStroke(1.dp, Color(0xFF2E2E34))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            zoomScale = (zoomScale - 0.25f).coerceIn(0.25f, 4.0f)
                            if (zoomScale <= 1.0f) panX = 0f
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }

                    Text(
                        text = "${(zoomScale * 100).toInt()}%",
                        color = Color(0xFF00BCD4),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                zoomScale = if (zoomScale == 1.0f) 1.5f else 1.0f
                                panX = 0f
                            }
                            .padding(horizontal = 4.dp)
                    )

                    IconButton(
                        onClick = {
                            zoomScale = (zoomScale + 0.25f).coerceIn(0.25f, 4.0f)
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // ==========================================
        // 2. TOP FLOATING APP BAR
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

                // Page indicator chip (e.g. "1 of 25")
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

            // GROUP B: Top-Center Floating Toolbar Pill (Pen, Highlighter, Eraser, Lasso ONLY)
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
                                Toast.makeText(context, "Lasso active: draw a loop around strokes to select", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            // GROUP C: Top-Right Pill (+, ⊘, Hand Draw, ⋮)
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
                    // Finger Drawing Toggle Button
                    IconButton(
                        onClick = {
                            allowFingerDrawing = !allowFingerDrawing
                            Toast.makeText(
                                context,
                                if (allowFingerDrawing) "Finger Drawing ON: You can draw using your hand" else "Finger Drawing OFF: Fingers scroll/zoom pages, Stylus draws",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = "Hand/Finger Drawing Toggle",
                            tint = if (allowFingerDrawing) Color(0xFF00BCD4) else Color(0xFF888888),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Plus (+) button -> Opens Add Menu (Image 2)
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

                    // Circle with slash (⊘) -> Read-Only mode toggle
                    IconButton(
                        onClick = {
                            isReadOnlyMode = !isReadOnlyMode
                            Toast.makeText(
                                context,
                                if (isReadOnlyMode) "Read-Only Mode enabled" else "Annotation Mode enabled",
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

                    // Three-dots menu (⋮) -> Opens Document Menu (Image 3)
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
                    // Plus (+) Custom Color Palette dialog trigger
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2A2A2E))
                            .clickable { showColorPaletteDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Choose Custom Color", tint = Color.LightGray, modifier = Modifier.size(13.dp))
                    }

                    HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(horizontal = 2.dp))

                    // Stroke Thickness Slider Trigger
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2A2A2E))
                            .clickable { showStrokeWidthDialog = !showStrokeWidthDialog },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size((uiState.selectedStrokeWidth * 0.7f).coerceIn(4f, 14f).dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00BCD4))
                        )
                    }
                }
            }
        }

        // ==========================================
        // 4. STROKE THICKNESS SLIDER POPOVER
        // ==========================================
        if (showStrokeWidthDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showStrokeWidthDialog = false }
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 64.dp)
                        .width(230.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* prevent dismiss on click inside */ }
                        .shadow(12.dp, RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
                    border = BorderStroke(1.dp, Color(0xFF2E2E34))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Stroke Width", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${uiState.selectedStrokeWidth.toInt()} pt", color = Color(0xFF00BCD4), fontSize = 12.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                IconButton(
                                    onClick = { showStrokeWidthDialog = false },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Slider(
                            value = uiState.selectedStrokeWidth,
                            onValueChange = { workspaceViewModel.selectStrokeWidth(it) },
                            valueRange = 1f..35f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00BCD4),
                                activeTrackColor = Color(0xFF00BCD4),
                                inactiveTrackColor = Color(0xFF333333)
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf(2f, 5f, 10f, 18f, 28f).forEach { pt ->
                                Text(
                                    text = "${pt.toInt()}",
                                    color = if (uiState.selectedStrokeWidth == pt) Color(0xFF00BCD4) else Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clickable { workspaceViewModel.selectStrokeWidth(pt) }
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 5. COLOR PALETTE DIALOG (Any Shade of Choice)
        // ==========================================
        if (showColorPaletteDialog) {
            var customHue by remember { mutableFloatStateOf(200f) }
            var customSaturation by remember { mutableFloatStateOf(1f) }
            var customValue by remember { mutableFloatStateOf(1f) }

            val curColor = remember(customHue, customSaturation, customValue) {
                val hsv = floatArrayOf(customHue, customSaturation, customValue)
                Color(android.graphics.Color.HSVToColor(hsv))
            }

            AlertDialog(
                onDismissRequest = { showColorPaletteDialog = false },
                title = { Text("Color Palette & Custom Shades", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Curated Shades:", color = Color.LightGray, fontSize = 12.sp)
                        val curatedShades = listOf(
                            Color(0xFF000000), Color(0xFF37474F), Color(0xFF78909C), Color(0xFFB0BEC5), Color(0xFFFFFFFF),
                            Color(0xFF0D47A1), Color(0xFF1976D2), Color(0xFF29B6F6), Color(0xFF00BCD4), Color(0xFF00E676),
                            Color(0xFF1B5E20), Color(0xFF388E3C), Color(0xFF8BC34A), Color(0xFFCDDC39), Color(0xFFFFEB3B),
                            Color(0xFFFFB300), Color(0xFFFF9800), Color(0xFFFF5722), Color(0xFFD50000), Color(0xFFC2185B),
                            Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF673AB7), Color(0xFF3F51B5), Color(0xFF5D4037)
                        )

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(5),
                            modifier = Modifier.height(130.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(curatedShades) { shade ->
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(shade)
                                        .border(1.dp, if (uiState.selectedColor == shade) Color(0xFF00BCD4) else Color(0xFF444444), CircleShape)
                                        .clickable {
                                            workspaceViewModel.selectColor(shade)
                                            showColorPaletteDialog = false
                                        }
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0xFF333333))

                        Text("Fine-tune Custom Shade:", color = Color.LightGray, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(curColor)
                                    .border(1.5.dp, Color.White, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Preview Color", color = Color.White, fontSize = 13.sp)
                        }

                        Text("Hue", color = Color.Gray, fontSize = 11.sp)
                        Slider(
                            value = customHue,
                            onValueChange = { customHue = it },
                            valueRange = 0f..360f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF00BCD4), activeTrackColor = Color(0xFF00BCD4))
                        )

                        Text("Saturation", color = Color.Gray, fontSize = 11.sp)
                        Slider(
                            value = customSaturation,
                            onValueChange = { customSaturation = it },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF00BCD4), activeTrackColor = Color(0xFF00BCD4))
                        )

                        Text("Brightness", color = Color.Gray, fontSize = 11.sp)
                        Slider(
                            value = customValue,
                            onValueChange = { customValue = it },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF00BCD4), activeTrackColor = Color(0xFF00BCD4))
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            workspaceViewModel.selectColor(curColor)
                            showColorPaletteDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4))
                    ) {
                        Text("Apply Color", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showColorPaletteDialog = false }) {
                        Text("Cancel", color = Color.Gray)
                    }
                },
                containerColor = Color(0xFF1E1E22)
            )
        }

        // ==========================================
        // 6. THE "ADD" MENU FLYOUT (Image 2)
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
                                    scope.launch { listState.animateScrollToItem(totalPages - 1) }
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
        // 7. THE THREE-DOTS MENU FLYOUT (Image 3)
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
        // 8. LEFT PAGE MANAGER SIDEBAR DRAWER (Image 4)
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

                        // Tab 0: Real PDF Page Thumbnails Grid (Image 4)
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
                                        val thumb = pageThumbnails[pageIdx]

                                        Column(horizontalAlignment = Alignment.Start) {
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(140.dp)
                                                    .clickable {
                                                        scope.launch { listState.animateScrollToItem(pageIdx) }
                                                        showPageDrawer = false
                                                    },
                                                shape = RoundedCornerShape(8.dp),
                                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                                border = BorderStroke(
                                                    width = if (isCurrent) 2.5.dp else 1.dp,
                                                    color = if (isCurrent) Color(0xFF00BCD4) else Color(0xFF333338)
                                                )
                                            ) {
                                                Box(modifier = Modifier.fillMaxSize()) {
                                                    if (thumb != null) {
                                                        Image(
                                                            bitmap = thumb.asImageBitmap(),
                                                            contentDescription = "Page ${pageIdx + 1}",
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentScale = ContentScale.Fit
                                                        )
                                                    } else {
                                                        // Paper template + live thumbnail miniature strokes
                                                        Box(modifier = Modifier.fillMaxSize()) {
                                                            PaperTemplateCanvas(paperStyle = uiState.paperStyle)
                                                            if (pageIdx == 0 && notebook?.previewLines?.isNotEmpty() == true) {
                                                                Column(
                                                                    modifier = Modifier.fillMaxSize().padding(8.dp),
                                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                                ) {
                                                                    notebook.previewLines.take(5).forEach { line ->
                                                                        Text(line, color = Color(0xFF222222), fontSize = 7.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                                    }
                                                                }
                                                            } else {
                                                                Canvas(modifier = Modifier.fillMaxSize()) {
                                                                    val scale = size.width / (scaledPageWidth.toPx().coerceAtLeast(1f))
                                                                    uiState.strokes.forEach { stroke ->
                                                                        if (stroke.points.size >= 2) {
                                                                            val miniPoints = stroke.points.map {
                                                                                InkPoint(it.x * scale, it.y * scale, it.pressure, it.timestamp)
                                                                            }
                                                                            val path = BezierSplineInterpolator.createSplinePath(miniPoints)
                                                                            drawPath(
                                                                                path = path,
                                                                                color = stroke.color,
                                                                                style = Stroke(width = (stroke.baseWidth * scale).coerceAtLeast(1f))
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }

                                                    // Miniature page number pill inside thumbnail
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomEnd)
                                                            .padding(4.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(Color.Black.copy(alpha = 0.65f))
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = "${pageIdx + 1}",
                                                            color = Color.White,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }

                                                    // Bookmark ribbon
                                                    if (notebook?.isBookmarked == true && pageIdx == 0) {
                                                        Icon(
                                                            imageVector = Icons.Default.Bookmark,
                                                            contentDescription = "Bookmark",
                                                            tint = Color(0xFF00BCD4),
                                                            modifier = Modifier.size(16.dp).align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "Page ${pageIdx + 1}",
                                                color = if (isCurrent) Color(0xFF00BCD4) else Color.LightGray,
                                                fontSize = 11.sp,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
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
                                                        scope.launch { listState.animateScrollToItem(totalPages - 1) }
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
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            scope.launch { listState.animateScrollToItem(0) }
                                            showPageDrawer = false
                                        },
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
                                                modifier = Modifier
                                                    .padding(vertical = 4.dp)
                                                    .clickable {
                                                        scope.launch { listState.animateScrollToItem(0) }
                                                        showPageDrawer = false
                                                    }
                                            )
                                        }
                                    } else {
                                        Text("Page 1: ${notebook?.title ?: "Note"}", color = Color.White, fontSize = 13.sp, modifier = Modifier.clickable {
                                            scope.launch { listState.animateScrollToItem(0) }
                                            showPageDrawer = false
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==========================================
        // 9. SUB-DIALOGS (Template, Rename, GoTo, etc.)
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
                                scope.launch { listState.animateScrollToItem(p - 1) }
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
                        Text("Strokes on Document: ${uiState.strokes.size}", color = Color.LightGray, fontSize = 13.sp)
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
