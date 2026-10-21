package com.graphite.app.feature.workspace.model

import android.util.Base64
import java.util.UUID

/**
 * Represents an imported photo / gallery image placed on a notebook canvas page.
 * Positions and dimensions are stored as normalized ratios (0..1) relative to page dimensions
 * to ensure responsive and consistent rendering across tablet / mobile orientations.
 */
data class CanvasImageElement(
    val id: String = UUID.randomUUID().toString(),
    val pageIndex: Int = 0,
    val filePath: String = "",
    val xRatio: Float = 0.25f,
    val yRatio: Float = 0.25f,
    val widthRatio: Float = 0.50f,
    val heightRatio: Float = 0.40f,
    val rotationDegrees: Float = 0f,
    val opacity: Float = 1.0f,
    val isLocked: Boolean = false,
    val keepAspectRatio: Boolean = true,
    val isCircleCrop: Boolean = false,
    val zIndex: Int = 0
) {
    fun serialize(): String {
        val b64Path = Base64.encodeToString(filePath.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "[GRAPHITE_IMAGE:id=$id;page=$pageIndex;path=$b64Path;x=$xRatio;y=$yRatio;w=$widthRatio;h=$heightRatio;rot=$rotationDegrees;op=$opacity;lock=$isLocked;aspect=$keepAspectRatio;circle=$isCircleCrop;z=$zIndex]"
    }

    companion object {
        fun deserialize(line: String): CanvasImageElement? {
            if (!line.startsWith("[GRAPHITE_IMAGE:") || !line.endsWith("]")) return null
            return try {
                val payload = line.removePrefix("[GRAPHITE_IMAGE:").removeSuffix("]")
                val parts = payload.split(";").associate {
                    val idx = it.indexOf('=')
                    if (idx != -1) it.substring(0, idx) to it.substring(idx + 1) else it to ""
                }
                val id = parts["id"]?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                val page = parts["page"]?.toIntOrNull() ?: 0
                val b64Path = parts["path"] ?: ""
                val path = try {
                    String(Base64.decode(b64Path, Base64.NO_WRAP), Charsets.UTF_8)
                } catch (_: Exception) {
                    b64Path
                }
                if (path.isBlank()) return null

                val x = parts["x"]?.toFloatOrNull() ?: 0.25f
                val y = parts["y"]?.toFloatOrNull() ?: 0.25f
                val w = parts["w"]?.toFloatOrNull() ?: 0.50f
                val h = parts["h"]?.toFloatOrNull() ?: 0.40f
                val rot = parts["rot"]?.toFloatOrNull() ?: 0f
                val op = parts["op"]?.toFloatOrNull() ?: 1.0f
                val lock = parts["lock"]?.toBooleanStrictOrNull() ?: false
                val aspect = parts["aspect"]?.toBooleanStrictOrNull() ?: true
                val circle = parts["circle"]?.toBooleanStrictOrNull() ?: false
                val z = parts["z"]?.toIntOrNull() ?: 0

                CanvasImageElement(
                    id = id,
                    pageIndex = page,
                    filePath = path,
                    xRatio = x,
                    yRatio = y,
                    widthRatio = w,
                    heightRatio = h,
                    rotationDegrees = rot,
                    opacity = op,
                    isLocked = lock,
                    keepAspectRatio = aspect,
                    isCircleCrop = circle,
                    zIndex = z
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
