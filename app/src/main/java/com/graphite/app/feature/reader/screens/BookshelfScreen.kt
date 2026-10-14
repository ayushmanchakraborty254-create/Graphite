package com.graphite.app.feature.reader.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.auth.GoogleProfileTopBarIcon
import com.graphite.app.core.drive.BookshelfSyncReport
import com.graphite.app.core.drive.DriveFolder
import com.graphite.app.core.drive.DriveFolderBrowserDialog
import com.graphite.app.core.drive.DriveUploadResult
import com.graphite.app.core.importer.ImportResult
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteSurfaceVariant
import com.graphite.app.data.model.Book
import com.graphite.app.data.model.BookCoverType
import com.graphite.app.data.repository.GraphiteRepository
import com.graphite.app.feature.reader.components.BookCover
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BookshelfScreen(
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val books by GraphiteRepository.books.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileImportManager = remember { GraphiteApplication.instance.fileImportManager }
    val driveManager = remember { GraphiteApplication.instance.googleDriveManager }

    var selectedCategory by remember { mutableStateOf("All Books") }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var isImportingDocument by remember { mutableStateOf(false) }

    // Sort & View state
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var currentSortOption by remember { mutableStateOf("Title (A-Z)") }
    var isGridView by remember { mutableStateOf(false) }

    // Google Drive 2-Way Sync state
    var showDriveSyncDialog by remember { mutableStateOf(false) }
    val isSyncingDrive by driveManager.isSyncingBookshelf.collectAsState()
    val driveSyncStatus by driveManager.bookshelfSyncStatus.collectAsState()

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
                        // Auto-upload to synced Drive folder if configured
                        if (driveManager.hasBookshelfSyncConfigured()) {
                            driveManager.uploadBookToSyncedDriveFolder(context, result.book)
                        }
                        showImportDialog = false
                        onOpenBook(result.book.id)
                    }
                    is ImportResult.SuccessNotebook -> {
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
            // Header Bar: Category selector, Sync button, Sort button, Profile
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
                    // Google Drive 2-Way Sync Button
                    val isSynced = driveManager.hasBookshelfSyncConfigured()
                    IconButton(onClick = { showDriveSyncDialog = true }) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = "Sync with Google Drive",
                                tint = if (isSynced) GraphiteOrange else Color.White
                            )
                            if (isSynced) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50))
                                        .align(Alignment.TopEnd)
                                )
                            }
                        }
                    }

                    // Sort & View Mode Menu Button
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = "Sort Books",
                                tint = Color.White
                            )
                        }

                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                            modifier = Modifier.background(GraphiteSurfaceVariant)
                        ) {
                            val sortOptions = listOf(
                                "Title (A-Z)",
                                "Title (Z-A)",
                                "Most Recent",
                                "Reading Progress",
                                "Author"
                            )
                            sortOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = opt,
                                            color = if (opt == currentSortOption) GraphiteOrange else Color.White
                                        )
                                    },
                                    onClick = {
                                        currentSortOption = opt
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                            HorizontalDivider(color = Color(0xFF333333))
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                                            contentDescription = null,
                                            tint = Color.LightGray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(if (isGridView) "Switch to List View" else "Switch to Grid View", color = Color.White)
                                    }
                                },
                                onClick = {
                                    isGridView = !isGridView
                                    sortMenuExpanded = false
                                }
                            )
                        }
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

            // Filter & Sort
            val filteredBooks = books.filter { book ->
                val matchesCategory = when (selectedCategory) {
                    "Completed" -> book.progressPercent >= 100
                    "Unread" -> book.progressPercent == 0
                    "Favorites" -> book.isFavorite
                    else -> true
                }
                val matchesSearch = if (searchQuery.isBlank()) true else {
                    book.title.contains(searchQuery, ignoreCase = true) || book.author.contains(searchQuery, ignoreCase = true)
                }
                matchesCategory && matchesSearch
            }.let { list ->
                when (currentSortOption) {
                    "Title (A-Z)" -> list.sortedBy { it.title.lowercase() }
                    "Title (Z-A)" -> list.sortedByDescending { it.title.lowercase() }
                    "Most Recent" -> list.sortedByDescending { it.lastReadTimeMillis }
                    "Reading Progress" -> list.sortedByDescending { it.progressPercent }
                    "Author" -> list.sortedBy { it.author.lowercase() }
                    else -> list
                }
            }

            Text(
                text = "${filteredBooks.size} Books",
                color = Color(0xFF757575),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )

            // Bookshelf List or Grid
            if (isGridView) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(filteredBooks) { book ->
                        BookshelfItemGridCard(
                            book = book,
                            onOpen = { onOpenBook(book.id) },
                            onToggleFavorite = { GraphiteRepository.toggleFavoriteBook(book.id) },
                            onUploadToDrive = {
                                scope.launch {
                                    val res = driveManager.uploadBookToSyncedDriveFolder(context, book)
                                    val msg = if (res is DriveUploadResult.Success) "Uploaded to Drive folder" else "Upload failed"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDelete = { GraphiteRepository.deleteBook(book.id) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(filteredBooks) { book ->
                        BookshelfItemRow(
                            book = book,
                            onOpen = { onOpenBook(book.id) },
                            onToggleFavorite = { GraphiteRepository.toggleFavoriteBook(book.id) },
                            onUploadToDrive = {
                                scope.launch {
                                    val res = driveManager.uploadBookToSyncedDriveFolder(context, book)
                                    val msg = if (res is DriveUploadResult.Success) "Uploaded to Drive folder" else "Upload failed"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDelete = { GraphiteRepository.deleteBook(book.id) }
                        )
                    }
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

    // Google Drive 2-Way Sync Dialog
    if (showDriveSyncDialog) {
        BookshelfDriveSyncDialog(
            driveManager = driveManager,
            onDismiss = { showDriveSyncDialog = false },
            onSyncCompleted = { report ->
                importStatusMessage = report.message
            }
        )
    }

    // Import Book Dialog
    if (showImportDialog) {
        ImportBookDialog(
            onDismiss = { showImportDialog = false },
            onLaunchImport = {
                fileImportLauncher.launch(
                    arrayOf(
                        "application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/vnd.ms-powerpoint",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/plain",
                        "*/*"
                    )
                )
            },
            onAddBook = { title, author, content ->
                val newBook = Book(
                    title = GraphiteRepository.disambiguateBookTitle(title),
                    author = author.ifBlank { "Unknown Author" },
                    content = content.ifBlank { "Sample imported content." },
                    coverType = BookCoverType.CUSTOM
                )
                GraphiteRepository.addBook(newBook)
                if (driveManager.hasBookshelfSyncConfigured()) {
                    scope.launch {
                        driveManager.uploadBookToSyncedDriveFolder(context, newBook)
                    }
                }
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
            title = { Text("Bookshelf Status", color = Color.White, fontWeight = FontWeight.Bold) },
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
private fun BookshelfDriveSyncDialog(
    driveManager: com.graphite.app.core.drive.GoogleDriveManager,
    onDismiss: () -> Unit,
    onSyncCompleted: (BookshelfSyncReport) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountEmail = driveManager.getAuthorizedAccountEmail()
    val syncedFolder = driveManager.getBookshelfSyncedFolder()
    val isSyncing by driveManager.isSyncingBookshelf.collectAsState()
    val syncStatusMsg by driveManager.bookshelfSyncStatus.collectAsState()

    var showDriveFolderBrowser by remember { mutableStateOf(false) }
    var showCustomFolderDialog by remember { mutableStateOf(false) }
    var customFolderInput by remember { mutableStateOf("") }

    val lastSyncMillis = driveManager.getLastBookshelfSyncTimeMillis()
    val lastSyncFormatted = if (lastSyncMillis > 0) {
        SimpleDateFormat("dd/MM/yy hh:mm a", Locale.getDefault()).format(Date(lastSyncMillis))
    } else "Never"

    AlertDialog(
        onDismissRequest = { if (!isSyncing) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudSync, contentDescription = null, tint = GraphiteOrange, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Google Drive 2-Way Sync", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Sync books bi-directionally between Bookshelf and your selected Google Drive folder.",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )

                // Current Account & Folder Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(accountEmail ?: "Not authorized", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = GraphiteOrange, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Folder: ${syncedFolder?.name ?: "Graphite Bookshelf (Auto-created)"}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Last synced: $lastSyncFormatted", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                if (isSyncing) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = GraphiteOrange, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(syncStatusMsg ?: "Syncing...", color = GraphiteOrange, fontSize = 13.sp)
                    }
                }

                // Folder action buttons: Browse Any Folder or Enter Custom Folder
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showDriveFolderBrowser = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C3E50))
                    ) {
                        Icon(Icons.Default.DriveFolderUpload, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF64B5F6))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse & Select Any Drive Folder", color = Color.White)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                customFolderInput = syncedFolder?.name ?: ""
                                showCustomFolderDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Enter Folder Name", fontSize = 12.sp)
                        }

                        if (syncedFolder != null) {
                            OutlinedButton(
                                onClick = {
                                    driveManager.setBookshelfSyncedFolder(null)
                                    Toast.makeText(context, "Reset to default Graphite Bookshelf folder", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray)
                            ) {
                                Text("Reset to Default", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val report = driveManager.syncBookshelfWithDrive(context, syncedFolder?.id)
                        onDismiss()
                        onSyncCompleted(report)
                    }
                },
                enabled = !isSyncing,
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
            ) {
                Text(if (isSyncing) "Syncing..." else "Sync Now", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSyncing) {
                Text("Close", color = Color.Gray)
            }
        },
        containerColor = GraphiteSurfaceVariant,
        shape = RoundedCornerShape(16.dp)
    )

    if (showDriveFolderBrowser) {
        DriveFolderBrowserDialog(
            driveManager = driveManager,
            initialFolder = syncedFolder ?: DriveFolder.ROOT,
            onFolderSelected = { chosen ->
                driveManager.setBookshelfSyncedFolder(chosen)
                showDriveFolderBrowser = false
                Toast.makeText(context, "Sync destination set to: ${chosen.name}", Toast.LENGTH_SHORT).show()
            },
            onLaunchPicker = { /* optional external picker */ },
            isAuthorizingPicker = false,
            onDismiss = { showDriveFolderBrowser = false }
        )
    }

    if (showCustomFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCustomFolderDialog = false },
            title = { Text("Custom Drive Folder", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter any folder name in your Google Drive. Graphite will search for or create this folder and sync books inside it.",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = customFolderInput,
                        onValueChange = { customFolderInput = it },
                        label = { Text("Folder Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = GraphiteOrange,
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = customFolderInput.trim()
                        if (trimmed.isNotBlank()) {
                            driveManager.setBookshelfSyncedFolder(DriveFolder("custom_$trimmed", trimmed))
                            Toast.makeText(context, "Folder set to: $trimmed", Toast.LENGTH_SHORT).show()
                            showCustomFolderDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
                ) {
                    Text("Save Folder", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomFolderDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }
}

@Composable
private fun BookshelfItemRow(
    book: Book,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onUploadToDrive: () -> Unit,
    onDelete: () -> Unit
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = book.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (book.isFavorite) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Favorite",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = book.author,
                color = Color(0xFF9E9E9E),
                fontSize = 13.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${book.progressPercent}%",
                color = if (book.progressPercent >= 100) Color(0xFF81C784) else Color(0xFF757575),
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
                    text = { Text(if (book.isFavorite) "Remove from Favorites" else "Add to Favorites", color = Color.White) },
                    onClick = {
                        menuExpanded = false
                        onToggleFavorite()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Reset Progress (0%)", color = Color.White) },
                    onClick = {
                        GraphiteRepository.updateBookProgress(book.id, 0)
                        menuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Mark as Completed (100%)", color = Color.White) },
                    onClick = {
                        GraphiteRepository.updateBookProgress(book.id, 100)
                        menuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Upload to Google Drive", color = GraphiteOrange) },
                    onClick = {
                        menuExpanded = false
                        onUploadToDrive()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete Book", color = Color(0xFFFF5252)) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun BookshelfItemGridCard(
    book: Book,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onUploadToDrive: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            BookCover(
                coverType = book.coverType,
                title = book.title,
                modifier = Modifier
                    .width(110.dp)
                    .height(150.dp)
            )
            if (book.isFavorite) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.padding(4.dp).size(20.dp).align(Alignment.TopEnd)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = book.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${book.progressPercent}%",
            color = Color(0xFF757575),
            fontSize = 11.sp
        )
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
                    Text("Import PDF / Word / PPTX", fontWeight = FontWeight.SemiBold)
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
