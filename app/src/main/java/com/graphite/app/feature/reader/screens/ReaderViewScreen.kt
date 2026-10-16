package com.graphite.app.feature.reader.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.BatteryManager
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.ContentScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.core.importer.DocumentContentParser
import com.graphite.app.core.importer.ParsedDocument
import com.graphite.app.core.importer.SlideContent
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteSurfaceVariant
import com.graphite.app.data.model.BookCoverType
import com.graphite.app.data.repository.GraphiteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ReaderViewScreen(
    bookId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val book = remember(bookId) {
        GraphiteRepository.getBook(bookId) ?: GraphiteRepository.getNotebookAsBook(bookId)
    }

    if (book == null) {
        Box(modifier = modifier.fillMaxSize().background(GraphiteBlack), contentAlignment = Alignment.Center) {
            Text("Document not found", color = Color.White)
        }
        return
    }

    val ext = book.filePath?.substringAfterLast('.', "")?.lowercase() ?: ""
    val isPdf = (book.filePath != null && ext == "pdf") || book.mimeType.contains("pdf", ignoreCase = true)
    val isPptx = ext in listOf("pptx", "ppt") || book.coverType == BookCoverType.CE_PPT || book.mimeType.contains("presentation", ignoreCase = true)
    val isDocx = ext in listOf("docx", "doc") || book.mimeType.contains("word", ignoreCase = true)
    val isXlsx = ext in listOf("xlsx", "xls", "csv") || book.mimeType.contains("spreadsheet", ignoreCase = true) || book.mimeType.contains("sheet", ignoreCase = true)

    var currentProgress by remember { mutableFloatStateOf((book.progressPercent).toFloat()) }
    var fontSize by remember { mutableFloatStateOf(16f) }
    var showFontDialog by remember { mutableStateOf(false) }
    var isBookmarked by remember { mutableStateOf(book.isBookmarked) }
    var isFullscreen by remember(isPdf) { mutableStateOf(isPdf) }
    var showPagePill by remember { mutableStateOf(true) }

    var pdfTotalPages by remember { mutableIntStateOf(book.totalChapters) }
    var pdfCurrentPage by remember { mutableIntStateOf(0) }

    // Parse presentation slides if PPTX
    var pptxSlides by remember { mutableStateOf<List<SlideContent>>(emptyList()) }
    var pptxCurrentSlide by remember { mutableIntStateOf(0) }

    var parsedDoc by remember { mutableStateOf<ParsedDocument?>(null) }
    var isLoadingDoc by remember { mutableStateOf(false) }

    // Auto-disappear floating page indicator pill after 1.2s for non-PDF
    LaunchedEffect(pdfCurrentPage, pptxCurrentSlide, isFullscreen) {
        if (!isPdf) {
            showPagePill = true
            delay(1200)
            showPagePill = false
        }
    }

    LaunchedEffect(book.filePath) {
        if (!book.filePath.isNullOrBlank()) {
            val file = File(book.filePath)
            if (file.exists()) {
                isLoadingDoc = true
                withContext(Dispatchers.IO) {
                    try {
                        val bytes = file.readBytes()
                        val parsed = DocumentContentParser.parse(bytes, file.name, book.mimeType)
                        withContext(Dispatchers.Main) {
                            parsedDoc = parsed
                            if (parsed.slides.isNotEmpty()) {
                                pptxSlides = parsed.slides
                                pdfTotalPages = parsed.slides.size
                            } else if (parsed.pageOrSlideCount > 0) {
                                pdfTotalPages = parsed.pageOrSlideCount
                            }
                            isLoadingDoc = false
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            isLoadingDoc = false
                        }
                    }
                }
            }
        }
    }

    val subtitle = when {
        isPdf -> "PDF Document • Page ${pdfCurrentPage + 1} of ${maxOf(1, pdfTotalPages)}"
        isPptx -> "PowerPoint Presentation • Slide ${pptxCurrentSlide + 1} of ${maxOf(1, pptxSlides.size.ifZero(book.totalChapters))}"
        isDocx -> "Word Document • ${maxOf(1, parsedDoc?.pageOrSlideCount ?: book.totalChapters)} pages"
        isXlsx -> "Spreadsheet Workbook • ${book.title}"
        else -> book.author
    }

    Scaffold(
        topBar = {
            if (!isPdf) {
                AnimatedVisibility(
                    visible = !isFullscreen,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    ReaderTopBar(
                        title = book.title,
                        subtitle = subtitle,
                        isPdf = isPdf,
                        isPptx = isPptx,
                        isBookmarked = isBookmarked,
                        onNavigateBack = onNavigateBack,
                        onOpenExternal = if (book.filePath != null) {
                            {
                                DocumentContentParser.openWithExternalApp(
                                    context,
                                    File(book.filePath),
                                    book.mimeType
                                )
                            }
                        } else null,
                        onAdjustFont = { showFontDialog = true },
                        onToggleBookmark = {
                            isBookmarked = !isBookmarked
                            GraphiteRepository.toggleBookmarkBook(book.id)
                            GraphiteRepository.toggleBookmarkNotebook(book.id)
                            val msg = if (isBookmarked) "Page bookmarked" else "Bookmark removed"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        },
        containerColor = GraphiteBlack
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GraphiteBlack)
        ) {
            when {
                isPdf && book.filePath != null -> {
                    PdfDocumentViewer(
                        filePath = book.filePath,
                        currentPageIndex = pdfCurrentPage,
                        totalPages = maxOf(1, pdfTotalPages),
                        onTotalPagesLoaded = { count ->
                            pdfTotalPages = maxOf(1, count)
                        },
                        onNextPage = {
                            if (pdfCurrentPage < pdfTotalPages - 1) {
                                pdfCurrentPage++
                                val progress = ((pdfCurrentPage + 1) * 100 / pdfTotalPages)
                                GraphiteRepository.updateBookProgress(book.id, progress)
                            }
                        },
                        onPreviousPage = {
                            if (pdfCurrentPage > 0) {
                                pdfCurrentPage--
                                val progress = ((pdfCurrentPage + 1) * 100 / pdfTotalPages)
                                GraphiteRepository.updateBookProgress(book.id, progress)
                            }
                        },
                        onToggleFullscreen = {
                            isFullscreen = !isFullscreen
                        },
                        onUserInteraction = {},
                        modifier = Modifier.fillMaxSize()
                    )

                    // Overlay Top Bar for PDF when !isFullscreen without displacing the PDF
                    AnimatedVisibility(
                        visible = !isFullscreen,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        ReaderTopBar(
                            title = book.title,
                            subtitle = subtitle,
                            isPdf = isPdf,
                            isPptx = isPptx,
                            isBookmarked = isBookmarked,
                            onNavigateBack = onNavigateBack,
                            onOpenExternal = if (book.filePath != null) {
                                {
                                    DocumentContentParser.openWithExternalApp(
                                        context,
                                        File(book.filePath),
                                        book.mimeType
                                    )
                                }
                            } else null,
                            onAdjustFont = { showFontDialog = true },
                            onToggleBookmark = {
                                isBookmarked = !isBookmarked
                                GraphiteRepository.toggleBookmarkBook(book.id)
                                GraphiteRepository.toggleBookmarkNotebook(book.id)
                                val msg = if (isBookmarked) "Page bookmarked" else "Bookmark removed"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
                isPptx -> {
                    PptxPresentationViewer(
                        slides = if (pptxSlides.isNotEmpty()) pptxSlides else (parsedDoc?.slides ?: emptyList()),
                        fallbackContent = parsedDoc?.extractedText?.ifBlank { null } ?: book.content,
                        currentSlideIndex = pptxCurrentSlide,
                        onSlideSelected = { idx ->
                            pptxCurrentSlide = idx
                            val total = maxOf(1, pptxSlides.size.ifZero(book.totalChapters))
                            val progress = ((pptxCurrentSlide + 1) * 100 / total)
                            GraphiteRepository.updateBookProgress(book.id, progress)
                        },
                        onNextSlide = {
                            val maxSlide = maxOf(0, pptxSlides.size.ifZero(book.totalChapters) - 1)
                            if (pptxCurrentSlide < maxSlide) {
                                pptxCurrentSlide++
                                val progress = ((pptxCurrentSlide + 1) * 100 / (maxSlide + 1))
                                GraphiteRepository.updateBookProgress(book.id, progress)
                            }
                        },
                        onPreviousSlide = {
                            if (pptxCurrentSlide > 0) {
                                pptxCurrentSlide--
                                val maxSlide = maxOf(0, pptxSlides.size.ifZero(book.totalChapters) - 1)
                                val progress = ((pptxCurrentSlide + 1) * 100 / (maxSlide + 1))
                                GraphiteRepository.updateBookProgress(book.id, progress)
                            }
                        },
                        documentFile = book.filePath?.let { File(it) },
                        mimeType = book.mimeType,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isFullscreen) PaddingValues(0.dp) else innerPadding)
                    )
                }
                isDocx -> {
                    val docParagraphs = remember(parsedDoc, book.content) {
                        if (parsedDoc?.paragraphs?.isNotEmpty() == true) {
                            parsedDoc!!.paragraphs
                        } else if (book.content.isNotBlank()) {
                            book.content.split("\n\n").filter { it.isNotBlank() }
                        } else {
                            listOf("Document: ${book.title}")
                        }
                    }
                    DocxDocumentViewer(
                        title = book.title,
                        paragraphs = docParagraphs,
                        fontSize = fontSize,
                        onFontSizeChange = { fontSize = it },
                        documentFile = book.filePath?.let { File(it) },
                        mimeType = book.mimeType,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isFullscreen) PaddingValues(0.dp) else innerPadding)
                    )
                }
                isXlsx -> {
                    XlsxSpreadsheetViewer(
                        title = book.title,
                        content = parsedDoc?.extractedText?.ifBlank { null } ?: book.content,
                        documentFile = book.filePath?.let { File(it) },
                        mimeType = book.mimeType,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isFullscreen) PaddingValues(0.dp) else innerPadding)
                    )
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isFullscreen) PaddingValues(0.dp) else innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = book.content,
                            color = Color(0xFFE0E0E0),
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.6f).sp,
                            fontFamily = FontFamily.Serif
                        )
                    }
                }
            }

            // Sleek floating page pill indicator that auto-disappears after 1.2s (only for non-PDF)
            if (!isPdf) {
                AnimatedVisibility(
                    visible = showPagePill,
                    enter = fadeIn() + scaleIn(initialScale = 0.88f),
                    exit = fadeOut() + scaleOut(targetScale = 0.88f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xDD1E1E22),
                        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                        shadowElevation = 8.dp
                    ) {
                        Text(
                            text = when {
                                isPptx -> "Slide ${pptxCurrentSlide + 1} of ${maxOf(1, pptxSlides.size.ifZero(book.totalChapters))}"
                                else -> "${currentProgress.toInt()}%"
                            },
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }

    if (showFontDialog) {
        AlertDialog(
            onDismissRequest = { showFontDialog = false },
            title = { Text("Typography Settings", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Font Size: ${fontSize.toInt()} sp", color = Color.LightGray)
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        valueRange = 12f..28f,
                        colors = SliderDefaults.colors(
                            thumbColor = GraphiteOrange,
                            activeTrackColor = GraphiteOrange
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFontDialog = false }) {
                    Text("Done", color = GraphiteOrange)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }
}

/**
 * Native PDF renderer utilizing Android's PdfRenderer API for Moto Pad 60 Pro tablet viewing
 * with high-fidelity pinch-to-zoom, pan, edge-tap navigation, and smooth 3D page curl transition.
 */
@Composable
private fun PdfDocumentViewer(
    filePath: String,
    currentPageIndex: Int,
    totalPages: Int,
    onTotalPagesLoaded: (Int) -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val file = remember(filePath) { File(filePath) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Zoom & Pan state
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    val context = LocalContext.current
    var currentTime by remember {
        mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
    }
    var batteryLevel by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        while (true) {
            currentTime = sdf.format(Date())
            batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            delay(30000)
        }
    }

    var totalDragX by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentPageIndex) {
        zoomScale = 1f
        panOffset = Offset.Zero
    }

    LaunchedEffect(filePath, currentPageIndex) {
        isLoading = true
        renderError = null
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        renderError = "File not found: ${file.name}"
                        isLoading = false
                    }
                    return@withContext
                }

                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val count = renderer.pageCount
                withContext(Dispatchers.Main) {
                    onTotalPagesLoaded(count)
                }

                if (currentPageIndex in 0 until count) {
                    val page = renderer.openPage(currentPageIndex)
                    // Render page at sharp tablet resolution
                    val width = (page.width * 1.8f).toInt()
                    val height = (page.height * 1.8f).toInt()
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    withContext(Dispatchers.Main) {
                        bitmap?.recycle()
                        bitmap = bmp
                        isLoading = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isLoading = false
                    }
                }
                renderer.close()
                pfd.close()
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    renderError = "Failed to render PDF page: ${e.message}"
                    isLoading = false
                }
            }
        }
    }

    DisposableEffect(filePath) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onUserInteraction()
                    val newZoom = (zoomScale * zoom).coerceIn(1f, 4f)
                    zoomScale = newZoom
                    if (newZoom > 1.02f) {
                        val maxPanX = (size.width * (newZoom - 1f)) / 2f
                        val maxPanY = (size.height * (newZoom - 1f)) / 2f
                        panOffset = Offset(
                            (panOffset.x + pan.x).coerceIn(-maxPanX, maxPanX),
                            (panOffset.y + pan.y).coerceIn(-maxPanY, maxPanY)
                        )
                    } else {
                        panOffset = Offset.Zero
                    }
                }
            }
            .pointerInput(zoomScale, currentPageIndex, totalPages) {
                detectTapGestures(
                    onDoubleTap = {
                        onUserInteraction()
                        if (zoomScale > 1.05f) {
                            zoomScale = 1f
                            panOffset = Offset.Zero
                        } else {
                            zoomScale = 2.2f
                        }
                    },
                    onTap = { tapOffset ->
                        onUserInteraction()
                        if (zoomScale <= 1.05f) {
                            val w = size.width
                            if (tapOffset.x < w * 0.20f) {
                                if (currentPageIndex > 0) {
                                    onPreviousPage()
                                }
                            } else if (tapOffset.x > w * 0.80f) {
                                if (currentPageIndex < totalPages - 1) {
                                    onNextPage()
                                }
                            } else {
                                onToggleFullscreen()
                            }
                        } else {
                            onToggleFullscreen()
                        }
                    }
                )
            }
            .pointerInput(zoomScale, currentPageIndex, totalPages) {
                if (zoomScale <= 1.05f) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            totalDragX = 0f
                            onUserInteraction()
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            totalDragX += dragAmount
                        },
                        onDragEnd = {
                            if (totalDragX < -60f && currentPageIndex < totalPages - 1) {
                                onNextPage()
                            } else if (totalDragX > 60f && currentPageIndex > 0) {
                                onPreviousPage()
                            }
                            totalDragX = 0f
                        },
                        onDragCancel = {
                            totalDragX = 0f
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = GraphiteOrange,
                modifier = Modifier.size(36.dp),
                strokeWidth = 3.dp
            )
        } else if (renderError != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "PDF Render Error",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = renderError!!,
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            bitmap?.let { bmp ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "PDF Page ${currentPageIndex + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
                                translationX = panOffset.x
                                translationY = panOffset.y
                            }
                    )
                }
            }
        }

        // Clean e-reader bottom status bar matching Image 2 (Time & Battery on bottom-left, page count on bottom-right)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = currentTime,
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal
                )
                BatteryPillIcon(level = batteryLevel)
            }

            Text(
                text = "${currentPageIndex + 1}/$totalPages",
                color = Color(0xFF8E8E93),
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

@Composable
private fun BatteryPillIcon(
    level: Int,
    modifier: Modifier = Modifier
) {
    val pct = if (level in 0..100) level else 100
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(9.dp)
                .clip(RoundedCornerShape(2.dp))
                .border(1.dp, Color(0xFF8E8E93), RoundedCornerShape(2.dp))
                .padding(1.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(pct / 100f)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color(0xFF8E8E93))
            )
        }
        Box(
            modifier = Modifier
                .width(1.5.dp)
                .height(4.dp)
                .background(Color(0xFF8E8E93), RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp))
        )
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    subtitle: String,
    isPdf: Boolean,
    isPptx: Boolean,
    isBookmarked: Boolean,
    onNavigateBack: () -> Unit,
    onOpenExternal: (() -> Unit)?,
    onAdjustFont: () -> Unit,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(GraphiteBlack.copy(alpha = 0.94f))
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    fontFamily = FontFamily.Serif
                )
                Text(
                    text = subtitle,
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onOpenExternal != null) {
                IconButton(onClick = onOpenExternal) {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = "Open in External App",
                        tint = Color.LightGray
                    )
                }
            }

            if (!isPdf && !isPptx) {
                IconButton(onClick = onAdjustFont) {
                    Icon(
                        imageVector = Icons.Default.FormatSize,
                        contentDescription = "Adjust Font",
                        tint = Color.White
                    )
                }
            }

            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = "Bookmark Page",
                    tint = if (isBookmarked) GraphiteOrange else Color.LightGray
                )
            }
        }
    }
}

