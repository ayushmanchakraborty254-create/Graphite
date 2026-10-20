package com.graphite.app.feature.workspace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.voice.TranscriptionMode
import com.graphite.app.core.voice.VoiceClip
import com.graphite.app.core.voice.VoiceTranscriptionManager
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun VoiceNoteDialog(
    notebookId: String,
    pageIndex: Int = 0,
    initialRecordAudioOnly: Boolean = false,
    onDismiss: () -> Unit,
    onSaveVoiceClip: ((VoiceClip) -> Unit)? = null,
    onTextTranscribed: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorderManager = remember { GraphiteApplication.instance.voiceRecorderManager }

    val isRecording by recorderManager.isRecording.collectAsState()
    val durationSec by recorderManager.recordingDurationSec.collectAsState()

    var isAudioNoteMode by remember { mutableStateOf(initialRecordAudioOnly) }
    var selectedMode by remember { mutableStateOf(TranscriptionMode.BULLET_POINTS) }
    var shouldTranscribeToText by remember(isAudioNoteMode) { mutableStateOf(!isAudioNoteMode) }
    var shouldSaveVoiceClip by remember(isAudioNoteMode) { mutableStateOf(isAudioNoteMode) }

    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var lastRecordedClip by remember { mutableStateOf<VoiceClip?>(null) }
    var liveSpeechText by remember { mutableStateOf("") }

    val speechRecognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            try {
                SpeechRecognizer.createSpeechRecognizer(context)
            } catch (_: Exception) { null }
        } else null
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
        }
    }

    fun startListeningEngine() {
        try {
            val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra("android.speech.extra.DICTATION_MODE", true)
            }
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {}
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = matches?.firstOrNull { it.isNotBlank() }
                    if (!best.isNullOrBlank()) {
                        liveSpeechText = VoiceTranscriptionManager.capitalizeSentences(best.trim())
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull { it.isNotBlank() }
                    if (!partial.isNullOrBlank()) {
                        liveSpeechText = VoiceTranscriptionManager.capitalizeSentences(partial.trim())
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer?.startListening(recognizerIntent)
        } catch (_: Exception) {}
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            recorderManager.startRecording(notebookId)
            startListeningEngine()
        } else {
            Toast.makeText(context, "Microphone permission is required. Please enable it in Settings.", Toast.LENGTH_LONG).show()
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    Dialog(onDismissRequest = {
        if (isRecording) {
            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
            recorderManager.stopRecording()
        }
        onDismiss()
    }) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Voice Note & AI Transcription",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        if (isRecording) {
                            try { speechRecognizer?.stopListening() } catch (_: Exception) {}
                            recorderManager.stopRecording()
                        }
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Segmented Mode Selector: Voice-to-Text vs Audio Note
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF28282C))
                        .padding(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!isAudioNoteMode) GraphiteOrange else Color.Transparent)
                            .clickable {
                                isAudioNoteMode = false
                                shouldTranscribeToText = true
                                shouldSaveVoiceClip = false
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Default.TextFields, contentDescription = null, tint = if (!isAudioNoteMode) Color.White else Color.Gray, modifier = Modifier.size(16.dp))
                            Text("Voice to Text", color = if (!isAudioNoteMode) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isAudioNoteMode) Color(0xFF00BCD4) else Color.Transparent)
                            .clickable {
                                isAudioNoteMode = true
                                shouldSaveVoiceClip = true
                                shouldTranscribeToText = false
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = if (isAudioNoteMode) Color.Black else Color.Gray, modifier = Modifier.size(16.dp))
                            Text("Audio Note", color = if (isAudioNoteMode) Color.Black else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (!isAudioNoteMode) "Dictates directly into text on the page. Raw audio is not saved."
                           else "Saves an audio memo to the page recordings manager.",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Microphone pulse indicator & timer
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRecording) Color(0xFFFF5252).copy(alpha = 0.2f)
                            else GraphiteOrange.copy(alpha = 0.15f)
                        )
                        .clickable(enabled = !isProcessing) {
                            if (isRecording) {
                                try { speechRecognizer?.stopListening() } catch (_: Exception) {}
                                val clip = recorderManager.stopRecording()
                                lastRecordedClip = clip
                            } else {
                                val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                if (perm == PackageManager.PERMISSION_GRANTED) {
                                    recorderManager.startRecording(notebookId)
                                    startListeningEngine()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                        tint = if (isRecording) Color(0xFFFF5252) else GraphiteOrange,
                        modifier = Modifier.size(42.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Timer text
                val mins = durationSec / 60
                val secs = durationSec % 60
                Text(
                    text = if (isRecording) String.format("%02d:%02d Recording...", mins, secs)
                           else if (lastRecordedClip != null) "Recorded: ${lastRecordedClip?.durationSeconds}s"
                           else "Tap to Start Recording",
                    color = if (isRecording) Color(0xFFFF5252) else Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Options Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF282828))
                        .padding(12.dp)
                ) {
                    if (!isAudioNoteMode) {
                        // Voice to Text Options
                        Text("Transcription Format:", color = Color.Gray, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TranscriptionModeChip(
                                label = "Bullet Points",
                                isSelected = selectedMode == TranscriptionMode.BULLET_POINTS,
                                onClick = { selectedMode = TranscriptionMode.BULLET_POINTS },
                                modifier = Modifier.weight(1f)
                            )
                            TranscriptionModeChip(
                                label = "Verbatim",
                                isSelected = selectedMode == TranscriptionMode.VERBATIM,
                                onClick = { selectedMode = TranscriptionMode.VERBATIM },
                                modifier = Modifier.weight(1f)
                            )
                            TranscriptionModeChip(
                                label = "Translate",
                                isSelected = selectedMode == TranscriptionMode.TRANSLATE_TO_ENGLISH,
                                onClick = { selectedMode = TranscriptionMode.TRANSLATE_TO_ENGLISH },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { shouldSaveVoiceClip = !shouldSaveVoiceClip },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = shouldSaveVoiceClip,
                                onCheckedChange = { shouldSaveVoiceClip = it },
                                colors = CheckboxDefaults.colors(checkedColor = GraphiteOrange)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Also keep audio file in page recordings", color = Color.LightGray, fontSize = 12.sp)
                        }
                    } else {
                        // Audio Note Options
                        Text("Audio Memo Settings:", color = Color.Gray, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { shouldTranscribeToText = !shouldTranscribeToText },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = shouldTranscribeToText,
                                onCheckedChange = { shouldTranscribeToText = it },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00BCD4))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Also transcribe speech to text", color = Color.LightGray, fontSize = 12.sp)
                        }
                    }
                }

                // Live Speech Recognition preview
                if (liveSpeechText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF26272C)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Live Speech Detected:",
                                color = Color(0xFF00BCD4),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = liveSpeechText,
                                color = Color.White,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                if (isProcessing) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(color = GraphiteOrange, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(statusMessage ?: "AI Transcribing...", color = Color.LightGray, fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isRecording) {
                                try { speechRecognizer?.stopListening() } catch (_: Exception) {}
                                recorderManager.stopRecording()
                            }
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val clip = if (isRecording) {
                                try { speechRecognizer?.stopListening() } catch (_: Exception) {}
                                recorderManager.stopRecording(pageIndex)
                            } else {
                                lastRecordedClip?.copy(pageIndex = pageIndex)
                            }

                            if (clip == null && liveSpeechText.isBlank()) {
                                Toast.makeText(context, "Please record audio first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            isProcessing = true
                            statusMessage = "Processing voice note..."

                            scope.launch {
                                try {
                                    var finalText = ""
                                    if (shouldTranscribeToText) {
                                        statusMessage = "Transcribing with Gemini / Groq..."
                                        val res = if (clip != null) {
                                            VoiceTranscriptionManager.transcribeAudio(File(clip.filePath), selectedMode)
                                        } else null

                                        if (res != null && res.isSuccess) {
                                            val tRes = res.getOrThrow()
                                            finalText = tRes.text
                                            statusMessage = "Transcribed using ${tRes.providerUsed}"
                                        } else if (liveSpeechText.isNotBlank()) {
                                            statusMessage = "Formatting speech..."
                                            finalText = VoiceTranscriptionManager.formatOrTranslateText(liveSpeechText, selectedMode)
                                            if (finalText.isBlank()) finalText = liveSpeechText
                                            statusMessage = "Transcribed with On-Device Speech"
                                        } else {
                                            statusMessage = "Audio saved"
                                        }
                                    }

                                    val finalClip = if (clip != null) {
                                        if (finalText.isNotBlank()) clip.copy(transcription = finalText) else clip
                                    } else null

                                    if (shouldSaveVoiceClip && finalClip != null) {
                                        onSaveVoiceClip?.invoke(finalClip)
                                    } else if (!shouldSaveVoiceClip && clip != null) {
                                        try { File(clip.filePath).delete() } catch (_: Exception) {}
                                    }
                                    if (finalText.isNotBlank()) {
                                        val capText = VoiceTranscriptionManager.capitalizeSentences(finalText)
                                        onTextTranscribed?.invoke(capText)
                                    }

                                    Toast.makeText(context, "Voice note added successfully!", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                        enabled = !isProcessing
                    ) {
                        Text(
                            text = if (isRecording) "Stop & Apply" else "Save & Apply",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptionModeChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) GraphiteOrange else Color(0xFF383838))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else Color.LightGray,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}
