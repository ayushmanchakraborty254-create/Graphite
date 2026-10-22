package com.graphite.app.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.graphite.app.feature.workspace.model.CanvasImageElement
import java.io.File
import kotlin.math.*

@Composable
fun CanvasImageOverlayItem(
    image: CanvasImageElement,
    isSelected: Boolean,
    basePageWidthPx: Float,
    basePageHeightPx: Float,
    pageScale: Float = 1f,
    onSelect: () -> Unit,
    onUpdateDraft: (CanvasImageElement) -> Unit,
    onSave: (CanvasImageElement) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Decode bitmap lazily from local file
    val bitmap = remember(image.filePath) {
        try {
            val file = File(image.filePath)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    if (bitmap == null) return

    val bmpAspect = remember(bitmap) {
        bitmap.height.toFloat() / bitmap.width.toFloat().coerceAtLeast(1f)
    }
    val pageAspectFactor = remember(basePageWidthPx, basePageHeightPx) {
        basePageWidthPx / basePageHeightPx.coerceAtLeast(1f)
    }

    // Local transient states synchronized with image model for 120 FPS continuous feedback
    var currentXRatio by remember(image.id, image.xRatio) { mutableFloatStateOf(image.xRatio) }
    var currentYRatio by remember(image.id, image.yRatio) { mutableFloatStateOf(image.yRatio) }
    var currentWidthRatio by remember(image.id, image.widthRatio) { mutableFloatStateOf(image.widthRatio) }
    var currentHeightRatio by remember(image.id, image.heightRatio) { mutableFloatStateOf(image.heightRatio) }
    var currentRotation by remember(image.id, image.rotationDegrees) { mutableFloatStateOf(image.rotationDegrees) }
    var currentOpacity by remember(image.id, image.opacity) { mutableFloatStateOf(image.opacity) }
    var isCircleMask by remember(image.id, image.isCircleCrop) { mutableStateOf(image.isCircleCrop) }
    var isAspectLocked by remember(image.id, image.keepAspectRatio) { mutableStateOf(image.keepAspectRatio) }
    var isLocked by remember(image.id, image.isLocked) { mutableStateOf(image.isLocked) }
    var currentZIndex by remember(image.id, image.zIndex) { mutableIntStateOf(image.zIndex) }

    var isRotatingHandle by remember { mutableStateOf(false) }
    var rotationAnchorX by remember { mutableFloatStateOf(0f) }
    var rotationAnchorY by remember { mutableFloatStateOf(0f) }

    var showOpacitySlider by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showCropDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isSelected) {
        if (!isSelected) {
            showOpacitySlider = false
            showContextMenu = false
            isRotatingHandle = false
        }
    }

    if (showCropDialog) {
        CanvasImageCropDialog(
            image = image.copy(
                xRatio = currentXRatio,
                yRatio = currentYRatio,
                widthRatio = currentWidthRatio,
                heightRatio = currentHeightRatio,
                rotationDegrees = currentRotation
            ),
            onDismiss = { showCropDialog = false },
            onApplyCrop = { newPath, newX, newY, newW, newH ->
                currentXRatio = newX
                currentYRatio = newY
                currentWidthRatio = newW
                currentHeightRatio = newH
                val updated = image.copy(
                    filePath = newPath,
                    xRatio = newX,
                    yRatio = newY,
                    widthRatio = newW,
                    heightRatio = newH,
                    rotationDegrees = currentRotation
                )
                onUpdateDraft(updated)
                onSave(updated)
            }
        )
    }

    val imgW = (currentWidthRatio * basePageWidthPx).coerceAtLeast(50f)
    val imgH = (currentHeightRatio * basePageHeightPx).coerceAtLeast(50f)

    // Inner padding around image so all 4 corner handles are completely within layout bounds
    val padAroundDp = 36.dp
    val padAroundPx = with(density) { padAroundDp.toPx() }

    val rotatedBoxWPx = imgW + padAroundPx * 2
    val rotatedBoxHPx = imgH + padAroundPx * 2

    val barPadTopDp = if (isSelected) 60.dp else 0.dp
    val barPadTopPx = with(density) { barPadTopDp.toPx() }

    // Rotation handle radius from center
    val rotRadiusPx = imgH / 2f + with(density) { 36.dp.toPx() }
    val maxReachPx = maxOf(imgW / 2f + padAroundPx, rotRadiusPx + with(density) { 32.dp.toPx() })

    val minOuterWPx = with(density) { 340.dp.toPx() }
    val outerWPx = maxOf(minOuterWPx, maxReachPx * 2f)
    val outerHPx = maxOf(rotatedBoxHPx + barPadTopPx, maxReachPx * 2f + barPadTopPx)

    // The outer Box is centered on the image's center
    val outerLeftPx = (currentXRatio * basePageWidthPx) + imgW / 2f - outerWPx / 2f
    val outerTopPx = (currentYRatio * basePageHeightPx) + imgH / 2f - outerHPx / 2f

    val imgCenterOuterX = outerWPx / 2f
    val imgCenterOuterY = outerHPx / 2f

    Box(
        modifier = Modifier
            .offset { IntOffset(outerLeftPx.roundToInt(), outerTopPx.roundToInt()) }
            .size(
                width = with(density) { outerWPx.toDp() },
                height = with(density) { outerHPx.toDp() }
            )
            .zIndex(if (currentZIndex < 0) -10f else (if (isSelected) 20f else currentZIndex.toFloat()))
    ) {
        // Rotated Core Container: holds image, selection boundary, 4 corner handles, and stem line
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (imgCenterOuterX - rotatedBoxWPx / 2f).roundToInt(),
                        (imgCenterOuterY - rotatedBoxHPx / 2f).roundToInt()
                    )
                }
                .size(
                    width = with(density) { rotatedBoxWPx.toDp() },
                    height = with(density) { rotatedBoxHPx.toDp() }
                )
                .graphicsLayer {
                    rotationZ = currentRotation
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
        ) {
            // 1. Image Bitmap Box with single-finger drag gesture to move the image
            Box(
                modifier = Modifier
                    .offset { IntOffset(padAroundPx.roundToInt(), padAroundPx.roundToInt()) }
                    .size(with(density) { imgW.toDp() }, with(density) { imgH.toDp() })
                    .graphicsLayer {
                        alpha = currentOpacity
                        if (isCircleMask) {
                            clip = true
                            shape = CircleShape
                        }
                    }
                    .pointerInput(image.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var isDrag = false
                            var prevPos = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!isDrag) {
                                        onSelect()
                                    } else {
                                        onSave(
                                            image.copy(
                                                xRatio = currentXRatio,
                                                yRatio = currentYRatio,
                                                widthRatio = currentWidthRatio,
                                                heightRatio = currentHeightRatio,
                                                rotationDegrees = currentRotation
                                            )
                                        )
                                    }
                                    break
                                }
                                val dragDelta = change.position - prevPos
                                prevPos = change.position
                                if (!isDrag && dragDelta.getDistance() > 4f) {
                                    isDrag = true
                                    onSelect()
                                }
                                if (isDrag && !isLocked) {
                                    change.consume()
                                    // Rotate drag vector by currentRotation to match unrotated page space
                                    val rad = Math.toRadians(currentRotation.toDouble())
                                    val cosA = cos(rad).toFloat()
                                    val sinA = sin(rad).toFloat()
                                    val pageDx = dragDelta.x * cosA - dragDelta.y * sinA
                                    val pageDy = dragDelta.x * sinA + dragDelta.y * cosA

                                    currentXRatio = (currentXRatio + pageDx / basePageWidthPx).coerceIn(-0.2f, 0.95f)
                                    currentYRatio = (currentYRatio + pageDy / basePageHeightPx).coerceIn(-0.2f, 0.95f)
                                    onUpdateDraft(
                                        image.copy(
                                            xRatio = currentXRatio,
                                            yRatio = currentYRatio,
                                            widthRatio = currentWidthRatio,
                                            heightRatio = currentHeightRatio,
                                            rotationDegrees = currentRotation
                                        )
                                    )
                                }
                            }
                        }
                    }
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Canvas Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 2. Selection border & interactive corner handles (when isSelected)
            if (isSelected) {
                // Blue selection boundary border
                Box(
                    modifier = Modifier
                        .offset { IntOffset(padAroundPx.roundToInt(), padAroundPx.roundToInt()) }
                        .size(with(density) { imgW.toDp() }, with(density) { imgH.toDp() })
                        .border(2.dp, Color(0xFF2196F3), if (isCircleMask) CircleShape else RoundedCornerShape(2.dp))
                )

                val handleVisualDp = 20.dp
                val handleTouchDp = 36.dp
                val handleTouchPx = with(density) { handleTouchDp.toPx() }
                val halfTouchPx = handleTouchPx / 2f

                // 1. Top-Left Resize Handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (padAroundPx - halfTouchPx).roundToInt(),
                                (padAroundPx - halfTouchPx).roundToInt()
                            )
                        }
                        .size(handleTouchDp)
                        .pointerInput(image.id) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var prevPos = down.position
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        onSave(
                                            image.copy(
                                                xRatio = currentXRatio,
                                                yRatio = currentYRatio,
                                                widthRatio = currentWidthRatio,
                                                heightRatio = currentHeightRatio,
                                                rotationDegrees = currentRotation
                                            )
                                        )
                                        break
                                    }
                                    val dragDelta = change.position - prevPos
                                    prevPos = change.position
                                    if (!isLocked) {
                                        change.consume()
                                        val dw = -dragDelta.x / basePageWidthPx
                                        val newW = (currentWidthRatio + dw).coerceIn(0.08f, 0.95f)
                                        val newH = if (isAspectLocked) {
                                            newW * bmpAspect * pageAspectFactor
                                        } else {
                                            (currentHeightRatio - dragDelta.y / basePageHeightPx).coerceIn(0.08f, 0.95f)
                                        }
                                        val dX = currentWidthRatio - newW
                                        val dY = currentHeightRatio - newH
                                        currentXRatio = (currentXRatio + dX).coerceIn(-0.2f, 0.95f)
                                        currentYRatio = (currentYRatio + dY).coerceIn(-0.2f, 0.95f)
                                        currentWidthRatio = newW
                                        currentHeightRatio = newH
                                        onUpdateDraft(
                                            image.copy(
                                                xRatio = currentXRatio,
                                                yRatio = currentYRatio,
                                                widthRatio = currentWidthRatio,
                                                heightRatio = currentHeightRatio
                                            )
                                        )
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(handleVisualDp)
                            .clip(CircleShape)
                            .background(Color(0xFF2196F3))
                            .border(2.dp, Color.White, CircleShape)
                    )
                }

                // 2. Top-Right Resize Handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (padAroundPx + imgW - halfTouchPx).roundToInt(),
                                (padAroundPx - halfTouchPx).roundToInt()
                            )
                        }
                        .size(handleTouchDp)
                        .pointerInput(image.id) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var prevPos = down.position
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        onSave(
                                            image.copy(
                                                xRatio = currentXRatio,
                                                yRatio = currentYRatio,
                                                widthRatio = currentWidthRatio,
                                                heightRatio = currentHeightRatio,
                                                rotationDegrees = currentRotation
                                            )
                                        )
                                        break
                                    }
                                    val dragDelta = change.position - prevPos
                                    prevPos = change.position
                                    if (!isLocked) {
                                        change.consume()
                                        val dw = dragDelta.x / basePageWidthPx
                                        val newW = (currentWidthRatio + dw).coerceIn(0.08f, 0.95f)
                                        val newH = if (isAspectLocked) {
                                            newW * bmpAspect * pageAspectFactor
                                        } else {
                                            (currentHeightRatio - dragDelta.y / basePageHeightPx).coerceIn(0.08f, 0.95f)
                                        }
                                        val dY = currentHeightRatio - newH
                                        currentYRatio = (currentYRatio + dY).coerceIn(-0.2f, 0.95f)
                                        currentWidthRatio = newW
                                        currentHeightRatio = newH
                                        onUpdateDraft(
                                            image.copy(
                                                xRatio = currentXRatio,
                                                yRatio = currentYRatio,
                                                widthRatio = currentWidthRatio,
                                                heightRatio = currentHeightRatio
                                            )
                                        )
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(handleVisualDp)
                            .clip(CircleShape)
                            .background(Color(0xFF2196F3))
                            .border(2.dp, Color.White, CircleShape)
                    )
                }

                // 3. Bottom-Left Resize Handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (padAroundPx - halfTouchPx).roundToInt(),
                                (padAroundPx + imgH - halfTouchPx).roundToInt()
                            )
                        }
                        .size(handleTouchDp)
                        .pointerInput(image.id) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var prevPos = down.position
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        onSave(
                                            image.copy(
                                                xRatio = currentXRatio,
                                                yRatio = currentYRatio,
                                                widthRatio = currentWidthRatio,
                                                heightRatio = currentHeightRatio,
                                                rotationDegrees = currentRotation
                                            )
                                        )
                                        break
                                    }
                                    val dragDelta = change.position - prevPos
                                    prevPos = change.position
                                    if (!isLocked) {
                                        change.consume()
                                        val dw = -dragDelta.x / basePageWidthPx
                                        val newW = (currentWidthRatio + dw).coerceIn(0.08f, 0.95f)
                                        val newH = if (isAspectLocked) {
                                            newW * bmpAspect * pageAspectFactor
                                        } else {
                                            (currentHeightRatio + dragDelta.y / basePageHeightPx).coerceIn(0.08f, 0.95f)
                                        }
                                        val dX = currentWidthRatio - newW
                                        currentXRatio = (currentXRatio + dX).coerceIn(-0.2f, 0.95f)
                                        currentWidthRatio = newW
                                        currentHeightRatio = newH
                                        onUpdateDraft(
                                            image.copy(
                                                xRatio = currentXRatio,
                                                widthRatio = currentWidthRatio,
                                                heightRatio = currentHeightRatio
                                            )
                                        )
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(handleVisualDp)
                            .clip(CircleShape)
                            .background(Color(0xFF2196F3))
                            .border(2.dp, Color.White, CircleShape)
                    )
                }

                // 4. Bottom-Right Resize Handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (padAroundPx + imgW - halfTouchPx).roundToInt(),
                                (padAroundPx + imgH - halfTouchPx).roundToInt()
                            )
                        }
                        .size(handleTouchDp)
                        .pointerInput(image.id) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var prevPos = down.position
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        onSave(
                                            image.copy(
                                                xRatio = currentXRatio,
                                                yRatio = currentYRatio,
                                                widthRatio = currentWidthRatio,
                                                heightRatio = currentHeightRatio,
                                                rotationDegrees = currentRotation
                                            )
                                        )
                                        break
                                    }
                                    val dragDelta = change.position - prevPos
                                    prevPos = change.position
                                    if (!isLocked) {
                                        change.consume()
                                        val dw = dragDelta.x / basePageWidthPx
                                        val newW = (currentWidthRatio + dw).coerceIn(0.08f, 0.95f)
                                        val newH = if (isAspectLocked) {
                                            newW * bmpAspect * pageAspectFactor
                                        } else {
                                            (currentHeightRatio + dragDelta.y / basePageHeightPx).coerceIn(0.08f, 0.95f)
                                        }
                                        currentWidthRatio = newW
                                        currentHeightRatio = newH
                                        onUpdateDraft(
                                            image.copy(
                                                widthRatio = currentWidthRatio,
                                                heightRatio = currentHeightRatio
                                            )
                                        )
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(handleVisualDp)
                            .clip(CircleShape)
                            .background(Color(0xFF2196F3))
                            .border(2.dp, Color.White, CircleShape)
                    )
                }

                // Vertical Stem Line from bottom edge of image
                val cxInRotated = padAroundPx + imgW / 2f
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cxInRotated - 1.dp.toPx()).roundToInt(), (padAroundPx + imgH).roundToInt()) }
                        .width(2.dp)
                        .height(24.dp)
                        .background(Color(0xFF2196F3))
                )
            }
        }

        // 3. Bottom Rotation Handle (⟳) - Stationary Touch Anchor & Smooth Real-time Visual Handle
        // Completely eliminates coordinate-shifting feedback loops and jitter by anchoring touch capture during dragging
        if (isSelected) {
            val rotKnobSizeDp = 32.dp
            val rotKnobTouchDp = 52.dp
            val rotKnobTouchPx = with(density) { rotKnobTouchDp.toPx() }
            val halfKnobTouchPx = rotKnobTouchPx / 2f

            val rad = Math.toRadians(currentRotation.toDouble())
            val knobCenterX = imgCenterOuterX - rotRadiusPx * sin(rad).toFloat()
            val knobCenterY = imgCenterOuterY + rotRadiusPx * cos(rad).toFloat()

            // 3A. Smooth Live Visual Knob: tracks currentRotation directly at 120 FPS
            Box(
                modifier = Modifier
                    .offset {
                        val liveRad = Math.toRadians(currentRotation.toDouble())
                        val liveX = imgCenterOuterX - rotRadiusPx * sin(liveRad).toFloat()
                        val liveY = imgCenterOuterY + rotRadiusPx * cos(liveRad).toFloat()
                        IntOffset(
                            (liveX - halfKnobTouchPx).roundToInt(),
                            (liveY - halfKnobTouchPx).roundToInt()
                        )
                    }
                    .size(rotKnobTouchDp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(rotKnobSizeDp)
                        .shadow(if (isRotatingHandle) 8.dp else 4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(if (isRotatingHandle) Color(0xFF2196F3) else Color.White)
                        .border(2.dp, if (isRotatingHandle) Color.White else Color(0xFF2196F3), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Rotate",
                        tint = if (isRotatingHandle) Color.White else Color(0xFF2196F3),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Interactive Angle Badge Tooltip while rotating
                if (isRotatingHandle) {
                    Box(
                        modifier = Modifier
                            .offset(y = (-32).dp)
                            .background(Color(0xFF1E1E24).copy(alpha = 0.95f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF33333E), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "${currentRotation.roundToInt()}°",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3B. Stationary Touch Anchor: coordinates DO NOT change during dragging, ensuring 100% stable, jitter-free angle computation
            Box(
                modifier = Modifier
                    .offset {
                        if (!isRotatingHandle) {
                            rotationAnchorX = knobCenterX - halfKnobTouchPx
                            rotationAnchorY = knobCenterY - halfKnobTouchPx
                        }
                        IntOffset(rotationAnchorX.roundToInt(), rotationAnchorY.roundToInt())
                    }
                    .size(rotKnobTouchDp)
                    .pointerInput(image.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            isRotatingHandle = true

                            val startFingerX = rotationAnchorX + down.position.x
                            val startFingerY = rotationAnchorY + down.position.y
                            var totalMovedPx = 0f

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    isRotatingHandle = false
                                    if (totalMovedPx < 8.dp.toPx()) {
                                        // Quick tap: smoothly reset angle to 0°
                                        currentRotation = 0f
                                        val updated = image.copy(rotationDegrees = 0f)
                                        onUpdateDraft(updated)
                                        onSave(updated)
                                        Toast.makeText(context, "Angle reset to 0°", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val updated = image.copy(
                                            xRatio = currentXRatio,
                                            yRatio = currentYRatio,
                                            widthRatio = currentWidthRatio,
                                            heightRatio = currentHeightRatio,
                                            rotationDegrees = currentRotation
                                        )
                                        onUpdateDraft(updated)
                                        onSave(updated)
                                    }
                                    break
                                }
                                if (!isLocked) {
                                    val dragVector = change.position - down.position
                                    totalMovedPx = dragVector.getDistance()
                                    change.consume()

                                    val curFingerX = startFingerX + dragVector.x
                                    val curFingerY = startFingerY + dragVector.y

                                    val dx = curFingerX - imgCenterOuterX
                                    val dy = curFingerY - imgCenterOuterY

                                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                                    if (dist > 15f) {
                                        var targetDeg = Math.toDegrees(atan2(-dx.toDouble(), dy.toDouble())).toFloat()
                                        if (targetDeg < 0f) targetDeg += 360f

                                        // Magnetic snap near cardinal angles (0°, 90°, 180°, 270°) and diagonals (45°, 135°, 225°, 315°)
                                        val snappedDeg = when {
                                            abs(targetDeg - 0f) < 4.5f || abs(targetDeg - 360f) < 4.5f -> 0f
                                            abs(targetDeg - 90f) < 4.5f -> 90f
                                            abs(targetDeg - 180f) < 4.5f -> 180f
                                            abs(targetDeg - 270f) < 4.5f -> 270f
                                            abs(targetDeg - 45f) < 2.5f -> 45f
                                            abs(targetDeg - 135f) < 2.5f -> 135f
                                            abs(targetDeg - 225f) < 2.5f -> 225f
                                            abs(targetDeg - 315f) < 2.5f -> 315f
                                            else -> targetDeg
                                        }

                                        currentRotation = snappedDeg
                                        onUpdateDraft(
                                            image.copy(
                                                xRatio = currentXRatio,
                                                yRatio = currentYRatio,
                                                widthRatio = currentWidthRatio,
                                                heightRatio = currentHeightRatio,
                                                rotationDegrees = snappedDeg
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
            )
        }

        // 4. Floating Action Bar directly above the image
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 8.dp)
                    .wrapContentSize()
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24).copy(alpha = 0.98f)),
                    border = BorderStroke(1.dp, Color(0xFF33333E)),
                    modifier = Modifier.shadow(8.dp, RoundedCornerShape(20.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // 1. Crop Dialog
                        IconButton(
                            onClick = { showCropDialog = true },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Crop,
                                contentDescription = "Crop",
                                tint = if (showCropDialog) Color(0xFF2196F3) else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // 2. Corner shape / Circle indicator
                        IconButton(
                            onClick = {
                                isCircleMask = !isCircleMask
                                val updated = image.copy(isCircleCrop = isCircleMask)
                                onUpdateDraft(updated)
                                onSave(updated)
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = if (isCircleMask) Icons.Default.RadioButtonChecked else Icons.Default.CropSquare,
                                contentDescription = "Shape Mask",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // 3. Opacity Half-Moon icon
                        IconButton(
                            onClick = { showOpacitySlider = !showOpacitySlider },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Contrast,
                                contentDescription = "Opacity",
                                tint = if (showOpacitySlider) Color(0xFF2196F3) else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Opacity percentage label
                        Text(
                            text = "${(currentOpacity * 100).toInt()}%",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { showOpacitySlider = !showOpacitySlider }
                        )

                        // 4. Angle indicator (e.g. 0°) - tap to reset
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2A2A32))
                                .clickable {
                                    currentRotation = 0f
                                    val updated = image.copy(rotationDegrees = 0f)
                                    onUpdateDraft(updated)
                                    onSave(updated)
                                    Toast.makeText(context, "Angle reset to 0°", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${currentRotation.toInt()}°",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 5. Duplicate
                        IconButton(
                            onClick = onDuplicate,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Duplicate",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // 6. Delete
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // 7. Three dots (Context menu popup)
                        Box {
                            IconButton(
                                onClick = { showContextMenu = !showContextMenu },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Icons.Default.MoreHoriz,
                                    contentDescription = "More",
                                    tint = if (showContextMenu) Color(0xFF2196F3) else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Robust DropdownMenu: rendered in its own Popup window with guaranteed hit-testing
                            DropdownMenu(
                                expanded = showContextMenu,
                                onDismissRequest = { showContextMenu = false },
                                modifier = Modifier
                                    .width(210.dp)
                                    .background(Color(0xFF1E1E24))
                                    .border(1.dp, Color(0xFF33333E), RoundedCornerShape(12.dp))
                            ) {
                                // Layer Ordering Buttons Row
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(
                                        text = "LAYER ORDER",
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF2A2A34))
                                            .padding(2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        IconButton(
                                            onClick = {
                                                currentZIndex = -10
                                                val updated = image.copy(
                                                    xRatio = currentXRatio,
                                                    yRatio = currentYRatio,
                                                    widthRatio = currentWidthRatio,
                                                    heightRatio = currentHeightRatio,
                                                    rotationDegrees = currentRotation,
                                                    zIndex = -10
                                                )
                                                onUpdateDraft(updated)
                                                onSave(updated)
                                                Toast.makeText(context, "Sent to Back (behind ink, shapes & text)", Toast.LENGTH_SHORT).show()
                                                showContextMenu = false
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.VerticalAlignBottom,
                                                contentDescription = "Send to Back",
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                currentZIndex = (currentZIndex - 1).coerceAtLeast(-50)
                                                val updated = image.copy(
                                                    xRatio = currentXRatio,
                                                    yRatio = currentYRatio,
                                                    widthRatio = currentWidthRatio,
                                                    heightRatio = currentHeightRatio,
                                                    rotationDegrees = currentRotation,
                                                    zIndex = currentZIndex
                                                )
                                                onUpdateDraft(updated)
                                                onSave(updated)
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ArrowDownward,
                                                contentDescription = "Layer Down",
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                currentZIndex = (currentZIndex + 1).coerceAtMost(50)
                                                val updated = image.copy(
                                                    xRatio = currentXRatio,
                                                    yRatio = currentYRatio,
                                                    widthRatio = currentWidthRatio,
                                                    heightRatio = currentHeightRatio,
                                                    rotationDegrees = currentRotation,
                                                    zIndex = currentZIndex
                                                )
                                                onUpdateDraft(updated)
                                                onSave(updated)
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ArrowUpward,
                                                contentDescription = "Layer Up",
                                                tint = Color.LightGray,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                currentZIndex = 10
                                                val updated = image.copy(
                                                    xRatio = currentXRatio,
                                                    yRatio = currentYRatio,
                                                    widthRatio = currentWidthRatio,
                                                    heightRatio = currentHeightRatio,
                                                    rotationDegrees = currentRotation,
                                                    zIndex = 10
                                                )
                                                onUpdateDraft(updated)
                                                onSave(updated)
                                                Toast.makeText(context, "Brought to Front (above ink, shapes & text)", Toast.LENGTH_SHORT).show()
                                                showContextMenu = false
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.VerticalAlignTop,
                                                contentDescription = "Bring to Front",
                                                tint = Color(0xFF2196F3),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(color = Color(0xFF2F2F38), modifier = Modifier.padding(vertical = 4.dp))

                                // Aspect ratio lock
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = isAspectLocked,
                                                onCheckedChange = {
                                                    isAspectLocked = it
                                                    val updated = image.copy(keepAspectRatio = it)
                                                    onUpdateDraft(updated)
                                                    onSave(updated)
                                                },
                                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF2196F3)),
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Keep aspect ratio", color = Color.White, fontSize = 12.sp)
                                        }
                                    },
                                    onClick = {
                                        isAspectLocked = !isAspectLocked
                                        val updated = image.copy(keepAspectRatio = isAspectLocked)
                                        onUpdateDraft(updated)
                                        onSave(updated)
                                    }
                                )

                                HorizontalDivider(color = Color(0xFF2F2F38), modifier = Modifier.padding(vertical = 4.dp))

                                // Copy
                                DropdownMenuItem(
                                    text = { Text("Copy", color = Color.White, fontSize = 13.sp) },
                                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp)) },
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Image", image.filePath))
                                        Toast.makeText(context, "Image copied", Toast.LENGTH_SHORT).show()
                                        showContextMenu = false
                                    }
                                )

                                // Cut
                                DropdownMenuItem(
                                    text = { Text("Cut", color = Color.White, fontSize = 13.sp) },
                                    leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp)) },
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Image", image.filePath))
                                        onDelete()
                                        Toast.makeText(context, "Image cut", Toast.LENGTH_SHORT).show()
                                        showContextMenu = false
                                    }
                                )

                                // Lock / Pin
                                DropdownMenuItem(
                                    text = { Text(if (isLocked) "Unlock" else "Lock in place", color = Color.White, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(
                                            if (isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = Color.LightGray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    onClick = {
                                        isLocked = !isLocked
                                        val updated = image.copy(isLocked = isLocked)
                                        onUpdateDraft(updated)
                                        onSave(updated)
                                        Toast.makeText(context, if (isLocked) "Image locked in place" else "Image unlocked", Toast.LENGTH_SHORT).show()
                                        showContextMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Opacity slider popup
            if (showOpacitySlider) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24)),
                    border = BorderStroke(1.dp, Color(0xFF33333E)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = 50.dp)
                        .width(220.dp)
                        .shadow(8.dp, RoundedCornerShape(12.dp))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Opacity", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("${(currentOpacity * 100).toInt()}%", color = Color(0xFF2196F3), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = currentOpacity,
                            onValueChange = {
                                currentOpacity = it
                                onUpdateDraft(image.copy(opacity = it))
                            },
                            onValueChangeFinished = {
                                onSave(image.copy(opacity = currentOpacity))
                            },
                            valueRange = 0.10f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF2196F3),
                                activeTrackColor = Color(0xFF2196F3),
                                inactiveTrackColor = Color(0xFF33333E)
                            )
                        )
                    }
                }
            }
        }
    }
}
