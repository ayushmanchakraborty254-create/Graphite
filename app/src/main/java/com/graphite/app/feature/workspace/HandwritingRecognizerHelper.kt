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

data class HandwritingRecognitionResult(
    val text: String,
    val bounds: RectF,
    val strokeCount: Int
)

object HandwritingRecognizerHelper {

    private val onDeviceRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Converts a collection of handwritten ink strokes into recognized text.
     * 1. Primary: Google Gemini 1.5 Flash Multimodal Vision API (handles cursive, normal writing,
     *    sketches, mathematical notation, and 100+ languages including Hindi, Bengali, Spanish, French, etc.)
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

        // Render ink strokes to a high-contrast bitmap with antialiased Bézier curves
        val pad = 48f
        val renderW = max(120, (rawWidth + pad * 2).toInt().coerceAtMost(2400))
        val renderH = max(120, (rawHeight + pad * 2).toInt().coerceAtMost(2400))

        val bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        val paint = Paint().apply {
            color = android.graphics.Color.BLACK
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }

        for (stroke in strokes) {
            if (stroke.points.size < 2) continue
            paint.strokeWidth = max(5f, stroke.baseWidth * 1.8f)
            val path = Path()
            val first = stroke.points[0]
            var prevX = first.x - minX + pad
            var prevY = first.y - minY + pad
            path.moveTo(prevX, prevY)

            for (i in 1 until stroke.points.size) {
                val pt = stroke.points[i]
                val curX = pt.x - minX + pad
                val curY = pt.y - minY + pad
                // Smooth quadratic Bézier interpolation between stroke points
                val midX = (prevX + curX) / 2f
                val midY = (prevY + curY) / 2f
                path.quadTo(prevX, prevY, midX, midY)
                prevX = curX
                prevY = curY
            }
            path.lineTo(prevX, prevY)
            canvas.drawPath(path, paint)
        }

        // 1. Try Gemini Vision API first (superior cursive, multilingual, and handwriting recognition)
        val geminiKey = GraphiteConfig.geminiApiKey
        var recognizedText = ""
        if (geminiKey.isNotBlank()) {
            try {
                recognizedText = recognizeWithGeminiVision(bitmap, geminiKey).trim()
            } catch (_: Exception) {
                // Network or quota error: will fallback to on-device ML Kit
            }
        }

        // 2. Fallback to on-device ML Kit if Gemini is unavailable or failed
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

        if (recognizedText.isNotBlank()) {
            HandwritingRecognitionResult(
                text = recognizedText,
                bounds = bounds,
                strokeCount = strokes.size
            )
        } else {
            null
        }
    }

    private fun recognizeWithGeminiVision(bitmap: Bitmap, apiKey: String): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        val jpegBytes = stream.toByteArray()
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val isOAuthToken = apiKey.startsWith("AQ.") || apiKey.startsWith("ya29.")
        val endpoint = if (isOAuthToken) {
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        } else {
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
        }

        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (isOAuthToken) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            setRequestProperty("x-goog-api-key", apiKey)
        }

        val prompt = "Transcribe the handwritten text shown in this image with maximum accuracy. The handwriting may be cursive, print, normal, or shorthand, and may be in ANY language (such as English, Hindi, Bengali, Spanish, French, German, Chinese, Japanese, Arabic, Russian, etc.). Output strictly and ONLY the recognized text. Do not add any conversational text, explanations, or quotes."

        val payload = JSONObject().apply {
            val contents = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                        put(JSONObject().put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Image)
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
            val textBuilder = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i)
                    val t = part?.optString("text") ?: ""
                    textBuilder.append(t)
                }
            }
            return textBuilder.toString().trim()
        }
        return ""
    }
}
