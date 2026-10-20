package com.graphite.app.feature.noteshelf

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.feature.workspace.model.PaperStyle

data class TemplateOption(
    val style: PaperStyle,
    val title: String,
    val category: String // "Basic", "Education", "Music"
)

val AllTemplateOptions = listOf(
    // Basic
    TemplateOption(PaperStyle.BLANK, "Blank", "Basic"),
    TemplateOption(PaperStyle.RULED, "Rule", "Basic"),
    TemplateOption(PaperStyle.GRID, "Grid", "Basic"),
    TemplateOption(PaperStyle.DOTTED, "Dot", "Basic"),
    // Education
    TemplateOption(PaperStyle.HEXAGONAL_GRID, "Hexagonal Grid", "Education"),
    TemplateOption(PaperStyle.CORNELL_A, "Cornell A", "Education"),
    TemplateOption(PaperStyle.CORNELL_B, "Cornell B", "Education"),
    TemplateOption(PaperStyle.LEGAL, "Legal", "Education"),
    TemplateOption(PaperStyle.ENGINEERING_GRID, "Engineering Grid", "Education"),
    // Music
    TemplateOption(PaperStyle.MUSIC_SCORE, "Music Score", "Music"),
    TemplateOption(PaperStyle.GUITAR_TABLATURE, "Guitar Tablature", "Music"),
    TemplateOption(PaperStyle.GUITAR_SCORE, "Guitar Score", "Music")
)

@Composable
fun PageTemplateDialog(
    selectedStyle: PaperStyle,
    onSelectStyle: (PaperStyle) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedColorIndex by remember { mutableIntStateOf(0) }
    var densityValue by remember { mutableFloatStateOf(3f) }
    var lineWidthValue by remember { mutableFloatStateOf(2f) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0=Built-in, 1=Custom

    val paperColors = listOf(
        Color(0xFFFFFFFF), // White
        Color(0xFFFFF9E6), // Cream
        Color(0xFFDCE6EC), // Slate Blue
        Color(0xFF2C3238), // Dark Slate
        Color(0xFF181A1D)  // Black
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .clickable(enabled = false) {}
                .shadow(16.dp, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2024))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header: Template title, close icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Template", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Built-in / Custom toggle + Size indicator (A4)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF2D2E33))
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (selectedTab == 0) Color(0xFF00BCD4) else Color.Transparent)
                                .clickable { selectedTab = 0 }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("✓ Built-in", color = if (selectedTab == 0) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (selectedTab == 1) Color(0xFF00BCD4) else Color.Transparent)
                                .clickable { selectedTab = 1 }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Custom", color = if (selectedTab == 1) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text("Size: A4 (2480 x 3508) ∨", color = Color.LightGray, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Paper Color Circles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    paperColors.forEachIndexed { idx, col ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(col)
                                .border(
                                    width = if (selectedColorIndex == idx) 2.5.dp else 1.dp,
                                    color = if (selectedColorIndex == idx) Color(0xFF00BCD4) else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { selectedColorIndex = idx }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Sliders: Density & Line width (1-5)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text("Density", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(60.dp))
                        Slider(
                            value = densityValue,
                            onValueChange = { densityValue = it },
                            valueRange = 1f..5f,
                            steps = 3,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF00BCD4), activeTrackColor = Color(0xFF00BCD4))
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text("Line width", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.width(68.dp))
                        Slider(
                            value = lineWidthValue,
                            onValueChange = { lineWidthValue = it },
                            valueRange = 1f..5f,
                            steps = 3,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF00BCD4), activeTrackColor = Color(0xFF00BCD4))
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF333339), modifier = Modifier.padding(vertical = 10.dp))

                // Templates Grid categorized (Basic, Education, Music)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 75.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(AllTemplateOptions) { option ->
                        val isPicked = selectedStyle == option.style
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onSelectStyle(option.style) }
                                .padding(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 68.dp, height = 90.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(paperColors[selectedColorIndex])
                                    .border(
                                        width = if (isPicked) 2.dp else 1.dp,
                                        color = if (isPicked) Color(0xFF00BCD4) else Color(0xFF44444A),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                            ) {
                                TemplateMiniPreview(style = option.style)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = option.title,
                                color = if (isPicked) Color(0xFF00BCD4) else Color.LightGray,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom button: Apply to current page
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BCD4)),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Text("Apply to current page", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun TemplateMiniPreview(style: PaperStyle) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val lineCol = Color.Gray.copy(alpha = 0.4f)
        when (style) {
            PaperStyle.BLANK -> {}
            PaperStyle.RULED -> {
                for (y in 15..h.toInt() - 10 step 14) {
                    drawLine(lineCol, androidx.compose.ui.geometry.Offset(4f, y.toFloat()), androidx.compose.ui.geometry.Offset(w - 4f, y.toFloat()), 1f)
                }
            }
            PaperStyle.GRID, PaperStyle.ENGINEERING_GRID -> {
                for (x in 6..w.toInt() - 4 step 10) {
                    drawLine(lineCol, androidx.compose.ui.geometry.Offset(x.toFloat(), 4f), androidx.compose.ui.geometry.Offset(x.toFloat(), h - 4f), 0.8f)
                }
                for (y in 6..h.toInt() - 4 step 10) {
                    drawLine(lineCol, androidx.compose.ui.geometry.Offset(4f, y.toFloat()), androidx.compose.ui.geometry.Offset(w - 4f, y.toFloat()), 0.8f)
                }
            }
            PaperStyle.DOTTED, PaperStyle.HEXAGONAL_GRID -> {
                for (x in 8..w.toInt() - 6 step 12) {
                    for (y in 8..h.toInt() - 6 step 12) {
                        drawCircle(lineCol, 1.2f, androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat()))
                    }
                }
            }
            PaperStyle.CORNELL_A -> {
                drawLine(lineCol, androidx.compose.ui.geometry.Offset(w * 0.32f, 4f), androidx.compose.ui.geometry.Offset(w * 0.32f, h - 18f), 1.2f)
                drawLine(lineCol, androidx.compose.ui.geometry.Offset(4f, h - 18f), androidx.compose.ui.geometry.Offset(w - 4f, h - 18f), 1.2f)
            }
            PaperStyle.CORNELL_B -> {
                drawLine(lineCol, androidx.compose.ui.geometry.Offset(4f, 18f), androidx.compose.ui.geometry.Offset(w - 4f, 18f), 1.2f)
                drawLine(lineCol, androidx.compose.ui.geometry.Offset(w * 0.3f, 18f), androidx.compose.ui.geometry.Offset(w * 0.3f, h - 4f), 1.2f)
            }
            PaperStyle.LEGAL -> {
                drawLine(Color(0xFFEF5350).copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(w * 0.22f, 4f), androidx.compose.ui.geometry.Offset(w * 0.22f, h - 4f), 1.5f)
                for (y in 16..h.toInt() - 8 step 14) {
                    drawLine(lineCol, androidx.compose.ui.geometry.Offset(4f, y.toFloat()), androidx.compose.ui.geometry.Offset(w - 4f, y.toFloat()), 0.8f)
                }
            }
            PaperStyle.MUSIC_SCORE, PaperStyle.GUITAR_TABLATURE, PaperStyle.GUITAR_SCORE -> {
                for (group in 0..2) {
                    val startY = 16f + group * 24f
                    for (line in 0..4) {
                        drawLine(lineCol, androidx.compose.ui.geometry.Offset(4f, startY + line * 3.5f), androidx.compose.ui.geometry.Offset(w - 4f, startY + line * 3.5f), 0.8f)
                    }
                }
            }
        }
    }
}
