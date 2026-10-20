package com.graphite.app.feature.workspace

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.voice.TranscriptionMode
import com.graphite.app.core.voice.VoiceClip
import com.graphite.app.core.voice.VoiceTranscriptionManager
import com.graphite.app.data.repository.GraphiteRepository
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceNotebookScreen(
    notebookId: String,
    onNavigateBack: () -> Unit
) {
    val notebook = remember(notebookId) { GraphiteRepository.getNotebook(notebookId) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorderManager = remember { GraphiteApplication.instance.voiceRecorderManager }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val f = recorderManager.startRecording(notebookId)
            if (f == null) {
                Toast.makeText(context, "Could not initialize audio recorder", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Microphone permission is required to record voice notes", Toast.LENGTH_SHORT).show()
        }
    }
    val clipboardManager = LocalClipboardManager.current

    val isRecording by recorderManager.isRecording.collectAsState()
    val recordingDuration by recorderManager.recordingDurationSec.collectAsState()
    val currentlyPlayingId by recorderManager.currentlyPlayingId.collectAsState()
    val playbackProgress by recorderManager.playbackProgress.collectAsState()

    var clips by remember { mutableStateOf(notebook?.voiceNotes ?: emptyList()) }
    var transcribingClipId by remember { mutableStateOf<String?>(null) }
    var transcriptionResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showTranscriptionModeDialogForClip by remember { mutableStateOf<VoiceClip?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                if (recorderManager.isRecording.value) {
                    val clip = recorderManager.stopRecording()
                    if (clip != null) {
                        GraphiteRepository.addVoiceClipToNotebook(notebookId, clip)
                    }
                }
                recorderManager.stopPlayback()
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(notebook?.title ?: "Voice Notebook", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("${clips.size} recordings", color = Color.Gray, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161618))
            )
        },
        containerColor = GraphiteBlack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Big Recording Studio Card at the top
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .shadow(8.dp, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1D21)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (isRecording) GraphiteOrange else Color(0xFF2E2F36))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRecording) "Recording Audio..." else "Tap to Record Voice Note",
                        color = if (isRecording) GraphiteOrange else Color.LightGray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    val minutes = recordingDuration / 60
                    val seconds = recordingDuration % 60
                    Text(
                        text = String.format("%02d:%02d", minutes, seconds),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Big Round Record / Stop Button
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(if (isRecording) Color(0xFFFF5252) else GraphiteOrange)
                            .clickable {
                                if (isRecording) {
                                    val newClip = recorderManager.stopRecording()
                                    if (newClip != null) {
                                        clips = listOf(newClip) + clips
                                        GraphiteRepository.addVoiceClipToNotebook(notebookId, newClip)
                                        Toast.makeText(context, "Voice note saved!", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                    if (perm == PackageManager.PERMISSION_GRANTED) {
                                        val f = recorderManager.startRecording(notebookId)
                                        if (f == null) {
                                            Toast.makeText(context, "Could not initialize audio recorder", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isRecording) "Stop" else "Record",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Clips list
            if (clips.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MicNone, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(54.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No recordings yet", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(clips) { clip ->
                        val isPlaying = currentlyPlayingId == clip.id
                        val transcription = transcriptionResults[clip.id] ?: clip.transcription

                        Card(
                            modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(12.dp)),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F24)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF2C2D33))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(if (isPlaying) GraphiteOrange else Color(0xFF2A2B31))
                                                .clickable { recorderManager.playClip(clip) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = if (isPlaying) "Pause" else "Play",
                                                tint = Color.White,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(clip.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            Text("${clip.durationSeconds}s • ${clip.createdAtFormatted}", color = Color.Gray, fontSize = 11.sp)
                                        }
                                    }

                                    // Action buttons: Transcribe
                                    Row {
                                        IconButton(onClick = {
                                            showTranscriptionModeDialogForClip = clip
                                        }) {
                                            if (transcribingClipId == clip.id) {
                                                CircularProgressIndicator(color = Color(0xFF00BCD4), modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(Icons.Default.Translate, contentDescription = "Transcribe", tint = Color(0xFF00BCD4), modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                }

                                if (isPlaying) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { playbackProgress },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = GraphiteOrange,
                                        trackColor = Color(0xFF333333)
                                    )
                                }

                                // Transcription view
                                if (!transcription.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141518)),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Converted Notes (AI):", color = Color(0xFF00BCD4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                IconButton(
                                                    onClick = {
                                                        clipboardManager.setText(AnnotatedString(transcription))
                                                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(22.dp)
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.LightGray, modifier = Modifier.size(13.dp))
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(transcription, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp)
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

    // Transcription Mode Dialog (Exact vs Smart Bullet Points vs Translation)
    showTranscriptionModeDialogForClip?.let { targetClip ->
        AlertDialog(
            onDismissRequest = { showTranscriptionModeDialogForClip = null },
            title = { Text("Speech-to-Text Conversion", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Select transcription format (using Gemini 1.5 Flash with Groq fallback):", color = Color.LightGray, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                    listOf(
                        Triple(TranscriptionMode.BULLET_POINTS, "Smart Bullet Points (AI)", "Formats speech into clean notes, bullet points and action items"),
                        Triple(TranscriptionMode.VERBATIM, "Verbatim Transcript", "Exact word-for-word transcript in spoken language"),
                        Triple(TranscriptionMode.TRANSLATE_TO_ENGLISH, "Multilingual Translate to English", "Translates speech in any language directly to English notes")
                    ).forEach { (mode, label, desc) ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    showTranscriptionModeDialogForClip = null
                                    transcribingClipId = targetClip.id
                                    scope.launch {
                                        val res = VoiceTranscriptionManager.transcribeAudio(File(targetClip.filePath), mode)
                                        transcribingClipId = null
                                        if (res.isSuccess) {
                                            val text = res.getOrNull()?.text ?: ""
                                            transcriptionResults = transcriptionResults + (targetClip.id to text)
                                            Toast.makeText(context, "Transcribed via ${res.getOrNull()?.providerUsed}!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Transcription error: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF26272D)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(label, color = Color(0xFF00BCD4), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(desc, color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTranscriptionModeDialogForClip = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E1F24)
        )
    }
}
