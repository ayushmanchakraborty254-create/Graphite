package com.graphite.app.feature.noteshelf

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import com.graphite.app.data.model.NotebookCover
import com.graphite.app.feature.workspace.model.PaperStyle

@Composable
fun NewHandwrittenNotebookModal(
    onDismiss: () -> Unit,
    onCreate: (title: String, cover: NotebookCover, template: PaperStyle, password: String?) -> Unit
) {
    var title by remember { mutableStateOf("My Notebook") }
    var selectedCover by remember { mutableStateOf(NotebookCover(category = "PLAIN", colorHex = 0xFF45A5B5, name = "Teal")) }
    var selectedTemplate by remember { mutableStateOf(PaperStyle.BLANK) }
    var password by remember { mutableStateOf<String?>(null) }
    var showPasswordInput by remember { mutableStateOf(false) }

    var isCoverPickerOpen by remember { mutableStateOf(false) }
    var isTemplatePickerOpen by remember { mutableStateOf(false) }

    if (isCoverPickerOpen) {
        FullCoverPickerSheet(
            selectedCover = selectedCover,
            onSelectCover = {
                selectedCover = it
            },
            onDismiss = { isCoverPickerOpen = false }
        )
        return
    }

    if (isTemplatePickerOpen) {
        PageTemplateDialog(
            selectedStyle = selectedTemplate,
            onSelectStyle = {
                selectedTemplate = it
            },
            onDismiss = { isTemplatePickerOpen = false }
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .clickable(enabled = false) {}
                .shadow(20.dp, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F23))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Side-by-side preview cards (Cover vs Template) matching PDF Page 11
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Cover Preview Card + "Cover ∨" pill
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.72f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { isCoverPickerOpen = true }
                        ) {
                            NotebookCoverView(cover = selectedCover, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { isCoverPickerOpen = true }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Cover", color = Color.White, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }

                    // Right: Template Preview Card + "Plain ∨" pill
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.72f)
                                .shadow(6.dp, RoundedCornerShape(4.dp))
                                .clickable { isTemplatePickerOpen = true },
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                TemplateMiniPreview(style = selectedTemplate)
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        val templateLabel = when (selectedTemplate) {
                            PaperStyle.BLANK -> "Plain"
                            PaperStyle.RULED -> "Ruled"
                            PaperStyle.GRID -> "Grid"
                            PaperStyle.DOTTED -> "Dot"
                            PaperStyle.CORNELL_A, PaperStyle.CORNELL_B -> "Cornell"
                            PaperStyle.LEGAL -> "Legal"
                            else -> "Template"
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { isTemplatePickerOpen = true }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(templateLabel, color = Color.White, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title input field
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("Notebook Title", color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00BCD4),
                        unfocusedBorderColor = Color(0xFF333339),
                        focusedContainerColor = Color(0xFF141517),
                        unfocusedContainerColor = Color(0xFF141517),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                if (showPasswordInput) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password ?: "",
                        onValueChange = { password = it.ifBlank { null } },
                        placeholder = { Text("Enter Password (optional)", color = Color.Gray) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00BCD4),
                            unfocusedBorderColor = Color(0xFF333339),
                            focusedContainerColor = Color(0xFF141517),
                            unfocusedContainerColor = Color(0xFF141517),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Blue/Teal "Create Notebook" button matching PDF Page 11
                Button(
                    onClick = {
                        val finalTitle = title.trim().ifBlank { "My Notebook" }
                        onCreate(finalTitle, selectedCover, selectedTemplate, password)
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF45A5B5)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Create Notebook", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Lock icon: "Add a password"
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showPasswordInput = !showPasswordInput }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF00BCD4), modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showPasswordInput) "Remove password" else "Add a password",
                        color = Color(0xFF00BCD4),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
