package com.graphite.app.feature.workspace

import android.graphics.Color.parseColor
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.feature.workspace.model.CanvasTextBox
import kotlin.math.roundToInt

/**
 * Modern, clean canvas text box matching reference design:
 * - Crisp blue border (#2196F3) when selected
 * - Top center grab handle pill with 3 horizontal dots [...] for smooth dragging
 * - Left and right circular handles (o) for width adjustments
 * - Floating mini-toolbar attached right above the text box
 * - In-place direct editing without bulky overlay cards
 * - Dynamic auto-expansion as text is typed without premature line-wrapping
 */
@Composable
fun CanvasTextBoxOverlayItem(
    box: CanvasTextBox,
    isSelected: Boolean,
    basePageWidthPx: Float,
    basePageHeightPx: Float,
    pageScale: Float = 1.0f,
    onSelect: () -> Unit,
    onFinishEditing: (CanvasTextBox) -> Unit,
    onUpdateDraft: (CanvasTextBox) -> Unit = {},
    onDelete: () -> Unit,
    onMove: (newXRatio: Float, newYRatio: Float) -> Unit,
    onTriggerVoice: (() -> Unit)? = null
) {
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }

    var currentText by remember(box.text) { mutableStateOf(box.text) }
    var currentSize by remember(box.fontSizeSp) { mutableFloatStateOf(box.fontSizeSp) }
    var currentColorHex by remember(box.colorHex) { mutableStateOf(box.colorHex) }
    var isBold by remember(box.isBold) { mutableStateOf(box.isBold) }
    var isItalic by remember(box.isItalic) { mutableStateOf(box.isItalic) }
    var isUnderline by remember(box.isUnderline) { mutableStateOf(box.isUnderline) }
    var isStrikethrough by remember(box.isStrikethrough) { mutableStateOf(box.isStrikethrough) }
    var textAlign by remember(box.textAlign) { mutableStateOf(box.textAlign) }
    var isAutoWidth by remember(box.isAutoWidth) { mutableStateOf(box.isAutoWidth) }
    var currentFontFamily by remember(box.fontFamily) { mutableStateOf(box.fontFamily) }
    var currentWidthRatio by remember(box.id, box.widthRatio) { mutableFloatStateOf(box.widthRatio.coerceIn(0.08f, 0.95f)) }

    var showColorPicker by remember { mutableStateOf(false) }

    val resolvedFontFamily = remember(currentFontFamily) {
        when (currentFontFamily.lowercase()) {
            "serif" -> FontFamily.Serif
            "monospace", "mono" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            else -> FontFamily.Default
        }
    }

    val resolvedTextAlign = remember(textAlign) {
        when (textAlign.lowercase()) {
            "center" -> TextAlign.Center
            "right" -> TextAlign.Right
            else -> TextAlign.Left
        }
    }

    val resolvedTextDecoration = remember(isUnderline, isStrikethrough) {
        when {
            isUnderline && isStrikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            isUnderline -> TextDecoration.Underline
            isStrikethrough -> TextDecoration.LineThrough
            else -> TextDecoration.None
        }
    }

    val textColor = remember(currentColorHex) {
        try {
            Color(parseColor(currentColorHex))
        } catch (_: Exception) {
            Color(0xFF1F1F1F)
        }
    }

    fun notifyDraftChange(
        text: String = currentText,
        size: Float = currentSize,
        colorHex: String = currentColorHex,
        bold: Boolean = isBold,
        italic: Boolean = isItalic,
        underline: Boolean = isUnderline,
        strikethrough: Boolean = isStrikethrough,
        align: String = textAlign,
        autoW: Boolean = isAutoWidth,
        font: String = currentFontFamily,
        widthRatio: Float = currentWidthRatio
    ) {
        onUpdateDraft(
            box.copy(
                text = text,
                fontSizeSp = size,
                colorHex = colorHex,
                isBold = bold,
                isItalic = italic,
                isUnderline = underline,
                isStrikethrough = strikethrough,
                textAlign = align,
                isAutoWidth = autoW,
                fontFamily = font,
                widthRatio = widthRatio
            )
        )
    }

    // Auto-focus when newly selected
    LaunchedEffect(isSelected) {
        if (isSelected && currentText.isEmpty()) {
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    var dragOffsetX by remember(box.id, box.xRatio, basePageWidthPx) {
        mutableFloatStateOf(box.xRatio * basePageWidthPx)
    }
    var dragOffsetY by remember(box.id, box.yRatio, basePageHeightPx) {
        mutableFloatStateOf(box.yRatio * basePageHeightPx)
    }

    // Dynamic auto-expansion measurement:
    // When isAutoWidth is true, the box widens horizontally with typed characters on the line
    // It will NOT break to next line unless user types \n or reaches the page border!
    val effSizeSp = (currentSize * pageScale).coerceAtLeast(10f)
    val maxAvailableWPx = ((0.96f - (dragOffsetX / basePageWidthPx)).coerceAtLeast(0.12f) * basePageWidthPx)
    val maxAvailableWDp = with(density) { maxAvailableWPx.toDp() }

    val calculatedWidthDp = if (!isAutoWidth && currentWidthRatio > 0.08f) {
        with(density) { (currentWidthRatio * basePageWidthPx).toDp() }.coerceIn(60.dp, maxAvailableWDp)
    } else {
        // Auto-expanding width: estimate width from longest line in text
        val lines = if (currentText.isEmpty()) listOf("Tap to type text") else currentText.split("\n")
        val longestLine = lines.maxByOrNull { it.length } ?: ""
        val charCount = longestLine.length.coerceAtLeast(10)
        val charWidthPx = (effSizeSp * 0.62f) * density.density
        val estimatedContentWPx = (charCount * charWidthPx) + (36f * density.density)
        val dynamicWDp = with(density) { estimatedContentWPx.toDp() }
        dynamicWDp.coerceIn(120.dp, maxAvailableWDp)
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) }
            .width(calculatedWidthDp)
    ) {
        // ========================================================
        // 1. FLOATING MINI-TOOLBAR (DIRECTLY ABOVE SELECTED TEXT BOX)
        // ========================================================
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-44).dp)
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22).copy(alpha = 0.98f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333338)),
                    modifier = Modifier.shadow(8.dp, RoundedCornerShape(20.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // Pencil / Keyboard focus icon
                        IconButton(
                            onClick = {
                                try { focusRequester.requestFocus() } catch (_: Exception) {}
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Focus Editor",
                                tint = Color(0xFF2196F3),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Speech Note / Voice Icon
                        IconButton(
                            onClick = { onTriggerVoice?.invoke() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ChatBubbleOutline,
                                contentDescription = "Voice Dictate",
                                tint = Color.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Color Indicator Circle (Clickable popup)
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(textColor)
                                .border(1.5.dp, Color.White, CircleShape)
                                .clickable { showColorPicker = !showColorPicker },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }

                        // Fill / Style Icon
                        IconButton(
                            onClick = {
                                isAutoWidth = !isAutoWidth
                                notifyDraftChange(autoW = isAutoWidth)
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FitScreen,
                                contentDescription = "Toggle Auto Width",
                                tint = if (isAutoWidth) Color(0xFF2196F3) else Color.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Font size badge (Aa 16)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2C2C32))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "Aa ${currentSize.roundToInt()}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Delete Trash Button
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete Text Box",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Compact Color Swatch Dropdown
                if (showColorPicker) {
                    Box(
                        modifier = Modifier
                            .offset(y = 42.dp)
                            .shadow(8.dp, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF252528))
                            .border(1.dp, Color(0xFF3E3E44), RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val colors = listOf(
                                "#1F1F1F", "#2196F3", "#D32F2F", "#388E3C",
                                "#F57C00", "#7B1FA2", "#00BCD4", "#E91E63"
                            )
                            colors.forEach { hex ->
                                val c = try { Color(parseColor(hex)) } catch (_: Exception) { Color.Black }
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .border(
                                            1.5.dp,
                                            if (currentColorHex.equals(hex, ignoreCase = true)) Color.White else Color.Transparent,
                                            CircleShape
                                        )
                                        .clickable {
                                            currentColorHex = hex
                                            showColorPicker = false
                                            notifyDraftChange(colorHex = hex)
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ========================================================
        // 2. TEXT BOX CANVAS CONTAINER
        // ========================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .then(
                    if (isSelected) {
                        Modifier
                            .background(Color(0xFF2196F3).copy(alpha = 0.04f))
                            .border(1.5.dp, Color(0xFF2196F3), RoundedCornerShape(4.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                try { focusRequester.requestFocus() } catch (_: Exception) {}
                            }
                    } else {
                        Modifier.clickable { onSelect() }
                    }
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            // In-place editable text area
            if (isSelected) {
                if (currentText.isEmpty()) {
                    Text(
                        text = "Type text here...",
                        color = Color.LightGray,
                        fontSize = effSizeSp.sp,
                        fontFamily = resolvedFontFamily,
                        textAlign = resolvedTextAlign
                    )
                }

                BasicTextField(
                    value = currentText,
                    onValueChange = {
                        currentText = it
                        notifyDraftChange(text = it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = effSizeSp.sp,
                        lineHeight = (effSizeSp * 1.32f).sp,
                        fontFamily = resolvedFontFamily,
                        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = resolvedTextDecoration,
                        textAlign = resolvedTextAlign
                    ),
                    cursorBrush = SolidColor(Color(0xFF2196F3)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                )
            } else {
                Text(
                    text = if (box.text.isNotBlank()) box.text else "(Empty text)",
                    color = if (box.text.isNotBlank()) textColor else Color.Gray,
                    fontSize = effSizeSp.sp,
                    lineHeight = (effSizeSp * 1.32f).sp,
                    fontFamily = resolvedFontFamily,
                    fontWeight = if (box.isBold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (box.isItalic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = resolvedTextDecoration,
                    textAlign = resolvedTextAlign,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ========================================================
        // 3. TOP CENTER GRAB HANDLE PILL [...] (FOR SMOOTH DRAGGING)
        // ========================================================
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-8).dp)
                    .size(width = 38.dp, height = 16.dp)
                    .shadow(3.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE3F2FD))
                    .border(1.dp, Color(0xFF2196F3), RoundedCornerShape(8.dp))
                    .pointerInput(box.id, basePageWidthPx, basePageHeightPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var prev = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    val finalX = (dragOffsetX / basePageWidthPx).coerceIn(0.01f, 0.92f)
                                    val finalY = (dragOffsetY / basePageHeightPx).coerceIn(0.01f, 0.95f)
                                    onMove(finalX, finalY)
                                    break
                                }
                                val delta = change.position - prev
                                prev = change.position
                                change.consume()
                                dragOffsetX = (dragOffsetX + delta.x).coerceIn(0f, basePageWidthPx * 0.92f)
                                dragOffsetY = (dragOffsetY + delta.y).coerceIn(0f, basePageHeightPx * 0.95f)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .size(3.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2196F3))
                        )
                    }
                }
            }

            // ========================================================
            // 4. RIGHT CIRCULAR RESIZE HANDLE (o)
            // ========================================================
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 8.dp)
                    .size(28.dp)
                    .pointerInput(box.id, basePageWidthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var prev = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    onFinishEditing(
                                        box.copy(
                                            isAutoWidth = false,
                                            widthRatio = currentWidthRatio,
                                            xRatio = (dragOffsetX / basePageWidthPx).coerceIn(0.01f, 0.92f),
                                            yRatio = (dragOffsetY / basePageHeightPx).coerceIn(0.01f, 0.95f)
                                        )
                                    )
                                    break
                                }
                                val delta = change.position - prev
                                prev = change.position
                                change.consume()
                                isAutoWidth = false
                                val deltaRatio = delta.x / basePageWidthPx
                                currentWidthRatio = (currentWidthRatio + deltaRatio).coerceIn(0.08f, 0.95f)
                                notifyDraftChange(autoW = false, widthRatio = currentWidthRatio)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .shadow(2.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, Color(0xFF2196F3), CircleShape)
                )
            }

            // ========================================================
            // 5. LEFT CIRCULAR RESIZE HANDLE (o)
            // ========================================================
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-8).dp)
                    .size(28.dp)
                    .pointerInput(box.id, basePageWidthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var prev = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    onFinishEditing(
                                        box.copy(
                                            isAutoWidth = false,
                                            widthRatio = currentWidthRatio,
                                            xRatio = (dragOffsetX / basePageWidthPx).coerceIn(0.01f, 0.92f),
                                            yRatio = (dragOffsetY / basePageHeightPx).coerceIn(0.01f, 0.95f)
                                        )
                                    )
                                    break
                                }
                                val delta = change.position - prev
                                prev = change.position
                                change.consume()
                                isAutoWidth = false
                                val deltaRatio = -delta.x / basePageWidthPx
                                currentWidthRatio = (currentWidthRatio + deltaRatio).coerceIn(0.08f, 0.95f)
                                dragOffsetX = (dragOffsetX + delta.x).coerceIn(0f, basePageWidthPx * 0.92f)
                                notifyDraftChange(autoW = false, widthRatio = currentWidthRatio)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .shadow(2.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, Color(0xFF2196F3), CircleShape)
                )
            }
        }
    }
}
