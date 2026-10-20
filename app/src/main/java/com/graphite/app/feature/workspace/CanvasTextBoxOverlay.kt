package com.graphite.app.feature.workspace

import android.graphics.Color.parseColor
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.feature.workspace.model.CanvasTextBox
import kotlin.math.roundToInt

@Composable
fun CanvasTextBoxOverlayItem(
    box: CanvasTextBox,
    isEditing: Boolean,
    isTextToolActive: Boolean,
    basePageWidthPx: Float,
    basePageHeightPx: Float,
    pageScale: Float = 1.0f,
    onStartEditing: () -> Unit,
    onFinishEditing: (CanvasTextBox) -> Unit,
    onUpdateDraft: (CanvasTextBox) -> Unit = {},
    onDelete: () -> Unit,
    onMove: (newXRatio: Float, newYRatio: Float) -> Unit,
    onTriggerVoice: (() -> Unit)? = null
) {
    val focusRequester = remember { FocusRequester() }
    var currentText by remember(box.text) { mutableStateOf(box.text) }
    var currentSize by remember(box.fontSizeSp) { mutableFloatStateOf(box.fontSizeSp) }
    var currentColorHex by remember(box.colorHex) { mutableStateOf(box.colorHex) }
    var isBold by remember(box.isBold) { mutableStateOf(box.isBold) }
    var isItalic by remember(box.isItalic) { mutableStateOf(box.isItalic) }
    var isUnderline by remember(box.isUnderline) { mutableStateOf(box.isUnderline) }
    var currentFontFamily by remember(box.fontFamily) { mutableStateOf(box.fontFamily) }
    var currentWidthRatio by remember(box.id, box.widthRatio) { mutableFloatStateOf(box.widthRatio.coerceIn(0.12f, 0.95f)) }

    val resolvedFontFamily = remember(currentFontFamily) {
        when (currentFontFamily.lowercase()) {
            "serif" -> FontFamily.Serif
            "monospace", "mono" -> FontFamily.Monospace
            "cursive" -> FontFamily.Cursive
            else -> FontFamily.Default
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
                fontFamily = font,
                widthRatio = widthRatio
            )
        )
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
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

    val density = LocalDensity.current
    val calculatedWidthDp = with(density) { (currentWidthRatio * basePageWidthPx).toDp() }
    val maxAllowedWidthDp = with(density) { (basePageWidthPx * 0.96f).toDp() }
    val editorWidthDp = calculatedWidthDp.coerceAtLeast(260.dp).coerceAtMost(maxAllowedWidthDp)
    val displayWidthDp = calculatedWidthDp.coerceIn(80.dp, maxAllowedWidthDp)

    Box(
        modifier = Modifier
            .offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) }
            .width(if (isEditing) editorWidthDp else displayWidthDp)
            .padding(2.dp)
    ) {
        if (isEditing) {
            Column(
                modifier = Modifier
                    .shadow(12.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E22).copy(alpha = 0.98f))
                    .border(1.5.dp, Color(0xFF00BCD4), RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                // Header Row 1: Immediate zero-latency drag grip + Font Size + Voice Mic + Delete + Done
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(box.id, basePageWidthPx, basePageHeightPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        val finalX = (dragOffsetX / basePageWidthPx).coerceIn(0.01f, 0.92f)
                                        val finalY = (dragOffsetY / basePageHeightPx).coerceIn(0.01f, 0.95f)
                                        onMove(finalX, finalY)
                                        break
                                    }
                                    val dragAmount = change.position - change.previousPosition
                                    change.consume()
                                    dragOffsetX = (dragOffsetX + dragAmount.x).coerceIn(0f, basePageWidthPx * 0.92f)
                                    dragOffsetY = (dragOffsetY + dragAmount.y).coerceIn(0f, basePageHeightPx * 0.95f)
                                }
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Drag grip indicator with comfortable touch target
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragHandle,
                            contentDescription = "Drag to move",
                            tint = Color(0xFF00BCD4),
                            modifier = Modifier.size(20.dp).padding(end = 4.dp)
                        )

                        // Font Size controls (- / +)
                        IconButton(
                            onClick = {
                                currentSize = (currentSize - 2f).coerceAtLeast(10f)
                                notifyDraftChange(size = currentSize)
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Smaller", tint = Color.LightGray, modifier = Modifier.size(13.dp))
                        }
                        Text(
                            text = "${currentSize.toInt()}",
                            color = Color(0xFF00BCD4),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        IconButton(
                            onClick = {
                                currentSize = (currentSize + 2f).coerceAtMost(48f)
                                notifyDraftChange(size = currentSize)
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Larger", tint = Color.LightGray, modifier = Modifier.size(13.dp))
                        }
                    }

                    // Direct Dictation into this active text box
                    if (onTriggerVoice != null) {
                        IconButton(
                            onClick = onTriggerVoice,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Dictate into Text Box",
                                tint = Color(0xFFFF7A30),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Delete & Done Actions
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = {
                                if (currentText.trim().isEmpty()) {
                                    onDelete()
                                } else {
                                    onFinishEditing(
                                        box.copy(
                                            text = currentText.trim(),
                                            fontSizeSp = currentSize,
                                            colorHex = currentColorHex,
                                            isBold = isBold,
                                            isItalic = isItalic,
                                            isUnderline = isUnderline,
                                            fontFamily = currentFontFamily,
                                            widthRatio = currentWidthRatio,
                                            xRatio = (dragOffsetX / basePageWidthPx).coerceIn(0.01f, 0.92f),
                                            yRatio = (dragOffsetY / basePageHeightPx).coerceIn(0.01f, 0.95f)
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Done", tint = Color(0xFF00E676), modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Toolbar Row 2: Rich Text Styles (B, I, U), Font Family, Width Presets, and Color Palette
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Bold Toggle Button
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isBold) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color(0xFF2C2C32))
                            .border(1.dp, if (isBold) Color(0xFF00BCD4) else Color(0xFF44444A), RoundedCornerShape(4.dp))
                            .clickable {
                                isBold = !isBold
                                notifyDraftChange(bold = isBold)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "B",
                            color = if (isBold) Color(0xFF00BCD4) else Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                    }

                    // Italic Toggle Button
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isItalic) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color(0xFF2C2C32))
                            .border(1.dp, if (isItalic) Color(0xFF00BCD4) else Color(0xFF44444A), RoundedCornerShape(4.dp))
                            .clickable {
                                isItalic = !isItalic
                                notifyDraftChange(italic = isItalic)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "I",
                            color = if (isItalic) Color(0xFF00BCD4) else Color.White,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    // Underline Toggle Button
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isUnderline) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color(0xFF2C2C32))
                            .border(1.dp, if (isUnderline) Color(0xFF00BCD4) else Color(0xFF44444A), RoundedCornerShape(4.dp))
                            .clickable {
                                isUnderline = !isUnderline
                                notifyDraftChange(underline = isUnderline)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "U",
                            color = if (isUnderline) Color(0xFF00BCD4) else Color.White,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    // Font Family Selector Chips
                    val fontOptions = listOf("Sans" to "Default", "Serif" to "Serif", "Mono" to "Monospace", "Cursive" to "Cursive")
                    fontOptions.forEach { (label, key) ->
                        val isSelected = currentFontFamily.equals(key, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color(0xFF2C2C32))
                                .border(1.dp, if (isSelected) Color(0xFF00BCD4) else Color(0xFF44444A), RoundedCornerShape(4.dp))
                                .clickable {
                                    currentFontFamily = key
                                    notifyDraftChange(font = key)
                                }
                                .padding(horizontal = 5.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color(0xFF00BCD4) else Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    // Quick Width Presets (allows 1-tap resizing of the editor & text box)
                    listOf("30%" to 0.30f, "50%" to 0.50f, "75%" to 0.75f, "90%" to 0.90f).forEach { (lbl, ratio) ->
                        val isChosen = kotlin.math.abs(currentWidthRatio - ratio) < 0.08f
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isChosen) Color(0xFF00BCD4).copy(alpha = 0.25f) else Color(0xFF2C2C32))
                                .border(1.dp, if (isChosen) Color(0xFF00BCD4) else Color(0xFF44444A), RoundedCornerShape(4.dp))
                            .clickable {
                                currentWidthRatio = ratio
                                notifyDraftChange(widthRatio = ratio)
                            }
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = lbl,
                                color = if (isChosen) Color(0xFF00BCD4) else Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    // Color swatches
                    val palette = listOf(
                        "#1F1F1F", // Dark
                        "#1976D2", // Blue
                        "#D32F2F", // Red
                        "#388E3C", // Green
                        "#F57C00", // Orange
                        "#7B1FA2"  // Purple
                    )
                    palette.forEach { hex ->
                        val isSelected = currentColorHex.equals(hex, ignoreCase = true)
                        val c = try { Color(parseColor(hex)) } catch (_: Exception) { Color.Black }
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    1.5.dp,
                                    if (isSelected) Color(0xFF00BCD4) else Color.Gray,
                                    CircleShape
                                )
                                .clickable {
                                    currentColorHex = hex
                                    notifyDraftChange(colorHex = hex)
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Editable Rich Text Input Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    if (currentText.isEmpty()) {
                        Text(
                            text = "Type text here...",
                            color = Color.LightGray,
                            fontSize = currentSize.sp,
                            fontFamily = resolvedFontFamily
                        )
                    }

                    val effEditSize = (currentSize * pageScale).coerceAtLeast(8.5f)
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
                            fontSize = effEditSize.sp,
                            lineHeight = (effEditSize * 1.35f).sp,
                            fontFamily = resolvedFontFamily,
                            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (isItalic) FontStyle.Italic else FontStyle.Normal,
                            textDecoration = if (isUnderline) TextDecoration.Underline else TextDecoration.None
                        ),
                        cursorBrush = SolidColor(Color(0xFF00BCD4)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Resize Bar: Real-time width indicator + corner drag handle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Width: ${(currentWidthRatio * 100).toInt()}% (Drag corner to resize)",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )

                    // Interactive Corner Drag Handle for fine-grained editor resizing
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF00BCD4).copy(alpha = 0.22f))
                            .pointerInput(box.id, basePageWidthPx) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            notifyDraftChange(widthRatio = currentWidthRatio)
                                            break
                                        }
                                        val dragAmount = change.position - change.previousPosition
                                        change.consume()
                                        val deltaRatio = dragAmount.x / basePageWidthPx
                                        currentWidthRatio = (currentWidthRatio + deltaRatio).coerceIn(0.18f, 0.95f)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Resize Editor Width",
                            tint = Color(0xFF00BCD4),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        } else {
            // Read/Display Mode on Paper Canvas
            val displayFontFamily = remember(box.fontFamily) {
                when (box.fontFamily.lowercase()) {
                    "serif" -> FontFamily.Serif
                    "monospace", "mono" -> FontFamily.Monospace
                    "cursive" -> FontFamily.Cursive
                    else -> FontFamily.Default
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        if (isTextToolActive) {
                            Modifier
                                .background(Color(0xFF00BCD4).copy(alpha = 0.08f))
                                .border(
                                    1.dp,
                                    Color(0xFF00BCD4).copy(alpha = 0.7f),
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        } else {
                            Modifier
                                .clickable { onStartEditing() }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        }
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTextToolActive) {
                        // 6-Dot Drag Move Handle: 38dp touch target + immediate zero-latency pointer tracking
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF00BCD4).copy(alpha = 0.22f))
                                .pointerInput(box.id, basePageWidthPx, basePageHeightPx) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) {
                                                val finalX = (dragOffsetX / basePageWidthPx).coerceIn(0.01f, 0.92f)
                                                val finalY = (dragOffsetY / basePageHeightPx).coerceIn(0.01f, 0.95f)
                                                onMove(finalX, finalY)
                                                break
                                            }
                                            val dragAmount = change.position - change.previousPosition
                                            change.consume()
                                            dragOffsetX = (dragOffsetX + dragAmount.x).coerceIn(0f, basePageWidthPx * 0.92f)
                                            dragOffsetY = (dragOffsetY + dragAmount.y).coerceIn(0f, basePageHeightPx * 0.95f)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragIndicator,
                                contentDescription = "Drag to Move",
                                tint = Color(0xFF00BCD4),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Tapping text box opens the rich text editor!
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = true)
                            .clickable { onStartEditing() }
                    ) {
                        val effDisplaySize = (box.fontSizeSp * pageScale).coerceAtLeast(8.5f)
                        Text(
                            text = if (box.text.isNotBlank()) box.text else "(Tap to edit text)",
                            color = if (box.text.isNotBlank()) textColor else Color.Gray,
                            fontSize = effDisplaySize.sp,
                            lineHeight = (effDisplaySize * 1.35f).sp,
                            fontFamily = displayFontFamily,
                            fontWeight = if (box.isBold) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (box.isItalic) FontStyle.Italic else FontStyle.Normal,
                            textDecoration = if (box.isUnderline) TextDecoration.Underline else TextDecoration.None
                        )
                    }

                    if (isTextToolActive) {
                        // Interactive Drag Handle to Resize Text Box Width directly on canvas!
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF00BCD4).copy(alpha = 0.20f))
                                .pointerInput(box.id, basePageWidthPx) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) {
                                                onFinishEditing(
                                                    box.copy(
                                                        widthRatio = currentWidthRatio,
                                                        xRatio = (dragOffsetX / basePageWidthPx).coerceIn(0.01f, 0.92f),
                                                        yRatio = (dragOffsetY / basePageHeightPx).coerceIn(0.01f, 0.95f)
                                                    )
                                                )
                                                break
                                            }
                                            val dragAmount = change.position - change.previousPosition
                                            change.consume()
                                            val deltaRatio = dragAmount.x / basePageWidthPx
                                            currentWidthRatio = (currentWidthRatio + deltaRatio).coerceIn(0.15f, 0.95f)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "Drag to Resize Width",
                                tint = Color(0xFF00BCD4),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Quick Delete Button (✕)
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
