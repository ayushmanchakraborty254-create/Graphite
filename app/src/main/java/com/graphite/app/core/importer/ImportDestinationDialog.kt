package com.graphite.app.core.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteSurfaceVariant

/**
 * Resolves human-readable display name and file size from a content or file URI.
 */
fun queryFileMetadata(context: Context, uri: Uri): Pair<String, String> {
    var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
    var sizeStr = ""
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx != -1) {
                    val n = cursor.getString(nameIdx)
                    if (!n.isNullOrBlank()) displayName = n
                }
                if (sizeIdx != -1) {
                    val bytes = cursor.getLong(sizeIdx)
                    if (bytes > 0) {
                        sizeStr = if (bytes >= 1048576) {
                            String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576f)
                        } else {
                            String.format(java.util.Locale.US, "%d KB", bytes / 1024)
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return Pair(displayName, sizeStr)
}

/**
 * Dialog shown when opening an external document with Graphite, asking the user
 * whether they want to store the chosen file in Bookshelf or Noteshelf.
 */
@Composable
fun ImportDestinationDialog(
    uri: Uri,
    isImporting: Boolean,
    onSelectDestination: (ImportDestination) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val (fileName, fileSize) = remember(uri) { queryFileMetadata(context, uri) }
    val isPdf = remember(uri, fileName) {
        fileName.endsWith(".pdf", ignoreCase = true) ||
            context.contentResolver.getType(uri)?.contains("pdf", ignoreCase = true) == true
    }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Open with Graphite",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                // File info card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF141416))
                        .border(1.dp, Color(0xFF2E2E32), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = GraphiteOrange,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = fileName,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (fileSize.isNotBlank()) {
                            Text(
                                text = fileSize,
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Where would you like to store this file?",
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )

                if (isImporting) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = GraphiteOrange,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Importing document...",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    // Option 1: Bookshelf
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF141416))
                            .border(1.dp, Color(0xFF333338), RoundedCornerShape(12.dp))
                            .clickable { onSelectDestination(ImportDestination.BOOKSHELF) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E2638)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = "Bookshelf",
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Bookshelf",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Reading Tab",
                                    color = Color(0xFF64B5F6),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Read with continuous scroll, bookmarking, and e-reader features",
                                color = Color(0xFF9E9E9E),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    // Option 2: Notes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF141416))
                            .border(1.dp, Color(0xFF333338), RoundedCornerShape(12.dp))
                            .clickable { onSelectDestination(ImportDestination.NOTES) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF332014)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.EditNote,
                                contentDescription = "Notes",
                                tint = GraphiteOrange,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Notes",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Noteshelf Tab",
                                    color = GraphiteOrange,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Write with stylus, annotate, draw, and manage notebook pages",
                                color = Color(0xFF9E9E9E),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (!isImporting) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.Gray, fontSize = 13.sp)
                }
            }
        },
        containerColor = GraphiteSurfaceVariant,
        shape = RoundedCornerShape(18.dp)
    )
}
