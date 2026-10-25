package com.graphite.app.feature.workspace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.graphite.app.core.config.GraphiteConfig
import com.graphite.app.feature.workspace.model.InkStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

data class HandwritingRecognitionResult(
    val text: String,
    val bounds: RectF,
    val strokeCount: Int
)

object HandwritingRecognizerHelper {

    private val onDeviceRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Active production Gemini multimodal vision models in order of speed and capability
    private val geminiModels = listOf(
        "gemini-1.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash-8b"
    )

    /**
     * Converts a collection of handwritten ink strokes into recognized text.
     * 1. Primary: Advanced Google Gemini Multimodal Vision API (specialized in decoding extremely messy,
     *    rushed, connected cursive, doctor-like handwriting, and 100+ languages including Hindi, Bengali,
     *    Spanish, French, German, Chinese, Japanese, Arabic, Russian, math, and punctuation).
     * 2. Secondary fallback: On-device ML Kit OCR for offline environments.
     */
    suspend fun recognizeStrokes(strokes: List<InkStroke>): HandwritingRecognitionResult? = withContext(Dispatchers.Default) {
        if (strokes.isEmpty()) return@withContext null

        val allPoints = strokes.flatMap { it.points }
        if (allPoints.isEmpty()) return@withContext null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        for (pt in allPoints) {
            if (pt.x < minX) minX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.x > maxX) maxX = pt.x
            if (pt.y > maxY) maxY = pt.y
        }

        val rawWidth = maxX - minX
        val rawHeight = maxY - minY
        if (rawWidth < 4f && rawHeight < 4f) return@withContext null

        val bounds = RectF(minX, minY, maxX, maxY)

        // Scale strokes to optimal AI vision canvas (target height ~360px, width up to 1200px)
        // Optimal scaling ensures cursive loops remain open without generating oversized bitmaps.
        val targetHeight = 360f
        val targetWidth = 1200f
        val scaleX = targetWidth / rawWidth.coerceAtLeast(15f)
        val scaleY = targetHeight / rawHeight.coerceAtLeast(15f)
        val scale = min(scaleX, scaleY).coerceIn(1.0f, 3.5f)

        val pad = (20f * scale).coerceIn(16f, 36f)
        val renderW = ((rawWidth * scale) + pad * 2f).toInt().coerceIn(160, 1400)
        val renderH = ((rawHeight * scale) + pad * 2f).toInt().coerceIn(120, 900)

        val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        val strokePaint = Paint().apply {
            color = android.graphics.Color.BLACK
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }

        val dotPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue

            // Proportional stroke width relative to scaled canvas (prevents cursive loops from merging)
            val strokeW = (stroke.baseWidth * scale * 1.0f).coerceIn(2.5f, 8f)
            strokePaint.strokeWidth = strokeW

            // Handle single-point strokes (dots on 'i'/'j', periods '.', commas, short accents)
            if (stroke.points.size == 1) {
                val pt = stroke.points[0]
                val dotX = (pt.x - minX) * scale + pad
                val dotY = (pt.y - minY) * scale + pad
                canvas.drawCircle(dotX, dotY, strokeW * 0.9f, dotPaint)
                continue
            }

            val path = Path()
            val first = stroke.points[0]
            var prevX = (first.x - minX) * scale + pad
            var prevY = (first.y - minY) * scale + pad
            path.moveTo(prevX, prevY)

