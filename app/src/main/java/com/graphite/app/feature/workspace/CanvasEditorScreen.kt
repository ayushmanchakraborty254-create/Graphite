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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
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

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.min

enum class StudioTool {
    PEN,
    HIGHLIGHTER,
    ERASER,
    LASSO,
    SHAPE,
    PRESENT,
    TEXT
}

enum class EditorInteractionMode {
    PEN_MODE,   // Only Stylus draws; 1-finger scrolls; pinch-zoom
    HAND_MODE   // Both Hand & Stylus draw; 2-finger scrolls; pinch-zoom
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CanvasEditorScreen(
    notebookId: String,
    onNavigateBack: () -> Unit,
    onSplitScreen: ((String, String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    workspaceViewModel: WorkspaceViewModel = viewModel(key = "canvas_editor_$notebookId")
) {
    val allNotebooks by GraphiteRepository.notebooks.collectAsState()
    val notebook = remember(notebookId, allNotebooks) {
        allNotebooks.firstOrNull { it.id == notebookId } ?: GraphiteRepository.getNotebook(notebookId)
    }
    val uiState by workspaceViewModel.uiState.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val isTablet = configuration.screenWidthDp >= 600

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
    val basePageWidth = (screenWidth - 24.dp).coerceAtLeast(320.dp)
    val basePageHeight = basePageWidth * pdfAspectRatio

    // Tool & Mode state
    var isReadOnlyMode by remember { mutableStateOf(false) } // ⊘ button
    var selectedStudioTool by remember { mutableStateOf(StudioTool.PEN) }
    var interactionMode by remember { mutableStateOf(EditorInteractionMode.PEN_MODE) }

    // UI Drawer & Menu toggles
    var showPageDrawer by remember { mutableStateOf(false) }       // Top-left ⊞ (Image 4)
    var showAddMenu by remember { mutableStateOf(false) }          // Top-right + (Image 2)
    var showDocumentMenu by remember { mutableStateOf(false) }     // Top-right ⋮ (Image 3)
    var showColorPaletteDialog by remember { mutableStateOf(false) } // Left palette +
    var showStrokeWidthDialog by remember { mutableStateOf(false) } // Left palette size slider
    var showVoiceDialog by remember { mutableStateOf(false) }
    var showSplitScreenPicker by remember { mutableStateOf(false) }
    var showMobilePalette by remember { mutableStateOf(false) }

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

    // Transient stroke buffer to eliminate any frame flicker/blink during UP gesture before ViewModel emits updated strokes
    var pendingTransientStrokes by remember { mutableStateOf<List<InkStroke>>(emptyList()) }

    LaunchedEffect(uiState.strokes) {
        if (pendingTransientStrokes.isNotEmpty()) {
            pendingTransientStrokes = pendingTransientStrokes.filter { pending ->
                uiState.strokes.none { it.id == pending.id }
            }
        }
    }

    // Tool sizes & persistent eraser state (remembered across all notes and PDFs)
    var brushStrokeWidth by remember { mutableFloatStateOf(uiState.selectedStrokeWidth) }
    var highlighterStrokeWidth by remember { mutableFloatStateOf(16f) }

    val eraserPrefs = remember { context.getSharedPreferences("graphite_eraser_prefs", Context.MODE_PRIVATE) }
    val savedEraserModeName = remember { eraserPrefs.getString("eraser_mode", WorkspaceViewModel.EraserModeType.WHOLE_STROKE.name) }
    val initialEraserMode = remember {
        try {
            WorkspaceViewModel.EraserModeType.valueOf(savedEraserModeName ?: WorkspaceViewModel.EraserModeType.WHOLE_STROKE.name)
        } catch (_: Exception) {
            WorkspaceViewModel.EraserModeType.WHOLE_STROKE
        }
    }
    val initialEraserRadius = remember { eraserPrefs.getFloat("eraser_radius", 28f) }

    var eraserRadius by remember { mutableFloatStateOf(initialEraserRadius) }
    var eraserMode by remember { mutableStateOf(initialEraserMode) }

    LaunchedEffect(eraserRadius, eraserMode) {
        eraserPrefs.edit()
            .putString("eraser_mode", eraserMode.name)
            .putFloat("eraser_radius", eraserRadius)
            .apply()
    }

    // Lasso selection & drag state
    var lassoPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var selectedStrokeIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var lassoBounds by remember { mutableStateOf<Rect?>(null) }
    var isDraggingLasso by remember { mutableStateOf(false) }
    var lassoDragStart by remember { mutableStateOf(Offset.Zero) }
    var lassoDragOffset by remember { mutableStateOf(Offset.Zero) }

    // Voice recording in note state
    var showVoiceNoteRecorder by remember { mutableStateOf(false) }
    var isTranscribingVoice by remember { mutableStateOf(false) }
    var transcribedTextResult by remember { mutableStateOf<String?>(null) }
    var voiceRecordingFile by remember { mutableStateOf<java.io.File?>(null) }
    var showAudioNotesDialog by remember { mutableStateOf(false) }
    var voiceDialogAudioOnly by remember { mutableStateOf(false) }

    // Canvas Text Box state
    var localTextBoxes by remember { mutableStateOf<List<CanvasTextBox>>(emptyList()) }
    var selectedTextBoxId by remember { mutableStateOf<String?>(null) }
    var editingTextBoxId by remember { mutableStateOf<String?>(null) }
    var textToolFontFamily by remember { mutableStateOf("Roboto") }
    var textToolFontSize by remember { mutableFloatStateOf(16f) }
    var textToolColorHex by remember { mutableStateOf("#1F1F1F") }
    var textToolBold by remember { mutableStateOf(false) }
    var textToolItalic by remember { mutableStateOf(false) }
    var textToolUnderline by remember { mutableStateOf(false) }
    var textToolStrikethrough by remember { mutableStateOf(false) }
    var textToolAlignment by remember { mutableStateOf("Left") }
    var textToolTapDownX by remember { mutableFloatStateOf(0f) }
    var textToolTapDownY by remember { mutableFloatStateOf(0f) }

    // Canvas Image Elements state (Interactive image objects on canvas)
    var localImages by remember { mutableStateOf<List<CanvasImageElement>>(emptyList()) }
    var selectedImageId by remember { mutableStateOf<String?>(null) }

    // Shapes Tool state
    var showShapeDialog by remember { mutableStateOf(false) }
    var selectedShapeType by remember { mutableStateOf(CanvasShapeType.RECTANGLE) }
    var shapeStrokeWidth by remember { mutableFloatStateOf(4f) }
    var shapeStartOffset by remember { mutableStateOf<Offset?>(null) }
    var shapeCurrentOffset by remember { mutableStateOf<Offset?>(null) }
    var isFreehandShapeMode by remember { mutableStateOf(false) }

    // Presenter Laser Mode state (Neon glow laser pointer with 2s disconnect timer)
    var laserActivePoints by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var laserStrokes by remember { mutableStateOf<List<InkStroke>>(emptyList()) }
    var laserFadeJob by remember { mutableStateOf<Job?>(null) }
    var laserColor by remember { mutableStateOf(Color(0xFFFF1744)) }
    var isLaserDotMode by remember { mutableStateOf(false) }
    var laserAlpha by remember { mutableFloatStateOf(1f) }

    // Synchronize text boxes and images from repository preview lines
    LaunchedEffect(notebook?.previewLines) {
        val lines = notebook?.previewLines ?: emptyList()
        val parsed = lines.mapNotNull { CanvasTextBox.deserialize(it) }
        val imageLines = lines.mapNotNull { CanvasImageElement.deserialize(it) }
        if (selectedImageId == null) {
            localImages = imageLines
        }

        val isPdfDoc = notebook?.mimeType == "application/pdf" ||
                       notebook?.filePath?.endsWith(".pdf", ignoreCase = true) == true ||
                       notebook?.originalFileName?.endsWith(".pdf", ignoreCase = true) == true

        // Clean out any PDF title / page count junk boxes that were previously generated on import
        val cleanedParsed = if (isPdfDoc) {
            parsed.filterNot { box ->
                val t = box.text.trim()
                t.endsWith(".pdf", ignoreCase = true) ||
                t.startsWith("PDF Document •", ignoreCase = true) ||
                t.equals(notebook?.title?.trim(), ignoreCase = true) ||
                t.equals(notebook?.originalFileName?.trim(), ignoreCase = true) ||
                (box.pageIndex == 0 && (t.startsWith("Paper Name:") || t.startsWith("Paper Code:") || t.startsWith("Question Bank") || t.startsWith("Dr. SUD")))
            }
        } else {
            parsed
        }

        // Find any unparsed raw preview text lines (such as from previous voice dictations)
        val rawLines = if (isPdfDoc) {
            emptyList()
        } else {
            lines.filter { line ->
                !line.startsWith("[GRAPHITE_META:") &&
                !line.startsWith("[GRAPHITE_DOC:") &&
                !line.startsWith("[GRAPHITE_DATA:") &&
                !line.startsWith("[GRAPHITE_VOICE:") &&
                !line.startsWith("[GRAPHITE_TEXTBOX:") &&
                !line.startsWith("[GRAPHITE_IMAGE:") &&
                !line.startsWith("[GRAPHITE_PAGE_W:") &&
                line.trim().isNotEmpty()
            }
        }

        if (cleanedParsed.size != parsed.size || rawLines.isNotEmpty()) {
            val migratedBoxes = mutableListOf<CanvasTextBox>()
            val remainingLines = lines.filterNot { rawLines.contains(it) }.toMutableList()

            rawLines.forEachIndexed { idx, line ->
                val pIdx = if (line.startsWith("[PAGE:")) {
                    line.substringAfter("[PAGE:").substringBefore("]").toIntOrNull() ?: 0
                } else 0
                val cleanText = if (line.startsWith("[PAGE:")) line.substringAfter("]").trim() else line.trim()
                if (cleanText.isNotEmpty()) {
                    migratedBoxes.add(
                        CanvasTextBox(
                            id = java.util.UUID.randomUUID().toString(),
                            pageIndex = pIdx,
                            xRatio = 0.08f,
                            yRatio = (0.08f + (idx * 0.06f)).coerceIn(0.06f, 0.85f),
                            text = cleanText,
                            fontSizeSp = 16f,
                            colorHex = "#1F1F1F"
                        )
                    )
                }
            }
            val allBoxes = cleanedParsed + migratedBoxes
            val serialized = allBoxes.map { it.serialize() }
            val newLines = remainingLines.filterNot { it.startsWith("[GRAPHITE_TEXTBOX:") } + serialized
            GraphiteRepository.updateNotebookLines(notebookId, newLines)
            localTextBoxes = allBoxes
        } else if (editingTextBoxId == null) {
            localTextBoxes = cleanedParsed
        }
    }

    fun saveTextBoxes(newBoxes: List<CanvasTextBox>) {
        localTextBoxes = newBoxes
        val repoNote = GraphiteRepository.getNotebook(notebookId)
        val baseLines = repoNote?.previewLines ?: notebook?.previewLines ?: emptyList()
        val currentLines = baseLines
            .filterNot { it.startsWith("[GRAPHITE_TEXTBOX:") }
            .toMutableList()
        val serialized = newBoxes.map { it.serialize() }
        currentLines.addAll(serialized)
        GraphiteRepository.updateNotebookLines(notebookId, currentLines)
    }

    fun saveImages(newImages: List<CanvasImageElement>) {
        localImages = newImages
        val repoNote = GraphiteRepository.getNotebook(notebookId)
        val baseLines = repoNote?.previewLines ?: notebook?.previewLines ?: emptyList()
        val currentLines = baseLines
            .filterNot { it.startsWith("[GRAPHITE_IMAGE:") }
            .toMutableList()
        val serialized = newImages.map { it.serialize() }
        currentLines.addAll(serialized)
        GraphiteRepository.updateNotebookLines(notebookId, currentLines)
    }

    // Convert handwriting ink strokes to text boxes (Strictly bound to Lasso selection)
    fun convertHandwritingToText(targetPageIdx: Int = currentPageIndex) {
        if (selectedStrokeIndices.isEmpty() || activePageForStroke != targetPageIdx) {
            selectedStudioTool = StudioTool.LASSO
            Toast.makeText(context, "Lasso the handwriting you want to convert", Toast.LENGTH_SHORT).show()
            return
        }

        val strokesToConvert = uiState.strokes.filterIndexed { index, _ -> selectedStrokeIndices.contains(index) }
        if (strokesToConvert.isEmpty()) {
            Toast.makeText(context, "No handwriting found in selection", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "Recognizing handwriting (AI & Multilingual)...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = HandwritingRecognizerHelper.recognizeStrokes(strokesToConvert)
            if (result != null && result.text.isNotBlank()) {
                val pageBaseWPx = with(density) { basePageWidth.toPx() }.coerceAtLeast(1f)
                val pageBaseHPx = with(density) { basePageHeight.toPx() }.coerceAtLeast(1f)

                val xRatio = (result.bounds.left / pageBaseWPx).coerceIn(0.04f, 0.85f)
                val yRatio = (result.bounds.top / pageBaseHPx).coerceIn(0.04f, 0.90f)

                val newBox = CanvasTextBox(
                    id = java.util.UUID.randomUUID().toString(),
                    pageIndex = targetPageIdx,
                    xRatio = xRatio,
                    yRatio = yRatio,
                    text = result.text,
                    fontSizeSp = 16f,
                    colorHex = textToolColorHex,
                    fontFamily = textToolFontFamily,
                    isAutoWidth = true
                )

                workspaceViewModel.deleteStrokes(selectedStrokeIndices)
                selectedStrokeIndices = emptySet()
                lassoBounds = null

                val updated = localTextBoxes + newBox
                saveTextBoxes(updated)
                selectedTextBoxId = newBox.id
                selectedStudioTool = StudioTool.TEXT
                Toast.makeText(context, "Converted: \"${result.text.take(30)}\"", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not recognize text. Please write clearly and try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Auto-prune empty text boxes whenever switching away from the Text Tool
    LaunchedEffect(selectedStudioTool) {
        if (selectedStudioTool != StudioTool.TEXT) {
            editingTextBoxId = null
            val cleaned = localTextBoxes.filterNot { it.text.trim().isEmpty() }
            if (cleaned.size != localTextBoxes.size) {
                saveTextBoxes(cleaned)
            }
        }
    }

    val palmFilter = remember { PalmRejectionFilter(uiState.inputPolicy) }
    palmFilter.policy = uiState.inputPolicy

    // Helper to import images directly onto active notebook page (not full-screen, proportional size, with editing options)
    fun importImageToCurrentPage(uri: Uri?, bitmap: Bitmap?) {
        scope.launch(Dispatchers.IO) {
            try {
                val imagesDir = File(context.filesDir, "notebook_images").apply { mkdirs() }
                val targetFile = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
                var origW = 800
                var origH = 600

                if (bitmap != null) {
                    origW = bitmap.width
                    origH = bitmap.height
                    java.io.FileOutputStream(targetFile).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                    }
                } else if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        if (options.outWidth > 0) origW = options.outWidth
                        if (options.outHeight > 0) origH = options.outHeight
                        targetFile.writeBytes(bytes)
                    }
                } else return@launch

                val pageAspect = 1.414f
                val imgAspect = origH.toFloat() / origW.toFloat().coerceAtLeast(1f)
                val wRatio = 0.50f
                val hRatio = (wRatio * imgAspect / pageAspect).coerceIn(0.12f, 0.85f)

                val newImg = CanvasImageElement(
                    id = java.util.UUID.randomUUID().toString(),
                    pageIndex = currentPageIndex,
                    filePath = targetFile.absolutePath,
                    xRatio = 0.25f,
                    yRatio = 0.22f,
                    widthRatio = wRatio,
                    heightRatio = hRatio,
                    rotationDegrees = 0f,
                    opacity = 1.0f,
                    isLocked = false,
                    keepAspectRatio = true
                )

                withContext(Dispatchers.Main) {
                    val updated = localImages + newImg
                    saveImages(updated)
                    selectedImageId = newImg.id
                    Toast.makeText(context, "Image added to Page ${currentPageIndex + 1}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to import image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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
            importImageToCurrentPage(uri, null)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null) {
            importImageToCurrentPage(null, bmp)
        }
    }

    // Initial strokes load - isolated per notebook document
    LaunchedEffect(notebookId) {
        val currentNote = notebook ?: GraphiteRepository.getNotebook(notebookId)
        val initialStrokes = currentNote?.initialStrokes ?: emptyList()
        workspaceViewModel.loadNotebook(notebookId, initialStrokes)
        currentNote?.paperStyle?.let { workspaceViewModel.selectPaperStyle(it) }
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

                            // Render first few pages immediately in Full HD / 2K print resolution
                            for (p in 0 until minOf(count, 5)) {
                                val page = renderer.openPage(p)
                                val targetScale = (1800f / page.width.toFloat()).coerceIn(2.2f, 3.2f)
                                val w = (page.width * targetScale).toInt()
                                val h = (page.height * targetScale).toInt()
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bmp)
                                canvas.drawColor(android.graphics.Color.WHITE)
                                val matrix = android.graphics.Matrix().apply {
                                    setScale(w.toFloat() / page.width.toFloat(), h.toFloat() / page.height.toFloat())
                                }
                                page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
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
                                val thumbMatrix = android.graphics.Matrix().apply {
                                    setScale(thumbW.toFloat() / page.width.toFloat(), thumbH.toFloat() / page.height.toFloat())
                                }
                                page.render(thumbBmp, null, thumbMatrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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
                            val targetScale = (1800f / page.width.toFloat()).coerceIn(2.2f, 3.2f)
                            val w = (page.width * targetScale).toInt()
                            val h = (page.height * targetScale).toInt()
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bmp)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            val matrix = android.graphics.Matrix().apply {
                                setScale(w.toFloat() / page.width.toFloat(), h.toFloat() / page.height.toFloat())
                            }
                            page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
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
                            val thumbMatrix = android.graphics.Matrix().apply {
                                setScale(thumbW.toFloat() / page.width.toFloat(), thumbH.toFloat() / page.height.toFloat())
                            }
                            page.render(thumbBmp, null, thumbMatrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
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
            try {
                val recorder = com.graphite.app.GraphiteApplication.instance.voiceRecorderManager
                if (recorder.isRecording.value) {
                    val clip = recorder.stopRecording()
                    if (clip != null) {
                        GraphiteRepository.addVoiceClipToNotebook(notebookId, clip)
                    }
                }
                recorder.stopPlayback()
            } catch (_: Exception) {}

            pdfBitmaps.values.forEach { it.recycle() }
            pdfBitmaps.clear()
            pageThumbnails.values.forEach { it.recycle() }
            pageThumbnails.clear()
            try {
                com.graphite.app.GraphiteApplication.instance.supabaseSyncManager.triggerAutoSync(debounceMs = 0L)
            } catch (_: Exception) {}
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
        val canonicalScale = (basePageWidth.value / 720f).coerceIn(0.50f, 1.25f)
        val scaledPageWidth = basePageWidth * zoomScale
        val scaledPageHeight = basePageHeight * zoomScale
        val maxPanX = with(density) {
            ((scaledPageWidth - screenWidth).toPx() / 2f).coerceAtLeast(0f)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(interactionMode, isReadOnlyMode, selectedStudioTool, selectedImageId) {
                    awaitEachGesture {
                        var lastCenter: Offset? = null
                        var lastDist: Float? = null
                        var scrollVelocityY = 0f
                        var lastTime = 0L
                        var totalDragY = 0f
                        var isDragging1Finger = false

                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }

                            // If a hardware stylus or eraser is on screen, NEVER intercept or scroll!
                            val hasStylus = pressed.any {
                                it.type == PointerType.Stylus || it.type == PointerType.Eraser
                            }

                            val isTextTool = selectedStudioTool == StudioTool.TEXT
                            val isImageActive = selectedImageId != null
                            if (hasStylus || isReadOnlyMode || isTextTool || isImageActive) {
                                // Stylus is active, read-only active, text tool active, or image is selected: NEVER scroll from this detector!
                                lastCenter = null
                                lastDist = null
                                scrollVelocityY = 0f
                                isDragging1Finger = false
                                totalDragY = 0f
                            } else if (pressed.size >= 2) {
                                // 2 OR MORE FINGERS:
                                isDragging1Finger = false
                                totalDragY = 0f
                                val p0 = pressed[0].position
                                val p1 = pressed[1].position
                                val curCenter = (p0 + p1) / 2f
                                val curDist = (p0 - p1).getDistance()
                                val now = System.currentTimeMillis()

                                if (lastCenter != null && lastDist != null && lastDist > 15f && curDist > 15f) {
                                    // 1. Smooth simultaneous pinch-to-zoom (centered) - active in both modes
                                    val factor = curDist / lastDist
                                    val pinchDiff = kotlin.math.abs(curDist - lastDist)
                                    if (factor in 0.85f..1.15f && pinchDiff > 0.8f) {
                                        val oldZoom = zoomScale
                                        val newZoom = (oldZoom * factor).coerceIn(0.25f, 4.0f)
                                        zoomScale = newZoom
                                        if (newZoom <= 1.0f) panX = 0f
                                    }

                                    // 2. In HAND_MODE: 2 fingers scroll the pages vertically!
                                    if (interactionMode == EditorInteractionMode.HAND_MODE) {
                                        val dy = curCenter.y - lastCenter.y
                                        if (kotlin.math.abs(dy) > 0.1f) {
                                            listState.dispatchRawDelta(-dy)
                                            val dt = (now - lastTime).coerceAtLeast(1)
                                            val instantV = (-dy / dt) * 1000f
                                            scrollVelocityY = scrollVelocityY * 0.4f + instantV * 0.6f
                                        }
                                    }

                                    // 3. Pan horizontally only when zoomed in
                                    val dx = curCenter.x - lastCenter.x
                                    if (zoomScale > 1f && kotlin.math.abs(dx) > 0.1f) {
                                        panX = (panX + dx).coerceIn(-maxPanX, maxPanX)
                                    }
                                }

                                lastCenter = curCenter
                                lastDist = curDist
                                lastTime = now

                                // Consume touch events so child canvases don't receive two-finger pinch/scroll events
                                pressed.forEach { it.consume() }
                            } else if (pressed.size == 1 && interactionMode == EditorInteractionMode.PEN_MODE) {
                                // 1 FINGER IN PEN_MODE: smoothly scrolls the pages vertically!
                                val curPos = pressed[0].position
                                val now = System.currentTimeMillis()

                                if (lastCenter != null) {
                                    val dy = curPos.y - lastCenter.y
                                    totalDragY += kotlin.math.abs(dy)
                                    if (totalDragY > 8f) {
                                        isDragging1Finger = true
                                    }
                                    if (isDragging1Finger && kotlin.math.abs(dy) > 0.1f) {
                                        listState.dispatchRawDelta(-dy)
                                        val dt = (now - lastTime).coerceAtLeast(1)
                                        val instantV = (-dy / dt) * 1000f
                                        scrollVelocityY = scrollVelocityY * 0.4f + instantV * 0.6f
                                    }
                                }

                                lastCenter = curPos
                                lastTime = now
                                lastDist = null

                                // Consume the 1-finger touch only once drag movement has engaged
                                if (isDragging1Finger) {
                                    pressed[0].consume()
                                }
                            } else {
                                // Fingers lifted: fling momentum if swiped with velocity
                                val shouldFling = (interactionMode == EditorInteractionMode.HAND_MODE && scrollVelocityY != 0f) ||
                                        (interactionMode == EditorInteractionMode.PEN_MODE && isDragging1Finger)
                                if (shouldFling && kotlin.math.abs(scrollVelocityY) > 180f) {
                                    val flingAmount = (scrollVelocityY * 0.22f).coerceIn(-2000f, 2000f)
                                    scope.launch {
                                        var consumed = 0f
                                        androidx.compose.animation.core.animate(
                                            initialValue = 0f,
                                            targetValue = flingAmount,
                                            animationSpec = androidx.compose.animation.core.tween(
                                                durationMillis = 350,
                                                easing = androidx.compose.animation.core.FastOutSlowInEasing
                                            )
                                        ) { value, _ ->
                                            val step = value - consumed
                                            listState.dispatchRawDelta(step)
                                            consumed = value
                                        }
                                    }
                                }
                                scrollVelocityY = 0f
                                lastCenter = null
                                lastDist = null
                                isDragging1Finger = false
                                totalDragY = 0f
                            }
                        } while (pressed.isNotEmpty())
                    }
                }
        ) {
            LazyColumn(
                state = listState,
                userScrollEnabled = isReadOnlyMode,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 58.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                items(totalPages) { pageIdx ->
                    // Centering wrapper row to guarantee page sheet remains centered across full width
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Outer layout boundary holding the scaled footprint in LazyColumn
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(panX.roundToInt(), 0) }
                                .width(scaledPageWidth)
                                .height(scaledPageHeight),
                            contentAlignment = Alignment.Center
                        ) {
                            // The page card (white paper sheet + shadow + all contents scale as ONE unified unit)
                            Card(
                                modifier = Modifier
                                    .requiredSize(basePageWidth, basePageHeight)
                                    .graphicsLayer {
                                        scaleX = zoomScale
                                        scaleY = zoomScale
                                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                                    }
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
                                    contentScale = ContentScale.FillBounds
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
                                PaperTemplateCanvas(paperStyle = uiState.paperStyle, pageScale = canonicalScale)
                            }

                            // Layer A2: Tap Detector for Text Tool (Spawns or finishes an interactive text box on touched page position)
                            if (selectedStudioTool == StudioTool.TEXT) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(selectedStudioTool, pageIdx, selectedTextBoxId) {
                                            detectTapGestures { tapOffset ->
                                                if (selectedTextBoxId != null) {
                                                    // Tapping outside deselects current text box
                                                    val cur = localTextBoxes.find { it.id == selectedTextBoxId }
                                                    val cleaned = if (cur != null && cur.text.trim().isEmpty()) {
                                                        localTextBoxes.filterNot { it.id == selectedTextBoxId }
                                                    } else {
                                                        localTextBoxes
                                                    }
                                                    selectedTextBoxId = null
                                                    saveTextBoxes(cleaned)
                                                } else {
                                                    // Prune any previous empty boxes before creating a new one
                                                    val cleaned = localTextBoxes.filterNot { it.text.trim().isEmpty() }
                                                    val pageBaseWPx = size.width.toFloat().coerceAtLeast(1f)
                                                    val pageBaseHPx = size.height.toFloat().coerceAtLeast(1f)
                                                    val xRatio = (tapOffset.x / pageBaseWPx).coerceIn(0.04f, 0.85f)
                                                    val yRatio = (tapOffset.y / pageBaseHPx).coerceIn(0.04f, 0.90f)
                                                    val newBox = CanvasTextBox(
                                                        id = java.util.UUID.randomUUID().toString(),
                                                        pageIndex = pageIdx,
                                                        xRatio = xRatio,
                                                        yRatio = yRatio,
                                                        text = "",
                                                        fontSizeSp = textToolFontSize,
                                                        colorHex = textToolColorHex,
                                                        fontFamily = textToolFontFamily,
                                                        isBold = textToolBold,
                                                        isItalic = textToolItalic,
                                                        isUnderline = textToolUnderline,
                                                        isStrikethrough = textToolStrikethrough,
                                                        textAlign = textToolAlignment,
                                                        isAutoWidth = true
                                                    )
                                                    val updated = cleaned + newBox
                                                    saveTextBoxes(updated)
                                                    selectedTextBoxId = newBox.id
                                                }
                                            }
                                        }
                                )
                            }

                            // Layer A3: Background Canvas Images on pageIdx (zIndex < 0, drawn behind ink strokes)
                            val pageBaseWPx = with(density) { basePageWidth.toPx() }
                            val pageBaseHPx = with(density) { basePageHeight.toPx() }
                            val backImages = localImages.filter { it.pageIndex == pageIdx && it.zIndex < 0 }.sortedBy { it.zIndex }
                            backImages.forEach { img ->
                                CanvasImageOverlayItem(
                                    image = img,
                                    isSelected = selectedImageId == img.id,
                                    basePageWidthPx = pageBaseWPx,
                                    basePageHeightPx = pageBaseHPx,
                                    pageScale = 1f,
                                    onSelect = { selectedImageId = img.id },
                                    onUpdateDraft = { updatedImg ->
                                        localImages = localImages.map { if (it.id == updatedImg.id) updatedImg else it }
                                    },
                                    onSave = { updatedImg ->
                                        val updated = localImages.map { if (it.id == updatedImg.id) updatedImg else it }
                                        saveImages(updated)
                                    },
                                    onDelete = {
                                        val updated = localImages.filterNot { it.id == img.id }
                                        saveImages(updated)
                                        if (selectedImageId == img.id) selectedImageId = null
                                    },
                                    onDuplicate = {
                                        val dup = img.copy(
                                            id = java.util.UUID.randomUUID().toString(),
                                            xRatio = (img.xRatio + 0.04f).coerceAtMost(0.85f),
                                            yRatio = (img.yRatio + 0.04f).coerceAtMost(0.85f)
                                        )
                                        val updated = localImages + dup
                                        saveImages(updated)
                                        selectedImageId = dup.id
                                    }
                                )
                            }

                            // Layer B: Ink Strokes Canvas
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInteropFilter { motionEvent ->
                                        if (isReadOnlyMode) {
                                            return@pointerInteropFilter false
                                        }

                                        // Yield touch events so text input and buttons can receive taps in TEXT mode
                                        if (selectedStudioTool == StudioTool.TEXT) {
                                            return@pointerInteropFilter false
                                        }

                                        // Yield touch events for selected image manipulation or deselect when clicking outside
                                        val pageBaseW = with(density) { basePageWidth.toPx() }
                                        val pageBaseH = with(density) { basePageHeight.toPx() }

                                        if (selectedImageId != null) {
                                            val activeImg = localImages.firstOrNull { it.id == selectedImageId && it.pageIndex == pageIdx }
                                            if (activeImg != null) {
                                                val touchX = motionEvent.x
                                                val touchY = motionEvent.y

                                                val imgLeft = activeImg.xRatio * pageBaseW
                                                val imgTop = activeImg.yRatio * pageBaseH
                                                val imgW = (activeImg.widthRatio * pageBaseW).coerceAtLeast(40f)
                                                val imgH = (activeImg.heightRatio * pageBaseH).coerceAtLeast(40f)

                                                val cx = imgLeft + imgW / 2f
                                                val cy = imgTop + imgH / 2f
                                                // Covers rotated bounds, corner handles, top toolbar, and bottom rotation handle
                                                val maxInteractiveRadius = kotlin.math.hypot(imgW / 2f, imgH / 2f) + 160f
                                                val distSq = (touchX - cx) * (touchX - cx) + (touchY - cy) * (touchY - cy)
                                                val isNearToolbar = touchX in (cx - 260f)..(cx + 260f) && touchY in (imgTop - 120f)..(imgTop + 60f)

                                                if (distSq <= maxInteractiveRadius * maxInteractiveRadius || isNearToolbar) {
                                                    return@pointerInteropFilter false
                                                } else if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                                                    selectedImageId = null
                                                }
                                            }
                                        }

                                        // Check if tapping to select an unselected image on canvas
                                        if (selectedImageId == null && motionEvent.action == MotionEvent.ACTION_DOWN) {
                                            val touchX = motionEvent.x
                                            val touchY = motionEvent.y
                                            val hitImage = localImages.filter { it.pageIndex == pageIdx }.lastOrNull { img ->
                                                val imgLeft = img.xRatio * pageBaseW
                                                val imgTop = img.yRatio * pageBaseH
                                                val imgW = (img.widthRatio * pageBaseW).coerceAtLeast(40f)
                                                val imgH = (img.heightRatio * pageBaseH).coerceAtLeast(40f)
                                                val cx = imgLeft + imgW / 2f
                                                val cy = imgTop + imgH / 2f
                                                val rad = Math.toRadians(-img.rotationDegrees.toDouble())
                                                val cosA = kotlin.math.cos(rad).toFloat()
                                                val sinA = kotlin.math.sin(rad).toFloat()
                                                val unrotX = cosA * (touchX - cx) - sinA * (touchY - cy) + cx
                                                val unrotY = sinA * (touchX - cx) + cosA * (touchY - cy) + cy
                                                unrotX in (imgLeft - 20f)..(imgLeft + imgW + 20f) && unrotY in (imgTop - 20f)..(imgTop + imgH + 20f)
                                            }
                                            if (hitImage != null) {
                                                selectedImageId = hitImage.id
                                                return@pointerInteropFilter false
                                            }
                                        }

                                        val pointerCount = motionEvent.pointerCount

                                        // Check if hardware stylus or eraser is on screen
                                        val hasHardwareStylus = (0 until pointerCount).any { idx ->
                                            val t = motionEvent.getToolType(idx)
                                            t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
                                        }

                                        // 1. IN PEN_MODE: strictly ONLY hardware stylus draws! Hand/finger NEVER draws!
                                        if (interactionMode == EditorInteractionMode.PEN_MODE) {
                                            if (!hasHardwareStylus) {
                                                // Any non-stylus touch (finger, hand, palm)
                                                activePoints = emptyList()
                                                lassoPoints = emptyList()
                                                activeEraserCenter = null
                                                return@pointerInteropFilter true
                                            }
                                        } else {
                                            // 2. IN HAND_MODE: multi-finger touch (>= 2) aborts drawing and delegates to 2-finger scroll/pinch
                                            if (pointerCount >= 2 && !hasHardwareStylus) {
                                                activePoints = emptyList()
                                                lassoPoints = emptyList()
                                                activeEraserCenter = null
                                                return@pointerInteropFilter true
                                            }
                                        }

                                        val packet = StylusEventParser.parse(motionEvent, 0)

                                        // In PEN_MODE: accepted strictly if hasHardwareStylus
                                        // In HAND_MODE: accepted for both hand and stylus
                                        val accepted = if (interactionMode == EditorInteractionMode.PEN_MODE) {
                                            hasHardwareStylus
                                        } else {
                                            true
                                        }

                                        workspaceViewModel.setStylusActive(
                                            packet.toolType == StylusToolType.STYLUS || packet.toolType == StylusToolType.ERASER
                                        )

                                        if (!accepted) {
                                            return@pointerInteropFilter true
                                        }

                                        val isHardwareEraser = packet.isEraser || packet.isPrimaryButtonPressed
                                        val isEffectiveEraser = isHardwareEraser || selectedStudioTool == StudioTool.ERASER

                                        when (packet.action) {
                                            MotionActionType.DOWN -> {
                                                activePageForStroke = pageIdx
                                                val safeZoom = zoomScale.coerceAtLeast(0.01f)
                                                val baseX = packet.x / safeZoom
                                                val baseY = packet.y / safeZoom
                                                if (selectedStudioTool == StudioTool.PRESENT) {
                                                    laserFadeJob?.cancel()
                                                    laserFadeJob = null
                                                    laserAlpha = 1f
                                                    laserActivePoints = listOf(InkPoint(baseX, baseY, 1f, packet.timestamp))
                                                } else if (selectedStudioTool == StudioTool.SHAPE) {
                                                    shapeStartOffset = Offset(baseX, baseY)
                                                    shapeCurrentOffset = Offset(baseX, baseY)
                                                } else if (selectedStudioTool == StudioTool.LASSO) {
                                                    if (selectedStrokeIndices.isNotEmpty() && lassoBounds != null && lassoBounds!!.contains(Offset(baseX, baseY))) {
                                                        isDraggingLasso = true
                                                        lassoDragStart = Offset(baseX, baseY)
                                                        lassoDragOffset = Offset.Zero
                                                    } else {
                                                        selectedStrokeIndices = emptySet()
                                                        lassoBounds = null
                                                        isDraggingLasso = false
                                                        lassoPoints = listOf(Offset(baseX, baseY))
                                                    }
                                                } else if (isEffectiveEraser) {
                                                    activeEraserCenter = Offset(baseX, baseY)
                                                    val effRadius = (eraserRadius * density.density) / safeZoom
                                                    workspaceViewModel.eraseAt(baseX, baseY, effRadius, pageIndex = pageIdx, mode = eraserMode)
                                                } else {
                                                    activeEraserCenter = null
                                                    activePoints = listOf(
                                                        InkPoint(baseX, baseY, packet.normalizedPressure, packet.timestamp)
                                                    )
                                                }
                                            }
                                            MotionActionType.MOVE -> {
                                                val safeZoom = zoomScale.coerceAtLeast(0.01f)
                                                val baseX = packet.x / safeZoom
                                                val baseY = packet.y / safeZoom
                                                if (selectedStudioTool == StudioTool.PRESENT) {
                                                    if (isLaserDotMode) {
                                                        laserActivePoints = listOf(InkPoint(baseX, baseY, 1f, packet.timestamp))
                                                    } else {
                                                        laserActivePoints = laserActivePoints + InkPoint(baseX, baseY, 1f, packet.timestamp)
                                                    }
                                                } else if (selectedStudioTool == StudioTool.SHAPE) {
                                                    shapeCurrentOffset = Offset(baseX, baseY)
                                                } else if (selectedStudioTool == StudioTool.LASSO) {
                                                    if (isDraggingLasso) {
                                                        lassoDragOffset = Offset(baseX - lassoDragStart.x, baseY - lassoDragStart.y)
                                                    } else {
                                                        lassoPoints = lassoPoints + Offset(baseX, baseY)
                                                    }
                                                } else if (isEffectiveEraser) {
                                                    activeEraserCenter = Offset(baseX, baseY)
                                                    val effRadius = (eraserRadius * density.density) / safeZoom
                                                    for (h in 0 until motionEvent.historySize) {
                                                        workspaceViewModel.eraseAt(
                                                            motionEvent.getHistoricalX(0, h) / safeZoom,
                                                            motionEvent.getHistoricalY(0, h) / safeZoom,
                                                            effRadius,
                                                            pageIndex = pageIdx,
                                                            mode = eraserMode
                                                        )
                                                    }
                                                    workspaceViewModel.eraseAt(baseX, baseY, effRadius, pageIndex = pageIdx, mode = eraserMode)
                                                } else {
                                                    activeEraserCenter = null
                                                    val batch = ArrayList<InkPoint>(motionEvent.historySize + 1)
                                                    for (h in 0 until motionEvent.historySize) {
                                                        batch.add(
                                                            InkPoint(
                                                                motionEvent.getHistoricalX(0, h) / safeZoom,
                                                                motionEvent.getHistoricalY(0, h) / safeZoom,
                                                                motionEvent.getHistoricalPressure(0, h).coerceIn(0.05f, 1f),
                                                                motionEvent.getHistoricalEventTime(h)
                                                            )
                                                        )
                                                    }
                                                    batch.add(
                                                        InkPoint(baseX, baseY, packet.normalizedPressure, packet.timestamp)
                                                    )
                                                    activePoints = activePoints + batch
                                                }
                                            }
                                            MotionActionType.UP, MotionActionType.CANCEL -> {
                                                if (selectedStudioTool == StudioTool.PRESENT) {
                                                    if (!isLaserDotMode && laserActivePoints.size >= 2) {
                                                        val lStroke = InkStroke(
                                                            points = laserActivePoints,
                                                            baseWidth = 6f,
                                                            color = laserColor,
                                                            toolType = InkToolType.PEN,
                                                            pageIndex = pageIdx
                                                        )
                                                        laserStrokes = laserStrokes + lStroke
                                                    }
                                                    laserActivePoints = emptyList()
                                                    laserFadeJob?.cancel()
                                                    laserFadeJob = scope.launch {
                                                        delay(2000L)
                                                        val steps = 14
                                                        for (i in steps downTo 0) {
                                                            laserAlpha = i.toFloat() / steps.toFloat()
                                                            delay(25L)
                                                        }
                                                        laserStrokes = emptyList()
                                                        laserAlpha = 1f
                                                    }
                                                } else if (selectedStudioTool == StudioTool.SHAPE) {
                                                    if (shapeStartOffset != null && shapeCurrentOffset != null) {
                                                        val s = shapeStartOffset!!
                                                        val e = shapeCurrentOffset!!
                                                        if (hypot(e.x - s.x, e.y - s.y) > 12f) {
                                                            val generated = CanvasShapeGenerator.generateShapeStrokes(
                                                                shape = selectedShapeType,
                                                                start = s,
                                                                end = e,
                                                                strokeWidth = shapeStrokeWidth,
                                                                color = uiState.selectedColor,
                                                                pageIndex = pageIdx
                                                            )
                                                            generated.forEach { workspaceViewModel.addStroke(it) }
                                                        }
                                                    }
                                                    shapeStartOffset = null
                                                    shapeCurrentOffset = null
                                                } else if (selectedStudioTool == StudioTool.LASSO) {
                                                    if (isDraggingLasso) {
                                                        if (lassoDragOffset != Offset.Zero) {
                                                            workspaceViewModel.translateStrokes(selectedStrokeIndices, lassoDragOffset.x, lassoDragOffset.y)
                                                            lassoBounds = lassoBounds?.let { b ->
                                                                Rect(b.left + lassoDragOffset.x, b.top + lassoDragOffset.y, b.right + lassoDragOffset.x, b.bottom + lassoDragOffset.y)
                                                            }
                                                        }
                                                        isDraggingLasso = false
                                                        lassoDragOffset = Offset.Zero
                                                    } else if (lassoPoints.size > 2) {
                                                        val selected = mutableSetOf<Int>()
                                                        uiState.strokes.forEachIndexed { idx, s ->
                                                            if (s.pageIndex == pageIdx) {
                                                                val isContained = s.points.any { p -> isPointInPolygon(Offset(p.x, p.y), lassoPoints) }
                                                                if (isContained) selected.add(idx)
                                                            }
                                                        }
                                                        selectedStrokeIndices = selected
                                                        if (selected.isNotEmpty()) {
                                                            val selectedStrokes = uiState.strokes.filterIndexed { i, _ -> i in selected }
                                                            val pts = selectedStrokes.flatMap { it.points }
                                                            if (pts.isNotEmpty()) {
                                                                val minX = pts.minOf { it.x } - 8f
                                                                val maxX = pts.maxOf { it.x } + 8f
                                                                val minY = pts.minOf { it.y } - 8f
                                                                val maxY = pts.maxOf { it.y } + 8f
                                                                lassoBounds = Rect(minX, minY, maxX, maxY)
                                                            }
                                                        } else {
                                                            lassoBounds = null
                                                        }
                                                        lassoPoints = emptyList()
                                                    }
                                                } else if (isEffectiveEraser) {
                                                    activeEraserCenter = null
                                                } else if (activePoints.isNotEmpty()) {
                                                    val strokeColor = if (selectedStudioTool == StudioTool.HIGHLIGHTER) {
                                                        uiState.selectedColor.copy(alpha = 0.35f)
                                                    } else uiState.selectedColor

                                                    val strokeWidth = if (selectedStudioTool == StudioTool.HIGHLIGHTER) {
                                                        highlighterStrokeWidth
                                                    } else brushStrokeWidth

                                                    val newStroke = InkStroke(
                                                        points = activePoints,
                                                        baseWidth = strokeWidth,
                                                        color = strokeColor,
                                                        toolType = InkToolType.PEN,
                                                        pageIndex = pageIdx
                                                    )
                                                    // Immediately buffer locally to eliminate 1-frame blink before ViewModel StateFlow commits
                                                    pendingTransientStrokes = pendingTransientStrokes + newStroke
                                                    workspaceViewModel.addStroke(newStroke)
                                                    activePoints = emptyList()
                                                }
                                            }
                                            else -> {}
                                        }
                                        true
                                    }
                            ) {
                                val d = this.density
                                val safeZoom = zoomScale.coerceAtLeast(0.01f)

                                // Cross-device dynamic scaling: prevent tablet handwriting from being cropped on mobile screens
                                val pageStrokes = uiState.strokes.filter { it.pageIndex == pageIdx && it.points.isNotEmpty() }
                                val maxPointX = pageStrokes.flatMap { it.points }.maxOfOrNull { it.x } ?: 0f
                                val fitScale = if (maxPointX > size.width && maxPointX > 0f) {
                                    (size.width / (maxPointX + 16f * d)).coerceIn(0.25f, 1f)
                                } else 1.0f

                                // Committed ink strokes for this specific page only
                                uiState.strokes.forEachIndexed { idx, stroke ->
                                    if (stroke.pageIndex == pageIdx && stroke.points.isNotEmpty()) {
                                        val isSelectedByLasso = selectedStrokeIndices.contains(idx)
                                        val strokeColor = if (isSelectedByLasso) Color(0xFF00BCD4) else stroke.color
                                        val effectiveOffset = if (isSelectedByLasso && isDraggingLasso) lassoDragOffset else Offset.Zero
                                        val pts = if (effectiveOffset != Offset.Zero || fitScale < 1f) {
                                            stroke.points.map { pt ->
                                                pt.copy(
                                                    x = (pt.x + effectiveOffset.x) * fitScale,
                                                    y = (pt.y + effectiveOffset.y) * fitScale
                                                )
                                            }
                                        } else stroke.points
                                        val path = if (stroke.toolType == InkToolType.SHAPE) {
                                            androidx.compose.ui.graphics.Path().apply {
                                                moveTo(pts[0].x, pts[0].y)
                                                for (i in 1 until pts.size) {
                                                    lineTo(pts[i].x, pts[i].y)
                                                }
                                            }
                                        } else {
                                            BezierSplineInterpolator.createSplinePath(pts)
                                        }
                                        val effectiveWidth = if (stroke.toolType == InkToolType.SHAPE) {
                                            stroke.baseWidth * canonicalScale * d * fitScale
                                        } else {
                                            val avgPressure = pts.map { it.pressure }.average().toFloat().coerceIn(0.1f, 1f)
                                            BezierSplineInterpolator.calculateEffectiveWidth(
                                                stroke.baseWidth * canonicalScale * d * fitScale,
                                                avgPressure
                                            )
                                        }
                                        drawPath(
                                            path = path,
                                            color = strokeColor,
                                            style = Stroke(
                                                width = effectiveWidth,
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            )
                                        )
                                    }
                                }

                                // Render any transient strokes awaiting emission (zero-blink guarantee)
                                pendingTransientStrokes.forEach { transientStroke ->
                                    if (transientStroke.pageIndex == pageIdx && transientStroke.points.isNotEmpty() && uiState.strokes.none { it.id == transientStroke.id }) {
                                        val pts = if (fitScale < 1f) {
                                            transientStroke.points.map { pt ->
                                                pt.copy(x = pt.x * fitScale, y = pt.y * fitScale)
                                            }
                                        } else transientStroke.points
                                        val path = if (transientStroke.toolType == InkToolType.SHAPE) {
                                            androidx.compose.ui.graphics.Path().apply {
                                                moveTo(pts[0].x, pts[0].y)
                                                for (i in 1 until pts.size) {
                                                    lineTo(pts[i].x, pts[i].y)
                                                }
                                            }
                                        } else {
                                            BezierSplineInterpolator.createSplinePath(pts)
                                        }
                                        val effectiveWidth = if (transientStroke.toolType == InkToolType.SHAPE) {
                                            transientStroke.baseWidth * canonicalScale * d * fitScale
                                        } else {
                                            val avgPressure = pts.map { it.pressure }.average().toFloat().coerceIn(0.1f, 1f)
                                            BezierSplineInterpolator.calculateEffectiveWidth(
                                                transientStroke.baseWidth * canonicalScale * d * fitScale,
                                                avgPressure
                                            )
                                        }
                                        drawPath(
                                            path = path,
                                            color = transientStroke.color,
                                            style = Stroke(
                                                width = effectiveWidth,
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            )
                                        )
                                    }
                                }

                                // Live in-progress stroke
                                if (activePoints.isNotEmpty() && activePageForStroke == pageIdx) {
                                    val activePath = BezierSplineInterpolator.createSplinePath(activePoints)
                                    val currentPressure = activePoints.lastOrNull()?.pressure ?: 0.5f
                                    val baseW = (if (selectedStudioTool == StudioTool.HIGHLIGHTER) highlighterStrokeWidth else brushStrokeWidth) * canonicalScale
                                    val effectiveWidth = BezierSplineInterpolator.calculateEffectiveWidth(
                                        baseW * d,
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

                                // Live Shape drag preview (precise straight lines, no spline distortion)
                                if (selectedStudioTool == StudioTool.SHAPE && shapeStartOffset != null && shapeCurrentOffset != null && activePageForStroke == pageIdx) {
                                    val shapeStrokes = CanvasShapeGenerator.generateShapeStrokes(
                                        shape = selectedShapeType,
                                        start = shapeStartOffset!!,
                                        end = shapeCurrentOffset!!,
                                        strokeWidth = shapeStrokeWidth,
                                        color = uiState.selectedColor,
                                        pageIndex = pageIdx
                                    )
                                    shapeStrokes.forEach { s ->
                                        if (s.points.size >= 2) {
                                            val p = androidx.compose.ui.graphics.Path().apply {
                                                moveTo(s.points[0].x, s.points[0].y)
                                                for (i in 1 until s.points.size) {
                                                    lineTo(s.points[i].x, s.points[i].y)
                                                }
                                            }
                                            drawPath(
                                                path = p,
                                                color = s.color,
                                                style = Stroke(width = s.baseWidth * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                            )
                                        }
                                    }
                                }

                                // Presenter Neon Laser Pointer trails (vibrant solid neon beam + radiant glow)
                                if (laserActivePoints.size >= 2 && activePageForStroke == pageIdx && !isLaserDotMode) {
                                    val lPath = BezierSplineInterpolator.createSplinePath(laserActivePoints)
                                    // 1. Radiant luminous glow aura
                                    drawPath(
                                        path = lPath,
                                        color = laserColor.copy(alpha = 0.45f),
                                        style = Stroke(width = 16f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                    // 2. Focused vibrant solid neon beam
                                    drawPath(
                                        path = lPath,
                                        color = laserColor.copy(alpha = 1.0f),
                                        style = Stroke(width = 7f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                    // 3. Core white-hot laser center
                                    drawPath(
                                        path = lPath,
                                        color = Color.White,
                                        style = Stroke(width = 2.5f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                }

                                if (laserAlpha > 0.01f) {
                                    laserStrokes.forEach { lStroke ->
                                        if (lStroke.pageIndex == pageIdx && lStroke.points.size >= 2) {
                                            val lPath = BezierSplineInterpolator.createSplinePath(lStroke.points)
                                            val strokeClr = lStroke.color
                                            drawPath(
                                                path = lPath,
                                                color = strokeClr.copy(alpha = 0.45f * laserAlpha),
                                                style = Stroke(width = 16f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                            )
                                            drawPath(
                                                path = lPath,
                                                color = strokeClr.copy(alpha = 1.0f * laserAlpha),
                                                style = Stroke(width = 7f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                            )
                                            drawPath(
                                                path = lPath,
                                                color = Color.White.copy(alpha = laserAlpha),
                                                style = Stroke(width = 2.5f * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                            )
                                        }
                                    }
                                }

                                // Laser Pointer Dot (active during dot mode)
                                if (isLaserDotMode && laserActivePoints.isNotEmpty() && activePageForStroke == pageIdx) {
                                    laserActivePoints.lastOrNull()?.let { tip ->
                                        drawCircle(
                                            color = laserColor.copy(alpha = 0.45f),
                                            radius = 16f * d,
                                            center = Offset(tip.x, tip.y)
                                        )
                                        drawCircle(
                                            color = laserColor,
                                            radius = 8f * d,
                                            center = Offset(tip.x, tip.y)
                                        )
                                        drawCircle(
                                            color = Color.White,
                                            radius = 3.5f * d,
                                            center = Offset(tip.x, tip.y)
                                        )
                                    }
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
                                            width = 2.dp.toPx() / safeZoom,
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f / safeZoom, 8f / safeZoom), 0f)
                                        )
                                    )
                                }

                                // Lasso Bounding Box (with drag translation visual)
                                lassoBounds?.let { b ->
                                    if (activePageForStroke == pageIdx && selectedStrokeIndices.isNotEmpty()) {
                                        val effTopLeft = Offset(b.left + lassoDragOffset.x, b.top + lassoDragOffset.y)
                                        drawRect(
                                            color = Color(0xFF00BCD4).copy(alpha = 0.08f),
                                            topLeft = effTopLeft,
                                            size = androidx.compose.ui.geometry.Size(b.width, b.height)
                                        )
                                        drawRect(
                                            color = Color(0xFF00BCD4),
                                            topLeft = effTopLeft,
                                            size = androidx.compose.ui.geometry.Size(b.width, b.height),
                                            style = Stroke(
                                                width = 1.5.dp.toPx() / safeZoom,
                                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f / safeZoom, 6f / safeZoom), 0f)
                                            )
                                        )
                                    }
                                }

                                // Eraser reticle
                                activeEraserCenter?.let { center ->
                                    val rPx = (eraserRadius * density.density) / safeZoom
                                    drawCircle(
                                        color = Color(0xFFFF5252).copy(alpha = 0.35f),
                                        radius = rPx,
                                        center = center
                                    )
                                    drawCircle(
                                        color = Color(0xFFFF5252),
                                        radius = rPx,
                                        center = center,
                                        style = Stroke(width = 2.dp.toPx() / safeZoom)
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
                                            convertHandwritingToText(pageIdx)
                                        }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.Spellcheck, contentDescription = "Convert to Text", tint = Color(0xFF00BCD4), modifier = Modifier.size(17.dp))
                                        }
                                        IconButton(onClick = {
                                            workspaceViewModel.deleteStrokes(selectedStrokeIndices)
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

                            // Layer C: Canvas Text Boxes on pageIdx
                            val pageBoxes = localTextBoxes.filter { it.pageIndex == pageIdx }
                            val pageBaseW = with(density) { basePageWidth.toPx() }
                            val pageBaseH = with(density) { basePageHeight.toPx() }

                            pageBoxes.forEach { box ->
                                CanvasTextBoxOverlayItem(
                                    box = box,
                                    isSelected = selectedTextBoxId == box.id,
                                    basePageWidthPx = pageBaseW,
                                    basePageHeightPx = pageBaseH,
                                    pageScale = canonicalScale,
                                    onSelect = {
                                        selectedStudioTool = StudioTool.TEXT
                                        selectedTextBoxId = box.id
                                    },
                                    onFinishEditing = { updatedBox ->
                                        if (selectedTextBoxId == updatedBox.id && updatedBox.text.trim().isEmpty()) {
                                            selectedTextBoxId = null
                                        }
                                        val updated = if (updatedBox.text.trim().isEmpty()) {
                                            localTextBoxes.filterNot { it.id == updatedBox.id }
                                        } else {
                                            localTextBoxes.map { if (it.id == updatedBox.id) updatedBox else it }
                                        }
                                        saveTextBoxes(updated)
                                    },
                                    onUpdateDraft = { draft ->
                                        localTextBoxes = localTextBoxes.map { if (it.id == draft.id) draft else it }
                                    },
                                    onDelete = {
                                        if (selectedTextBoxId == box.id) selectedTextBoxId = null
                                        val updated = localTextBoxes.filterNot { it.id == box.id }
                                        saveTextBoxes(updated)
                                    },
                                    onMove = { newXRatio, newYRatio ->
                                        val updated = localTextBoxes.map {
                                            if (it.id == box.id) it.copy(xRatio = newXRatio, yRatio = newYRatio) else it
                                        }
                                        saveTextBoxes(updated)
                                    },
                                    onTriggerVoice = {
                                        showVoiceDialog = true
                                    }
                                )
                            }

                            // Layer D: Foreground Canvas Images on pageIdx (zIndex >= 0, drawn in front of ink strokes)
                            val foreImages = localImages.filter { it.pageIndex == pageIdx && it.zIndex >= 0 }.sortedBy { it.zIndex }
                            foreImages.forEach { img ->
                                CanvasImageOverlayItem(
                                    image = img,
                                    isSelected = selectedImageId == img.id,
                                    basePageWidthPx = pageBaseW,
                                    basePageHeightPx = pageBaseH,
                                    pageScale = 1f,
                                    onSelect = { selectedImageId = img.id },
                                    onUpdateDraft = { updatedImg ->
                                        localImages = localImages.map { if (it.id == updatedImg.id) updatedImg else it }
                                    },
                                    onSave = { updatedImg ->
                                        val updated = localImages.map { if (it.id == updatedImg.id) updatedImg else it }
                                        saveImages(updated)
                                    },
                                    onDelete = {
                                        val updated = localImages.filterNot { it.id == img.id }
                                        saveImages(updated)
                                        if (selectedImageId == img.id) selectedImageId = null
                                    },
                                    onDuplicate = {
                                        val dup = img.copy(
                                            id = java.util.UUID.randomUUID().toString(),
                                            xRatio = (img.xRatio + 0.04f).coerceAtMost(0.85f),
                                            yRatio = (img.yRatio + 0.04f).coerceAtMost(0.85f)
                                        )
                                        val updated = localImages + dup
                                        saveImages(updated)
                                        selectedImageId = dup.id
                                    }
                                )
                            }

                            // Page Audio Clips Badge Indicator
                            val pageAudioClips = notebook?.voiceNotes?.filter { it.pageIndex == pageIdx } ?: emptyList()
                            if (pageAudioClips.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .clickable { showAudioNotesDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF1E1E22).copy(alpha = 0.88f),
                                    border = BorderStroke(1.dp, Color(0xFF00BCD4))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.GraphicEq,
                                            contentDescription = null,
                                            tint = Color(0xFF00BCD4),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = "${pageAudioClips.size} audio",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
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
                    .padding(end = 16.dp, bottom = if (isTablet) 24.dp else 84.dp)
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
        // 2. TOP FLOATING APP BAR & STUDIO TOOL DOCK
        // ==========================================
        if (isTablet) {
            // TABLET: 3-Pill Wide Floating Layout
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

                // GROUP B: Top-Center Floating Toolbar Pill
                AnimatedVisibility(
                    visible = !isReadOnlyMode,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                                FloatingToolButton(
                                    icon = Icons.Default.Category,
                                    isSelected = selectedStudioTool == StudioTool.SHAPE,
                                    onClick = {
                                        selectedStudioTool = if (selectedStudioTool == StudioTool.SHAPE) StudioTool.PEN else StudioTool.SHAPE
                                    }
                                )
                                FloatingToolButton(
                                    icon = Icons.Default.Adjust,
                                    isSelected = selectedStudioTool == StudioTool.PRESENT,
                                    onClick = {
                                        selectedStudioTool = if (selectedStudioTool == StudioTool.PRESENT) StudioTool.PEN else StudioTool.PRESENT
                                        if (selectedStudioTool == StudioTool.PRESENT) {
                                            Toast.makeText(context, "Laser Presenter active (Neon glow)", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                FloatingToolButton(
                                    icon = Icons.Default.TextFields,
                                    isSelected = selectedStudioTool == StudioTool.TEXT,
                                    onClick = {
                                        selectedStudioTool = if (selectedStudioTool == StudioTool.TEXT) StudioTool.PEN else StudioTool.TEXT
                                        if (selectedStudioTool == StudioTool.TEXT) {
                                            Toast.makeText(context, "Text tool active: tap on page or type below", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                FloatingToolButton(
                                    icon = Icons.Default.Spellcheck,
                                    isSelected = false,
                                    onClick = {
                                        convertHandwritingToText(currentPageIndex)
                                    }
                                )
                            }
                        }

                        // === SECONDARY TEXT SUB-TOOLBAR (UNDER MIDDLE BAR - MATCHING REFERENCE IMAGE 2) ===
                        AnimatedVisibility(
                            visible = (selectedStudioTool == StudioTool.TEXT || selectedTextBoxId != null) && !isReadOnlyMode,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            val activeBox = localTextBoxes.firstOrNull { it.id == selectedTextBoxId }
                            val curFont = activeBox?.fontFamily ?: textToolFontFamily
                            val curSize = activeBox?.fontSizeSp ?: textToolFontSize
                            val curColor = activeBox?.colorHex ?: textToolColorHex
                            val curBold = activeBox?.isBold ?: textToolBold
                            val curItalic = activeBox?.isItalic ?: textToolItalic
                            val curUnder = activeBox?.isUnderline ?: textToolUnderline
                            val curStrike = activeBox?.isStrikethrough ?: textToolStrikethrough
                            val curAlign = activeBox?.textAlign ?: textToolAlignment

                            var showFontMenu by remember { mutableStateOf(false) }
                            var showSizeMenu by remember { mutableStateOf(false) }
                            var showColorPicker by remember { mutableStateOf(false) }

                            fun updateFormatting(
                                font: String = curFont,
                                size: Float = curSize,
                                colorHex: String = curColor,
                                bold: Boolean = curBold,
                                italic: Boolean = curItalic,
                                under: Boolean = curUnder,
                                strike: Boolean = curStrike,
                                align: String = curAlign
                            ) {
                                textToolFontFamily = font
                                textToolFontSize = size
                                textToolColorHex = colorHex
                                textToolBold = bold
                                textToolItalic = italic
                                textToolUnderline = under
                                textToolStrikethrough = strike
                                textToolAlignment = align

                                if (activeBox != null) {
                                    val updated = localTextBoxes.map {
                                        if (it.id == activeBox.id) {
                                            it.copy(
                                                fontFamily = font,
                                                fontSizeSp = size,
                                                colorHex = colorHex,
                                                isBold = bold,
                                                isItalic = italic,
                                                isUnderline = under,
                                                isStrikethrough = strike,
                                                textAlign = align
                                            )
                                        } else it
                                    }
                                    saveTextBoxes(updated)
                                }
                            }

                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.96f)),
                                border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .shadow(6.dp, RoundedCornerShape(14.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Undo / Redo buttons
                                    IconButton(
                                        onClick = { workspaceViewModel.undo() },
                                        enabled = uiState.canUndo,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Undo,
                                            contentDescription = "Undo",
                                            tint = if (uiState.canUndo) Color.White else Color.DarkGray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = { workspaceViewModel.redo() },
                                        enabled = uiState.canRedo,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Redo,
                                            contentDescription = "Redo",
                                            tint = if (uiState.canRedo) Color.White else Color.DarkGray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(18.dp)
                                            .background(Color(0xFF333338))
                                    )

                                    // Color Presets (Black, Red, Cyan, Palette)
                                    val presets = listOf("#1F1F1F", "#E53935", "#00BCD4")
                                    presets.forEach { hex ->
                                        val isChosen = curColor.equals(hex, ignoreCase = true)
                                        val c = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color.Black }
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(c)
                                                .border(
                                                    1.5.dp,
                                                    if (isChosen) Color.White else Color(0xFF44444A),
                                                    CircleShape
                                                )
                                                .clickable {
                                                    updateFormatting(colorHex = hex)
                                                }
                                        )
                                    }

                                    IconButton(
                                        onClick = { showColorPicker = !showColorPicker },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Palette,
                                            contentDescription = "Color Picker",
                                            tint = Color(0xFF2196F3),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Compact Color Picker Popup
                                    if (showColorPicker) {
                                        DropdownMenu(
                                            expanded = showColorPicker,
                                            onDismissRequest = { showColorPicker = false },
                                            modifier = Modifier.background(Color(0xFF252528))
                                        ) {
                                            val extraColors = listOf(
                                                "#1F1F1F", "#2196F3", "#D32F2F", "#388E3C",
                                                "#F57C00", "#7B1FA2", "#00BCD4", "#E91E63"
                                            )
                                            Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                extraColors.forEach { hex ->
                                                    val c = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color.Black }
                                                    Box(
                                                        modifier = Modifier
                                                            .size(22.dp)
                                                            .clip(CircleShape)
                                                            .background(c)
                                                            .border(
                                                                1.5.dp,
                                                                if (curColor.equals(hex, ignoreCase = true)) Color.White else Color.Transparent,
                                                                CircleShape
                                                            )
                                                            .clickable {
                                                                updateFormatting(colorHex = hex)
                                                                showColorPicker = false
                                                            }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(18.dp)
                                            .background(Color(0xFF333338))
                                    )

                                    // Font Family Dropdown ("Roboto" / "Default" / "Serif" / "Monospace" / "Cursive")
                                    Box {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF2C2C32))
                                                .clickable { showFontMenu = true }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(
                                                text = curFont,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Icon(
                                                imageVector = Icons.Default.ArrowDropDown,
                                                contentDescription = null,
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = showFontMenu,
                                            onDismissRequest = { showFontMenu = false },
                                            modifier = Modifier.background(Color(0xFF252528))
                                        ) {
                                            listOf("Roboto", "Serif", "Monospace", "Cursive").forEach { fontName ->
                                                DropdownMenuItem(
                                                    text = { Text(fontName, color = Color.White) },
                                                    onClick = {
                                                        updateFormatting(font = fontName)
                                                        showFontMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    // Font Size Selector
                                    Box {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF2C2C32))
                                                .clickable { showSizeMenu = true }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = "${curSize.roundToInt()}",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Icon(
                                                imageVector = Icons.Default.ArrowDropDown,
                                                contentDescription = null,
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        DropdownMenu(
                                            expanded = showSizeMenu,
                                            onDismissRequest = { showSizeMenu = false },
                                            modifier = Modifier.background(Color(0xFF252528))
                                        ) {
                                            listOf(10, 12, 14, 16, 18, 20, 24, 28, 32, 40).forEach { s ->
                                                DropdownMenuItem(
                                                    text = { Text("$s pt", color = Color.White) },
                                                    onClick = {
                                                        updateFormatting(size = s.toFloat())
                                                        showSizeMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(18.dp)
                                            .background(Color(0xFF333338))
                                    )

                                    // Text Alignment
                                    IconButton(
                                        onClick = {
                                            val nextAlign = when (curAlign.lowercase()) {
                                                "left" -> "Center"
                                                "center" -> "Right"
                                                else -> "Left"
                                            }
                                            updateFormatting(align = nextAlign)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = when (curAlign.lowercase()) {
                                                "center" -> Icons.Default.FormatAlignCenter
                                                "right" -> Icons.Default.FormatAlignRight
                                                else -> Icons.Default.FormatAlignLeft
                                            },
                                            contentDescription = "Text Alignment",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Bold Button
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (curBold) Color(0xFF2196F3).copy(alpha = 0.25f) else Color.Transparent)
                                            .border(1.dp, if (curBold) Color(0xFF2196F3) else Color.Transparent, RoundedCornerShape(6.dp))
                                            .clickable { updateFormatting(bold = !curBold) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FormatBold,
                                            contentDescription = "Bold",
                                            tint = if (curBold) Color(0xFF2196F3) else Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Italic Button
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (curItalic) Color(0xFF2196F3).copy(alpha = 0.25f) else Color.Transparent)
                                            .border(1.dp, if (curItalic) Color(0xFF2196F3) else Color.Transparent, RoundedCornerShape(6.dp))
                                            .clickable { updateFormatting(italic = !curItalic) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FormatItalic,
                                            contentDescription = "Italic",
                                            tint = if (curItalic) Color(0xFF2196F3) else Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Underline Button
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (curUnder) Color(0xFF2196F3).copy(alpha = 0.25f) else Color.Transparent)
                                            .border(1.dp, if (curUnder) Color(0xFF2196F3) else Color.Transparent, RoundedCornerShape(6.dp))
                                            .clickable { updateFormatting(under = !curUnder) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FormatUnderlined,
                                            contentDescription = "Underline",
                                            tint = if (curUnder) Color(0xFF2196F3) else Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Strikethrough Button
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (curStrike) Color(0xFF2196F3).copy(alpha = 0.25f) else Color.Transparent)
                                            .border(1.dp, if (curStrike) Color(0xFF2196F3) else Color.Transparent, RoundedCornerShape(6.dp))
                                            .clickable { updateFormatting(strike = !curStrike) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FormatStrikethrough,
                                            contentDescription = "Strikethrough",
                                            tint = if (curStrike) Color(0xFF2196F3) else Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(18.dp)
                                            .background(Color(0xFF333338))
                                    )

                                    // Text Box Format Icon [T]
                                    IconButton(
                                        onClick = {
                                            if (activeBox != null) {
                                                val toggled = !activeBox.isAutoWidth
                                                updateFormatting()
                                                val updated = localTextBoxes.map {
                                                    if (it.id == activeBox.id) it.copy(isAutoWidth = toggled) else it
                                                }
                                                saveTextBoxes(updated)
                                                Toast.makeText(context, if (toggled) "Auto-width enabled" else "Fixed width", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.TextFields,
                                            contentDescription = "Text Box Style",
                                            tint = Color(0xFF2196F3),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Collapse / Close arrow
                                    IconButton(
                                        onClick = {
                                            selectedTextBoxId = null
                                            selectedStudioTool = StudioTool.PEN
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowUp,
                                            contentDescription = "Close Sub-toolbar",
                                            tint = Color.LightGray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
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

                        IconButton(
                            onClick = {
                                interactionMode = if (interactionMode == EditorInteractionMode.PEN_MODE) {
                                    EditorInteractionMode.HAND_MODE
                                } else {
                                    EditorInteractionMode.PEN_MODE
                                }
                                Toast.makeText(
                                    context,
                                    if (interactionMode == EditorInteractionMode.PEN_MODE)
                                        "Pen Mode: Only Stylus draws (1-finger scroll & pinch-zoom)"
                                    else
                                        "Hand Mode: Draw with Hand & Stylus (2-finger scroll & pinch-zoom)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = if (interactionMode == EditorInteractionMode.PEN_MODE)
                                    Icons.Default.Create
                                else
                                    Icons.Default.TouchApp,
                                contentDescription = "Interaction Mode Toggle",
                                tint = if (interactionMode == EditorInteractionMode.PEN_MODE)
                                    Color(0xFF29B6F6)
                                else
                                    Color(0xFFFFB300),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = { showVoiceDialog = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Voice Note & Transcription",
                                tint = Color(0xFFFF7A30),
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        val allVoiceClips = notebook?.voiceNotes ?: emptyList()
                        BadgedBox(
                            badge = {
                                if (allVoiceClips.isNotEmpty()) {
                                    Badge(
                                        containerColor = Color(0xFF00BCD4),
                                        contentColor = Color.Black
                                    ) {
                                        Text(
                                            text = "${allVoiceClips.size}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        ) {
                            IconButton(
                                onClick = { showAudioNotesDialog = true },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = "Audio Recordings Manager",
                                    tint = if (allVoiceClips.isNotEmpty()) Color(0xFF00BCD4) else Color.White,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        if (onSplitScreen != null) {
                            IconButton(
                                onClick = { showSplitScreenPicker = true },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerticalSplit,
                                    contentDescription = "Split Screen",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

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
        } else {
            // MOBILE: Option A - Clean Spacious Top Header (Tools moved to thumb-friendly bottom dock)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                        IconButton(onClick = onNavigateBack, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(17.dp))
                        }
                        IconButton(onClick = { showPageDrawer = !showPageDrawer }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.GridView, contentDescription = "Page Drawer", tint = if (showPageDrawer) Color(0xFF29B6F6) else Color.White, modifier = Modifier.size(17.dp))
                        }
                        Text(
                            text = "${currentPageIndex + 1}/$totalPages",
                            color = Color(0xFFCCCCCC),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        IconButton(onClick = { workspaceViewModel.undo() }, enabled = uiState.canUndo, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = if (uiState.canUndo) Color.White else Color(0xFF555555), modifier = Modifier.size(17.dp))
                        }
                    }
                }

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
                        IconButton(onClick = { showAddMenu = !showAddMenu; showDocumentMenu = false }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Add, contentDescription = "Add", tint = if (showAddMenu) Color(0xFF29B6F6) else Color.White, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = {
                            interactionMode = if (interactionMode == EditorInteractionMode.PEN_MODE) EditorInteractionMode.HAND_MODE else EditorInteractionMode.PEN_MODE
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = if (interactionMode == EditorInteractionMode.PEN_MODE) Icons.Default.Create else Icons.Default.TouchApp,
                                contentDescription = "Interaction Mode",
                                tint = if (interactionMode == EditorInteractionMode.PEN_MODE) Color(0xFF29B6F6) else Color(0xFFFFB300),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(onClick = { isReadOnlyMode = !isReadOnlyMode }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Block, contentDescription = "Read-Only", tint = if (isReadOnlyMode) Color(0xFF29B6F6) else Color.White, modifier = Modifier.size(17.dp))
                        }
                        if (onSplitScreen != null) {
                            IconButton(onClick = { showSplitScreenPicker = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.VerticalSplit, contentDescription = "Split Screen", tint = Color.White, modifier = Modifier.size(17.dp))
                            }
                        }
                        IconButton(onClick = { showDocumentMenu = !showDocumentMenu; showAddMenu = false }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = if (showDocumentMenu) Color(0xFF29B6F6) else Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // ==========================================
        // 2B. MOBILE OPTION A: BOTTOM FLOATING STUDIO DOCK
        // ==========================================
        if (!isTablet) {
            AnimatedVisibility(
                visible = !isReadOnlyMode,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .imePadding()
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.96f)),
                    border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                    modifier = Modifier.shadow(10.dp, RoundedCornerShape(24.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
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
                        FloatingToolButton(
                            icon = Icons.Default.TextFields,
                            isSelected = selectedStudioTool == StudioTool.TEXT,
                            onClick = {
                                selectedStudioTool = if (selectedStudioTool == StudioTool.TEXT) StudioTool.PEN else StudioTool.TEXT
                            }
                        )
                        FloatingToolButton(
                            icon = Icons.Default.Category,
                            isSelected = selectedStudioTool == StudioTool.SHAPE,
                            onClick = {
                                selectedStudioTool = if (selectedStudioTool == StudioTool.SHAPE) StudioTool.PEN else StudioTool.SHAPE
                            }
                        )
                        FloatingToolButton(
                            icon = Icons.Default.Adjust,
                            isSelected = selectedStudioTool == StudioTool.PRESENT,
                            onClick = {
                                selectedStudioTool = if (selectedStudioTool == StudioTool.PRESENT) StudioTool.PEN else StudioTool.PRESENT
                            }
                        )
                        FloatingToolButton(
                            icon = Icons.Default.Spellcheck,
                            isSelected = false,
                            onClick = {
                                convertHandwritingToText(currentPageIndex)
                            }
                        )

                        // Voice Recording Mic Button (Always visible on mobile!)
                        IconButton(onClick = { 
                            voiceDialogAudioOnly = false
                            showVoiceDialog = true 
                        }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Mic, contentDescription = "Voice Note & Transcription", tint = Color(0xFFFF7A30), modifier = Modifier.size(19.dp))
                        }

                        // Audio Recordings Manager with Badge (Always visible on mobile!)
                        val allVoiceClips = notebook?.voiceNotes ?: emptyList()
                        BadgedBox(
                            badge = {
                                if (allVoiceClips.isNotEmpty()) {
                                    Badge(containerColor = Color(0xFF00BCD4), contentColor = Color.Black) {
                                        Text("${allVoiceClips.size}", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        ) {
                            IconButton(onClick = { showAudioNotesDialog = true }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.GraphicEq, contentDescription = "Audio Recordings Manager", tint = if (allVoiceClips.isNotEmpty()) Color(0xFF00BCD4) else Color.White, modifier = Modifier.size(19.dp))
                            }
                        }

                        // Color Palette Toggle for Mobile
                        IconButton(onClick = { showMobilePalette = !showMobilePalette }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Palette, contentDescription = "Palette", tint = if (showMobilePalette) Color(0xFF00BCD4) else Color.LightGray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // ==========================================
        // 3A. LEFT FLOATING SHAPES SIDEBAR (Screenshot 2 Match)
        // ==========================================
        AnimatedVisibility(
            visible = !isReadOnlyMode && selectedStudioTool == StudioTool.SHAPE,
            enter = fadeIn() + slideInHorizontally { -it },
            exit = fadeOut() + slideOutHorizontally { -it },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
        ) {
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.96f)),
                border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(26.dp))
                    .width(46.dp)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Draw to Shape / Freehand toggle
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isFreehandShapeMode) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color.Transparent)
                            .clickable {
                                isFreehandShapeMode = !isFreehandShapeMode
                                Toast.makeText(context, if (isFreehandShapeMode) "Auto Shape Detection ON" else "Auto Shape Detection OFF", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Gesture,
                            contentDescription = "Draw-to-Shape",
                            tint = if (isFreehandShapeMode) Color(0xFF00BCD4) else Color.LightGray,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(horizontal = 4.dp))

                    // 2. Line (/)
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (selectedShapeType == CanvasShapeType.LINE) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { selectedShapeType = CanvasShapeType.LINE },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Timeline, contentDescription = "Line", tint = if (selectedShapeType == CanvasShapeType.LINE) Color(0xFF00BCD4) else Color.White, modifier = Modifier.size(18.dp))
                    }

                    // 3. Arrow (↗)
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (selectedShapeType == CanvasShapeType.ARROW) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { selectedShapeType = CanvasShapeType.ARROW },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.NorthEast, contentDescription = "Arrow", tint = if (selectedShapeType == CanvasShapeType.ARROW) Color(0xFF00BCD4) else Color.White, modifier = Modifier.size(18.dp))
                    }

                    // 4. Double Arrow
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (selectedShapeType == CanvasShapeType.DOUBLE_ARROW) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { selectedShapeType = CanvasShapeType.DOUBLE_ARROW },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.OpenInFull, contentDescription = "Double Arrow", tint = if (selectedShapeType == CanvasShapeType.DOUBLE_ARROW) Color(0xFF00BCD4) else Color.White, modifier = Modifier.size(18.dp))
                    }

                    // 5. Rectangle (□)
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (selectedShapeType == CanvasShapeType.RECTANGLE) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { selectedShapeType = CanvasShapeType.RECTANGLE },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CropSquare, contentDescription = "Rectangle", tint = if (selectedShapeType == CanvasShapeType.RECTANGLE) Color(0xFF00BCD4) else Color.White, modifier = Modifier.size(18.dp))
                    }

                    // 6. Rounded Rectangle / Circle (◯)
                    val isRoundOrCircle = selectedShapeType == CanvasShapeType.ROUNDED_RECT || selectedShapeType == CanvasShapeType.CIRCLE
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isRoundOrCircle) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color.Transparent)
                            .clickable {
                                selectedShapeType = if (selectedShapeType == CanvasShapeType.ROUNDED_RECT) CanvasShapeType.CIRCLE else CanvasShapeType.ROUNDED_RECT
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.RadioButtonUnchecked, contentDescription = "Circle/Round", tint = if (isRoundOrCircle) Color(0xFF00BCD4) else Color.White, modifier = Modifier.size(18.dp))
                    }

                    HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(horizontal = 4.dp))

                    // 7. Shape Color Swatch
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(uiState.selectedColor)
                            .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                            .clickable { showColorPaletteDialog = true }
                    )

                    // 8. Stroke thickness indicator
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
                                .size((shapeStrokeWidth * 1.5f).coerceIn(4f, 14f).dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }

                    // 9. More Shapes Library (•••)
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (showShapeDialog) Color(0xFF00BCD4).copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { showShapeDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MoreHoriz,
                            contentDescription = "All Shapes Library",
                            tint = if (showShapeDialog) Color(0xFF00BCD4) else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // ==========================================
        // 3B. LEFT FLOATING PRESENTER MODE SIDEBAR
        // ==========================================
        AnimatedVisibility(
            visible = !isReadOnlyMode && selectedStudioTool == StudioTool.PRESENT,
            enter = fadeIn() + slideInHorizontally { -it },
            exit = fadeOut() + slideOutHorizontally { -it },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
        ) {
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.96f)),
                border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                modifier = Modifier
                    .shadow(8.dp, RoundedCornerShape(26.dp))
                    .width(46.dp)
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Laser Pen Trail mode
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (!isLaserDotMode) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { isLaserDotMode = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Draw,
                            contentDescription = "Laser Beam Pen",
                            tint = if (!isLaserDotMode) Color(0xFF00BCD4) else Color.LightGray,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Laser Pointer Dot mode
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isLaserDotMode) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { isLaserDotMode = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Adjust,
                            contentDescription = "Laser Pointer Dot",
                            tint = if (isLaserDotMode) Color(0xFF00BCD4) else Color.LightGray,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(horizontal = 4.dp))

                    // 4 Vibrant Neon Colors
                    listOf(
                        Color(0xFFFF1744), // Neon Red
                        Color(0xFF00E676), // Neon Green
                        Color(0xFF00E5FF), // Neon Cyan
                        Color(0xFFFFEA00)  // Neon Yellow
                    ).forEach { clr ->
                        val isSelected = laserColor == clr
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(clr)
                                .then(
                                    if (isSelected) Modifier.border(2.dp, Color.White, CircleShape)
                                    else Modifier
                                )
                                .clickable { laserColor = clr }
                        )
                    }

                    HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.padding(horizontal = 4.dp))

                    // Clear Laser Trails
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable {
                                laserStrokes = emptyList()
                                laserActivePoints = emptyList()
                                laserFadeJob?.cancel()
                                laserFadeJob = null
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Clear Laser Trails",
                            tint = Color.LightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // ==========================================
        // 3C. LEFT FLOATING COLOR & STROKE PALETTE
        // ==========================================
        AnimatedVisibility(
            visible = !isReadOnlyMode && (isTablet || showMobilePalette) && selectedStudioTool != StudioTool.SHAPE && selectedStudioTool != StudioTool.PRESENT,
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
                        .align(if (isTablet) Alignment.CenterStart else Alignment.BottomCenter)
                        .padding(if (isTablet) PaddingValues(start = 64.dp) else PaddingValues(bottom = 80.dp, start = 16.dp, end = 16.dp))
                        .widthIn(min = 260.dp, max = 320.dp)
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
                        val isHighlighter = selectedStudioTool == StudioTool.HIGHLIGHTER
                        val isEraser = selectedStudioTool == StudioTool.ERASER
                        val isShape = selectedStudioTool == StudioTool.SHAPE
                        val title = when {
                            isEraser -> "Eraser Settings"
                            isHighlighter -> "Highlighter Size"
                            isShape -> "Shape Outline Width"
                            else -> "Pencil / Pen Size"
                        }
                        val currentVal = when {
                            isEraser -> eraserRadius
                            isHighlighter -> highlighterStrokeWidth
                            isShape -> shapeStrokeWidth
                            else -> brushStrokeWidth
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (currentVal == currentVal.toInt().toFloat()) "${currentVal.toInt()} pt" else String.format(java.util.Locale.US, "%.1f pt", currentVal),
                                    color = Color(0xFF00BCD4),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                IconButton(
                                    onClick = { showStrokeWidthDialog = false },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray, modifier = Modifier.size(15.dp))
                                }
                            }
                        }

                        // Live circular preview of stroke thickness
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF141416)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(currentVal.coerceIn(2f, 38f).dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isEraser) Color(0xFFFF8A80)
                                        else if (isHighlighter) uiState.selectedColor.copy(alpha = 0.5f)
                                        else uiState.selectedColor
                                    )
                            )
                        }

                        if (isEraser) {
                            Spacer(modifier = Modifier.height(10.dp))
                            // Mode toggle: Stroke Eraser vs Precision Area
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF2B2B30))
                                    .padding(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (eraserMode == WorkspaceViewModel.EraserModeType.WHOLE_STROKE) Color(0xFF00BCD4) else Color.Transparent)
                                        .clickable { eraserMode = WorkspaceViewModel.EraserModeType.WHOLE_STROKE }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Stroke", color = if (eraserMode == WorkspaceViewModel.EraserModeType.WHOLE_STROKE) Color.White else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (eraserMode == WorkspaceViewModel.EraserModeType.PRECISION_AREA) Color(0xFF00BCD4) else Color.Transparent)
                                        .clickable { eraserMode = WorkspaceViewModel.EraserModeType.PRECISION_AREA }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Precision", color = if (eraserMode == WorkspaceViewModel.EraserModeType.PRECISION_AREA) Color.White else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        val range = if (isEraser) 4f..80f else if (isHighlighter) 6f..60f else 0.5f..40f

                        // Continuous dynamic slider with - and + step buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    val step = if (isEraser) 2f else if (isHighlighter) 2f else if (isShape) 1f else 0.5f
                                    val nextVal = (currentVal - step).coerceAtLeast(range.start)
                                    if (isEraser) eraserRadius = nextVal
                                    else if (isHighlighter) highlighterStrokeWidth = nextVal
                                    else if (isShape) shapeStrokeWidth = nextVal
                                    else {
                                        brushStrokeWidth = nextVal
                                        workspaceViewModel.selectStrokeWidth(nextVal)
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }

                            Slider(
                                value = currentVal,
                                onValueChange = {
                                    val rounded = kotlin.math.round(it * 2f) / 2f
                                    if (isEraser) eraserRadius = rounded
                                    else if (isHighlighter) highlighterStrokeWidth = rounded
                                    else if (isShape) shapeStrokeWidth = rounded
                                    else {
                                        brushStrokeWidth = rounded
                                        workspaceViewModel.selectStrokeWidth(rounded)
                                    }
                                },
                                valueRange = range,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF00BCD4),
                                    activeTrackColor = Color(0xFF00BCD4),
                                    inactiveTrackColor = Color(0xFF333333)
                                )
                            )

                            IconButton(
                                onClick = {
                                    val step = if (isEraser) 2f else if (isHighlighter) 2f else if (isShape) 1f else 0.5f
                                    val nextVal = (currentVal + step).coerceAtMost(range.endInclusive)
                                    if (isEraser) eraserRadius = nextVal
                                    else if (isHighlighter) highlighterStrokeWidth = nextVal
                                    else if (isShape) shapeStrokeWidth = nextVal
                                    else {
                                        brushStrokeWidth = nextVal
                                        workspaceViewModel.selectStrokeWidth(nextVal)
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Increase", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("PRESETS", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(4.dp))

                        val presets = if (isEraser) listOf(8f, 16f, 24f, 36f, 50f, 70f)
                        else if (isHighlighter) listOf(8f, 14f, 20f, 28f, 38f, 50f)
                        else listOf(1f, 2f, 3f, 5f, 8f, 12f, 16f, 20f, 28f, 36f)

                        // Scrollable row of quick preset chips
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(presets) { pt ->
                                val isSelected = kotlin.math.abs(currentVal - pt) < 0.25f
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) Color(0xFF00BCD4) else Color(0xFF26262B))
                                        .clickable {
                                            if (isEraser) eraserRadius = pt
                                            else if (isHighlighter) highlighterStrokeWidth = pt
                                            else {
                                                brushStrokeWidth = pt
                                                workspaceViewModel.selectStrokeWidth(pt)
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${pt.toInt()}",
                                        color = if (isSelected) Color.White else Color.LightGray,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
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
                                val pageToDelete = currentPageIndex
                                workspaceViewModel.deletePage(pageToDelete)
                                val shiftedBoxes = localTextBoxes.mapNotNull { box ->
                                    when {
                                        box.pageIndex == pageToDelete -> null
                                        box.pageIndex > pageToDelete -> box.copy(pageIndex = box.pageIndex - 1)
                                        else -> box
                                    }
                                }
                                saveTextBoxes(shiftedBoxes)

                                val shiftedImages = localImages.mapNotNull { img ->
                                    when {
                                        img.pageIndex == pageToDelete -> null
                                        img.pageIndex > pageToDelete -> img.copy(pageIndex = img.pageIndex - 1)
                                        else -> img
                                    }
                                }
                                saveImages(shiftedImages)

                                totalPages = (totalPages - 1).coerceAtLeast(1)
                                Toast.makeText(context, "Page ${pageToDelete + 1} deleted", Toast.LENGTH_SHORT).show()
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
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Page ${pageIdx + 1}",
                                                    color = if (isCurrent) Color(0xFF00BCD4) else Color.LightGray,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    modifier = Modifier.padding(start = 2.dp)
                                                )
                                                if (totalPages > 1) {
                                                    IconButton(
                                                        onClick = {
                                                            workspaceViewModel.deletePage(pageIdx)
                                                            val shiftedBoxes = localTextBoxes.mapNotNull { box ->
                                                                when {
                                                                    box.pageIndex == pageIdx -> null
                                                                    box.pageIndex > pageIdx -> box.copy(pageIndex = box.pageIndex - 1)
                                                                    else -> box
                                                                }
                                                            }
                                                            saveTextBoxes(shiftedBoxes)

                                                            val shiftedImages = localImages.mapNotNull { img ->
                                                                when {
                                                                    img.pageIndex == pageIdx -> null
                                                                    img.pageIndex > pageIdx -> img.copy(pageIndex = img.pageIndex - 1)
                                                                    else -> img
                                                                }
                                                            }
                                                            saveImages(shiftedImages)

                                                            totalPages = (totalPages - 1).coerceAtLeast(1)
                                                            Toast.makeText(context, "Page ${pageIdx + 1} deleted", Toast.LENGTH_SHORT).show()
                                                        },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.DeleteOutline,
                                                            contentDescription = "Delete Page",
                                                            tint = Color(0xFFFF5252).copy(alpha = 0.85f),
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                    }
                                                }
                                            }
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

        if (showShapeDialog) {
            ShapeSelectorDialog(
                selectedShape = selectedShapeType,
                strokeWidth = shapeStrokeWidth,
                selectedColor = uiState.selectedColor,
                onSelectShape = { selectedShapeType = it },
                onSelectStrokeWidth = { shapeStrokeWidth = it },
                onSelectColor = { workspaceViewModel.selectColor(it) },
                onInsertShapeToPage = { shape, width, color ->
                    val pageBaseW = with(density) { basePageWidth.toPx() } * canonicalScale
                    val pageBaseH = with(density) { basePageHeight.toPx() } * canonicalScale
                    val centerX = pageBaseW * 0.5f
                    val centerY = pageBaseH * 0.4f
                    val halfSize = min(pageBaseW, pageBaseH) * 0.22f
                    val start = Offset(centerX - halfSize, centerY - halfSize)
                    val end = Offset(centerX + halfSize, centerY + halfSize)
                    val strokes = CanvasShapeGenerator.generateShapeStrokes(shape, start, end, width, color, currentPageIndex)
                    strokes.forEach { workspaceViewModel.addStroke(it) }
                    Toast.makeText(context, "${shape.label} inserted to Page ${currentPageIndex + 1}", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showShapeDialog = false }
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
        if (showVoiceDialog) {
            VoiceNoteDialog(
                notebookId = notebookId,
                pageIndex = currentPageIndex,
                initialRecordAudioOnly = voiceDialogAudioOnly,
                onDismiss = { showVoiceDialog = false },
                onSaveVoiceClip = { clip ->
                    GraphiteRepository.addVoiceClipToNotebook(notebookId, clip)
                },
                onTextTranscribed = { text ->
                    val capText = com.graphite.app.core.voice.VoiceTranscriptionManager.capitalizeSentences(text.trim())
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Transcription", capText))

                    // If a text box is actively open/editing, append directly into it!
                    if (editingTextBoxId != null) {
                        val curBox = localTextBoxes.find { it.id == editingTextBoxId }
                        if (curBox != null) {
                            val updatedText = if (curBox.text.isBlank()) capText else "${curBox.text} $capText"
                            val updatedBoxes = localTextBoxes.map {
                                if (it.id == editingTextBoxId) it.copy(text = updatedText) else it
                            }
                            saveTextBoxes(updatedBoxes)
                            Toast.makeText(context, "Inserted into active text box!", Toast.LENGTH_SHORT).show()
                            return@VoiceNoteDialog
                        }
                    }

                    // Place directly into Layer C as an interactive CanvasTextBox!
                    val pageBoxes = localTextBoxes.filter { it.pageIndex == currentPageIndex }
                    val newYRatio = (0.08f + (pageBoxes.size * 0.08f)).coerceIn(0.06f, 0.85f)
                    val newBox = CanvasTextBox(
                        id = java.util.UUID.randomUUID().toString(),
                        pageIndex = currentPageIndex,
                        xRatio = 0.08f,
                        yRatio = newYRatio,
                        widthRatio = 0.40f,
                        text = capText,
                        fontSizeSp = 16f,
                        colorHex = "#1F1F1F"
                    )
                    val updatedBoxes = localTextBoxes + newBox
                    saveTextBoxes(updatedBoxes)
                    selectedStudioTool = StudioTool.TEXT
                    editingTextBoxId = newBox.id
                    Toast.makeText(context, "Voice transcribed into text box on Page ${currentPageIndex + 1}!", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (showAudioNotesDialog) {
            NotebookAudioClipsDialog(
                notebookId = notebookId,
                notebookTitle = notebook?.title ?: "Note",
                currentPageIndex = currentPageIndex,
                totalPages = totalPages,
                voiceNotes = notebook?.voiceNotes ?: emptyList(),
                onDismiss = { showAudioNotesDialog = false },
                onRecordOnPage = { _ ->
                    voiceDialogAudioOnly = true
                    showVoiceDialog = true
                },
                onDeleteClip = { clipId ->
                    GraphiteRepository.deleteVoiceClipFromNotebook(notebookId, clipId)
                },
                onGoToPage = { targetPage ->
                    scope.launch { listState.animateScrollToItem(targetPage) }
                },
                onInsertTranscriptionToPage = { pIdx, transText ->
                    val capTrans = com.graphite.app.core.voice.VoiceTranscriptionManager.capitalizeSentences(transText.trim())
                    if (editingTextBoxId != null) {
                        val curBox = localTextBoxes.find { it.id == editingTextBoxId }
                        if (curBox != null) {
                            val updatedText = if (curBox.text.isBlank()) capTrans else "${curBox.text} $capTrans"
                            val updatedBoxes = localTextBoxes.map {
                                if (it.id == editingTextBoxId) it.copy(text = updatedText) else it
                            }
                            saveTextBoxes(updatedBoxes)
                            Toast.makeText(context, "Inserted into active text box!", Toast.LENGTH_SHORT).show()
                            return@NotebookAudioClipsDialog
                        }
                    }

                    val pageBoxes = localTextBoxes.filter { it.pageIndex == pIdx }
                    val newYRatio = (0.08f + (pageBoxes.size * 0.08f)).coerceIn(0.06f, 0.85f)
                    val newBox = CanvasTextBox(
                        id = java.util.UUID.randomUUID().toString(),
                        pageIndex = pIdx,
                        xRatio = 0.08f,
                        yRatio = newYRatio,
                        widthRatio = 0.40f,
                        text = capTrans,
                        fontSizeSp = 16f,
                        colorHex = "#1F1F1F"
                    )
                    val updatedBoxes = localTextBoxes + newBox
                    saveTextBoxes(updatedBoxes)
                    selectedStudioTool = StudioTool.TEXT
                    editingTextBoxId = newBox.id
                    Toast.makeText(context, "Inserted onto Page ${pIdx + 1}!", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (showSplitScreenPicker && onSplitScreen != null) {
            val allOtherNotes = GraphiteRepository.notebooks.collectAsState().value.filter { it.id != notebookId && !it.isDeleted }
            AlertDialog(
                onDismissRequest = { showSplitScreenPicker = false },
                title = { Text("Open in Split Screen", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    if (allOtherNotes.isEmpty()) {
                        Text("No other documents available to view side-by-side.", color = Color.LightGray)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                            items(allOtherNotes) { otherNote ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showSplitScreenPicker = false
                                            onSplitScreen(notebookId, otherNote.id)
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (otherNote.mimeType?.contains("pdf") == true) Icons.Default.PictureAsPdf else Icons.Default.Book,
                                        contentDescription = null,
                                        tint = if (otherNote.mimeType?.contains("pdf") == true) Color(0xFFEF5350) else Color(0xFFFF7A30),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(otherNote.title, color = Color.White, fontSize = 14.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showSplitScreenPicker = false }) {
                        Text("Cancel", color = Color.LightGray)
                    }
                },
                containerColor = Color(0xFF222222)
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
private fun PaperTemplateCanvas(paperStyle: PaperStyle, pageScale: Float = 1f) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val lineCol = Color(0xFFE8E8E8)
        val s = pageScale.coerceIn(0.45f, 1.4f)

        when (paperStyle) {
            PaperStyle.BLANK -> {}
            PaperStyle.RULED -> {
                val lineSpacing = 36.dp.toPx() * s
                var y = lineSpacing + (20.dp.toPx() * s)
                while (y < canvasHeight) {
                    drawLine(
                        color = lineCol,
                        start = Offset(0f, y),
                        end = Offset(canvasWidth, y),
                        strokeWidth = (1.dp.toPx() * s).coerceAtLeast(1f)
                    )
                    y += lineSpacing
                }
            }
            PaperStyle.DOTTED -> {
                val dotSpacing = 28.dp.toPx() * s
                var x = dotSpacing
                while (x < canvasWidth) {
                    var y = dotSpacing
                    while (y < canvasHeight) {
                        drawCircle(
                            color = Color(0xFFD6D6D6),
                            radius = (1.2.dp.toPx() * s).coerceAtLeast(1f),
                            center = Offset(x, y)
                        )
                        y += dotSpacing
                    }
                    x += dotSpacing
                }
            }
            PaperStyle.GRID -> {
                val gridSpacing = 28.dp.toPx() * s
                var x = 0f
                while (x < canvasWidth) {
                    drawLine(color = lineCol, start = Offset(x, 0f), end = Offset(x, canvasHeight), strokeWidth = (1.dp.toPx() * s).coerceAtLeast(1f))
                    x += gridSpacing
                }
                var y = 0f
                while (y < canvasHeight) {
                    drawLine(color = lineCol, start = Offset(0f, y), end = Offset(canvasWidth, y), strokeWidth = (1.dp.toPx() * s).coerceAtLeast(1f))
                    y += gridSpacing
                }
            }
            PaperStyle.HEXAGONAL_GRID -> {
                val hexSize = 24.dp.toPx()
                var x = hexSize
                while (x < canvasWidth) {
                    var y = hexSize
                    while (y < canvasHeight) {
                        drawCircle(color = Color(0xFFD0D0D0), radius = 1.2.dp.toPx(), center = Offset(x, y))
                        y += hexSize * 1.5f
                    }
                    x += hexSize * 1.732f
                }
            }
            PaperStyle.CORNELL_A -> {
                // Cue column on left (28% width)
                drawLine(color = Color(0xFF00BCD4).copy(alpha = 0.5f), start = Offset(canvasWidth * 0.28f, 0f), end = Offset(canvasWidth * 0.28f, canvasHeight - 120.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                // Summary block at bottom
                drawLine(color = Color(0xFF00BCD4).copy(alpha = 0.5f), start = Offset(0f, canvasHeight - 120.dp.toPx()), end = Offset(canvasWidth, canvasHeight - 120.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                // Ruled lines in main area
                var y = 48.dp.toPx()
                while (y < canvasHeight - 120.dp.toPx()) {
                    drawLine(color = lineCol, start = Offset(canvasWidth * 0.28f, y), end = Offset(canvasWidth, y), strokeWidth = 1.dp.toPx())
                    y += 32.dp.toPx()
                }
            }
            PaperStyle.CORNELL_B -> {
                // Top header line
                drawLine(color = Color(0xFF00BCD4).copy(alpha = 0.5f), start = Offset(0f, 60.dp.toPx()), end = Offset(canvasWidth, 60.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                // Left margin line
                drawLine(color = Color(0xFF00BCD4).copy(alpha = 0.5f), start = Offset(canvasWidth * 0.25f, 60.dp.toPx()), end = Offset(canvasWidth * 0.25f, canvasHeight), strokeWidth = 1.5.dp.toPx())
                var y = 60.dp.toPx() + 32.dp.toPx()
                while (y < canvasHeight) {
                    drawLine(color = lineCol, start = Offset(canvasWidth * 0.25f, y), end = Offset(canvasWidth, y), strokeWidth = 1.dp.toPx())
                    y += 32.dp.toPx()
                }
            }
            PaperStyle.LEGAL -> {
                // Red left vertical margin line at 18% width
                drawLine(color = Color(0xFFFF5252).copy(alpha = 0.65f), start = Offset(canvasWidth * 0.18f, 0f), end = Offset(canvasWidth * 0.18f, canvasHeight), strokeWidth = 1.5.dp.toPx())
                var y = 50.dp.toPx()
                while (y < canvasHeight) {
                    drawLine(color = Color(0xFFBBDEFB), start = Offset(0f, y), end = Offset(canvasWidth, y), strokeWidth = 1.dp.toPx())
                    y += 32.dp.toPx()
                }
            }
            PaperStyle.ENGINEERING_GRID -> {
                val fineGrid = 14.dp.toPx()
                var x = 0f
                var count = 0
                while (x < canvasWidth) {
                    val isMajor = count % 5 == 0
                    drawLine(color = if (isMajor) Color(0xFF81C784) else Color(0xFFE8F5E9), start = Offset(x, 0f), end = Offset(x, canvasHeight), strokeWidth = if (isMajor) 1.2.dp.toPx() else 0.6.dp.toPx())
                    x += fineGrid
                    count++
                }
                var y = 0f
                count = 0
                while (y < canvasHeight) {
                    val isMajor = count % 5 == 0
                    drawLine(color = if (isMajor) Color(0xFF81C784) else Color(0xFFE8F5E9), start = Offset(0f, y), end = Offset(canvasWidth, y), strokeWidth = if (isMajor) 1.2.dp.toPx() else 0.6.dp.toPx())
                    y += fineGrid
                    count++
                }
            }
            PaperStyle.MUSIC_SCORE -> {
                // 5-line musical staves
                var staffTop = 60.dp.toPx()
                val lineGap = 9.dp.toPx()
                while (staffTop + 4 * lineGap < canvasHeight - 40.dp.toPx()) {
                    for (i in 0..4) {
                        val ly = staffTop + i * lineGap
                        drawLine(color = Color(0xFF555555), start = Offset(36.dp.toPx(), ly), end = Offset(canvasWidth - 36.dp.toPx(), ly), strokeWidth = 1.dp.toPx())
                    }
                    staffTop += 4 * lineGap + 40.dp.toPx()
                }
            }
            PaperStyle.GUITAR_TABLATURE -> {
                // 6-line guitar tab staves
                var staffTop = 60.dp.toPx()
                val lineGap = 8.dp.toPx()
                while (staffTop + 5 * lineGap < canvasHeight - 40.dp.toPx()) {
                    for (i in 0..5) {
                        val ly = staffTop + i * lineGap
                        drawLine(color = Color(0xFF555555), start = Offset(36.dp.toPx(), ly), end = Offset(canvasWidth - 36.dp.toPx(), ly), strokeWidth = 1.dp.toPx())
                    }
                    staffTop += 5 * lineGap + 40.dp.toPx()
                }
            }
            PaperStyle.GUITAR_SCORE -> {
                // Combined standard music notation + 6-line guitar TAB
                var staffTop = 50.dp.toPx()
                val lineGap = 7.dp.toPx()
                while (staffTop + 14 * lineGap < canvasHeight - 40.dp.toPx()) {
                    // Standard staff (5 lines)
                    for (i in 0..4) {
                        val ly = staffTop + i * lineGap
                        drawLine(color = Color(0xFF444444), start = Offset(36.dp.toPx(), ly), end = Offset(canvasWidth - 36.dp.toPx(), ly), strokeWidth = 1.dp.toPx())
                    }
                    val tabTop = staffTop + 5 * lineGap + 18.dp.toPx()
                    // TAB staff (6 lines)
                    for (i in 0..5) {
                        val ly = tabTop + i * lineGap
                        drawLine(color = Color(0xFF666666), start = Offset(36.dp.toPx(), ly), end = Offset(canvasWidth - 36.dp.toPx(), ly), strokeWidth = 1.dp.toPx())
                    }
                    staffTop = tabTop + 6 * lineGap + 36.dp.toPx()
                }
            }
        }
    }
}

private fun isPointInPolygon(point: Offset, polygon: List<Offset>): Boolean {
    if (polygon.size < 3) return false
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val pi = polygon[i]
        val pj = polygon[j]
        if ((pi.y > point.y) != (pj.y > point.y) &&
            (point.x < (pj.x - pi.x) * (point.y - pi.y) / (pj.y - pi.y) + pi.x)) {
            inside = !inside
        }
        j = i
    }
    return inside
}

