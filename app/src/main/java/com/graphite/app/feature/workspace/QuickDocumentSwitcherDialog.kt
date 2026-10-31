package com.graphite.app.feature.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.data.model.NotebookItem

private enum class DocFilterType {
    ALL, PDF, NOTE
}

@Composable
fun QuickDocumentSwitcherDialog(
    currentNotebookId: String,
    allNotes: List<NotebookItem>,
    isInSplitScreen: Boolean = false,
    splitPaneLabel: String? = null,
    onDismiss: () -> Unit,
    onSelectDocument: (NotebookItem) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(DocFilterType.ALL) }

    val currentNote = remember(allNotes, currentNotebookId) {
        allNotes.firstOrNull { it.id == currentNotebookId }
    }

    val totalPdfCount = remember(allNotes) {
        allNotes.count { it.mimeType?.contains("pdf", ignoreCase = true) == true || it.filePath?.endsWith(".pdf", ignoreCase = true) == true }
    }
    val totalNoteCount = remember(allNotes, totalPdfCount) {
        allNotes.size - totalPdfCount
    }

    val filteredList = remember(allNotes, searchQuery, selectedFilter) {
        allNotes.filter { note ->
            val isPdf = note.mimeType?.contains("pdf", ignoreCase = true) == true ||
                    note.filePath?.endsWith(".pdf", ignoreCase = true) == true

            val matchesFilter = when (selectedFilter) {
                DocFilterType.ALL -> true
                DocFilterType.PDF -> isPdf
                DocFilterType.NOTE -> !isPdf
            }

            val matchesSearch = if (searchQuery.isBlank()) true else {
                note.title.contains(searchQuery.trim(), ignoreCase = true)
            }

            matchesFilter && matchesSearch
        }.sortedWith(
            compareByDescending<NotebookItem> { it.id == currentNotebookId }
                .thenByDescending { it.updatedAtMillis }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
            border = BorderStroke(1.dp, Color(0xFF2E2E34)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF2A2016)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = GraphiteOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Text(
                                text = if (isInSplitScreen && splitPaneLabel != null) "Switch $splitPaneLabel" else "Switch Document",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (currentNote != null) {
                                Text(
                                    text = "Current: ${currentNote.title}",
                                    color = Color(0xFFA0A0AA),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF242428))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.LightGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search Input
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            "Search notes & PDFs...",
                            color = Color(0xFF71717A),
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Color(0xFF9CA3AF),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF131316),
                        unfocusedContainerColor = Color(0xFF131316),
                        focusedBorderColor = GraphiteOrange,
                        unfocusedBorderColor = Color(0xFF2C2C34),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChipTab(
                        label = "All (${allNotes.size})",
                        isSelected = selectedFilter == DocFilterType.ALL,
                        onClick = { selectedFilter = DocFilterType.ALL }
                    )
                    FilterChipTab(
                        label = "PDFs ($totalPdfCount)",
                        isSelected = selectedFilter == DocFilterType.PDF,
                        onClick = { selectedFilter = DocFilterType.PDF },
                        accentColor = Color(0xFFEF5350)
                    )
                    FilterChipTab(
                        label = "Notes ($totalNoteCount)",
                        isSelected = selectedFilter == DocFilterType.NOTE,
                        onClick = { selectedFilter = DocFilterType.NOTE },
                        accentColor = GraphiteOrange
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Document list
                if (filteredList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FindInPage,
                                contentDescription = null,
                                tint = Color(0xFF52525B),
                                modifier = Modifier.size(42.dp)
                            )
                            Text(
                                text = "No documents found",
                                color = Color(0xFF71717A),
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredList, key = { it.id }) { doc ->
                            val isCurrent = doc.id == currentNotebookId
                            val isPdf = doc.mimeType?.contains("pdf", ignoreCase = true) == true ||
                                    doc.filePath?.endsWith(".pdf", ignoreCase = true) == true
                            val isKeep = doc.mimeType == "text/keep"

                            DocumentRowItem(
                                doc = doc,
                                isCurrent = isCurrent,
                                isPdf = isPdf,
                                isKeep = isKeep,
                                onClick = {
                                    onSelectDocument(doc)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipTab(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    accentColor: Color = GraphiteOrange
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) accentColor.copy(alpha = 0.16f) else Color(0xFF222226),
        label = "chipBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) accentColor else Color(0xFF323238),
        label = "chipBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color(0xFFA1A1AA),
        label = "chipText"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun DocumentRowItem(
    doc: NotebookItem,
    isCurrent: Boolean,
    isPdf: Boolean,
    isKeep: Boolean,
    onClick: () -> Unit
) {
    val bgModifier = if (isCurrent) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF232328))
            .border(1.dp, Color(0xFF29B6F6).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
    } else {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E22))
            .border(1.dp, Color(0xFF2B2B32), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    }

    Row(
        modifier = bgModifier.padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Doc type icon
        val iconColor = when {
            isPdf -> Color(0xFFEF5350)
            isKeep -> Color(0xFF26A69A)
            else -> GraphiteOrange
        }
        val iconBgColor = iconColor.copy(alpha = 0.14f)

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when {
                    isPdf -> Icons.Default.PictureAsPdf
                    isKeep -> Icons.AutoMirrored.Filled.Notes
                    else -> Icons.Default.Description
                },
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Document details
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = doc.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                // Format badge
                val badgeText = when {
                    isPdf -> "PDF"
                    isKeep -> "KEEP"
                    else -> "NOTE"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(iconColor.copy(alpha = 0.2f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = iconColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val pageText = if (isPdf || doc.pageCount > 1) "${doc.pageCount} pgs" else "1 pg"
                Text(
                    text = pageText,
                    color = Color(0xFF71717A),
                    fontSize = 11.sp
                )
                Text(
                    text = "•",
                    color = Color(0xFF52525B),
                    fontSize = 10.sp
                )
                Text(
                    text = doc.updatedAtFormatted,
                    color = Color(0xFF71717A),
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Trailing status / button
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0D324D))
                    .border(1.dp, Color(0xFF29B6F6), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "ACTIVE",
                    color = Color(0xFF29B6F6),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            IconButton(
                onClick = onClick,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A2A30))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = "Switch to document",
                    tint = Color.LightGray,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
