package com.graphite.app.feature.workspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.graphite.app.feature.workspace.model.CanvasImageElement
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

@Composable
fun CanvasImageCropDialog(
    image: CanvasImageElement,
    onDismiss: () -> Unit,
    onApplyCrop: (newFilePath: String, newWidthRatio: Float, newHeightRatio: Float) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val originalBitmap = remember(image.filePath) {
        try {
            val file = File(image.filePath)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        } catch (_: Exception) {
            null
        }
    }

    if (originalBitmap == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, "Cannot open image to crop", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
        return
    }

    // Relative crop margins inside the image: 0.0 to 1.0
    var leftCropRatio by remember { mutableFloatStateOf(0.05f) }
    var topCropRatio by remember { mutableFloatStateOf(0.05f) }
    var rightCropRatio by remember { mutableFloatStateOf(0.05f) }
    var bottomCropRatio by remember { mutableFloatStateOf(0.05f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
            border = BorderStroke(1.dp, Color(0xFF2C2C36)),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Crop,
                            contentDescription = null,
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Crop Image",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.LightGray)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Interactive Crop Area
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0D0D10)),
                    contentAlignment = Alignment.Center
                ) {
                    val maxW = maxWidth
                    val maxH = maxHeight

                    val imgW = originalBitmap.width.toFloat()
                    val imgH = originalBitmap.height.toFloat()

                    val containerAspect = maxW.value / maxH.value.coerceAtLeast(1f)
                    val imgAspect = imgW / imgH.coerceAtLeast(1f)

                    val dispWidth = if (imgAspect > containerAspect) maxW else maxH * imgAspect
                    val dispHeight = if (imgAspect > containerAspect) maxW / imgAspect else maxH

                    val dispWidthPx = with(density) { dispWidth.toPx() }
                    val dispHeightPx = with(density) { dispHeight.toPx() }

                    Box(
                        modifier = Modifier
                            .size(dispWidth, dispHeight)
                            .border(1.dp, Color(0xFF33333E))
                    ) {
                        // Original preview
                        androidx.compose.foundation.Image(
                            bitmap = originalBitmap.asImageBitmap(),
                            contentDescription = "Original",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Darkened semi-transparent overlay outside crop box
                        // Top
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(dispHeight * topCropRatio)
                                .align(Alignment.TopCenter)
                                .background(Color.Black.copy(alpha = 0.55f))
                        )
                        // Bottom
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(dispHeight * bottomCropRatio)
                                .align(Alignment.BottomCenter)
                                .background(Color.Black.copy(alpha = 0.55f))
                        )
                        // Left
                        Box(
                            modifier = Modifier
                                .width(dispWidth * leftCropRatio)
                                .fillMaxHeight()
                                .align(Alignment.CenterStart)
                                .background(Color.Black.copy(alpha = 0.55f))
                        )
                        // Right
                        Box(
                            modifier = Modifier
                                .width(dispWidth * rightCropRatio)
                                .fillMaxHeight()
                                .align(Alignment.CenterEnd)
                                .background(Color.Black.copy(alpha = 0.55f))
                        )

                        // Crop Boundary Box with corner handles
                        val cropLeft = dispWidthPx * leftCropRatio
                        val cropTop = dispHeightPx * topCropRatio
                        val cropW = (dispWidthPx * (1f - leftCropRatio - rightCropRatio)).coerceAtLeast(40f)
                        val cropH = (dispHeightPx * (1f - topCropRatio - bottomCropRatio)).coerceAtLeast(40f)

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(cropLeft.roundToInt(), cropTop.roundToInt()) }
                                .size(
                                    width = with(density) { cropW.toDp() },
                                    height = with(density) { cropH.toDp() }
                                )
                                .border(2.dp, Color(0xFF2196F3))
                        )

                        // Draggable Corner handles
                        val handleSize = 22.dp

                        // Top-Left Handle
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        (cropLeft - with(density) { (handleSize / 2).toPx() }).roundToInt(),
                                        (cropTop - with(density) { (handleSize / 2).toPx() }).roundToInt()
                                    )
                                }
                                .size(handleSize)
                                .clip(CircleShape)
                                .background(Color(0xFF2196F3))
                                .border(2.dp, Color.White, CircleShape)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        leftCropRatio = (leftCropRatio + dragAmount.x / dispWidthPx).coerceIn(0f, 0.85f - rightCropRatio)
                                        topCropRatio = (topCropRatio + dragAmount.y / dispHeightPx).coerceIn(0f, 0.85f - bottomCropRatio)
                                    }
                                }
                        )

                        // Bottom-Right Handle
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        (cropLeft + cropW - with(density) { (handleSize / 2).toPx() }).roundToInt(),
                                        (cropTop + cropH - with(density) { (handleSize / 2).toPx() }).roundToInt()
                                    )
                                }
                                .size(handleSize)
                                .clip(CircleShape)
                                .background(Color(0xFF2196F3))
                                .border(2.dp, Color.White, CircleShape)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        rightCropRatio = (rightCropRatio - dragAmount.x / dispWidthPx).coerceIn(0f, 0.85f - leftCropRatio)
                                        bottomCropRatio = (bottomCropRatio - dragAmount.y / dispHeightPx).coerceIn(0f, 0.85f - topCropRatio)
                                    }
                                }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Quick presets (Reset, Square 1:1, 4:3, 16:9)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            leftCropRatio = 0f
                            topCropRatio = 0f
                            rightCropRatio = 0f
                            bottomCropRatio = 0f
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282830)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Reset", color = Color.White, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            val margin = 0.12f
                            leftCropRatio = margin
                            rightCropRatio = margin
                            topCropRatio = margin
                            bottomCropRatio = margin
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282830)),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Center", color = Color.White, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Apply Button
                Button(
                    onClick = {
                        try {
                            val origW = originalBitmap.width
                            val origH = originalBitmap.height

                            val startX = (origW * leftCropRatio).toInt().coerceIn(0, origW - 1)
                            val startY = (origH * topCropRatio).toInt().coerceIn(0, origH - 1)
                            val endX = (origW * (1f - rightCropRatio)).toInt().coerceIn(startX + 1, origW)
                            val endY = (origH * (1f - bottomCropRatio)).toInt().coerceIn(startY + 1, origH)

                            val cropW = (endX - startX).coerceAtLeast(10)
                            val cropH = (endY - startY).coerceAtLeast(10)

                            val croppedBitmap = Bitmap.createBitmap(originalBitmap, startX, startY, cropW, cropH)
                            val imagesDir = File(context.filesDir, "notebook_images").apply { mkdirs() }
                            val targetFile = File(imagesDir, "crop_${System.currentTimeMillis()}.jpg")

                            FileOutputStream(targetFile).use { fos ->
                                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                            }

                            // Compute updated dimension ratios to maintain proper visual size
                            val widthFactor = cropW.toFloat() / origW.toFloat()
                            val heightFactor = cropH.toFloat() / origH.toFloat()
                            val newW = (image.widthRatio * widthFactor).coerceIn(0.12f, 0.95f)
                            val newH = (image.heightRatio * heightFactor).coerceIn(0.12f, 0.95f)

                            onApplyCrop(targetFile.absolutePath, newW, newH)
                            Toast.makeText(context, "Image cropped successfully", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error cropping: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply Crop", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
