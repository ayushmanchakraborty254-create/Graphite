package com.graphite.app.core.voice

import android.content.Context
import android.util.Base64
import com.graphite.app.core.config.GraphiteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

enum class TranscriptionMode {
    VERBATIM,           // Exact word-for-word speech to text
    BULLET_POINTS,      // AI structured summary with bullet points & action items
    TRANSLATE_TO_ENGLISH // Multilingual translation from any spoken language into English notes
}

data class TranscriptionResult(
    val text: String,
    val providerUsed: String, // "Gemini 1.5 Flash", "Groq Whisper + LLaMA", "Android Native"
    val isSummary: Boolean
)

object VoiceTranscriptionManager {

    /**
     * Transcribes an audio file (.m4a, .mp3, .wav) with automatic failover:
     * 1. Primary: Google Gemini 1.5 Flash (transcribes + auto-formats into bullet points / translates)
     * 2. Secondary: Groq Whisper Large v3 Turbo + Groq LLaMA 3.3 70B (if Gemini hits 429 quota or fails)
     */
    suspend fun transcribeAudio(
        audioFile: File,
        mode: TranscriptionMode = TranscriptionMode.BULLET_POINTS
    ): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("Audio file is empty or does not exist"))
        }

        var lastError: Exception? = null

        // 1. Try Primary: Google Gemini 1.5 Flash
        val geminiKey = GraphiteConfig.geminiApiKey
        if (geminiKey.isNotBlank()) {
            try {
                val geminiRes = callGeminiAudioApi(audioFile, mode, geminiKey)
                if (geminiRes.isSuccess) {
                    return@withContext geminiRes
                } else {
                    lastError = geminiRes.exceptionOrNull() as? Exception
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        // 2. Try Secondary Failover: Groq API (Whisper + LLaMA 3.3)
        val groqKey = GraphiteConfig.groqApiKey
        if (groqKey.isNotBlank()) {
            try {
                val groqRes = callGroqTranscriptionApi(audioFile, mode, groqKey)
                if (groqRes.isSuccess) {
                    return@withContext groqRes
                } else {
                    lastError = groqRes.exceptionOrNull() as? Exception
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: IllegalStateException("No transcription service available or both APIs exhausted"))
    }

    /**
     * Capitalizes the first letter of every sentence and bullet point.
     */
    fun capitalizeSentences(input: String): String {
        if (input.isBlank()) return input
        val regex = Regex("""(^|[.!?]\s+|\n\s*[-*•]?\s*)([a-z])""")
        return regex.replace(input) { matchResult ->
            val prefix = matchResult.groupValues[1]
            val letter = matchResult.groupValues[2]
            prefix + letter.uppercase(java.util.Locale.getDefault())
        }
    }

    private fun callGeminiAudioApi(
        audioFile: File,
        mode: TranscriptionMode,
        apiKey: String
    ): Result<TranscriptionResult> {
        val isOAuthToken = apiKey.startsWith("ya29.")
        val endpoint = if (isOAuthToken) {
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        } else {
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        }
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (isOAuthToken) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            } else {
                setRequestProperty("x-goog-api-key", apiKey)
            }
        }

        val promptText = when (mode) {
            TranscriptionMode.VERBATIM ->
                "Transcribe this audio recording accurately word-for-word in its original spoken language with proper punctuation. The speaker may be speaking rapidly or fast; capture every word and syllable accurately without omitting anything. Capitalize the first letter of every sentence. Return only the transcript."
            TranscriptionMode.BULLET_POINTS ->
                "Transcribe this audio (the speaker may be speaking fast) and convert it into clean, well-structured notes with clear bullet points, key takeaways, and action items. Capitalize the first letter of each sentence and bullet point. Make it concise and readable."
            TranscriptionMode.TRANSLATE_TO_ENGLISH ->
                "Listen to this audio (which may be rapidly spoken in any language e.g. Hindi, Bengali, Spanish, French, etc.) and translate it directly into fluent, well-structured English notes formatted in clean bullet points with proper sentence capitalization."
        }

        val audioBytes = audioFile.readBytes()
        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val mimeType = when {
            audioFile.name.endsWith(".m4a", true) -> "audio/mp4"
            audioFile.name.endsWith(".mp3", true) -> "audio/mp3"
            audioFile.name.endsWith(".wav", true) -> "audio/wav"
            else -> "audio/mp4"
        }

        val payload = JSONObject().apply {
            val contents = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().put("text", promptText))
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", mimeType)
                            put("data", base64Audio)
                        }))
                    }
                    put("parts", parts)
                }
                put(contentObj)
            }
            put("contents", contents)
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode == 200) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonResponse = JSONObject(responseText)
            val candidates = jsonResponse.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val text = parts?.optJSONObject(0)?.optString("text") ?: ""
            if (text.isNotBlank()) {
                val cleanedText = capitalizeSentences(text.trim())
                return Result.success(
                    TranscriptionResult(
                        text = cleanedText,
                        providerUsed = "Gemini 1.5 Flash",
                        isSummary = mode != TranscriptionMode.VERBATIM
                    )
                )
            }
        }

        val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
        return Result.failure(IOException("Gemini API error ($responseCode): $errorText"))
    }

    private fun callGroqTranscriptionApi(
        audioFile: File,
        mode: TranscriptionMode,
        apiKey: String
    ): Result<TranscriptionResult> {
        val boundary = "Boundary${System.currentTimeMillis()}"
        val url = URL("https://api.groq.com/openai/v1/audio/transcriptions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        val mimeType = when {
            audioFile.name.endsWith(".m4a", true) -> "audio/m4a"
            audioFile.name.endsWith(".mp3", true) -> "audio/mp3"
            audioFile.name.endsWith(".wav", true) -> "audio/wav"
            else -> "audio/m4a"
        }

        conn.outputStream.use { os ->
            val writer = BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8))

            // Model field: whisper-large-v3-turbo
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
            writer.write("whisper-large-v3-turbo\r\n")

            // Response format
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n")
            writer.write("json\r\n")

            // Prompt field: Guides Whisper to recognize rapidly spoken fast speech and capitalize sentences
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n")
            writer.write("Accurately transcribe rapidly spoken, fast continuous speech with proper sentence capitalization and punctuation.\r\n")

            // File field
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\n")
            writer.write("Content-Type: $mimeType\r\n\r\n")
            writer.flush()

            FileInputStream(audioFile).use { input ->
                input.copyTo(os)
            }
            os.flush()

            writer.write("\r\n--$boundary--\r\n")
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode == 200) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonResponse = JSONObject(responseText)
            val rawTranscript = jsonResponse.optString("text", "").trim()

            if (mode == TranscriptionMode.VERBATIM || rawTranscript.isBlank()) {
                val cleanedText = capitalizeSentences(rawTranscript)
                return Result.success(
                    TranscriptionResult(
                        text = cleanedText,
                        providerUsed = "Groq Whisper Large v3",
                        isSummary = false
                    )
                )
            }

            // Post-process with Groq LLaMA 3.3 70B for bullet points & translation
            val formatted = callGroqLlamaFormatting(rawTranscript, mode, apiKey)
            val cleanedFormatted = capitalizeSentences(formatted.ifBlank { rawTranscript })
            return Result.success(
                TranscriptionResult(
                    text = cleanedFormatted,
                    providerUsed = "Groq Whisper + LLaMA 3.3",
                    isSummary = true
                )
            )
        }

        val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
        return Result.failure(IOException("Groq API error ($responseCode): $err"))
    }

    fun formatOrTranslateText(
        rawTranscript: String,
        mode: TranscriptionMode
    ): String {
        val groqKey = GraphiteConfig.groqApiKey
        if (groqKey.isNotBlank()) {
            val res = callGroqLlamaFormatting(rawTranscript, mode, groqKey)
            if (res.isNotBlank()) return capitalizeSentences(res)
        }
        return capitalizeSentences(rawTranscript)
    }

    private fun callGroqLlamaFormatting(
        rawTranscript: String,
        mode: TranscriptionMode,
        apiKey: String
    ): String {
        return try {
            val url = URL("https://api.groq.com/openai/v1/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }

            val instruction = if (mode == TranscriptionMode.TRANSLATE_TO_ENGLISH) {
                "Translate the following transcript into fluent English notes formatted as concise bullet points with key highlights."
            } else {
                "Format the following transcript into clean, organized notes with concise bullet points and action items."
            }

            val payload = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are an expert note summarizer. Keep bullet points crisp and actionable.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "$instruction\n\nTranscript:\n$rawTranscript")
                    })
                }
                put("messages", messages)
                put("temperature", 0.3)
            }

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val choices = JSONObject(resp).optJSONArray("choices")
                choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: rawTranscript
            } else rawTranscript
        } catch (_: Exception) {
            rawTranscript
        }
    }
}
