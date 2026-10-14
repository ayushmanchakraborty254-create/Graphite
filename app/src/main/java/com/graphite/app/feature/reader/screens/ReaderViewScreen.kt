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

@Composable
fun ReaderViewScreen(
    bookId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val book = remember(bookId) { GraphiteRepository.getBook(bookId) }
    var currentProgress by remember { mutableFloatStateOf((book?.progressPercent ?: 1).toFloat()) }
    var fontSize by remember { mutableFloatStateOf(16f) }
    var showFontDialog by remember { mutableStateOf(false) }

    val isPdf = book?.filePath != null && book.filePath.endsWith(".pdf", ignoreCase = true)
    var pdfTotalPages by remember { mutableIntStateOf(book?.totalChapters ?: 1) }
    var pdfCurrentPage by remember { mutableIntStateOf(0) }

    if (book == null) {
        Box(modifier = modifier.fillMaxSize().background(GraphiteBlack), contentAlignment = Alignment.Center) {
            Text("Document not found", color = Color.White)
        }
        return
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = book.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = if (isPdf) "PDF Document • Page ${pdfCurrentPage + 1} of $pdfTotalPages" else book.author,
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isPdf) {
                        IconButton(onClick = { showFontDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.FormatSize,
                                contentDescription = "Adjust Font",
                                tint = Color.White
                            )
                        }
                    }
                    IconButton(onClick = { /* Bookmark */ }) {
                        Icon(
                            imageVector = Icons.Default.BookmarkBorder,
                            contentDescription = "Bookmark Page",
                            tint = GraphiteOrange
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
                            text = if (isPdf) {
                                "Page ${pdfCurrentPage + 1} of $pdfTotalPages"
                            } else {
                                "Chapter ${book.currentChapter} of ${book.totalChapters}"
                            },
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                        Text(
                            text = if (isPdf) {
                                "${((pdfCurrentPage + 1) * 100 / maxOf(1, pdfTotalPages))}%"
                            } else {
                                "${currentProgress.toInt()}%"
                            },
                            color = GraphiteOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = if (isPdf) pdfCurrentPage.toFloat() else currentProgress,
                        onValueChange = {
                            if (isPdf) {
                                pdfCurrentPage = it.toInt().coerceIn(0, maxOf(0, pdfTotalPages - 1))
                                val progress = ((pdfCurrentPage + 1) * 100 / maxOf(1, pdfTotalPages))
                                GraphiteRepository.updateBookProgress(book.id, progress)
                            } else {
                                currentProgress = it
                                GraphiteRepository.updateBookProgress(book.id, it.toInt())
                            }
                        },
                        valueRange = if (isPdf) 0f..maxOf(0, pdfTotalPages - 1).toFloat() else 0f..100f,
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
        if (isPdf && book.filePath != null) {
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
        } else {
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
