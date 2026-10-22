package com.graphite.app.feature.reader.screens

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.Identity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.graphite.app.core.auth.AccountRequiredDialog
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

    val googleAccountManager = remember { GraphiteApplication.instance.googleAccountManager }
    val googleUser by googleAccountManager.currentUser.collectAsState()
    val isGuestMode by googleAccountManager.isGuestMode.collectAsState()
    val hasActiveSession = googleUser != null || isGuestMode

    var showAccountRequiredDialog by remember { mutableStateOf(false) }
    var pendingAccountAction by remember { mutableStateOf("import books") }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        googleAccountManager.handleSignInResult(result.data)
    }

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

    // Book Selection & Deletion state
    var selectedBookIds by remember { mutableStateOf(setOf<String>()) }
    var booksToDelete by remember { mutableStateOf<List<Book>?>(null) }
    var isDeletingBooks by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        GraphiteRepository.cleanBookDuplicatesAndGhosts()
    }

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
            if (selectedBookIds.isNotEmpty()) {
                // Contextual Selection Action Bar with Delete (Bin) icon
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedBookIds = emptySet() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Selection",
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${selectedBookIds.size} Selected",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val currentCategoryFilteredIds = books.filter { book ->
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
                        }.map { it.id }.toSet()

                        val allSelected = selectedBookIds.size == currentCategoryFilteredIds.size && currentCategoryFilteredIds.isNotEmpty()
                        IconButton(
                            onClick = {
                                selectedBookIds = if (allSelected) emptySet() else currentCategoryFilteredIds
                            }
                        ) {
                            Icon(
                                imageVector = if (allSelected) Icons.Default.CheckCircle else Icons.Default.SelectAll,
                                contentDescription = if (allSelected) "Deselect All" else "Select All",
                                tint = if (allSelected) GraphiteOrange else Color.White
                            )
                        }

                        IconButton(
                            onClick = {
                                val toDelete = books.filter { it.id in selectedBookIds }
                                if (toDelete.isNotEmpty()) {
                                    booksToDelete = toDelete
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Selected Books",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            } else {
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
                    IconButton(onClick = {
                        if (!hasActiveSession) {
                            pendingAccountAction = "sync books with Google Drive"
                            showAccountRequiredDialog = true
                        } else {
                            showDriveSyncDialog = true
                        }
                    }) {
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
            if (filteredBooks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 88.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = Color.DarkGray,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (!hasActiveSession) "Account Required" else if (searchQuery.isNotBlank()) "No matching PDF books" else "No PDF books in Bookshelf",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (!hasActiveSession) "Sign in with Google to sync your reading library with Drive, or use a guest account to read offline." else if (searchQuery.isNotBlank()) "Try a different search query" else "Tap + to import a PDF document or sync with Google Drive",
                            color = Color(0xFF9E9E9E),
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        if (!hasActiveSession) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    pendingAccountAction = "access your books"
                                    showAccountRequiredDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Sign In / Guest Account", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else {
                val isSelectionMode = selectedBookIds.isNotEmpty()
                val toggleBookSelection = { bookId: String ->
                    selectedBookIds = if (bookId in selectedBookIds) {
                        selectedBookIds - bookId
                    } else {
                        selectedBookIds + bookId
                    }
                }

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
                                isSelected = book.id in selectedBookIds,
                                isSelectionMode = isSelectionMode,
                                onOpen = {
                                    if (isSelectionMode) {
                                        toggleBookSelection(book.id)
                                    } else {
                                        onOpenBook(book.id)
                                    }
                                },
                                onLongClick = {
                                    toggleBookSelection(book.id)
                                },
                                onToggleFavorite = { GraphiteRepository.toggleFavoriteBook(book.id) },
                                onUploadToDrive = {
                                    scope.launch {
                                        val res = driveManager.uploadBookToSyncedDriveFolder(context, book)
                                        val msg = if (res is DriveUploadResult.Success) "Uploaded to Drive folder" else "Upload failed"
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onDelete = { booksToDelete = listOf(book) }
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
                                isSelected = book.id in selectedBookIds,
                                isSelectionMode = isSelectionMode,
                                onOpen = {
                                    if (isSelectionMode) {
                                        toggleBookSelection(book.id)
                                    } else {
                                        onOpenBook(book.id)
                                    }
                                },
                                onLongClick = {
                                    toggleBookSelection(book.id)
                                },
                                onToggleFavorite = { GraphiteRepository.toggleFavoriteBook(book.id) },
                                onUploadToDrive = {
                                    scope.launch {
                                        val res = driveManager.uploadBookToSyncedDriveFolder(context, book)
                                        val msg = if (res is DriveUploadResult.Success) "Uploaded to Drive folder" else "Upload failed"
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onDelete = { booksToDelete = listOf(book) }
                            )
                        }
                    }
                }
            }
        }

        // Floating Action Button (FAB) matching Page 2
        FloatingActionButton(
            onClick = {
                if (!hasActiveSession) {
                    pendingAccountAction = "import PDF books"
                    showAccountRequiredDialog = true
                } else {
                    fileImportLauncher.launch(arrayOf("application/pdf"))
                }
            },
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
                contentDescription = "Import PDF Document",
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

    // Delete Book Choices Dialog
    booksToDelete?.let { targetBooks ->
        DeleteBookChoiceDialog(
            books = targetBooks,
            isDeleting = isDeletingBooks,
            onDismiss = {
                if (!isDeletingBooks) booksToDelete = null
            },
            onDeleteDeviceOnly = {
                targetBooks.forEach { b ->
                    GraphiteRepository.deleteBook(b.id)
                }
                selectedBookIds = selectedBookIds - targetBooks.map { it.id }.toSet()
                booksToDelete = null
                Toast.makeText(context, if (targetBooks.size == 1) "Deleted from device" else "Deleted ${targetBooks.size} books from device", Toast.LENGTH_SHORT).show()
            },
            onDeleteCloudAndDevice = {
                isDeletingBooks = true
                scope.launch {
                    targetBooks.forEach { b ->
                        driveManager.deleteBookFromDrive(context, b)
                        GraphiteRepository.deleteBook(b.id)
                    }
                    isDeletingBooks = false
                    selectedBookIds = selectedBookIds - targetBooks.map { it.id }.toSet()
                    booksToDelete = null
                    val msg = if (targetBooks.size == 1) {
                        "Deleted from device & Google Drive"
                    } else {
                        "Deleted ${targetBooks.size} books from device & Google Drive"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Import Book Dialog
    if (showImportDialog) {
        ImportBookDialog(
            onDismiss = { showImportDialog = false },
            onLaunchImport = {
                fileImportLauncher.launch(arrayOf("application/pdf"))
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

    if (showAccountRequiredDialog) {
        AccountRequiredDialog(
            onDismiss = { showAccountRequiredDialog = false },
            onSignInWithGoogle = {
                showAccountRequiredDialog = false
                val intent = googleAccountManager.getSignInIntent(context)
                googleSignInLauncher.launch(intent)
            },
            onContinueAsGuest = {
                showAccountRequiredDialog = false
                googleAccountManager.enableGuestMode()
            },
            actionDescription = pendingAccountAction
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
    val accountEmail = driveManager.getResolvedAccountEmail(context)
    val activeFolder by driveManager.activeBookshelfFolder.collectAsState()
    val defaultFolder by driveManager.defaultBookshelfFolder.collectAsState()
    val curatedFolders by driveManager.curatedBookshelfFolders.collectAsState()
    val isSyncing by driveManager.isSyncingBookshelf.collectAsState()
    val syncStatusMsg by driveManager.bookshelfSyncStatus.collectAsState()

    var showDriveFolderBrowser by remember { mutableStateOf(false) }
    var showCustomFolderDialog by remember { mutableStateOf(false) }
    var customFolderInput by remember { mutableStateOf("") }
    var isAuthorizingPicker by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        isAuthorizingPicker = false
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val data = activityResult.data
            if (data != null) {
                scope.launch {
                    try {
                        val authClient = Identity.getAuthorizationClient(context)
                        val authResult = authClient.getAuthorizationResultFromIntent(data)
                        authResult.toGoogleSignInAccount()?.email?.let { email ->
                            driveManager.saveAuthorizedAccount(email)
                        }
                        val res = driveManager.handlePickerAuthorizationResult(
                            context = context,
                            result = authResult,
                            notebook = null
                        )
                        if (res.isSuccess) {
                            val chosen = res.getOrNull()
                            if (chosen != null) {
                                driveManager.addCuratedBookshelfFolder(chosen)
                                driveManager.setBookshelfSyncedFolder(chosen)
                                showDriveFolderBrowser = false
                                Toast.makeText(context, "Added to synced folders: ${chosen.name}", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                        }
                    } catch (_: Exception) {}

                    val fallback = driveManager.handlePickerResult(
                        context = context,
                        intent = data,
                        notebook = null
                    )
                    if (fallback.isSuccess) {
                        val chosen = fallback.getOrNull()
                        if (chosen != null) {
                            driveManager.addCuratedBookshelfFolder(chosen)
                            driveManager.setBookshelfSyncedFolder(chosen)
                            showDriveFolderBrowser = false
                            Toast.makeText(context, "Added to synced folders: ${chosen.name}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "No folder selected in Drive Picker", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Sync books bi-directionally between Bookshelf and your selected Google Drive folder.",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )

                // Current Account & Status Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(accountEmail ?: "Not authorized", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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

                // Curated Folders Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Your Synced Folders",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Tap to switch destination. Star ★ to set as default cross-device folder.",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )

                    val displayCuratedFolders = remember(curatedFolders) {
                        val result = mutableListOf<DriveFolder>()
                        for (f in curatedFolders) {
                            val idx = result.indexOfFirst {
                                it.id == f.id || it.name.trim().equals(f.name.trim(), ignoreCase = true)
                            }
                            if (idx >= 0) {
                                val existing = result[idx]
                                if ((existing.id.startsWith("default_") || existing.id.startsWith("custom_")) &&
                                    (!f.id.startsWith("default_") && !f.id.startsWith("custom_"))
                                ) {
                                    result[idx] = f
                                }
                            } else {
                                result.add(f)
                            }
                        }
                        result
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        displayCuratedFolders.forEach { folder ->
                            val isSelected = folder.id == activeFolder.id ||
                                    (folder.id.startsWith("default_") && activeFolder.id.startsWith("default_")) ||
                                    folder.name.equals(activeFolder.name, ignoreCase = true)
                            val isDefault = folder.id == defaultFolder.id ||
                                    (folder.id.startsWith("default_") && defaultFolder.id.startsWith("default_")) ||
                                    folder.name.equals(defaultFolder.name, ignoreCase = true)

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFF2A2215) else Color(0xFF1E1E1E),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) GraphiteOrange else Color(0xFF333333)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        driveManager.setBookshelfSyncedFolder(folder)
                                        Toast.makeText(context, "Active folder: ${folder.name}", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = if (isSelected) GraphiteOrange else Color.LightGray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = folder.name,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))

                                    if (isDefault) {
                                        Surface(
                                            color = GraphiteOrange.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(4.dp),
                                            border = BorderStroke(1.dp, GraphiteOrange.copy(alpha = 0.5f))
                                        ) {
                                            Text(
                                                text = "★ Default",
                                                color = GraphiteOrange,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    } else {
                                        IconButton(
                                            onClick = {
                                                driveManager.setDefaultBookshelfFolder(folder)
                                                Toast.makeText(context, "${folder.name} set as default cross-device folder", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.StarBorder,
                                                contentDescription = "Set as Default",
                                                tint = Color.Gray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    if (!isDefault && folder.id != driveManager.DEFAULT_BOOKSHELF_FOLDER.id) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = {
                                                driveManager.removeCuratedBookshelfFolder(folder.id)
                                                Toast.makeText(context, "Removed ${folder.name}", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Remove from list",
                                                tint = Color.LightGray.copy(alpha = 0.6f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
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
                                customFolderInput = ""
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

                        OutlinedButton(
                            onClick = {
                                driveManager.resetBookshelfSyncedFolderToDefault()
                                Toast.makeText(context, "Reset to default: ${defaultFolder.name}", Toast.LENGTH_SHORT).show()
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
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val report = driveManager.syncBookshelfWithDrive(context, activeFolder.id)
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
            initialFolder = activeFolder,
            onFolderSelected = { chosen ->
                driveManager.addCuratedBookshelfFolder(chosen)
                driveManager.setBookshelfSyncedFolder(chosen)
                showDriveFolderBrowser = false
                Toast.makeText(context, "Sync destination set to: ${chosen.name}", Toast.LENGTH_SHORT).show()
            },
            onLaunchPicker = {
                isAuthorizingPicker = true
                scope.launch {
                    try {
                        val authClient = Identity.getAuthorizationClient(context)
                        val request = driveManager.buildPickerAuthorizationRequest()
                        authClient.authorize(request)
                            .addOnSuccessListener { authResult ->
                                if (authResult.hasResolution()) {
                                    val pendingIntent = authResult.pendingIntent
                                    if (pendingIntent != null) {
                                        pickerLauncher.launch(
                                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                        )
                                    } else {
                                        isAuthorizingPicker = false
                                        Toast.makeText(context, "Picker authorization resolution missing", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    isAuthorizingPicker = false
                                    scope.launch {
                                        val res = driveManager.handlePickerAuthorizationResult(
                                            context = context,
                                            result = authResult,
                                            notebook = null
                                        )
                                        if (res.isSuccess) {
                                            val chosen = res.getOrNull()
                                            if (chosen != null) {
                                                driveManager.addCuratedBookshelfFolder(chosen)
                                                driveManager.setBookshelfSyncedFolder(chosen)
                                                showDriveFolderBrowser = false
                                                Toast.makeText(context, "Sync destination set to: ${chosen.name}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            }
                            .addOnFailureListener { e ->
                                isAuthorizingPicker = false
                                Toast.makeText(context, "Google Drive Picker error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    } catch (e: Exception) {
                        isAuthorizingPicker = false
                        Toast.makeText(context, "Error starting Google Drive Picker: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            isAuthorizingPicker = isAuthorizingPicker,
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
                            scope.launch {
                                val res = driveManager.createOrConnectBookshelfFolder(context, trimmed)
                                val folder = res.getOrNull()
                                val name = folder?.name ?: trimmed
                                Toast.makeText(context, "Folder set to: $name", Toast.LENGTH_SHORT).show()
                                showCustomFolderDialog = false
                            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookshelfItemRow(
    book: Book,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onUploadToDrive: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFF2A2016) else Color.Transparent)
            .then(
                if (isSelected) Modifier.border(1.5.dp, GraphiteOrange, RoundedCornerShape(8.dp))
                else Modifier
            )
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) GraphiteOrange else Color.Gray,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }

        // Book Cover
        BookCover(
            book = book,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookshelfItemGridCard(
    book: Book,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onUploadToDrive: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFF2A2016) else Color.Transparent)
            .then(
                if (isSelected) Modifier.border(1.5.dp, GraphiteOrange, RoundedCornerShape(8.dp))
                else Modifier
            )
            .combinedClickable(
                onClick = onOpen,
                onLongClick = onLongClick
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            BookCover(
                book = book,
                modifier = Modifier
                    .width(110.dp)
                    .height(150.dp)
            )
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(GraphiteOrange)
                        .align(Alignment.TopStart),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
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
private fun DeleteBookChoiceDialog(
    books: List<Book>,
    isDeleting: Boolean,
    onDismiss: () -> Unit,
    onDeleteDeviceOnly: () -> Unit,
    onDeleteCloudAndDevice: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (books.size == 1) "Delete Book" else "Delete ${books.size} Books",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (isDeleting) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = GraphiteOrange, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Deleting from Google Drive and device...", color = Color.LightGray, fontSize = 13.sp)
                    }
                } else {
                    Text(
                        text = if (books.size == 1) {
                            "Choose how you want to delete \"${books.first().title}\":"
                        } else {
                            "Choose how you want to delete these ${books.size} books:"
                        },
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )

                    // Option 1: Delete from this device only
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E1E1E),
                        border = BorderStroke(1.dp, Color(0xFF333333)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeleteDeviceOnly() }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = GraphiteOrange, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Delete from this device only", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Removes the local file. The book remains safe in Google Drive.", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }

                    // Option 2: Delete from Cloud & Device
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF261818),
                        border = BorderStroke(1.dp, Color(0xFF552222)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeleteCloudAndDevice() }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Delete from Cloud & Device", color = Color(0xFFFF7070), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Removes from this device AND permanently deletes the file from Google Drive.", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text("Cancel", color = Color.Gray)
            }
        },
        containerColor = GraphiteSurfaceVariant,
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun ImportBookDialog(
    onDismiss: () -> Unit,
    onLaunchImport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import PDF Document", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Select a PDF document (.pdf) from your device to import into Bookshelf.",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
                Button(
                    onClick = onLaunchImport,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
                ) {
                    Icon(imageVector = Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select PDF Document", fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        },
        containerColor = GraphiteSurfaceVariant
    )
}
