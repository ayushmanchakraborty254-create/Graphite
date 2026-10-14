package com.graphite.app.feature.reader.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteSurfaceVariant
import com.graphite.app.data.repository.GraphiteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.graphite.app.core.importer.DocumentContentParser
import com.graphite.app.core.importer.ParsedDocument
import com.graphite.app.core.importer.SlideContent
import com.graphite.app.data.model.BookCoverType

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

    var currentProgress by remember { mutableFloatStateOf((book?.progressPercent ?: 0).toFloat()) }
    var fontSize by remember { mutableFloatStateOf(16f) }
    var showFontDialog by remember { mutableStateOf(false) }
    var isBookmarked by remember { mutableStateOf(book?.isBookmarked == true) }

    if (book == null) {
        Box(modifier = modifier.fillMaxSize().background(GraphiteBlack), contentAlignment = Alignment.Center) {
            Text("Document not found", color = Color.White)
        }
        return
    }

    val ext = book.filePath?.substringAfterLast('.', "")?.lowercase() ?: ""
    val isPdf = (book.filePath != null && ext == "pdf") || book.mimeType.contains("pdf", ignoreCase = true)
    val isPptx = ext in listOf("pptx", "ppt") || book.coverType == BookCoverType.CE_PPT
    val isDocx = ext in listOf("docx", "doc")

    var pdfTotalPages by remember { mutableIntStateOf(book.totalChapters) }
    var pdfCurrentPage by remember { mutableIntStateOf(0) }

    // Parse presentation slides if PPTX
    var pptxSlides by remember { mutableStateOf<List<SlideContent>>(emptyList()) }
    var pptxCurrentSlide by remember { mutableIntStateOf(0) }

    LaunchedEffect(book.filePath) {
        if (isPptx && book.filePath != null) {
            val file = File(book.filePath)
            if (file.exists()) {
                withContext(Dispatchers.IO) {
                    val bytes = file.readBytes()
                    val parsed = DocumentContentParser.parse(bytes, file.name, book.mimeType)
                    withContext(Dispatchers.Main) {
                        if (parsed.slides.isNotEmpty()) {
                            pptxSlides = parsed.slides
                            pdfTotalPages = parsed.slides.size
                        }
                    }
                }
            }
        }
    }

    val subtitle = when {
        isPdf -> "PDF Document • Page ${pdfCurrentPage + 1} of ${maxOf(1, pdfTotalPages)}"
        isPptx -> "PowerPoint Presentation • Slide ${pptxCurrentSlide + 1} of ${maxOf(1, pptxSlides.size.ifZero(book.totalChapters))}"
        isDocx -> "Word Document • ${book.author}"
        else -> book.author
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GraphiteBlack)
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
                            text = book.title,
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
                    // Open with external app (Word, PowerPoint, Google Docs, etc.)
                    if (book.filePath != null) {
                        IconButton(
                            onClick = {
                                DocumentContentParser.openWithExternalApp(
                                    context,
                                    File(book.filePath),
                                    book.mimeType
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "Open in External App",
                                tint = Color.LightGray
                            )
                        }
                    }

                    if (!isPdf && !isPptx) {
                        IconButton(onClick = { showFontDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.FormatSize,
                                contentDescription = "Adjust Font",
                                tint = Color.White
                            )
                        }
                    }

                    // Bookmark button (Functional with persistence)
                    IconButton(
                        onClick = {
                            isBookmarked = !isBookmarked
                            GraphiteRepository.toggleBookmarkBook(book.id)
                            GraphiteRepository.toggleBookmarkNotebook(book.id)
                            val msg = if (isBookmarked) "Page bookmarked" else "Bookmark removed"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "Bookmark Page",
                            tint = if (isBookmarked) GraphiteOrange else Color.LightGray
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                color = Color(0xFF141414),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                isPdf -> "Page ${pdfCurrentPage + 1} of ${maxOf(1, pdfTotalPages)}"
                                isPptx -> "Slide ${pptxCurrentSlide + 1} of ${maxOf(1, pptxSlides.size.ifZero(book.totalChapters))}"
                                else -> "Chapter ${book.currentChapter} of ${book.totalChapters}"
                            },
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                        Text(
                            text = when {
                                isPdf -> "${((pdfCurrentPage + 1) * 100 / maxOf(1, pdfTotalPages))}%"
                                isPptx -> "${((pptxCurrentSlide + 1) * 100 / maxOf(1, pptxSlides.size.ifZero(book.totalChapters)))}%"
                                else -> "${currentProgress.toInt()}%"
                            },
                            color = GraphiteOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = when {
                            isPdf -> pdfCurrentPage.toFloat()
                            isPptx -> pptxCurrentSlide.toFloat()
                            else -> currentProgress
                        },
                        onValueChange = {
                            when {
                                isPdf -> {
                                    pdfCurrentPage = it.toInt().coerceIn(0, maxOf(0, pdfTotalPages - 1))
                                    val progress = ((pdfCurrentPage + 1) * 100 / maxOf(1, pdfTotalPages))
                                    GraphiteRepository.updateBookProgress(book.id, progress)
                                }
                                isPptx -> {
                                    val maxSlide = maxOf(0, pptxSlides.size.ifZero(book.totalChapters) - 1)
                                    pptxCurrentSlide = it.toInt().coerceIn(0, maxSlide)
                                    val progress = ((pptxCurrentSlide + 1) * 100 / maxOf(1, maxSlide + 1))
                                    GraphiteRepository.updateBookProgress(book.id, progress)
                                }
                                else -> {
                                    currentProgress = it
                                    GraphiteRepository.updateBookProgress(book.id, it.toInt())
                                }
                            }
                        },
                        valueRange = when {
                            isPdf -> 0f..maxOf(0, pdfTotalPages - 1).toFloat()
                            isPptx -> 0f..maxOf(0, pptxSlides.size.ifZero(book.totalChapters) - 1).toFloat()
                            else -> 0f..100f
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = GraphiteOrange,
                            activeTrackColor = GraphiteOrange,
                            inactiveTrackColor = Color(0xFF333333)
                        )
                    )
                }
            }
        },
        containerColor = GraphiteBlack
    ) { innerPadding ->
        when {
            isPdf && book.filePath != null -> {
                PdfDocumentViewer(
                    filePath = book.filePath,
                    currentPageIndex = pdfCurrentPage,
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }
            isPptx -> {
                PptxPresentationViewer(
                    slides = pptxSlides,
                    fallbackContent = book.content,
                    currentSlideIndex = pptxCurrentSlide,
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }
            isDocx -> {
                DocxDocumentViewer(
                    content = book.content,
                    fontSize = fontSize,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
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
 * Native PDF renderer utilizing Android's PdfRenderer API for Moto Pad 60 Pro tablet viewing.
 */
@Composable
private fun PdfDocumentViewer(
    filePath: String,
    currentPageIndex: Int,
    onTotalPagesLoaded: (Int) -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val file = remember(filePath) { File(filePath) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

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
                    val width = (page.width * 1.6f).toInt()
                    val height = (page.height * 1.6f).toInt()
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
            .background(Color(0xFF0D0D0D)),
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "PDF Page ${currentPageIndex + 1}",
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                    )

                    // Overlay Navigation Touch Targets on Left and Right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = onPreviousPage,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous Page",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = onNextPage,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next Page",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PptxPresentationViewer(
    slides: List<SlideContent>,
    fallbackContent: String,
    currentSlideIndex: Int,
    onNextSlide: () -> Unit,
    onPreviousSlide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalSlides = maxOf(1, slides.size)
    val safeIndex = currentSlideIndex.coerceIn(0, totalSlides - 1)
    val activeSlide = slides.getOrNull(safeIndex)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Slide Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Slideshow,
                            contentDescription = null,
                            tint = GraphiteOrange,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Slide ${safeIndex + 1} of $totalSlides",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFD32F2F))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("PPTX", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(color = Color(0xFF2E2E2E))

                if (activeSlide != null) {
                    Text(
                        text = activeSlide.title,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (activeSlide.bullets.isNotEmpty()) {
                        activeSlide.bullets.forEach { bullet ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text("•  ", color = GraphiteOrange, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = bullet,
                                    color = Color(0xFFDEDEDE),
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
                                )
                            }
                        }
                    } else if (activeSlide.fullText.isNotBlank()) {
                        Text(
                            text = activeSlide.fullText,
                            color = Color(0xFFDEDEDE),
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                    }
                } else {
                    // Fallback to text lines
                    Text(
                        text = fallbackContent,
                        color = Color(0xFFDEDEDE),
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                }
            }
        }

        // Overlay Navigation Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPreviousSlide,
                enabled = safeIndex > 0,
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = if (safeIndex > 0) 0.65f else 0.2f))
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
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = if (safeIndex < totalSlides - 1) 0.65f else 0.2f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Next Slide",
                    tint = if (safeIndex < totalSlides - 1) Color.White else Color.Gray
                )
            }
        }
    }
}

@Composable
private fun DocxDocumentViewer(
    content: String,
    fontSize: Float,
    modifier: Modifier = Modifier
) {
    val paragraphs = remember(content) {
        content.split("\n\n").filter { it.isNotBlank() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = Color(0xFF42A5F5),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Word Document (${paragraphs.size} paragraphs)",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1976D2))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("DOCX", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        HorizontalDivider(color = Color(0xFF2E2E2E))

        paragraphs.forEach { para ->
            Text(
                text = para.trim(),
                color = Color(0xFFE2E2E2),
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.6f).sp,
                fontFamily = FontFamily.Serif
            )
        }
    }
}

private fun Int.ifZero(defaultVal: Int): Int = if (this <= 0) defaultVal else this
