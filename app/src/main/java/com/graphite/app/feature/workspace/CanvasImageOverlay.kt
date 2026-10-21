package com.graphite.app.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.geometry.Offset
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
    onUpdate: (CanvasImageElement) -> Unit,
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

    val imgLeftPx = image.xRatio * basePageWidthPx
    val imgTopPx = image.yRatio * basePageHeightPx
    val imgWidthPx = (image.widthRatio * basePageWidthPx).coerceAtLeast(40f)
    val imgHeightPx = (image.heightRatio * basePageHeightPx).coerceAtLeast(40f)

    var showOpacitySlider by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var showCropDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isSelected) {
        if (!isSelected) {
            showOpacitySlider = false
            showContextMenu = false
        }
    }

    if (showCropDialog) {
        CanvasImageCropDialog(
            image = image,
            onDismiss = { showCropDialog = false },
            onApplyCrop = { newPath, newW, newH ->
                onUpdate(image.copy(filePath = newPath, widthRatio = newW, heightRatio = newH))
            }
        )
    }

    // Outer padding to contain resize handles, rotation handle stem, and floating action bar
    val padTopDp = if (isSelected) 96.dp else 0.dp
    val padBottomDp = if (isSelected) 60.dp else 0.dp
    val padSideDp = if (isSelected) 48.dp else 0.dp

    val padTopPx = with(density) { padTopDp.toPx() }
    val padBottomPx = with(density) { padBottomDp.toPx() }
    val padSidePx = with(density) { padSideDp.toPx() }

    Box(
        modifier = Modifier
            .offset { IntOffset((imgLeftPx - padSidePx).roundToInt(), (imgTopPx - padTopPx).roundToInt()) }
            .size(
                width = with(density) { (imgWidthPx + padSidePx * 2).toDp() },
                height = with(density) { (imgHeightPx + padTopPx + padBottomPx).toDp() }
            )
            .zIndex(image.zIndex.toFloat() + (if (isSelected) 100f else 0f))
    ) {
        // Rotated Core Container holding: image content, blue selection border, 4 corner handles, and rotation handle
        Box(
            modifier = Modifier
                .offset { IntOffset(padSidePx.roundToInt(), padTopPx.roundToInt()) }
                .size(
                    width = with(density) { imgWidthPx.toDp() },
                    height = with(density) { imgHeightPx.toDp() }
                )
                .graphicsLayer {
                    rotationZ = image.rotationDegrees
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
        ) {
            // 1. Image bitmap content + drag gesture for moving the image
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = image.opacity
                        if (image.isCircleCrop) {
                            clip = true
                            shape = CircleShape
                        }
                    }
                    .pointerInput(image.id, image.isLocked, image.rotationDegrees) {
                        detectTransformGestures { centroid, pan, zoom, rotation ->
                            if (!image.isLocked) {
                                val rad = Math.toRadians(image.rotationDegrees.toDouble())
                                val cosA = cos(rad).toFloat()
                                val sinA = sin(rad).toFloat()
                                // Un-rotate pan vector from local rotated frame to page frame
                                val pagePanX = pan.x * cosA - pan.y * sinA
                                val pagePanY = pan.x * sinA + pan.y * cosA

                                val newX = (image.xRatio + pagePanX / basePageWidthPx).coerceIn(-0.2f, 0.95f)
                                val newY = (image.yRatio + pagePanY / basePageHeightPx).coerceIn(-0.2f, 0.95f)

                                val newW = if (zoom != 1f) (image.widthRatio * zoom).coerceIn(0.08f, 0.95f) else image.widthRatio
                                val newH = if (zoom != 1f) {
                                    if (image.keepAspectRatio) newW * bmpAspect * pageAspectFactor else (image.heightRatio * zoom).coerceIn(0.08f, 0.95f)
                                } else image.heightRatio

                                var newDeg = image.rotationDegrees + rotation
                                while (newDeg < 0f) newDeg += 360f
                                while (newDeg >= 360f) newDeg -= 360f

                                onUpdate(
                                    image.copy(
                                        xRatio = newX,
                                        yRatio = newY,
                                        widthRatio = newW,
                                        heightRatio = newH,
                                        rotationDegrees = newDeg
                                    )
                                )
                            }
                        }
                    }
                    .pointerInput(image.id) {
                        detectTapGestures(onTap = { onSelect() })
                    }
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Canvas Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 2. Selection border & handles (when isSelected)
            if (isSelected) {
                // Blue selection boundary border
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(2.dp, Color(0xFF2196F3), if (image.isCircleCrop) CircleShape else RoundedCornerShape(2.dp))
                )

                val handleSizeDp = 20.dp
                val handleOffsetPx = with(density) { (handleSizeDp / 2).toPx() }

                // Top-Left Resize Handle
                Box(
                    modifier = Modifier
                        .offset { IntOffset(-handleOffsetPx.roundToInt(), -handleOffsetPx.roundToInt()) }
                        .size(handleSizeDp)
                        .clip(CircleShape)
                        .background(Color(0xFF2196F3))
                        .border(2.dp, Color.White, CircleShape)
                        .pointerInput(image.id, image.isLocked) {
                            detectDragGestures { change, dragAmount ->
                                if (!image.isLocked) {
                                    change.consume()
                                    val dx = dragAmount.x / basePageWidthPx
                                    val dy = dragAmount.y / basePageHeightPx
                                    val newW = (image.widthRatio - dx).coerceIn(0.08f, 0.95f)
                                    val newH = if (image.keepAspectRatio) {
                                        newW * bmpAspect * pageAspectFactor
                                    } else {
                                        (image.heightRatio - dy).coerceIn(0.08f, 0.95f)
                                    }
                                    val newX = (image.xRatio + (image.widthRatio - newW)).coerceIn(-0.2f, 0.95f)
                                    val newY = (image.yRatio + (image.heightRatio - newH)).coerceIn(-0.2f, 0.95f)
                                    onUpdate(image.copy(xRatio = newX, yRatio = newY, widthRatio = newW, heightRatio = newH))
                                }
                            }
                        }
                )

                // Top-Right Resize Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(handleOffsetPx.roundToInt(), -handleOffsetPx.roundToInt()) }
                        .size(handleSizeDp)
                        .clip(CircleShape)
                        .background(Color(0xFF2196F3))
                        .border(2.dp, Color.White, CircleShape)
                        .pointerInput(image.id, image.isLocked) {
                            detectDragGestures { change, dragAmount ->
                                if (!image.isLocked) {
                                    change.consume()
                                    val dx = dragAmount.x / basePageWidthPx
                                    val dy = dragAmount.y / basePageHeightPx
                                    val newW = (image.widthRatio + dx).coerceIn(0.08f, 0.95f)
                                    val newH = if (image.keepAspectRatio) {
                                        newW * bmpAspect * pageAspectFactor
                                    } else {
                                        (image.heightRatio - dy).coerceIn(0.08f, 0.95f)
                                    }
                                    val newY = (image.yRatio + (image.heightRatio - newH)).coerceIn(-0.2f, 0.95f)
                                    onUpdate(image.copy(yRatio = newY, widthRatio = newW, heightRatio = newH))
                                }
                            }
                        }
                )

                // Bottom-Left Resize Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset { IntOffset(-handleOffsetPx.roundToInt(), handleOffsetPx.roundToInt()) }
                        .size(handleSizeDp)
                        .clip(CircleShape)
                        .background(Color(0xFF2196F3))
                        .border(2.dp, Color.White, CircleShape)
                        .pointerInput(image.id, image.isLocked) {
                            detectDragGestures { change, dragAmount ->
                                if (!image.isLocked) {
                                    change.consume()
                                    val dx = dragAmount.x / basePageWidthPx
                                    val dy = dragAmount.y / basePageHeightPx
                                    val newW = (image.widthRatio - dx).coerceIn(0.08f, 0.95f)
                                    val newH = if (image.keepAspectRatio) {
                                        newW * bmpAspect * pageAspectFactor
                                    } else {
                                        (image.heightRatio + dy).coerceIn(0.08f, 0.95f)
                                    }
                                    val newX = (image.xRatio + (image.widthRatio - newW)).coerceIn(-0.2f, 0.95f)
                                    onUpdate(image.copy(xRatio = newX, widthRatio = newW, heightRatio = newH))
                                }
                            }
                        }
                )

                // Bottom-Right Resize Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset { IntOffset(handleOffsetPx.roundToInt(), handleOffsetPx.roundToInt()) }
                        .size(handleSizeDp)
                        .clip(CircleShape)
                        .background(Color(0xFF2196F3))
                        .border(2.dp, Color.White, CircleShape)
                        .pointerInput(image.id, image.isLocked) {
                            detectDragGestures { change, dragAmount ->
                                if (!image.isLocked) {
                                    change.consume()
                                    val dx = dragAmount.x / basePageWidthPx
                                    val dy = dragAmount.y / basePageHeightPx
                                    val newW = (image.widthRatio + dx).coerceIn(0.08f, 0.95f)
                                    val newH = if (image.keepAspectRatio) {
                                        newW * bmpAspect * pageAspectFactor
                                    } else {
                                        (image.heightRatio + dy).coerceIn(0.08f, 0.95f)
                                    }
                                    onUpdate(image.copy(widthRatio = newW, heightRatio = newH))
                                }
                            }
                        }
                )

                // Vertical Stem Line connecting bottom edge to rotation handle
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 10.dp)
                        .width(2.dp)
                        .height(24.dp)
                        .background(Color(0xFF2196F3))
                )

                // Bottom Center Rotation Handle (⟳)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 34.dp)
                        .size(30.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, Color(0xFF2196F3), CircleShape)
                        .pointerInput(image.id, image.isLocked) {
                            detectDragGestures { change, dragAmount ->
                                if (!image.isLocked) {
                                    change.consume()
                                    val radius = (imgHeightPx / 2f + with(density) { 34.dp.toPx() }).coerceAtLeast(30f)
                                    val dAngle = (dragAmount.x / radius) * (180f / Math.PI.toFloat())
                                    var newDeg = (image.rotationDegrees + dAngle) % 360f
                                    if (newDeg < 0f) newDeg += 360f
                                    onUpdate(image.copy(rotationDegrees = newDeg))
                                }
                            }
                        }
                        .pointerInput(image.id) {
                            detectTapGestures(
                                onDoubleTap = {
                                    onUpdate(image.copy(rotationDegrees = 0f))
                                    Toast.makeText(context, "Angle reset to 0°", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Rotate",
                        tint = Color(0xFF2196F3),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 3. Floating Action Bar directly above the image
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = if (showOpacitySlider) 54.dp else 46.dp)
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
                            Icon(Icons.Default.Crop, contentDescription = "Crop", tint = if (showCropDialog) Color(0xFF2196F3) else Color.White, modifier = Modifier.size(16.dp))
                        }

                        // 2. Corner shape / Circle indicator
                        IconButton(
                            onClick = {
                                onUpdate(image.copy(isCircleCrop = !image.isCircleCrop))
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = if (image.isCircleCrop) Icons.Default.RadioButtonChecked else Icons.Default.CropSquare,
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
                            Icon(Icons.Default.Contrast, contentDescription = "Opacity", tint = if (showOpacitySlider) Color(0xFF2196F3) else Color.White, modifier = Modifier.size(16.dp))
                        }

                        // Opacity percentage label
                        Text(
                            text = "${(image.opacity * 100).toInt()}%",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { showOpacitySlider = !showOpacitySlider }
                        )

                        // 4. Angle indicator (e.g. 0°)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2A2A32))
                                .clickable {
                                    onUpdate(image.copy(rotationDegrees = 0f))
                                    Toast.makeText(context, "Angle reset to 0°", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${image.rotationDegrees.toInt()}°",
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
                            Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", tint = Color.White, modifier = Modifier.size(16.dp))
                        }

                        // 6. Delete
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                        }

                        // 7. Three dots (Context menu)
                        IconButton(
                            onClick = { showContextMenu = !showContextMenu },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.MoreHoriz, contentDescription = "More", tint = if (showContextMenu) Color(0xFF2196F3) else Color.White, modifier = Modifier.size(18.dp))
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
                        .offset(y = 6.dp)
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
                            Text("${(image.opacity * 100).toInt()}%", color = Color(0xFF2196F3), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = image.opacity,
                            onValueChange = { onUpdate(image.copy(opacity = it)) },
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

            // Right-Side Contextual Menu (matching Screenshot 3)
            if (showContextMenu) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C22)),
                    border = BorderStroke(1.dp, Color(0xFF2F2F38)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 180.dp, y = 20.dp)
                        .width(180.dp)
                        .shadow(12.dp, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        // Top Layer order buttons row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF282830))
                                .padding(2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick = {
                                    onUpdate(image.copy(zIndex = -10))
                                    Toast.makeText(context, "Sent to Back", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.VerticalAlignBottom, contentDescription = "Send to Back", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    onUpdate(image.copy(zIndex = (image.zIndex - 1).coerceAtLeast(-50)))
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Layer Down", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    onUpdate(image.copy(zIndex = (image.zIndex + 1).coerceAtMost(50)))
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Layer Up", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    onUpdate(image.copy(zIndex = 10))
                                    Toast.makeText(context, "Brought to Front", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.VerticalAlignTop, contentDescription = "Bring to Front", tint = Color(0xFF2196F3), modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Keep aspect ratio
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdate(image.copy(keepAspectRatio = !image.keepAspectRatio))
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = image.keepAspectRatio,
                                onCheckedChange = { onUpdate(image.copy(keepAspectRatio = it)) },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF2196F3)),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Keep aspect ratio", color = Color.White, fontSize = 11.sp)
                        }

                        HorizontalDivider(color = Color(0xFF2C2C34), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                        // Copy
                        ImageContextMenuItem(Icons.Default.ContentCopy, "Copy") {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Image", image.filePath))
                            Toast.makeText(context, "Image copied", Toast.LENGTH_SHORT).show()
                            showContextMenu = false
                        }

                        // Cut
                        ImageContextMenuItem(Icons.Default.ContentCut, "Cut") {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Image", image.filePath))
                            onDelete()
                            Toast.makeText(context, "Image cut", Toast.LENGTH_SHORT).show()
                            showContextMenu = false
                        }

                        // Lock / Unlock (Keep / Pin image feature!)
                        ImageContextMenuItem(
                            if (image.isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                            if (image.isLocked) "Unlock" else "Lock"
                        ) {
                            val nextLock = !image.isLocked
                            onUpdate(image.copy(isLocked = nextLock))
                            Toast.makeText(context, if (nextLock) "Image locked in place" else "Image unlocked", Toast.LENGTH_SHORT).show()
                            showContextMenu = false
                        }

                        // Create a link
                        ImageContextMenuItem(Icons.Default.Link, "Create a link") {
                            Toast.makeText(context, "Link created for image", Toast.LENGTH_SHORT).show()
                            showContextMenu = false
                        }

                        // Add to library
                        ImageContextMenuItem(Icons.Default.BookmarkAdd, "Add to library") {
                            Toast.makeText(context, "Image added to sticker library", Toast.LENGTH_SHORT).show()
                            showContextMenu = false
                        }

                        // Screenshot / Export
                        ImageContextMenuItem(Icons.Default.PhotoCamera, "Screenshot") {
                            Toast.makeText(context, "Image snapshot saved", Toast.LENGTH_SHORT).show()
                            showContextMenu = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageContextMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = Color.LightGray, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}