enum class SlideTheme(
    val label: String,
    val canvasBg: Color,
    val titleColor: Color,
    val textColor: Color,
    val cardBg: Color,
    val accentColor: Color
) {
    MODERN_DARK("Dark", Color(0xFF16181E), Color(0xFFFFFFFF), Color(0xFFE2E8F0), Color(0xFF222631), Color(0xFFFF7043)),
    LIGHT_DECK("Light", Color(0xFFFFFFFF), Color(0xFF0F172A), Color(0xFF334155), Color(0xFFF1F5F9), Color(0xFFD24726)),
    NAVY("Navy", Color(0xFF0B132B), Color(0xFFFFFFFF), Color(0xFFCAD2C5), Color(0xFF1C2541), Color(0xFF00B4D8))
}

@Composable
private fun PptxPresentationViewer(
    slides: List<SlideContent>,
    fallbackContent: String,
    currentSlideIndex: Int,
    onSlideSelected: (Int) -> Unit,
    onNextSlide: () -> Unit,
    onPreviousSlide: () -> Unit,
    documentFile: File?,
    mimeType: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTheme by remember { mutableStateOf(SlideTheme.MODERN_DARK) }
    val totalSlides = maxOf(1, slides.size)
    val safeIndex = currentSlideIndex.coerceIn(0, totalSlides - 1)
    val activeSlide = slides.getOrNull(safeIndex)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090A0D))
    ) {
        // Presentation Top Bar
        Surface(
            color = Color(0xFF14161C),
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFD24726)) // PowerPoint red
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("PPTX", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }

                    SlideTheme.entries.forEach { theme ->
                        val isSelected = selectedTheme == theme
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) selectedTheme.accentColor else Color(0xFF222631))
                                .clickable { selectedTheme = theme }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = theme.label,
                                color = if (isSelected) Color.White else Color.LightGray,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Slide ${safeIndex + 1} of $totalSlides",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (documentFile != null) {
                        FilledTonalButton(
                            onClick = {
                                DocumentContentParser.openWithExternalApp(context, documentFile, mimeType)
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFFD24726),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Slideshow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open in PowerPoint", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // 16:9 Slide Presentation Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .aspectRatio(16f / 9f)
                    .widthIn(max = 840.dp),
                colors = CardDefaults.cardColors(containerColor = selectedTheme.canvasBg),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Slide Title with Accent underline
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = activeSlide?.title ?: "Presentation Slide ${safeIndex + 1}",
                            color = selectedTheme.titleColor,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(selectedTheme.accentColor)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (activeSlide != null && activeSlide.bullets.isNotEmpty()) {
                        activeSlide.bullets.forEachIndexed { bIdx, bullet ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = selectedTheme.cardBg),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(selectedTheme.accentColor.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${bIdx + 1}",
                                            color = selectedTheme.accentColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = bullet,
                                        color = selectedTheme.textColor,
                                        fontSize = 15.sp,
                                        lineHeight = 22.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                }
                            }
                        }
                    } else if (activeSlide != null && activeSlide.fullText.isNotBlank()) {
                        Text(
                            text = activeSlide.fullText,
                            color = selectedTheme.textColor,
                            fontSize = 16.sp,
                            lineHeight = 24.sp
                        )
                    } else {
                        Text(
                            text = fallbackContent,
                            color = selectedTheme.textColor,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    }
                }
            }

            // Left / Right Circular Navigation Targets on Slide Canvas
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPreviousSlide,
                    enabled = safeIndex > 0,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = if (safeIndex > 0) 0.7f else 0.2f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous Slide",
                        tint = if (safeIndex > 0) Color.White else Color.Gray
                    )
                }

                IconButton(
                    onClick = onNextSlide,
                    enabled = safeIndex < totalSlides - 1,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = if (safeIndex < totalSlides - 1) 0.7f else 0.2f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next Slide",
                        tint = if (safeIndex < totalSlides - 1) Color.White else Color.Gray
                    )
                }
            }
        }

        // Horizontal Slide Filmstrip / Thumbnail Strip (Bottom)
        if (slides.isNotEmpty()) {
            Surface(
                color = Color(0xFF101217),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                shadowElevation = 4.dp
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(slides) { idx, slide ->
                        val isCurrent = idx == safeIndex
                        Card(
                            modifier = Modifier
                                .width(110.dp)
                                .fillMaxHeight()
                                .clickable { onSlideSelected(idx) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) Color(0xFF262C38) else Color(0xFF181A20)
                            ),
                            shape = RoundedCornerShape(6.dp),
                            border = if (isCurrent) androidx.compose.foundation.BorderStroke(2.dp, selectedTheme.accentColor) else null
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(6.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${idx + 1}",
                                        color = if (isCurrent) selectedTheme.accentColor else Color.Gray,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (isCurrent) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(selectedTheme.accentColor)
                                        )
                                    }
                                }
                                Text(
                                    text = slide.title,
                                    color = if (isCurrent) Color.White else Color.LightGray,
                                    fontSize = 10.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class DocTheme(
    val label: String,
    val pageBackground: Color,
    val textColor: Color,
    val secondaryColor: Color,
    val borderColor: Color,
    val deskBackground: Color
) {
    PAPER("Paper", Color(0xFFFFFFFF), Color(0xFF1E2022), Color(0xFF6B7280), Color(0xFFE5E7EB), Color(0xFF121316)),
    SEPIA("Sepia", Color(0xFFFAF3E0), Color(0xFF382918), Color(0xFF7D654C), Color(0xFFE8D7B8), Color(0xFF161412)),
    SLATE("Slate", Color(0xFF1E2530), Color(0xFFF1F5F9), Color(0xFF94A3B8), Color(0xFF334155), Color(0xFF0F1115)),
    DARK("Dark", Color(0xFF18181B), Color(0xFFF4F4F5), Color(0xFFA1A1AA), Color(0xFF27272A), Color(0xFF09090B))
}

@Composable
private fun DocxDocumentViewer(
    title: String,
    paragraphs: List<String>,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    documentFile: File?,
    mimeType: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTheme by remember { mutableStateOf(DocTheme.PAPER) }
    val totalWords = remember(paragraphs) {
        paragraphs.sumOf { it.split(Regex("\\s+")).filter { w -> w.isNotBlank() }.size }
    }
    val estimatedPages = maxOf(1, totalWords / 250)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(selectedTheme.deskBackground)
    ) {
        // Document Top Toolbar (Word blue header banner)
        Surface(
            color = Color(0xFF1A1C22),
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Word badge & Theme chips
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2B579A)) // Microsoft Word blue
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("DOCX", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }

                    // Theme selector chips
                    DocTheme.entries.forEach { theme ->
                        val isSelected = selectedTheme == theme
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) GraphiteOrange else Color(0xFF282C35))
                                .clickable { selectedTheme = theme }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = theme.label,
                                color = if (isSelected) Color.White else Color.LightGray,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // Font size controls & Open in Word button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = { onFontSizeChange((fontSize - 1f).coerceAtLeast(12f)) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("A-", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Text(
                        text = "${fontSize.toInt()}",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    IconButton(
                        onClick = { onFontSizeChange((fontSize + 1f).coerceAtMost(28f)) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("A+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    if (documentFile != null) {
                        FilledTonalButton(
                            onClick = {
                                DocumentContentParser.openWithExternalApp(context, documentFile, mimeType)
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color(0xFF2B579A),
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Open in Word", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Central Document Sheet
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = selectedTheme.pageBackground),
                shape = RoundedCornerShape(10.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp, vertical = 44.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Document Header inside the sheet
                    Text(
                        text = title,
                        color = selectedTheme.textColor,
                        fontSize = (fontSize * 1.5f).sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif,
                        lineHeight = (fontSize * 1.8f).sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${paragraphs.size} paragraphs • $totalWords words • ~$estimatedPages pages",
                            color = selectedTheme.secondaryColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(selectedTheme.borderColor)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("DOCX Format", color = selectedTheme.textColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    HorizontalDivider(color = selectedTheme.borderColor, thickness = 1.dp)

                    Spacer(modifier = Modifier.height(4.dp))

                    // Formatted Paragraphs
                    paragraphs.forEachIndexed { index, rawPara ->
                        val trimmed = rawPara.trim()
                        val isHeading = trimmed.length < 80 && (
                            trimmed.all { it.isUpperCase() || !it.isLetter() } ||
                            trimmed.startsWith("Chapter", ignoreCase = true) ||
                            trimmed.startsWith("Part", ignoreCase = true) ||
                            trimmed.startsWith("Section", ignoreCase = true) ||
                            trimmed.endsWith(":")
                        )

                        if (isHeading) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = trimmed,
                                color = selectedTheme.textColor,
                                fontSize = (fontSize * 1.25f).sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                lineHeight = (fontSize * 1.5f).sp
                            )
                        } else if (trimmed.startsWith("•") || trimmed.startsWith("-") || trimmed.matches(Regex("^\\d+[.)].*"))) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text("•  ", color = GraphiteOrange, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = trimmed.replace(Regex("^[•\\-]\\s*"), ""),
                                    color = selectedTheme.textColor,
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize * 1.6f).sp,
                                    fontFamily = FontFamily.Serif
                                )
                            }
                        } else {
                            Text(
                                text = trimmed,
                                color = selectedTheme.textColor,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.65f).sp,
                                fontFamily = FontFamily.Serif
                            )
                        }

                        // Subtle page break indicator every 8 paragraphs
                        if (index > 0 && index % 8 == 0 && index < paragraphs.size - 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                HorizontalDivider(modifier = Modifier.weight(1f), color = selectedTheme.borderColor)
                                Text(
                                    text = "  Page ${(index / 8) + 1}  ",
                                    color = selectedTheme.secondaryColor,
                                    fontSize = 10.sp
                                )
                                HorizontalDivider(modifier = Modifier.weight(1f), color = selectedTheme.borderColor)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun XlsxSpreadsheetViewer(
    title: String,
    content: String,
    documentFile: File?,
    mimeType: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rows = remember(content) {
        content.lines().filter { it.isNotBlank() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0F12))
    ) {
        Surface(
            color = Color(0xFF161920),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF107C41)) // Excel green
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("XLSX", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                    Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }

                if (documentFile != null) {
                    FilledTonalButton(
                        onClick = {
                            DocumentContentParser.openWithExternalApp(context, documentFile, mimeType)
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF107C41),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.GridOn, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Open in Excel", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(rows) { index, rowText ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (index == 0) Color(0xFF1E2430) else if (index % 2 == 0) Color(0xFF15181F) else Color(0xFF1A1E26)
                    ),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = Color(0xFF107C41),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(36.dp)
                        )
                        Text(
                            text = rowText,
                            color = if (index == 0) Color.White else Color(0xFFE2E8F0),
                            fontSize = 13.sp,
                            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

private fun Int.ifZero(defaultVal: Int): Int = if (this <= 0) defaultVal else this
