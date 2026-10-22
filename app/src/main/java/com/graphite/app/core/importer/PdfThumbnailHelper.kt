package com.graphite.app.core.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thread-safe, high-performance in-memory cache and loader for PDF page 0 thumbnails.
 * Eliminates repeated disk I/O and PdfRenderer recreation during list/grid scrolling.
 */
object PdfThumbnailHelper {
    // 60-entry LRU cache in RAM (~30-40 MB max for thumbnail-size bitmaps)
    private val memoryCache = object : LruCache<String, Bitmap>(60) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return (bitmap.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun getFromCache(key: String): Bitmap? = synchronized(memoryCache) {
        memoryCache.get(key)
    }

    fun putInCache(key: String, bitmap: Bitmap) = synchronized(memoryCache) {
        memoryCache.put(key, bitmap)
    }

    suspend fun getOrRenderThumbnail(
        context: Context,
        filePath: String,
        targetWidth: Int = 300
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cached = getFromCache(filePath)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        try {
            val pfd: ParcelFileDescriptor = (if (filePath.startsWith("content://")) {
                context.contentResolver.openFileDescriptor(Uri.parse(filePath), "r")
            } else {
                val file = File(filePath)
                if (file.exists() && file.canRead()) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } else null
            }) ?: return@withContext null

            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount > 0) {
                        renderer.openPage(0).use { page ->
                            val pageW = page.width.toFloat().coerceAtLeast(1f)
                            val pageH = page.height.toFloat().coerceAtLeast(1f)
                            val w = targetWidth.coerceIn(120, 720)
                            val h = ((w * pageH) / pageW).toInt().coerceIn(100, 1000)

                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bmp)
                            canvas.drawColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            putInCache(filePath, bmp)
                            return@withContext bmp
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        null
    }
}

/**
 * High-performance Compose component to render the first page of a PDF document.
 * Renders instantly from memory cache if available, preventing scroll hitching.
 */
@Composable
fun PdfPageThumbnail(
    filePath: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    targetWidth: Int = 300
) {
    val context = LocalContext.current
    var bitmap by remember(filePath) {
        mutableStateOf(PdfThumbnailHelper.getFromCache(filePath))
    }

    LaunchedEffect(filePath) {
        if (bitmap == null || bitmap?.isRecycled == true) {
            val rendered = PdfThumbnailHelper.getOrRenderThumbnail(context, filePath, targetWidth)
            if (rendered != null) {
                bitmap = rendered
            }
        }
    }

    val currentBmp = bitmap
    if (currentBmp != null && !currentBmp.isRecycled) {
        Image(
            bitmap = currentBmp.asImageBitmap(),
            contentDescription = "PDF Page Thumbnail",
            contentScale = contentScale,
            modifier = modifier.fillMaxSize()
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF2A2A2A))
        )
    }
}
