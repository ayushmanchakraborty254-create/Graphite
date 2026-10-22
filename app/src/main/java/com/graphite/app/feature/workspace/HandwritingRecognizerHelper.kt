package com.graphite.app.feature.workspace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max

data class HandwritingRecognitionResult(
    val text: String,
    val bounds: RectF,
    val strokeCount: Int
)

object HandwritingRecognizerHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Converts a collection of ink strokes into recognized text using Google ML Kit.
     * Renders strokes onto a normalized high-contrast bitmap and runs on-device recognition.
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

        // Render ink strokes to a high-contrast bitmap with white background and black ink
        val pad = 40f
        val renderW = max(100, (rawWidth + pad * 2).toInt().coerceAtMost(2400))
        val renderH = max(100, (rawHeight + pad * 2).toInt().coerceAtMost(2400))

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
            paint.strokeWidth = max(4f, stroke.baseWidth * 1.5f)
            val path = android.graphics.Path()
            val first = stroke.points[0]
            path.moveTo(first.x - minX + pad, first.y - minY + pad)
            for (i in 1 until stroke.points.size) {
                val pt = stroke.points[i]
                path.lineTo(pt.x - minX + pad, pt.y - minY + pad)
            }
            canvas.drawPath(path, paint)
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val recognizedText = suspendCancellableCoroutine<String> { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text.trim()
                    continuation.resume(text)
                }
                .addOnFailureListener {
                    continuation.resume("")
                }
        }

        bitmap.recycle()

        if (recognizedText.isNotEmpty()) {
            HandwritingRecognitionResult(
                text = recognizedText,
                bounds = bounds,
                strokeCount = strokes.size
            )
        } else {
            null
        }
    }
}
