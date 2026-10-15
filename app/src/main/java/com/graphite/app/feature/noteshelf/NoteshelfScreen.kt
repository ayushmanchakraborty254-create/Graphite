package com.graphite.app.feature.noteshelf

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.animation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.auth.GoogleProfileTopBarIcon
import com.graphite.app.core.drive.DriveUploadState
import com.graphite.app.core.importer.ImportResult
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteOutline
import com.graphite.app.core.theme.GraphiteSurfaceVariant
import com.graphite.app.data.model.FolderItem
import com.graphite.app.data.model.NotebookItem
import com.graphite.app.data.repository.GraphiteRepository
import com.graphite.app.feature.workspace.model.PaperStyle
import kotlinx.coroutines.launch

@Composable
fun NoteshelfScreen(
    onOpenNotebook: (String) -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val notebooks by GraphiteRepository.notebooks.collectAsState()
    val folders by GraphiteRepository.folders.collectAsState()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val driveManager = remember { GraphiteApplication.instance.googleDriveManager }
    val fileImportManager = remember { GraphiteApplication.instance.fileImportManager }
    var selectedNotebookForUpload by remember { mutableStateOf<NotebookItem?>(null) }

    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var isImportingFile by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600
    val syncManager = remember { GraphiteApplication.instance.supabaseSyncManager }
    var isSyncingNotes by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        syncManager.triggerAutoSync(debounceMs = 0L)
    }

    // Dialogs & state
    var selectedCategory by remember { mutableStateOf("All Notes") }
    var sidebarVisible by remember(isTablet) { mutableStateOf(isTablet) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }

    var showNewNotebookDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showRenameDialogForNote by remember { mutableStateOf<NotebookItem?>(null) }
    var showMoveFolderDialogForNote by remember { mutableStateOf<NotebookItem?>(null) }

    var moreMenuExpanded by remember { mutableStateOf(false) }
    var currentSortOption by remember { mutableStateOf("Date Modified (Newest)") }
    var isListView by remember { mutableStateOf(false) }

    val fileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImportingFile = true
            scope.launch {
                val result = fileImportManager.importToNotes(context, uri)
                isImportingFile = false
                when (result) {
                    is ImportResult.SuccessNotebook -> {
                        importStatusMessage = result.message
                        onOpenNotebook(result.notebook.id)
                    }
                    is ImportResult.SuccessBook -> {
                        importStatusMessage = "Imported \"${result.book.title}\""
                    }
                    is ImportResult.Error -> {
                        importStatusMessage = result.message
                    }
                }
            }
        }
    }

    val driveAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val authResult = driveManager.handleAuthorizationResult(result.data)
        if (authResult.isSuccess) {
            val toUpload = selectedNotebookForUpload
            if (toUpload != null) {
                selectedNotebookForUpload = null
                scope.launch {
                    driveManager.startUploadFlow(context, toUpload)
                }
            } else {
                isSyncingNotes = true
                scope.launch {
                    val dResult = driveManager.syncNotesWithDrive(context)
                    val sResult = syncManager.syncNotes()
                    isSyncingNotes = false
                    val count = dResult.getOrNull() ?: sResult.getOrNull() ?: 0
                    Toast.makeText(context, "Synced $count notes", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Filter notes according to category and search query
    val allNotesWithDeleted = GraphiteRepository.getAllNotesIncludingDeleted()
    val baseNotes = if (selectedCategory == "Trash") {
        allNotesWithDeleted.filter { it.isDeleted }
    } else {
        allNotesWithDeleted.filter { !it.isDeleted }
    }

    val categoryFiltered = when (selectedCategory) {
        "All Notes" -> baseNotes
        "Starred" -> baseNotes.filter { it.isStarred }
        "Unfiled" -> baseNotes.filter { it.folderId.isBlank() || it.folderId == "unfiled" }
        "Trash" -> baseNotes
        "Photos" -> baseNotes.filter { it.hasPhotos || it.previewLines.any { l -> l.contains("image", true) || l.contains("photo", true) } }
        "Recordings" -> baseNotes.filter { it.hasRecordings || it.previewLines.any { l -> l.contains("audio", true) || l.contains("recording", true) } }
        "Bookmarks" -> baseNotes.filter { it.isBookmarked }
        "Templates" -> emptyList()
        else -> baseNotes.filter { it.folderId == selectedCategory }
    }

    val searchFiltered = if (searchQuery.isBlank()) categoryFiltered else {
        categoryFiltered.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    val sortedNotebooks = when (currentSortOption) {
        "Date Modified (Newest)" -> searchFiltered.sortedByDescending { it.updatedAtMillis }
        "Date Modified (Oldest)" -> searchFiltered.sortedBy { it.updatedAtMillis }
        "Title (A-Z)" -> searchFiltered.sortedBy { it.title.lowercase() }
        "Title (Z-A)" -> searchFiltered.sortedByDescending { it.title.lowercase() }
        else -> searchFiltered
    }

    val sidebarContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .width(if (isTablet) 240.dp else 280.dp)
                .fillMaxHeight()
                .background(if (isTablet) Color(0xFF0F0F0F) else Color(0xFF141416))
                .border(width = 0.5.dp, color = GraphiteOutline)
                .padding(horizontal = 14.dp, vertical = 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
        ) {
            // Top Title: Noteshelf
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Noteshelf",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.Gray
                            )
                        }
                    }
                    if (!isTablet) {
                        IconButton(onClick = { sidebarVisible = false }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Sidebar",
                                tint = Color.LightGray
                            )
                        }
                    }
                }
            }

            // Primary Smart Categories
            SidebarItem(
                title = "All Notes",
                icon = Icons.Default.Home,
                count = notebooks.count { !it.isDeleted },
                isSelected = selectedCategory == "All Notes",
                onClick = {
                    selectedCategory = "All Notes"
                    if (!isTablet) sidebarVisible = false
                }
            )
            SidebarItem(
                title = "Starred",
                icon = Icons.Default.Star,
                count = notebooks.count { it.isStarred && !it.isDeleted }.takeIf { it > 0 },
                isSelected = selectedCategory == "Starred",
                onClick = {
                    selectedCategory = "Starred"
                    if (!isTablet) sidebarVisible = false
                }
            )
            SidebarItem(
                title = "Unfiled",
                icon = Icons.Default.Inbox,
                isSelected = selectedCategory == "Unfiled",
                onClick = {
                    selectedCategory = "Unfiled"
                    if (!isTablet) sidebarVisible = false
                }
            )
            SidebarItem(
                title = "Trash",
                icon = Icons.Default.DeleteOutline,
                count = allNotesWithDeleted.count { it.isDeleted }.takeIf { it > 0 },
                isSelected = selectedCategory == "Trash",
                onClick = {
                    selectedCategory = "Trash"
                    if (!isTablet) sidebarVisible = false
                }
            )
            SidebarItem(
                title = "Templates",
                icon = Icons.Default.Palette,
                badgeText = "New",
                isSelected = selectedCategory == "Templates",
                onClick = {
                    selectedCategory = "Templates"
                    if (!isTablet) sidebarVisible = false
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Collapsible Folders Section
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Folders",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }

            folders.forEach { folder ->
                val noteCount = notebooks.count { it.folderId == folder.id && !it.isDeleted }
                SidebarItem(
                    title = folder.name,
                    icon = Icons.Default.Folder,
                    count = noteCount,
                    isSelected = selectedCategory == folder.id,
                    onClick = {
                        selectedCategory = folder.id
                        if (!isTablet) sidebarVisible = false
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showNewFolderDialog = true }
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AddCircleOutline,
                    contentDescription = "New Folder",
                    tint = GraphiteOrange,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "New Folder",
                    color = GraphiteOrange,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content Section
            Text(
                text = "Content",
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            SidebarItem(
                title = "Photos",
                icon = Icons.Default.Image,
                isSelected = selectedCategory == "Photos",
                onClick = {
                    selectedCategory = "Photos"
                    if (!isTablet) sidebarVisible = false
                }
            )
            SidebarItem(
                title = "Recordings",
                icon = Icons.Default.Mic,
                isSelected = selectedCategory == "Recordings",
                onClick = {
                    selectedCategory = "Recordings"
                    if (!isTablet) sidebarVisible = false
                }
            )
            SidebarItem(
                title = "Bookmarks",
                icon = Icons.Default.BookmarkBorder,
                isSelected = selectedCategory == "Bookmarks",
                onClick = {
                    selectedCategory = "Bookmarks"
                    if (!isTablet) sidebarVisible = false
                }
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GraphiteBlack)
            .statusBarsPadding()
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Sidebar for Tablet
            if (isTablet && sidebarVisible) {
                sidebarContent()
            }

            // Right Main Content Pane
            Column(
                modifier = Modifier
                    .then(if (isTablet) Modifier.weight(1f) else Modifier.fillMaxWidth())
                    .fillMaxHeight()
                    .padding(horizontal = if (isTablet) 18.dp else 14.dp, vertical = 12.dp)
            ) {
            // Main Top Bar: Sidebar toggle, Category title, Action buttons (+) (Search) (More)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { sidebarVisible = !sidebarVisible }) {
                        Icon(
                            imageVector = Icons.Default.ViewSidebar,
                            contentDescription = "Toggle Sidebar",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    val headerTitle = when (selectedCategory) {
                        "All Notes" -> "All Notes"
                        "Starred" -> "Starred Notes"
                        "Unfiled" -> "Unfiled Notes"
                        "Trash" -> "Trash"
                        "Templates" -> "Paper Templates"
                        "Photos" -> "Photos & Media Notes"
                        "Recordings" -> "Voice Recordings"
                        "Bookmarks" -> "Bookmarked Notes"
                        else -> folders.find { it.id == selectedCategory }?.name ?: selectedCategory
                    }
                    Text(
                        text = headerTitle,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showNewNotebookDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.AddCircle,
                            contentDescription = "Add Notebook",
                            tint = GraphiteOrange,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Notes",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            isSyncingNotes = true
                            scope.launch {
                                val sResult = syncManager.syncNotes()
                                isSyncingNotes = false
                                if (sResult.isSuccess) {
                                    Toast.makeText(context, "Synced ${sResult.getOrNull()} notes with Supabase", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Sync error: ${sResult.exceptionOrNull()?.message ?: "Check connection"}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isSyncingNotes
                    ) {
                        if (isSyncingNotes) {
                            CircularProgressIndicator(
                                color = GraphiteOrange,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Sync Notes",
                                tint = Color.White
                            )
                        }
                    }

                    // More Options Dropdown Menu
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options",
                                tint = Color.White
                            )
                        }

                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false },
                            modifier = Modifier.background(GraphiteSurfaceVariant)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Sort: Date Modified (Newest)", color = Color.White) },
                                onClick = {
                                    currentSortOption = "Date Modified (Newest)"
                                    moreMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort: Date Modified (Oldest)", color = Color.White) },
                                onClick = {
                                    currentSortOption = "Date Modified (Oldest)"
                                    moreMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort: Title (A-Z)", color = Color.White) },
                                onClick = {
                                    currentSortOption = "Title (A-Z)"
                                    moreMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort: Title (Z-A)", color = Color.White) },
                                onClick = {
                                    currentSortOption = "Title (Z-A)"
                                    moreMenuExpanded = false
                                }
                            )
                            HorizontalDivider(color = Color(0xFF333333))
                            DropdownMenuItem(
                                text = {
                                    Text(if (isListView) "Switch to Grid View" else "Switch to List View", color = Color.White)
                                },
                                onClick = {
                                    isListView = !isListView
                                    moreMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("New Folder", color = GraphiteOrange) },
                                onClick = {
                                    moreMenuExpanded = false
                                    showNewFolderDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sync Notes with Cloud", color = GraphiteOrange) },
                                onClick = {
                                    moreMenuExpanded = false
                                    if (!driveManager.hasDrivePermission(context)) {
                                        selectedNotebookForUpload = null
                                        val intent = driveManager.createAuthorizationIntent(context)
                                        driveAuthLauncher.launch(intent)
                                    } else {
                                        isSyncingNotes = true
                                        scope.launch {
                                            val dResult = driveManager.syncNotesWithDrive(context)
                                            val sResult = syncManager.syncNotes()
                                            isSyncingNotes = false
                                            val count = dResult.getOrNull() ?: sResult.getOrNull() ?: 0
                                            Toast.makeText(context, "Synced $count notes", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                            if (selectedCategory == "Trash") {
                                DropdownMenuItem(
                                    text = { Text("Empty Trash", color = Color(0xFFFF5252)) },
                                    onClick = {
                                        GraphiteRepository.emptyTrash()
                                        moreMenuExpanded = false
                                        Toast.makeText(context, "Trash emptied", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))
                    GoogleProfileTopBarIcon()
                }
            }

            if (showSearchBar) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search notes...", color = Color.Gray) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GraphiteOrange,
                        unfocusedBorderColor = Color(0xFF333333),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }

            // Quick Creation Action Cards Row (Quick Note, Notebook, Import File)
            if (selectedCategory != "Templates" && selectedCategory != "Trash") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    QuickActionCard(
                        title = "Quick Note",
                        icon = Icons.Default.NoteAlt,
                        iconTint = Color(0xFFFFD54F),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val quickNote = GraphiteRepository.createNotebook("Quick Note")
                            onOpenNotebook(quickNote.id)
                        }
                    )
                    QuickActionCard(
                        title = "Notebook",
                        icon = Icons.Default.Book,
                        iconTint = Color(0xFF81C784),
                        modifier = Modifier.weight(1f),
                        onClick = { showNewNotebookDialog = true }
                    )
                    QuickActionCard(
                        title = "Import File",
                        icon = Icons.Default.FileDownload,
                        iconTint = Color(0xFF64B5F6),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            fileImportLauncher.launch(
                                arrayOf(
                                    "application/pdf",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                    "application/json",
                                    "*/*"
                                )
                            )
                        }
                    )
                }
            }

            if (selectedCategory == "Trash") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF211515))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Items in trash will remain until emptied.", color = Color.LightGray, fontSize = 12.sp)
                    Button(
                        onClick = {
                            GraphiteRepository.emptyTrash()
                            Toast.makeText(context, "Trash emptied", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Empty Trash", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // View: Templates Grid OR Notebooks (Grid/List)
            if (selectedCategory == "Templates") {
                TemplatesGrid(
                    onCreateFromTemplate = { title, paperStyle ->
                        val newNote = GraphiteRepository.createNotebook(title, paperStyle = paperStyle)
                        onOpenNotebook(newNote.id)
                    }
                )
            } else if (isListView) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(sortedNotebooks) { notebook ->
                        NotebookListRow(
                            notebook = notebook,
                            onClick = { onOpenNotebook(notebook.id) },
                            onToggleStar = { GraphiteRepository.toggleStarNotebook(notebook.id) },
                            onToggleBookmark = { GraphiteRepository.toggleBookmarkNotebook(notebook.id) },
                            onRename = { showRenameDialogForNote = notebook },
                            onMoveFolder = { showMoveFolderDialogForNote = notebook },
                            onRestore = { GraphiteRepository.restoreNotebook(notebook.id) },
                            onPermanentlyDelete = { GraphiteRepository.permanentlyDeleteNotebook(notebook.id) },
                            onDelete = { GraphiteRepository.deleteNotebook(notebook.id) },
                            onUploadToDrive = {
                                selectedNotebookForUpload = notebook
                                if (!driveManager.hasDrivePermission(context)) {
                                    val intent = driveManager.createAuthorizationIntent(context)
                                    driveAuthLauncher.launch(intent)
                                } else {
                                    scope.launch {
                                        driveManager.startUploadFlow(context, notebook)
                                    }
                                }
                            }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(sortedNotebooks) { notebook ->
                        NotebookThumbnailCard(
                            notebook = notebook,
                            onClick = { onOpenNotebook(notebook.id) },
                            onToggleStar = { GraphiteRepository.toggleStarNotebook(notebook.id) },
                            onToggleBookmark = { GraphiteRepository.toggleBookmarkNotebook(notebook.id) },
                            onRename = { showRenameDialogForNote = notebook },
                            onMoveFolder = { showMoveFolderDialogForNote = notebook },
                            onRestore = { GraphiteRepository.restoreNotebook(notebook.id) },
                            onPermanentlyDelete = { GraphiteRepository.permanentlyDeleteNotebook(notebook.id) },
                            onDelete = { GraphiteRepository.deleteNotebook(notebook.id) },
                            onUploadToDrive = {
                                selectedNotebookForUpload = notebook
                                if (!driveManager.hasDrivePermission(context)) {
                                    val intent = driveManager.createAuthorizationIntent(context)
                                    driveAuthLauncher.launch(intent)
                                } else {
                                    scope.launch {
                                        driveManager.startUploadFlow(context, notebook)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

        // Mobile slide-over drawer overlay
        if (!isTablet) {
            AnimatedVisibility(
                visible = sidebarVisible,
                enter = fadeIn() + slideInHorizontally(initialOffsetX = { -it }),
                exit = fadeOut() + slideOutHorizontally(targetOffsetX = { -it })
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable { sidebarVisible = false }
                    )
                    sidebarContent()
                }
            }
        }
    }

    if (showNewNotebookDialog) {
        NewNotebookDialog(
            onDismiss = { showNewNotebookDialog = false },
            onCreate = { title ->
                val newNote = GraphiteRepository.createNotebook(title)
                showNewNotebookDialog = false
                onOpenNotebook(newNote.id)
            }
        )
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onCreate = { name ->
                GraphiteRepository.addFolder(name)
                showNewFolderDialog = false
            }
        )
    }

    showRenameDialogForNote?.let { note ->
        RenameNoteDialog(
            currentTitle = note.title,
            onDismiss = { showRenameDialogForNote = null },
            onRename = { newTitle ->
                GraphiteRepository.renameNotebook(note.id, newTitle)
                showRenameDialogForNote = null
            }
        )
    }

    showMoveFolderDialogForNote?.let { note ->
        MoveNoteFolderDialog(
            folders = folders,
            currentFolderId = note.folderId,
            onDismiss = { showMoveFolderDialogForNote = null },
            onSelectFolder = { folderId ->
                GraphiteRepository.moveNotebookToFolder(note.id, folderId)
                showMoveFolderDialogForNote = null
            }
        )
    }

    if (isImportingFile) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Importing File", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = GraphiteOrange, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Reading and validating document from tablet storage...", color = Color.LightGray, fontSize = 13.sp)
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
private fun SidebarItem(
    title: String,
    icon: ImageVector,
    count: Int? = null,
    badgeText: String? = null,
    isSelected: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) GraphiteOrange else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) Color.White else Color(0xFFB0B0B0),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                color = if (isSelected) Color.White else Color(0xFFD0D0D0),
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }

        if (count != null) {
            Text(
                text = count.toString(),
                color = if (isSelected) Color.White else Color.Gray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        } else if (badgeText != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFFF9800))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badgeText,
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(76.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun NotebookThumbnailCard(
    notebook: NotebookItem,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRename: () -> Unit,
    onMoveFolder: () -> Unit,
    onRestore: () -> Unit,
    onPermanentlyDelete: () -> Unit,
    onDelete: () -> Unit,
    onUploadToDrive: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isPdf = notebook.mimeType?.contains("pdf", ignoreCase = true) == true ||
            notebook.filePath?.endsWith(".pdf", ignoreCase = true) == true
    val isDoc = notebook.filePath?.endsWith(".docx", ignoreCase = true) == true ||
            notebook.filePath?.endsWith(".pptx", ignoreCase = true) == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Document Thumbnail Sheet matching Page 4
        Card(
            modifier = Modifier
                .width(130.dp)
                .height(170.dp)
                .shadow(4.dp, RoundedCornerShape(6.dp)),
            colors = CardDefaults.cardColors(
                containerColor = if (isPdf) Color(0xFF211515) else Color(0xFFFAFAFA)
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isPdf) {
                    // PDF Badge & Document Layout
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFD32F2F))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("PDF", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            if (notebook.isStarred) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(16.dp))
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = "PDF File",
                            tint = Color(0xFFEF5350),
                            modifier = Modifier.size(46.dp)
                        )

                        Text(
                            text = "${notebook.pageCount} pages",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (isDoc) {
                    // Word / PPTX Document Layout
                    val isPpt = notebook.filePath?.endsWith(".pptx", true) == true
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isPpt) Color(0xFFD32F2F) else Color(0xFF1976D2))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(if (isPpt) "PPTX" else "DOCX", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            if (notebook.isStarred) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(16.dp))
                            }
                        }

                        Icon(
                            imageVector = if (isPpt) Icons.Default.Slideshow else Icons.Default.Description,
                            contentDescription = "Document",
                            tint = if (isPpt) Color(0xFFFF7043) else Color(0xFF42A5F5),
                            modifier = Modifier.size(46.dp)
                        )

                        Text(
                            text = if (isPpt) "${notebook.pageCount} slides" else "${notebook.pageCount} pages",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    // Handwritten Lined Paper Notebook Layout
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    ) {
                        repeat(9) { lineIdx ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(if (lineIdx % 3 == 0) 0.65f else 0.9f)
                                    .height(1.dp)
                                    .background(Color(0xFFE0E0E0))
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }
                }

                // Cloud Upload Badge Icon (top right)
                IconButton(
                    onClick = onUploadToDrive,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(26.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = "Upload to Google Drive",
                        tint = GraphiteOrange,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // 3-dots Menu Button (bottom right)
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Note Options",
                        tint = if (isPdf) Color.LightGray else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(GraphiteSurfaceVariant)
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isPdf) "Open PDF" else "Open Note", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            onClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (notebook.isStarred) "Unstar" else "Star", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            onToggleStar()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (notebook.isBookmarked) "Remove Bookmark" else "Bookmark", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            onToggleBookmark()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Move to Folder", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            onMoveFolder()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Upload to Google Drive", color = GraphiteOrange) },
                        onClick = {
                            menuExpanded = false
                            onUploadToDrive()
                        }
                    )
                    HorizontalDivider(color = Color(0xFF333333))
                    if (notebook.isDeleted) {
                        DropdownMenuItem(
                            text = { Text("Restore Note", color = Color(0xFF81C784)) },
                            onClick = {
                                menuExpanded = false
                                onRestore()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Permanently", color = Color(0xFFFF5252)) },
                            onClick = {
                                menuExpanded = false
                                onPermanentlyDelete()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Move to Trash", color = Color(0xFFFF5252)) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Title and Timestamp matching Page 4
        Text(
            text = notebook.title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = notebook.updatedAtFormatted,
            color = Color(0xFF888888),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun NotebookListRow(
    notebook: NotebookItem,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRename: () -> Unit,
    onMoveFolder: () -> Unit,
    onRestore: () -> Unit,
    onPermanentlyDelete: () -> Unit,
    onDelete: () -> Unit,
    onUploadToDrive: () -> Unit
) {
    val isPdf = notebook.mimeType?.contains("pdf", ignoreCase = true) == true ||
            notebook.filePath?.endsWith(".pdf", ignoreCase = true) == true
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF141414))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.Description,
            contentDescription = null,
            tint = if (isPdf) Color(0xFFEF5350) else GraphiteOrange,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = notebook.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (notebook.isStarred) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(14.dp))
                }
            }
            Text(
                text = "${if (isPdf) "PDF • " else ""}${notebook.updatedAtFormatted}",
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = null, tint = Color.Gray)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(GraphiteSurfaceVariant)
            ) {
                DropdownMenuItem(
                    text = { Text(if (isPdf) "Open PDF" else "Open Note", color = Color.White) },
                    onClick = { menuExpanded = false; onClick() }
                )
                DropdownMenuItem(
                    text = { Text(if (notebook.isStarred) "Unstar" else "Star", color = Color.White) },
                    onClick = { menuExpanded = false; onToggleStar() }
                )
                DropdownMenuItem(
                    text = { Text("Rename", color = Color.White) },
                    onClick = { menuExpanded = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Upload to Drive", color = GraphiteOrange) },
                    onClick = { menuExpanded = false; onUploadToDrive() }
                )
                if (notebook.isDeleted) {
                    DropdownMenuItem(
                        text = { Text("Restore", color = Color(0xFF81C784)) },
                        onClick = { menuExpanded = false; onRestore() }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Move to Trash", color = Color(0xFFFF5252)) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplatesGrid(
    onCreateFromTemplate: (String, PaperStyle) -> Unit
) {
    val templates = listOf(
        Triple("Ruled Paper", PaperStyle.RULED, "Classic horizontal lined note"),
        Triple("Grid Paper", PaperStyle.GRID, "Math, science & architectural grid"),
        Triple("Dot Grid", PaperStyle.DOTTED, "Bullet journaling & wireframes"),
        Triple("Blank Canvas", PaperStyle.BLANK, "Freeform drawing & sketching"),
        Triple("Cornell Notes", PaperStyle.RULED, "Lecture review & key points"),
        Triple("Weekly Planner", PaperStyle.GRID, "Daily schedule & priority tasks")
    )

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(templates) { (title, style, desc) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCreateFromTemplate(title, style) },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF191919)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(GraphiteOrange.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (style) {
                                PaperStyle.GRID -> Icons.Default.GridOn
                                PaperStyle.DOTTED -> Icons.Default.Grain
                                PaperStyle.BLANK -> Icons.Default.CropPortrait
                                else -> Icons.Default.MenuBook
                            },
                            contentDescription = null,
                            tint = GraphiteOrange,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(desc, color = Color.Gray, fontSize = 11.sp, maxLines = 2)
                    Button(
                        onClick = { onCreateFromTemplate(title, style) },
                        colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Use Template", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewNotebookDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Notebook", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Notebook Title", color = Color.Gray) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onCreate(title.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
            ) {
                Text("Create", color = Color.White)
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

@Composable
private fun NewFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Folder", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder Name", color = Color.Gray) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
            ) {
                Text("Create", color = Color.White)
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

@Composable
private fun RenameNoteDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var newTitle by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Note", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text("Title", color = Color.Gray) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { if (newTitle.isNotBlank()) onRename(newTitle.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
            ) {
                Text("Rename", color = Color.White)
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

@Composable
private fun MoveNoteFolderDialog(
    folders: List<FolderItem>,
    currentFolderId: String,
    onDismiss: () -> Unit,
    onSelectFolder: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to Folder", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    TextButton(
                        onClick = { onSelectFolder("unfiled") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("• Unfiled", color = if (currentFolderId == "unfiled") GraphiteOrange else Color.White)
                    }
                }
                items(folders) { folder ->
                    TextButton(
                        onClick = { onSelectFolder(folder.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("• ${folder.name}", color = if (currentFolderId == folder.id) GraphiteOrange else Color.White)
                    }
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
