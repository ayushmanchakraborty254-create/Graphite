package com.graphite.app.core.supabase

import androidx.compose.ui.graphics.Color
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkStroke
import com.graphite.app.feature.workspace.model.InkToolType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SerializedInkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 0.5f,
    val timestamp: Long = 0L
)

@Serializable
data class SerializedInkStroke(
    val id: String,
    val points: List<SerializedInkPoint>,
    val baseWidth: Float,
    val colorArgb: Long,
    val toolType: String,
    val pageIndex: Int = 0,
    val isCanonical: Boolean = false
)

object InkSerialization {
    private val json = Json { ignoreUnknownKeys = true }

    fun serializeStrokes(strokes: List<InkStroke>): String {
        val serialized = strokes.map { s ->
            SerializedInkStroke(
                id = s.id,
                points = s.points.map { p ->
                    SerializedInkPoint(p.x, p.y, p.pressure, p.timestamp)
                },
                baseWidth = s.baseWidth,
                colorArgb = s.color.value.toLong(),
                toolType = s.toolType.name,
                pageIndex = s.pageIndex,
                isCanonical = s.isCanonical
            )
        }
        return json.encodeToString(serialized)
    }

    fun deserializeStrokes(jsonString: String): List<InkStroke> {
        if (jsonString.isBlank() || jsonString == "[]") return emptyList()
        return try {
            val list = json.decodeFromString<List<SerializedInkStroke>>(jsonString)
            list.map { s ->
                InkStroke(
                    id = s.id,
                    points = s.points.map { p ->
                        InkPoint(p.x, p.y, p.pressure, p.timestamp)
                    },
                    baseWidth = s.baseWidth,
                    color = Color(s.colorArgb.toULong()),
                    toolType = try {
                        InkToolType.valueOf(s.toolType)
                    } catch (_: Exception) {
                        InkToolType.PEN
                    },
                    pageIndex = s.pageIndex,
                    isCanonical = s.isCanonical
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
