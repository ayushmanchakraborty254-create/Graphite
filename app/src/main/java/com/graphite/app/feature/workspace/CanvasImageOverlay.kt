package com.graphite.app.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
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
    pageScale: Float,
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

    val imgLeftPx = image.xRatio * basePageWidthPx * pageScale
    val imgTopPx = image.yRatio * basePageHeightPx * pageScale
    val imgWidthPx = (image.widthRatio * basePageWidthPx * pageScale).coerceAtLeast(40f)
    val imgHeightPx = (image.heightRatio * basePageHeightPx * pageScale).coerceAtLeast(40f)

    var showOpacitySlider by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }

    // Root wrapper
    Box(
        modifier = Modifier
            .offset { IntOffset(imgLeftPx.roundToInt(), imgTopPx.roundToInt()) }
            .size(
                width = with(density) { imgWidthPx.toDp() },
                height = with(density) { imgHeightPx.toDp() }
            )
            .zIndex(image.zIndex.toFloat() + (if (isSelected) 50f else 0f))
    ) {
        // Image content with rotation, opacity, and mask
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = image.rotationDegrees
                    alpha = image.opacity
                    if (image.isCircleCrop) {
                        clip = true
                        shape = CircleShape
                    }
                }
                .pointerInput(image.id, image.isLocked) {
                    detectDragGestures(
                        onDragStart = { onSelect() },
                        onDrag = { change, dragAmount ->
                            if (!image.isLocked) {
                                change.consume()
                                val newX = (image.xRatio + dragAmount.x / (basePageWidthPx * pageScale)).coerceIn(0f, 0.90f)
                                val newY = (image.yRatio + dragAmount.y / (basePageHeightPx * pageScale)).coerceIn(0f, 0.90f)
                                onUpdate(image.copy(xRatio = newX, yRatio = newY))
                            }
                        }
                    )
                }
                .clickable { onSelect() }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Canvas Image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Selection boundary & handles (Active when isSelected)
        if (isSelected) {
            // Blue bounding box border
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = image.rotationDegrees }
                    .border(2.dp, Color(0xFF2196F3), if (image.isCircleCrop) CircleShape else RoundedCornerShape(2.dp))
            )

            // 4 Corner Resize Handles
            val handleSizeDp = 18.dp
            val handleOffsetPx = with(density) { (handleSizeDp / 2).toPx() }

            // Top-Left Handle
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
                                val dxRatio = dragAmount.x / (basePageWidthPx * pageScale)
                                val dyRatio = dragAmount.y / (basePageHeightPx * pageScale)
                                val newW = (image.widthRatio - dxRatio).coerceIn(0.12f, 0.95f)
                                val newH = if (image.keepAspectRatio) {
                                    newW * (bitmap.height.toFloat() / bitmap.width.toFloat())
                                } else {
                                    (image.heightRatio - dyRatio).coerceIn(0.12f, 0.95f)
                                }
                                val newX = (image.xRatio + dxRatio).coerceIn(0f, 0.90f)
                                val newY = (image.yRatio + dyRatio).coerceIn(0f, 0.90f)
                                onUpdate(image.copy(xRatio = newX, yRatio = newY, widthRatio = newW, heightRatio = newH))
                            }
                        }
                    }
            )

            // Top-Right Handle
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
                                val dxRatio = dragAmount.x / (basePageWidthPx * pageScale)
                                val newW = (image.widthRatio + dxRatio).coerceIn(0.12f, 0.95f)
                                val newH = if (image.keepAspectRatio) {
                                    newW * (bitmap.height.toFloat() / bitmap.width.toFloat())
                                } else {
                                    (image.heightRatio - (dragAmount.y / (basePageHeightPx * pageScale))).coerceIn(0.12f, 0.95f)
                                }
                                onUpdate(image.copy(widthRatio = newW, heightRatio = newH))
                            }
                        }
                    }
            )

            // Bottom-Left Handle
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
                                val dxRatio = dragAmount.x / (basePageWidthPx * pageScale)
                                val newW = (image.widthRatio - dxRatio).coerceIn(0.12f, 0.95f)
                                val newH = if (image.keepAspectRatio) {
                                    newW * (bitmap.height.toFloat() / bitmap.width.toFloat())
                                } else {
                                    (image.heightRatio + (dragAmount.y / (basePageHeightPx * pageScale))).coerceIn(0.12f, 0.95f)
                                }
                                val newX = (image.xRatio + dxRatio).coerceIn(0f, 0.90f)
                                onUpdate(image.copy(xRatio = newX, widthRatio = newW, heightRatio = newH))
                            }
                        }
                    }
            )

            // Bottom-Right Handle
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
                                val dxRatio = dragAmount.x / (basePageWidthPx * pageScale)
                                val newW = (image.widthRatio + dxRatio).coerceIn(0.12f, 0.95f)
                                val newH = if (image.keepAspectRatio) {
                                    newW * (bitmap.height.toFloat() / bitmap.width.toFloat())
                                } else {
                                    (image.heightRatio + (dragAmount.y / (basePageHeightPx * pageScale))).coerceIn(0.12f, 0.95f)
                                }
                                onUpdate(image.copy(widthRatio = newW, heightRatio = newH))
                            }
                        }
                    }
            )

            // Bottom Center Rotation Handle (⟳) matching Screenshot 3
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 38.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, Color(0xFF2196F3), CircleShape)
                    .pointerInput(image.id, image.isLocked) {
                        detectDragGestures { change, dragAmount ->
                            if (!image.isLocked) {
                                change.consume()
                                val centerX = imgWidthPx / 2f
                                val centerY = imgHeightPx / 2f
                                val curPos = change.position
                                val angleRad = atan2((curPos.y - centerY).toDouble(), (curPos.x - centerX).toDouble())
                                var deg = Math.toDegrees(angleRad).toFloat() + 90f
                                while (deg < 0f) deg += 360f
                                while (deg >= 360f) deg -= 360f
                                onUpdate(image.copy(rotationDegrees = deg))
                            }
                        }
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

            // Floating Action Bar directly above the image (matching Screenshot 3)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-48).dp)
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
                        // 1. Crop / Circle Mask
                        IconButton(
                            onClick = {
                                onUpdate(image.copy(isCircleCrop = !image.isCircleCrop))
                                Toast.makeText(context, if (!image.isCircleCrop) "Circle mask applied" else "Rectangle restored", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.Crop, contentDescription = "Crop", tint = if (image.isCircleCrop) Color(0xFF2196F3) else Color.White, modifier = Modifier.size(16.dp))
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
                        .offset(y = (-92).dp)
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
                        .offset(x = 190.dp, y = 0.dp)
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
