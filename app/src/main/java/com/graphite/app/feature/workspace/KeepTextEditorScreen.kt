package com.graphite.app.feature.workspace

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.data.repository.GraphiteRepository
import kotlinx.coroutines.delay

data class ChecklistItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isDone: Boolean = false
)

@Composable
fun KeepTextEditorScreen(
    notebookId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val notebook = remember(notebookId) { GraphiteRepository.getNotebook(notebookId) }

    var title by remember { mutableStateOf(notebook?.title ?: "Note") }
    var noteBody by remember { mutableStateOf("") }
    var checklistItems by remember { mutableStateOf<List<ChecklistItem>>(emptyList()) }
    var showCheckedItems by remember { mutableStateOf(true) }
    var showVoiceDialog by remember { mutableStateOf(false) }

    // Parse existing previewLines into checklist items and body text
    LaunchedEffect(notebookId) {
        notebook?.let { n ->
            title = n.title
            val parsedItems = mutableListOf<ChecklistItem>()
            val bodyLines = mutableListOf<String>()
            for (line in n.previewLines) {
                if (line.startsWith("[x] ")) {
                    parsedItems.add(ChecklistItem(text = line.removePrefix("[x] "), isDone = true))
                } else if (line.startsWith("[ ] ")) {
                    parsedItems.add(ChecklistItem(text = line.removePrefix("[ ] "), isDone = false))
                } else {
                    bodyLines.add(line)
                }
            }
            checklistItems = parsedItems
            noteBody = bodyLines.joinToString("\n")
        }
    }

    // Auto-save logic
    fun saveNote() {
        val serializedLines = mutableListOf<String>()
        checklistItems.forEach { item ->
            val prefix = if (item.isDone) "[x] " else "[ ] "
            serializedLines.add("$prefix${item.text.trim()}")
        }
        if (noteBody.isNotBlank()) {
            serializedLines.addAll(noteBody.lines().filter { it.isNotBlank() })
        }
        GraphiteRepository.updateTextNotebook(
            id = notebookId,
            title = title.ifBlank { "Untitled Note" },
            lines = serializedLines
        )
    }

    // Auto-save debounced on edits
    LaunchedEffect(title, checklistItems, noteBody) {
        delay(400)
        saveNote()
    }

    BackHandler {
        saveNote()
        onNavigateBack()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        containerColor = Color(0xFF141414),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        saveNote()
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Text Note",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showVoiceDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice to Text",
                            tint = GraphiteOrange
                        )
                    }
                    IconButton(onClick = {
                        checklistItems = checklistItems + ChecklistItem(text = "")
                    }) {
                        Icon(
                            imageVector = Icons.Default.Checklist,
                            contentDescription = "Add Checklist",
                            tint = GraphiteOrange
                        )
                    }
                    IconButton(onClick = {
                        GraphiteRepository.deleteNotebook(notebookId)
                        Toast.makeText(context, "Note moved to trash", Toast.LENGTH_SHORT).show()
                        onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Note",
                            tint = Color.LightGray
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        val focusManager = LocalFocusManager.current
        val uncompleted = checklistItems.filter { !it.isDone }
        val completed = checklistItems.filter { it.isDone }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Note Title (Google Keep large title style)
            item {
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = {
                        Text(
                            text = "Title",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF666666)
                        )
                    },
                    textStyle = TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Uncompleted Checklist Items
            itemsIndexed(uncompleted, key = { _, item -> item.id }) { _, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            checklistItems = checklistItems.map {
                                if (it.id == item.id) it.copy(isDone = true) else it
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = "Complete task",
                            tint = Color.LightGray,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    TextField(
                        value = item.text,
                        onValueChange = { newText ->
                            checklistItems = checklistItems.map {
                                if (it.id == item.id) it.copy(text = newText) else it
                            }
                        },
                        placeholder = { Text("List item", color = Color.Gray, fontSize = 15.sp) },
                        textStyle = TextStyle(fontSize = 15.sp, color = Color.White),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = {
                                checklistItems = checklistItems + ChecklistItem(text = "")
                            }
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            checklistItems = checklistItems.filter { it.id != item.id }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Delete item",
                            tint = Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Quick Add Item Button
            if (checklistItems.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                checklistItems = checklistItems + ChecklistItem(text = "")
                            }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add list item",
                            tint = GraphiteOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "List item",
                            color = GraphiteOrange,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Completed Checklist Items Header & Items
            if (completed.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showCheckedItems = !showCheckedItems }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (showCheckedItems) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${completed.size} checked items",
                            color = Color.Gray,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (showCheckedItems) {
                    itemsIndexed(completed, key = { _, item -> item.id }) { _, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    checklistItems = checklistItems.map {
                                        if (it.id == item.id) it.copy(isDone = false) else it
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckBox,
                                    contentDescription = "Uncheck task",
                                    tint = GraphiteOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Text(
                                text = item.text,
                                fontSize = 15.sp,
                                color = Color.Gray,
                                textDecoration = TextDecoration.LineThrough,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 14.dp)
                            )

                            IconButton(
                                onClick = {
                                    checklistItems = checklistItems.filter { it.id != item.id }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Delete item",
                                    tint = Color(0xFF555555),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Freeform Note Body (Paragraphs / typing)
            item {
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = noteBody,
                    onValueChange = { noteBody = it },
                    placeholder = {
                        Text(
                            text = if (checklistItems.isEmpty()) "Note..." else "Add note text...",
                            fontSize = 15.sp,
                            color = Color(0xFF666666)
                        )
                    },
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        color = Color.White,
                        lineHeight = 22.sp
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 220.dp)
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                val recorder = com.graphite.app.GraphiteApplication.instance.voiceRecorderManager
                if (recorder.isRecording.value) {
                    val clip = recorder.stopRecording()
                    if (clip != null) {
                        GraphiteRepository.addVoiceClipToNotebook(notebookId, clip)
                    }
                }
                recorder.stopPlayback()
            } catch (_: Exception) {}
        }
    }

    if (showVoiceDialog) {
        VoiceNoteDialog(
            notebookId = notebookId,
            onDismiss = { showVoiceDialog = false },
            onSaveVoiceClip = { clip ->
                GraphiteRepository.addVoiceClipToNotebook(notebookId, clip)
            },
            onTextTranscribed = { text ->
                if (checklistItems.isNotEmpty()) {
                    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val newItems = lines.map { ChecklistItem(text = it.removePrefix("- ").removePrefix("* ")) }
                    checklistItems = checklistItems + newItems
                } else {
                    noteBody = if (noteBody.isBlank()) text else "$noteBody\n\n$text"
                }
                saveNote()
            }
        )
    }
}
