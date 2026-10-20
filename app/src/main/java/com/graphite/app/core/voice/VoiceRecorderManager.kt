package com.graphite.app.core.voice

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class VoiceClip(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String,
    val title: String,
    val durationSeconds: Int,
    val createdAtFormatted: String,
    val transcription: String? = null,
    val pageIndex: Int = 0
)

class VoiceRecorderManager(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var currentRecordingFile: File? = null
    private var recordStartTime: Long = 0L

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

    private val _currentlyPlayingId = MutableStateFlow<String?>(null)
    val currentlyPlayingId: StateFlow<String?> = _currentlyPlayingId.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private var durationJob: Job? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    fun startRecording(notebookId: String? = null): File? {
        stopRecording()
        val dir = File(context.filesDir, "voice_notes").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val prefix = if (notebookId != null) "note_${notebookId}_" else "voice_"
        val file = File(dir, "${prefix}${timeStamp}.m4a")
        currentRecordingFile = file

        return try {
            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            newRecorder.apply {
                try {
                    // VOICE_RECOGNITION activates Android hardware DSP noise suppression,
                    // acoustic echo cancellation, and voice-band filtering
                    setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                } catch (_: Exception) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1) // Mono eliminates ambient stereo phase noise & background room hiss
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = newRecorder
            _isRecording.value = true
            recordStartTime = System.currentTimeMillis()
            _recordingDurationSec.value = 0

            durationJob?.cancel()
            durationJob = scope.launch {
                while (_isRecording.value) {
                    delay(1000)
                    _recordingDurationSec.value = ((System.currentTimeMillis() - recordStartTime) / 1000).toInt()
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            _isRecording.value = false
            null
        }
    }

    fun stopRecording(pageIndex: Int = 0): VoiceClip? {
        durationJob?.cancel()
        val file = currentRecordingFile
        val duration = _recordingDurationSec.value
        try {
            recorder?.stop()
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        _isRecording.value = false
        _recordingDurationSec.value = 0

        if (file != null && file.exists() && file.length() > 0) {
            val formatter = SimpleDateFormat("MMM d, hh:mm a", Locale.getDefault())
            return VoiceClip(
                filePath = file.absolutePath,
                title = "Voice Note ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())}",
                durationSeconds = maxOf(1, duration),
                createdAtFormatted = formatter.format(Date()),
                pageIndex = pageIndex
            )
        }
        return null
    }

    fun playClip(clip: VoiceClip) {
        if (_currentlyPlayingId.value == clip.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        val file = File(clip.filePath)
        if (!file.exists()) return

        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopPlayback()
                }
                start()
            }
            player = mp
            _currentlyPlayingId.value = clip.id

            playbackJob?.cancel()
            playbackJob = scope.launch {
                val total = maxOf(1, mp.duration)
                while (_currentlyPlayingId.value == clip.id) {
                    if (mp.isPlaying) {
                        _playbackProgress.value = mp.currentPosition.toFloat() / total.toFloat()
                    }
                    delay(100)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopPlayback()
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {}
        player = null
        _currentlyPlayingId.value = null
        _playbackProgress.value = 0f
    }
}
