package com.graphite.app.core.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
                val targetFile = if (file.exists() && file.canRead()) file else {
                    try {
                        val notesDir = File(context.filesDir, "imported_notes")
                        if (notesDir.exists()) {
                            val baseName = file.name
                            notesDir.listFiles()?.firstOrNull { f ->
                                f.length() > 0 && (
                                    f.name.endsWith("_$baseName", ignoreCase = true) ||
                                    f.name.equals(baseName, ignoreCase = true) ||
                                    f.name.contains(baseName, ignoreCase = true)
                                )
                            }
                        } else null
                    } catch (_: Exception) { null }
                }
                if (targetFile != null && targetFile.exists() && targetFile.canRead()) {
                    ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
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
                .background(Color(0xFFFAFAFA))
        )
    }
}

/**
 * Thread-safe, high-performance in-memory cache and loader for Image note thumbnails.
 * Eliminates repeated disk I/O and BitmapFactory decoding during list/grid scrolling.
 */
object ImageThumbnailHelper {
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

    suspend fun getOrDecodeThumbnail(
        context: Context,
        filePath: String,
        targetWidth: Int = 300
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cached = getFromCache(filePath)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            if (filePath.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(filePath))?.use {
                    BitmapFactory.decodeStream(it, null, boundsOptions)
                }
            } else {
                val f = File(filePath)
                if (!f.exists() || !f.canRead()) return@withContext null
                BitmapFactory.decodeFile(f.absolutePath, boundsOptions)
            }

            val origW = boundsOptions.outWidth.coerceAtLeast(1)
            val origH = boundsOptions.outHeight.coerceAtLeast(1)
            var inSampleSize = 1
            val maxBound = targetWidth.coerceIn(120, 720) * 2
            while ((origW / inSampleSize) > maxBound || (origH / inSampleSize) > maxBound) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decoded = if (filePath.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(filePath))?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                }
            } else {
                BitmapFactory.decodeFile(filePath, decodeOptions)
            }

            if (decoded != null) {
                putInCache(filePath, decoded)
                return@withContext decoded
            }
        } catch (_: Exception) {}
        null
    }
}

/**
 * High-performance Compose component to render the thumbnail of an image note.
 * Renders instantly from memory cache if available, preventing scroll hitching.
 */
@Composable
fun ImagePageThumbnail(
    filePath: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    targetWidth: Int = 300
) {
    val context = LocalContext.current
    var bitmap by remember(filePath) {
        mutableStateOf(ImageThumbnailHelper.getFromCache(filePath))
    }

    LaunchedEffect(filePath) {
        if (bitmap == null || bitmap?.isRecycled == true) {
            val decoded = ImageThumbnailHelper.getOrDecodeThumbnail(context, filePath, targetWidth)
            if (decoded != null) {
                bitmap = decoded
            }
        }
    }

    val currentBmp = bitmap
    if (currentBmp != null && !currentBmp.isRecycled) {
        Image(
            bitmap = currentBmp.asImageBitmap(),
            contentDescription = "Image Note Thumbnail",
            contentScale = contentScale,
            modifier = modifier.fillMaxSize()
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFFF5F5F5))
        )
    }
}
