package com.graphite.app.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.voice.VoiceClip
import java.io.File

@Composable
fun NotebookAudioClipsDialog(
    notebookId: String,
    notebookTitle: String,
    currentPageIndex: Int,
    totalPages: Int,
    voiceNotes: List<VoiceClip>,
    onDismiss: () -> Unit,
    onRecordOnPage: (pageIndex: Int) -> Unit,
    onDeleteClip: (clipId: String) -> Unit,
    onGoToPage: (pageIndex: Int) -> Unit,
    onInsertTranscriptionToPage: (pageIndex: Int, text: String) -> Unit
) {
    val context = LocalContext.current
    val recorderManager = remember { GraphiteApplication.instance.voiceRecorderManager }
    val currentlyPlayingId by recorderManager.currentlyPlayingId.collectAsState()
    val playbackProgress by recorderManager.playbackProgress.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Current Page, 1: All Pages
    var clipToDelete by remember { mutableStateOf<VoiceClip?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            recorderManager.stopPlayback()
        }
    }

    val pageClips = remember(voiceNotes, currentPageIndex) {
        voiceNotes.filter { it.pageIndex == currentPageIndex }
    }
    val displayedClips = if (selectedTab == 0) pageClips else voiceNotes

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
            border = BorderStroke(1.dp, Color(0xFF2C2C32))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00BCD4).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = Color(0xFF00BCD4),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Audio Recordings",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "$notebookTitle • ${voiceNotes.size} total",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.LightGray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF28282D))
                        .padding(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedTab == 0) Color(0xFF00BCD4) else Color.Transparent)
                            .clickable { selectedTab = 0 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Page ${currentPageIndex + 1} (${pageClips.size})",
                            color = if (selectedTab == 0) Color.Black else Color.LightGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selectedTab == 1) Color(0xFF00BCD4) else Color.Transparent)
                            .clickable { selectedTab = 1 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "All Pages (${voiceNotes.size})",
                            color = if (selectedTab == 1) Color.Black else Color.LightGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quick Action Button: Record on active page
                Button(
                    onClick = {
                        onDismiss()
                        onRecordOnPage(currentPageIndex)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Record Audio on Page ${currentPageIndex + 1}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // List of audio recordings
                if (displayedClips.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = Color(0xFF44444A),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = if (selectedTab == 0)
                                    "No audio recordings on Page ${currentPageIndex + 1}"
                                else
                                    "No audio recordings in this notebook yet",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(displayedClips, key = { it.id }) { clip ->
                            val isPlaying = currentlyPlayingId == clip.id
                            val fileExists = File(clip.filePath).exists()

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isPlaying) Color(0xFF262C30) else Color(0xFF25252A)
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isPlaying) Color(0xFF00BCD4) else Color(0xFF33333A)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    // Row 1: Page tag, Title & Delete
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color(0xFF00BCD4).copy(alpha = 0.2f),
                                                border = BorderStroke(1.dp, Color(0xFF00BCD4).copy(alpha = 0.5f))
                                            ) {
                                                Text(
                                                    text = "Page ${clip.pageIndex + 1}",
                                                    color = Color(0xFF00BCD4),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            Text(
                                                text = clip.title,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        IconButton(
                                            onClick = { clipToDelete = clip },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = Color(0xFFFF5252),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Row 2: Date & Duration
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = clip.createdAtFormatted,
                                            color = Color.Gray,
                                            fontSize = 11.sp
                                        )
                                        val min = clip.durationSeconds / 60
                                        val sec = clip.durationSeconds % 60
                                        Text(
                                            text = String.format("%d:%02d", min, sec),
                                            color = Color(0xFF00BCD4),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Playback Controller
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF1A1A1E))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = {
                                                if (isPlaying) {
                                                    recorderManager.stopPlayback()
                                                } else {
                                                    recorderManager.playClip(clip)
                                                }
                                            },
                                            modifier = Modifier.size(32.dp),
                                            enabled = fileExists
                                        ) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = if (isPlaying) "Pause" else "Play",
                                                tint = if (fileExists) Color(0xFF00BCD4) else Color.Gray,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        LinearProgressIndicator(
                                            progress = { if (isPlaying) playbackProgress else 0f },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = Color(0xFF00BCD4),
                                            trackColor = Color(0xFF33333A)
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Text(
                                            text = if (!fileExists) "Offline" else if (isPlaying) "Playing" else "Ready",
                                            color = if (!fileExists) Color(0xFFFF5252) else Color.LightGray,
                                            fontSize = 10.sp
                                        )
                                    }

                                    // Transcription if available
                                    if (!clip.transcription.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF1B1B1F),
                                            border = BorderStroke(1.dp, Color(0xFF2C2C30)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp)) {
                                                Text(
                                                    text = clip.transcription,
                                                    color = Color(0xFFE0E0E0),
                                                    fontSize = 11.sp,
                                                    lineHeight = 16.sp,
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis
                                                )

                                                Spacer(modifier = Modifier.height(6.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.End,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    TextButton(
                                                        onClick = {
                                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                            cm?.setPrimaryClip(ClipData.newPlainText("Voice Note", clip.transcription))
                                                            Toast.makeText(context, "Copied transcription", Toast.LENGTH_SHORT).show()
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(13.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Copy", color = Color.LightGray, fontSize = 10.sp)
                                                    }

                                                    Spacer(modifier = Modifier.width(6.dp))

                                                    TextButton(
                                                        onClick = {
                                                            onInsertTranscriptionToPage(clip.pageIndex, clip.transcription)
                                                            Toast.makeText(context, "Inserted onto Page ${clip.pageIndex + 1}!", Toast.LENGTH_SHORT).show()
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Icon(Icons.Default.TextFields, contentDescription = null, tint = Color(0xFF00BCD4), modifier = Modifier.size(13.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Insert on Page", color = Color(0xFF00BCD4), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Jump to Page button (if viewing all pages and clip is on another page)
                                    if (selectedTab == 1 && clip.pageIndex != currentPageIndex) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    onDismiss()
                                                    onGoToPage(clip.pageIndex)
                                                }
                                                .padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Go to Page ${clip.pageIndex + 1}",
                                                color = Color(0xFF00BCD4),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                                contentDescription = null,
                                                tint = Color(0xFF00BCD4),
                                                modifier = Modifier.size(11.dp)
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
    }

    // Confirmation dialog before deleting an audio clip
    clipToDelete?.let { clip ->
        AlertDialog(
            onDismissRequest = { clipToDelete = null },
            title = { Text("Delete Recording?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${clip.title}\" from Page ${clip.pageIndex + 1}? This action cannot be undone.",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (currentlyPlayingId == clip.id) {
                            recorderManager.stopPlayback()
                        }
                        onDeleteClip(clip.id)
                        clipToDelete = null
                        Toast.makeText(context, "Recording deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { clipToDelete = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E1E22)
        )
    }
}
