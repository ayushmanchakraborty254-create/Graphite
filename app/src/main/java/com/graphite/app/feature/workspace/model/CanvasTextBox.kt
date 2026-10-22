package com.graphite.app.feature.workspace.model

import android.util.Base64
import java.util.UUID

/**
 * Represents a formatted rich-text box placed on a specific notebook page.
 * Positions are stored as normalized ratios (0..1) relative to page dimensions
 * to ensure responsive rendering across tablets and mobile screens without cropping.
 */
data class CanvasTextBox(
    val id: String = UUID.randomUUID().toString(),
    val pageIndex: Int = 0,
    val xRatio: Float = 0.1f,
    val yRatio: Float = 0.1f,
    val widthRatio: Float = 0.40f, // Normalized width ratio relative to page width (0.12..0.95)
    val text: String = "",
    val fontSizeSp: Float = 16f,
    val colorHex: String = "#1F1F1F",
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isUnderline: Boolean = false,
    val isStrikethrough: Boolean = false,
    val textAlign: String = "Left", // "Left", "Center", "Right"
    val isAutoWidth: Boolean = true, // Whether width dynamically expands with typed text
    val fontFamily: String = "Default" // "Default", "Serif", "Monospace", "Cursive"
) {
    fun serialize(): String {
        val b64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "[GRAPHITE_TEXTBOX:id=$id;page=$pageIndex;x=$xRatio;y=$yRatio;w=$widthRatio;size=$fontSizeSp;col=$colorHex;bold=$isBold;italic=$isItalic;under=$isUnderline;strike=$isStrikethrough;align=$textAlign;autoW=$isAutoWidth;font=$fontFamily;text=$b64Text]"
    }

    companion object {
        fun deserialize(line: String): CanvasTextBox? {
            if (!line.startsWith("[GRAPHITE_TEXTBOX:") || !line.endsWith("]")) return null
            return try {
                val payload = line.removePrefix("[GRAPHITE_TEXTBOX:").removeSuffix("]")
                val parts = payload.split(";").associate {
                    val idx = it.indexOf('=')
                    if (idx != -1) it.substring(0, idx) to it.substring(idx + 1) else it to ""
                }
                val id = parts["id"]?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                val page = parts["page"]?.toIntOrNull() ?: 0
                val x = parts["x"]?.toFloatOrNull() ?: 0.1f
                val y = parts["y"]?.toFloatOrNull() ?: 0.1f
                val w = parts["w"]?.toFloatOrNull() ?: 0.40f
                val size = parts["size"]?.toFloatOrNull() ?: 16f
                val col = parts["col"]?.takeIf { it.isNotBlank() } ?: "#1F1F1F"
                val isBold = parts["bold"]?.toBooleanStrictOrNull() ?: false
                val isItalic = parts["italic"]?.toBooleanStrictOrNull() ?: false
                val isUnderline = parts["under"]?.toBooleanStrictOrNull() ?: false
                val isStrike = parts["strike"]?.toBooleanStrictOrNull() ?: false
                val align = parts["align"]?.takeIf { it.isNotBlank() } ?: "Left"
                val autoW = parts["autoW"]?.toBooleanStrictOrNull() ?: (w <= 0.15f || parts["w"] == null)
                val fontFamily = parts["font"]?.takeIf { it.isNotBlank() } ?: "Default"

                val rawText = parts["text"] ?: ""
                val decodedText = try {
                    String(Base64.decode(rawText, Base64.NO_WRAP), Charsets.UTF_8)
                } catch (_: Exception) {
                    rawText
                }

                // Discard blank or ghost placeholder text boxes
                if (decodedText.trim().isEmpty() || decodedText.trim() == "(Empty text box)") {
                    return null
                }

                CanvasTextBox(
                    id = id,
                    pageIndex = page,
                    xRatio = x,
                    yRatio = y,
                    widthRatio = w.coerceIn(0.08f, 0.95f),
                    text = decodedText,
                    fontSizeSp = size,
                    colorHex = col,
                    isBold = isBold,
                    isItalic = isItalic,
                    isUnderline = isUnderline,
                    isStrikethrough = isStrike,
                    textAlign = align,
                    isAutoWidth = autoW,
                    fontFamily = fontFamily
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
