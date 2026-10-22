package com.graphite.app.feature.reader.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.core.importer.PdfPageThumbnail
import com.graphite.app.data.model.Book
import com.graphite.app.data.model.BookCoverType
import java.io.File

/**
 * Universal Book Cover component for Bookshelf:
 * - For PDF documents: Renders the real first page of the PDF as the cover page.
 * - For books with cover images: Renders the book cover image bitmap.
 * - For classic / curated books: Renders custom embossed cover artwork (e.g. Treasure Island, CE PPT).
 */
@Composable
fun BookCover(
    book: Book,
    modifier: Modifier = Modifier
) {
    val isPdf = book.mimeType.equals("application/pdf", ignoreCase = true) ||
            book.filePath?.endsWith(".pdf", ignoreCase = true) == true

    Box(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(6.dp))
    ) {
        if (isPdf && !book.filePath.isNullOrBlank()) {
            // First page of the PDF rendered as the cover page
            Box(modifier = Modifier.fillMaxSize()) {
                PdfPageThumbnail(
                    filePath = book.filePath,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 320
                )
                // Subtle badge indicating PDF document at top left
                Box(
                    modifier = Modifier
                        .padding(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFFD32F2F).copy(alpha = 0.90f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .align(Alignment.TopStart)
                ) {
                    Text(
                        text = "PDF",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else if (!book.filePath.isNullOrBlank() && isImageFile(book.filePath)) {
            // Book cover image file
            CoverImageView(filePath = book.filePath, fallbackTitle = book.title)
        } else {
            // Standard book artwork (Treasure Island, CE PPT, or textured book styling)
            StandardBookCoverLayout(
                coverType = book.coverType,
                title = book.title,
                author = book.author
            )
        }
    }
}

/**
 * Legacy overload supporting direct coverType and title parameters.
 */
@Composable
fun BookCover(
    coverType: BookCoverType,
    title: String,
    modifier: Modifier = Modifier,
    filePath: String? = null,
    mimeType: String? = null
) {
    val isPdf = mimeType?.equals("application/pdf", ignoreCase = true) == true ||
            filePath?.endsWith(".pdf", ignoreCase = true) == true

    Box(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(6.dp))
    ) {
        if (isPdf && !filePath.isNullOrBlank()) {
            Box(modifier = Modifier.fillMaxSize()) {
                PdfPageThumbnail(
                    filePath = filePath,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 320
                )
                Box(
                    modifier = Modifier
                        .padding(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFFD32F2F).copy(alpha = 0.90f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .align(Alignment.TopStart)
                ) {
                    Text(
                        text = "PDF",
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else if (!filePath.isNullOrBlank() && isImageFile(filePath)) {
            CoverImageView(filePath = filePath, fallbackTitle = title)
        } else {
            StandardBookCoverLayout(
                coverType = coverType,
                title = title,
                author = ""
            )
        }
    }
}

private fun isImageFile(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext in listOf("jpg", "jpeg", "png", "webp", "bmp")
}

@Composable
private fun CoverImageView(filePath: String, fallbackTitle: String) {
    var bitmap by remember(filePath) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }

    LaunchedEffect(filePath) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                bitmap = bmp
            }
        } catch (_: Exception) {}
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = fallbackTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        StandardBookCoverLayout(
            coverType = BookCoverType.CUSTOM,
            title = fallbackTitle,
            author = ""
        )
    }
}

@Composable
private fun StandardBookCoverLayout(
    coverType: BookCoverType,
    title: String,
    author: String
) {
    when (coverType) {
        BookCoverType.TREASURE_ISLAND -> {
            // Nautical vintage book cover matching Page 1 & 2
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFFFF8E7),
                                Color(0xFFF4E4BA),
                                Color(0xFFE2C992)
                            )
                        )
                    )
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "TREASURE\nISLAND",
                    color = Color(0xFF7A1C00),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                    textAlign = TextAlign.Center,
                    lineHeight = 11.sp
                )

                // Sailing ship illustration
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color(0xFF2B4C7E).copy(alpha = 0.15f), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsBoat,
                        contentDescription = null,
                        tint = Color(0xFFC04E22),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        tint = Color(0xFF8D6E63),
                        modifier = Modifier.size(8.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "R. L. STEVENSON",
                        color = Color(0xFF4E342E),
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        BookCoverType.CE_PPT -> {
            // Presentation slide cover
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFB3E5FC),
                                Color(0xFFE1F5FE)
                            )
                        )
                    )
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .background(Color(0xFF0288D1), RoundedCornerShape(2.dp))
                )

                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = Color(0xFF0277BD),
                    modifier = Modifier.size(28.dp)
                )

                Text(
                    text = "CE PPT",
                    color = Color(0xFF01579B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        BookCoverType.CUSTOM -> {
            // Rich textured book cover with book spine shading
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF141414), // Spine shadow
                                Color(0xFF262626),
                                Color(0xFF2E2E2E),
                                Color(0xFF222222)
                            )
                        )
                    )
            ) {
                // Spine accent line
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(4.dp)
                        .background(Color(0xFFFF7A30).copy(alpha = 0.5f))
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Icon(
                        imageVector = Icons.Default.Book,
                        contentDescription = null,
                        tint = Color(0xFFFF7A30),
                        modifier = Modifier.size(26.dp)
                    )

                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    if (author.isNotBlank() && author != "Unknown Author") {
                        Text(
                            text = author,
                            color = Color(0xFFB0B0B0),
                            fontSize = 8.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}