            for (i in 1 until stroke.points.size) {
                val pt = stroke.points[i]
                val curX = (pt.x - minX) * scale + pad
                val curY = (pt.y - minY) * scale + pad
                // Smooth quadratic Bézier interpolation preserves natural cursive handwriting ligatures
                val midX = (prevX + curX) / 2f
                val midY = (prevY + curY) / 2f
                path.quadTo(prevX, prevY, midX, midY)
                prevX = curX
                prevY = curY
            }
            path.lineTo(prevX, prevY)
            canvas.drawPath(path, strokePaint)
        }

        // 1. Try Gemini Multimodal Vision API across model hierarchy
        val geminiKey = GraphiteConfig.geminiApiKey
        var recognizedText = ""
        if (geminiKey.isNotBlank()) {
            for (model in geminiModels) {
                try {
                    val candidate = recognizeWithGeminiVision(bitmap, geminiKey, model).trim()
                    if (candidate.isNotBlank()) {
                        recognizedText = candidate
                        break
                    }
                } catch (_: Exception) {
                    // Try next model candidate in case of temporary rate limit or 503 spike
                }
            }
        }

        // 2. Secondary fallback: Groq Vision API (llama-3.2-11b-vision-preview)
        if (recognizedText.isBlank()) {
            val groqKey = GraphiteConfig.groqApiKey
            if (groqKey.isNotBlank()) {
                try {
                    val candidate = recognizeWithGroqVision(bitmap, groqKey).trim()
                    if (candidate.isNotBlank()) {
                        recognizedText = candidate
                    }
                } catch (_: Exception) {
                    // Fall back to on-device recognizer
                }
            }
        }

        // 3. Tertiary fallback: On-device ML Kit OCR if completely offline
        if (recognizedText.isBlank()) {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            recognizedText = suspendCancellableCoroutine { continuation ->
                onDeviceRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        continuation.resume(visionText.text.trim())
                    }
                    .addOnFailureListener {
                        continuation.resume("")
                    }
            }
        }

        bitmap.recycle()

        // Clean up markdown fences or extraneous quotation marks if present
        var cleaned = recognizedText.trim()
        if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
            val lines = cleaned.lines()
            if (lines.size >= 2) {
                cleaned = lines.subList(1, lines.size - 1).joinToString("\n").trim()
            }
        }
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length > 2) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }

        if (cleaned.isNotBlank()) {
            HandwritingRecognitionResult(
                text = cleaned,
                bounds = bounds,
                strokeCount = strokes.size
            )
        } else {
            null
        }
    }

    private fun recognizeWithGeminiVision(bitmap: Bitmap, apiKey: String, modelName: String): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val jpegBytes = stream.toByteArray()
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val isOAuthToken = apiKey.startsWith("ya29.")
        val endpoint = if (isOAuthToken) {
            "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
        } else {
            "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
        }

        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (isOAuthToken) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            } else {
                setRequestProperty("x-goog-api-key", apiKey)
            }
        }

        val prompt = """
        You are an elite, world-class handwriting recognition expert with superhuman capability to decipher the messiest, most rushed, illegible, cursive, and doctor-like handwriting with 100% precision.

        CRITICAL TRANSCRIPTION INSTRUCTIONS:
        1. Transcribe EVERY single word, letter, and character present in this handwritten image. Do NOT omit, skip, or truncate any words or letters.
        2. Decipher rushed cursive ligatures, loops, slants, and connected strokes using language context, grammar, and vocabulary. If a cursive letter is ambiguous, deduce the exact intended word.
        3. If there are multiple lines of handwriting, preserve the line breaks with newline (\n) characters.
        4. Accurately transcribe punctuation (periods, commas, apostrophes, question marks, dashes), numbers, and mathematical symbols.
        5. Support all languages and scripts (English, Hindi, Bengali, Spanish, French, German, Chinese, Japanese, Arabic, Russian, and all other languages).
        6. Output STRICTLY AND ONLY the transcribed text. Do NOT add any preamble, conversational commentary, explanations, quotes, or markdown code fences.
        """.trimIndent()

        val payload = JSONObject().apply {
            val contents = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                        put(JSONObject().put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Image)
                        }))
                    }
                    put("parts", parts)
                }
                put(contentObj)
            }
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("topP", 0.95)
            })
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
            val textBuilder = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i)
                    val t = part?.optString("text") ?: ""
                    textBuilder.append(t)
                }
            }
            return textBuilder.toString().trim()
        } else {
            val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            android.util.Log.e("HandwritingRecognizer", "Gemini API error ($responseCode) on $modelName: $errorBody")
        }
        return ""
    }

    private fun recognizeWithGroqVision(bitmap: Bitmap, apiKey: String): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val jpegBytes = stream.toByteArray()
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val url = URL("https://api.groq.com/openai/v1/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val prompt = "Transcribe EVERY word, character, and line of handwritten text in this image with exact precision. Support cursive handwriting, punctuation, and multiple languages. Output STRICTLY AND ONLY the transcribed text without quotes, markdown code fences, or commentary."

        val payload = JSONObject().apply {
            put("model", "llama-3.2-11b-vision-preview")
            val messages = JSONArray().apply {
                val userMsg = JSONObject().apply {
                    put("role", "user")
                    val contentArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    }
                    put("content", contentArr)
                }
                put(userMsg)
            }
            put("messages", messages)
            put("temperature", 0.1)
        }

        OutputStreamWriter(conn.outputStream).use { writer ->
            writer.write(payload.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode == 200) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonResponse = JSONObject(responseText)
            val choices = jsonResponse.optJSONArray("choices")
            val firstChoice = choices?.optJSONObject(0)
            val message = firstChoice?.optJSONObject("message")
            return message?.optString("content")?.trim() ?: ""
        } else {
            val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            android.util.Log.e("HandwritingRecognizer", "Groq API error ($responseCode): $errorBody")
        }
        return ""
    }
}
