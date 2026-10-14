package com.graphite.app.feature.reader.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.core.auth.GoogleProfileTopBarIcon
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteSurfaceVariant
import com.graphite.app.data.model.Book
import com.graphite.app.data.repository.GraphiteRepository
import com.graphite.app.feature.reader.components.BookCover

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.importer.ImportResult
import kotlinx.coroutines.launch

@Composable
fun BookshelfScreen(
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val books by GraphiteRepository.books.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileImportManager = remember { GraphiteApplication.instance.fileImportManager }

    var selectedCategory by remember { mutableStateOf("All Books") }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var isImportingDocument by remember { mutableStateOf(false) }

    val fileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImportingDocument = true
            scope.launch {
                val result = fileImportManager.importToBookshelf(context, uri)
                isImportingDocument = false
                when (result) {
                    is ImportResult.SuccessBook -> {
                        importStatusMessage = "Imported \"${result.book.title}\" (${result.pageCount} pages) to Bookshelf"
                        showImportDialog = false
                        onOpenBook(result.book.id)
                    }
                    is ImportResult.SuccessNotebook -> {
                        // Handled for completeness
                        importStatusMessage = "Imported note \"${result.notebook.title}\""
                        showImportDialog = false
                    }
                    is ImportResult.Error -> {
                        importStatusMessage = result.message
                    }
                }
            }
        }
    }

    val categories = listOf("All Books", "Favorites", "Unread", "Completed")

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GraphiteBlack)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Header Bar: "All Books ⌄" and menu icon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { categoryMenuExpanded = true }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedCategory,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Serif
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Select Category",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false },
                        modifier = Modifier.background(GraphiteSurfaceVariant)
                    ) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = cat,
                                        color = if (cat == selectedCategory) GraphiteOrange else Color.White
                                    )
                                },
                                onClick = {
                                    selectedCategory = cat
                                    categoryMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { /* Sort / View options */ }) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "Sort Books",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    GoogleProfileTopBarIcon()
                }
            }

            // Pill Search Bar: "Search Books"
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "Search Books",
                        color = Color(0xFF757575),
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color(0xFF757575),
                        modifier = Modifier.size(20.dp)
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GraphiteOrange,
                    unfocusedBorderColor = Color(0xFF2E2E2E),
                    focusedContainerColor = Color(0xFF161616),
                    unfocusedContainerColor = Color(0xFF161616),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            // Metadata Counter: "2 Books"
            val filteredBooks = books.filter { book ->
                val matchesCategory = when (selectedCategory) {
                    "Completed" -> book.progressPercent >= 100
                    "Unread" -> book.progressPercent == 0
                    else -> true
                }
                val matchesSearch = if (searchQuery.isBlank()) true else {
                    book.title.contains(searchQuery, ignoreCase = true) || book.author.contains(searchQuery, ignoreCase = true)
                }
                matchesCategory && matchesSearch
            }

            Text(
                text = "${filteredBooks.size} Books",
                color = Color(0xFF757575),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )

            // Bookshelf List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                items(filteredBooks) { book ->
                    BookshelfItemRow(
                        book = book,
                        onOpen = { onOpenBook(book.id) }
                    )
                }
            }
        }

        // Floating Action Button (FAB) matching Page 2
        FloatingActionButton(
            onClick = { showImportDialog = true },
            containerColor = GraphiteOrange,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 24.dp)
                .size(56.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Import or Add Book",
                modifier = Modifier.size(28.dp)
            )
        }
    }

    // Import Book Dialog
    if (showImportDialog) {
        ImportBookDialog(
            onDismiss = { showImportDialog = false },
            onLaunchImport = {
                fileImportLauncher.launch(arrayOf("application/pdf", "application/json", "application/octet-stream", "*/*"))
            },
            onAddBook = { title, author, content ->
                GraphiteRepository.addBook(title, author, content)
                showImportDialog = false
            }
        )
    }

    if (isImportingDocument) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Importing Document", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = GraphiteOrange, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Reading PDF / document from tablet storage...", color = Color.LightGray, fontSize = 13.sp)
                }
            },
            confirmButton = {},
            containerColor = GraphiteSurfaceVariant,
            shape = RoundedCornerShape(14.dp)
        )
    }

    importStatusMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { importStatusMessage = null },
            title = { Text("Import Status", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(text = msg, color = Color.LightGray, fontSize = 13.sp)
            },
            confirmButton = {
                Button(
                    onClick = { importStatusMessage = null },
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
                ) {
                    Text("OK", color = Color.White)
                }
            },
            containerColor = GraphiteSurfaceVariant,
            shape = RoundedCornerShape(14.dp)
        )
    }
}

@Composable
private fun BookshelfItemRow(
    book: Book,
    onOpen: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Book Cover
        BookCover(
            coverType = book.coverType,
            title = book.title,
            modifier = Modifier
                .width(68.dp)
                .height(96.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Info & Progress percentage
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = book.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = book.author,
                color = Color(0xFF9E9E9E),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${book.progressPercent}%",
                color = Color(0xFF757575),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // 3-dots Menu
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More",
                    tint = Color(0xFF757575)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(GraphiteSurfaceVariant)
            ) {
                DropdownMenuItem(
                    text = { Text("Open Book", color = Color.White) },
                    onClick = {
                        menuExpanded = false
                        onOpen()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Reset Progress", color = Color.White) },
                    onClick = {
                        GraphiteRepository.updateBookProgress(book.id, 0)
                        menuExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ImportBookDialog(
    onDismiss: () -> Unit,
    onLaunchImport: () -> Unit,
    onAddBook: (String, String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import E-Book / Document", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Tablet SAF file picker option
                OutlinedButton(
                    onClick = onLaunchImport,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GraphiteOrange)
                ) {
                    Icon(imageVector = Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import PDF / Note from Tablet", fontWeight = FontWeight.SemiBold)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = GraphiteSurfaceVariant)
                    Text(" OR CREATE CUSTOM ", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    HorizontalDivider(modifier = Modifier.weight(1f), color = GraphiteSurfaceVariant)
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Sample Text / Chapter Preview", color = Color.Gray) },
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onAddBook(title, author.ifBlank { "Unknown Author" }, content.ifBlank { "Sample imported content." })
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
            ) {
                Text("Add to Bookshelf", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        },
        containerColor = GraphiteSurfaceVariant
    )
}
