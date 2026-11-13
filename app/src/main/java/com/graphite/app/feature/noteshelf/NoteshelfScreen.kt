package com.graphite.app.feature.noteshelf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.graphite.app.data.model.NotebookCover
import com.graphite.app.core.importer.PdfPageThumbnail
import com.graphite.app.core.sharing.FileSharingHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.content.Context
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.auth.AccountRequiredDialog
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
import com.graphite.app.feature.workspace.model.SplitLayoutOrientation
import kotlinx.coroutines.launch

data class DuplicateFileWarningInfo(
    val targetFolder: FolderItem,
    val duplicateTitle: String,
    val noteToMove: NotebookItem,
    val otherNoteIdsToMove: Set<String> = emptySet()
)

@Composable
fun NoteshelfScreen(
    onOpenNotebook: (String) -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    onOpenSplitScreen: ((String, String, SplitLayoutOrientation) -> Unit)? = null,
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
    var importProgressText by remember { mutableStateOf("Reading and validating document from tablet storage...") }

    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600
    val syncManager = remember { GraphiteApplication.instance.supabaseSyncManager }
    var isSyncingNotes by remember { mutableStateOf(false) }

    val appUpdateManager = remember { GraphiteApplication.instance.appUpdateManager }
    val latestAvailableUpdate by appUpdateManager.latestAvailableUpdate.collectAsState()

    val googleAccountManager = remember { GraphiteApplication.instance.googleAccountManager }
    val googleUser by googleAccountManager.currentUser.collectAsState()
    val isGuestMode by googleAccountManager.isGuestMode.collectAsState()
    val hasActiveSession = googleUser != null || isGuestMode

    var showAccountRequiredDialog by remember { mutableStateOf(false) }
    var pendingAccountAction by remember { mutableStateOf("create a notebook") }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        googleAccountManager.handleSignInResult(result.data)
    }

    LaunchedEffect(hasActiveSession) {
        GraphiteRepository.cleanNotebookDuplicatesAndGhosts()
        if (hasActiveSession) {
            withContext(Dispatchers.IO) {
                googleAccountManager.ensureAuthenticatedWithSupabase(context)
            }
        }
        syncManager.triggerAutoSync(debounceMs = 0L)
    }

    // Dialogs & state
    var selectedCategory by remember { mutableStateOf("All Notes") }
    var sidebarVisible by remember(isTablet) { mutableStateOf(isTablet) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }

    var showNewNotebookDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var selectedParentFolderForSubfolder by remember { mutableStateOf<FolderItem?>(null) }
    var showRenameDialogForNote by remember { mutableStateOf<NotebookItem?>(null) }
    var showCoverPickerForNote by remember { mutableStateOf<NotebookItem?>(null) }
    var showMoveFolderDialogForNote by remember { mutableStateOf<NotebookItem?>(null) }
    var showMoveMultiFolderDialog by remember { mutableStateOf(false) }
    var showCreationOptionsSheet by remember { mutableStateOf(false) }

    var pendingUnlockNote by remember { mutableStateOf<NotebookItem?>(null) }
    var noteToChangePassword by remember { mutableStateOf<NotebookItem?>(null) }
    var splitScreenDocA by remember { mutableStateOf<NotebookItem?>(null) }
    var showSplitDocPicker by remember { mutableStateOf(false) }
    var showMultiSelectSplitDialog by remember { mutableStateOf(false) }

    val handleOpenNote: (NotebookItem) -> Unit = { notebook ->
        if (notebook.isLocked && !notebook.password.isNullOrBlank()) {
            pendingUnlockNote = notebook
        } else {
            onOpenNotebook(notebook.id)
        }
    }

    var moreMenuExpanded by remember { mutableStateOf(false) }
    val sortPrefs = remember { context.getSharedPreferences("graphite_sort_prefs", Context.MODE_PRIVATE) }
    var currentSortOption by remember {
        mutableStateOf(sortPrefs.getString("notes_sort_order", "Date Modified (Newest)") ?: "Date Modified (Newest)")
    }
    var customOrderIds by remember {
        val saved = sortPrefs.getString("custom_order_ids", "") ?: ""
        mutableStateOf(saved.split(",").filter { it.isNotBlank() })
    }
    var folderForOptionsDialog by remember { mutableStateOf<FolderItem?>(null) }
    var isListView by remember { mutableStateOf(false) }

    var showFoldersOnNotesScreen by remember {
        mutableStateOf(sortPrefs.getBoolean("show_folders_on_screen", true))
    }

    // Drag and Drop state
    var isDraggingNote by remember { mutableStateOf(false) }
    var draggedNotebook by remember { mutableStateOf<NotebookItem?>(null) }
    var duplicateWarningDialogInfo by remember { mutableStateOf<DuplicateFileWarningInfo?>(null) }
    var dragTouchPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var totalDragDistance by remember { mutableFloatStateOf(0f) }
    var hoveredFolderId by remember { mutableStateOf<String?>(null) }
    val folderBoundsMap = remember { mutableMapOf<String, Rect>() }

    // Folder upload state
    var uploadingFolderInfo by remember { mutableStateOf<Pair<FolderItem, Triple<Int, Int, String>?>?>(null) }
    var isUploadingFolder by remember { mutableStateOf(false) }

    // Note Selection & Deletion state
    var selectedNotebookIds by remember { mutableStateOf(setOf<String>()) }
    var notesToDelete by remember { mutableStateOf<List<NotebookItem>?>(null) }
    var isDeletingNotesCloud by remember { mutableStateOf(false) }

    LaunchedEffect(googleUser?.email, isGuestMode) {
        selectedCategory = "All Notes"
        selectedNotebookIds = emptySet()
        isDraggingNote = false
        draggedNotebook = null
        hoveredFolderId = null
        duplicateWarningDialogInfo = null
    }

    val fileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isImportingFile = true
            scope.launch {
                if (uris.size == 1) {
                    importProgressText = "Reading and validating document from tablet storage..."
                    val result = fileImportManager.importToNotes(context, uris[0])
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
                } else {
                    var successCount = 0
                    var failCount = 0
                    val total = uris.size
                    for ((index, uri) in uris.withIndex()) {
                        importProgressText = "Importing document ${index + 1} of $total..."
                        try {
                            when (fileImportManager.importToNotes(context, uri)) {
                                is ImportResult.SuccessNotebook, is ImportResult.SuccessBook -> {
                                    successCount++
                                }
                                is ImportResult.Error -> {
                                    failCount++
                                }
                            }
                        } catch (_: Exception) {
                            failCount++
                        }
                    }
                    isImportingFile = false
                    importStatusMessage = when {
                        failCount == 0 -> "Successfully imported all $successCount notes."
                        successCount > 0 -> "Imported $successCount notes ($failCount failed)."
                        else -> "Failed to import selected documents."
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

    val handleUploadWholeFolder: (FolderItem) -> Unit = { folder ->
        val folderNotes = notebooks.filter { it.folderId == folder.id && !it.isDeleted }
        if (folderNotes.isEmpty()) {
            Toast.makeText(context, "Folder \"${folder.name}\" has no notes to upload.", Toast.LENGTH_SHORT).show()
        } else if (!driveManager.hasDrivePermission(context)) {
            selectedNotebookForUpload = null
            val intent = driveManager.createAuthorizationIntent(context)
            driveAuthLauncher.launch(intent)
        } else {
            isUploadingFolder = true
            uploadingFolderInfo = Pair(folder, Triple(0, folderNotes.size, "Preparing folder in Google Drive..."))
            scope.launch {
                val result = driveManager.uploadEntireFolder(
                    context = context,
                    folder = folder,
                    items = folderNotes,
                    onProgress = { current, total, itemTitle ->
                        uploadingFolderInfo = Pair(folder, Triple(current, total, itemTitle))
                    }
                )
                isUploadingFolder = false
                uploadingFolderInfo = null
                if (result.isSuccess) {
                    val count = result.getOrNull() ?: 0
                    if (count > 0) {
                        val noteLabel = if (count == 1) "1 note" else "$count notes"
                        Toast.makeText(context, "Synced $noteLabel in \"${folder.name}\" to Google Drive!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Folder \"${folder.name}\" is already up to date in Google Drive!", Toast.LENGTH_LONG).show()
                    }
                    syncManager.syncNotes()
                } else {
                    Toast.makeText(context, "Folder upload failed: ${result.exceptionOrNull()?.message ?: "Check connection"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val handleDropOnFolder: (String) -> Unit = { targetFolderId ->
        val target = folders.find { it.id == targetFolderId }
        val note = draggedNotebook
        if (target != null && note != null) {
            val toMove = if (note.id in selectedNotebookIds && selectedNotebookIds.size > 1) {
                selectedNotebookIds
            } else {
                setOf(note.id)
            }
            val notesToMoveList = notebooks.filter { it.id in toMove }
            val existingNotesInTarget = notebooks.filter { it.folderId == target.id && !it.isDeleted }
            val duplicateNote = notesToMoveList.find { n ->
                existingNotesInTarget.any { existing ->
                    existing.id != n.id && existing.title.trim().equals(n.title.trim(), ignoreCase = true)
                }
            }
            if (duplicateNote != null) {
                duplicateWarningDialogInfo = DuplicateFileWarningInfo(
                    targetFolder = target,
                    duplicateTitle = duplicateNote.title,
                    noteToMove = duplicateNote,
                    otherNoteIdsToMove = toMove - duplicateNote.id
                )
            } else {
                GraphiteRepository.moveNotebooksToFolder(toMove, target.id)
                val msg = if (toMove.size == 1) "Moved \"${note.title}\" to ${target.name}"
                          else "Moved ${toMove.size} notes to ${target.name}"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                selectedNotebookIds = emptySet()
            }
        }
    }

    // Filter notes according to category and search query
    val allNotesWithDeleted = GraphiteRepository.getAllNotesIncludingDeleted()
    val baseNotes = if (selectedCategory == "Trash") {
        allNotesWithDeleted.filter { it.isDeleted }
    } else {
        notebooks.filter { !it.isDeleted }
    }

    val categoryFiltered = when (selectedCategory) {
        "All Notes" -> {
            if (hasActiveSession && showFoldersOnNotesScreen && folders.any { it.parentId == null }) {
                baseNotes.filter { it.folderId.isBlank() || it.folderId == "unfiled" }
            } else {
                baseNotes
            }
        }
        "Starred" -> baseNotes.filter { it.isStarred }
        "Unfiled" -> baseNotes.filter { it.folderId.isBlank() || it.folderId == "unfiled" }
        "Trash" -> baseNotes
        "Photos" -> baseNotes.filter { it.hasPhotos || it.previewLines.any { l -> l.contains("image", true) || l.contains("photo", true) } }
        "Recordings" -> baseNotes.filter { it.hasRecordings || it.voiceNotes.isNotEmpty() || it.previewLines.any { l -> l.contains("[GRAPHITE_VOICE:") || l.contains("audio", true) || l.contains("recording", true) } }
        "Bookmarks" -> baseNotes.filter { it.isBookmarked }
        "Templates" -> emptyList()
        else -> baseNotes.filter { it.folderId == selectedCategory }
    }

    val searchFiltered = if (searchQuery.isBlank()) categoryFiltered else {
        categoryFiltered.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    val sortedNotebooks = remember(searchFiltered, currentSortOption, customOrderIds) {
        when (currentSortOption) {
            "Custom" -> {
                searchFiltered.sortedBy { nb ->
                    val idx = customOrderIds.indexOf(nb.id)
                    if (idx >= 0) idx else Int.MAX_VALUE
                }
            }
            "Date Modified (Newest)" -> searchFiltered.sortedByDescending { it.updatedAtMillis }
            "Date Modified (Oldest)" -> searchFiltered.sortedBy { it.updatedAtMillis }
            "Title (A-Z)" -> searchFiltered.sortedWith { a, b -> com.graphite.app.core.util.NaturalOrderComparator.compare(a.title, b.title) }
            "Title (Z-A)" -> searchFiltered.sortedWith { a, b -> com.graphite.app.core.util.NaturalOrderComparator.compare(b.title, a.title) }
            else -> searchFiltered
        }
    }

    val moveNoteInCustomOrder: (String, Int) -> Unit = { noteId, delta ->
        val allIds = sortedNotebooks.map { it.id }
        val mergedList = customOrderIds.toMutableList()
        allIds.forEach { id -> if (!mergedList.contains(id)) mergedList.add(id) }

        val idx = mergedList.indexOf(noteId)
        if (idx >= 0) {
            val targetIdx = (idx + delta).coerceIn(0, mergedList.size - 1)
            if (targetIdx != idx) {
                mergedList.removeAt(idx)
                mergedList.add(targetIdx, noteId)
                customOrderIds = mergedList
                sortPrefs.edit().putString("custom_order_ids", mergedList.joinToString(",")).apply()
            }
        }
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
                count = (notebooks.count { it.isStarred && !it.isDeleted } + folders.count { it.isStarred }).takeIf { it > 0 },
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
                isSelected = selectedCategory == "Templates",
                onClick = {
                    selectedCategory = "Templates"
                    if (!isTablet) sidebarVisible = false
                }
            )
            val recordingsCount = notebooks.count { !it.isDeleted && (it.hasRecordings || it.voiceNotes.isNotEmpty() || it.previewLines.any { l -> l.contains("[GRAPHITE_VOICE:") }) }
            SidebarItem(
                title = "Recordings",
                icon = Icons.Default.GraphicEq,
                count = recordingsCount.takeIf { it > 0 },
                isSelected = selectedCategory == "Recordings",
                onClick = {
                    selectedCategory = "Recordings"
                    if (!isTablet) sidebarVisible = false
                }
            )

            // Folders & Subfolders Section (strictly visible only when user has active session)
            if (hasActiveSession) {
                Spacer(modifier = Modifier.height(16.dp))

                if (folders.isNotEmpty()) {
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

                    val rootFolders = folders.filter { it.parentId == null }
                    rootFolders.forEach { folder ->
                        val noteCount = notebooks.count { it.folderId == folder.id && !it.isDeleted }
                        val subfolders = folders.filter { it.parentId == folder.id }
                        val folderColor = folder.colorHex?.let { Color(it) } ?: Color(0xFFFFD54F)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                SidebarItem(
                                    title = folder.name,
                                    icon = Icons.Default.Folder,
                                    iconSize = 24.dp,
                                    iconTint = folderColor,
                                    count = noteCount,
                                    isSelected = selectedCategory == folder.id,
                                    onLongClick = { folderForOptionsDialog = folder },
                                    onClick = {
                                        selectedCategory = folder.id
                                        if (!isTablet) sidebarVisible = false
                                    }
                                )
                            }
                            IconButton(
                                onClick = { folderForOptionsDialog = folder },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Folder Settings",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    selectedParentFolderForSubfolder = folder
                                    showNewFolderDialog = true
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CreateNewFolder,
                                    contentDescription = "Add Subfolder",
                                    tint = Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Render subfolders indented
                        subfolders.forEach { subfolder ->
                            val subNoteCount = notebooks.count { it.folderId == subfolder.id && !it.isDeleted }
                            val subColor = subfolder.colorHex?.let { Color(it) } ?: Color(0xFFFFD54F)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    SidebarItem(
                                        title = subfolder.name,
                                        icon = Icons.Default.FolderOpen,
                                        iconSize = 22.dp,
                                        iconTint = subColor,
                                        count = subNoteCount,
                                        isSelected = selectedCategory == subfolder.id,
                                        onLongClick = { folderForOptionsDialog = subfolder },
                                        onClick = {
                                            selectedCategory = subfolder.id
                                            if (!isTablet) sidebarVisible = false
                                        }
                                    )
                                }
                                IconButton(
                                    onClick = { folderForOptionsDialog = subfolder },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Subfolder Settings",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            selectedParentFolderForSubfolder = null
                            showNewFolderDialog = true
                        }
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
            }
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
            if (selectedNotebookIds.isNotEmpty()) {
                // Contextual Selection Action Bar with Delete (Bin) icon
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedNotebookIds = emptySet() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel Selection",
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${selectedNotebookIds.size} Selected",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedNotebookIds.size == 2 && onOpenSplitScreen != null) {
                            Button(
                                onClick = {
                                    showMultiSelectSplitDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Icon(Icons.Default.Splitscreen, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Split View (2)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        val allFilteredIds = sortedNotebooks.map { it.id }.toSet()
                        val allSelected = selectedNotebookIds.size == allFilteredIds.size && allFilteredIds.isNotEmpty()

                        IconButton(
                            onClick = {
                                selectedNotebookIds = if (allSelected) emptySet() else allFilteredIds
                            }
                        ) {
                            Icon(
                                imageVector = if (allSelected) Icons.Default.CheckCircle else Icons.Default.SelectAll,
                                contentDescription = if (allSelected) "Deselect All" else "Select All",
                                tint = if (allSelected) GraphiteOrange else Color.White
                            )
                        }

                        IconButton(
                            onClick = { showMoveMultiFolderDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.DriveFileMove,
                                contentDescription = "Move Selected to Folder",
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val toShare = allNotesWithDeleted.filter { it.id in selectedNotebookIds }
                                if (toShare.isNotEmpty()) {
                                    FileSharingHelper.shareNotebooks(context, toShare)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Selected Notes",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val toDelete = allNotesWithDeleted.filter { it.id in selectedNotebookIds }
                                if (toDelete.isNotEmpty()) {
                                    notesToDelete = toDelete
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Selected Notes",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            } else {
                // Main Top Bar: Sidebar toggle, Category title, Action buttons (+) (Search) (More)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 12.dp),
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
                        "Starred" -> "Favorites & Starred"
                        "Unfiled" -> "Unfiled Notes"
                        "Trash" -> "Trash"
                        "Templates" -> "Templates"
                        "Photos" -> "Photos & Media Notes"
                        "Recordings" -> "Voice Recordings"
                        "Bookmarks" -> "Bookmarked Notes"
                        else -> folders.find { it.id == selectedCategory }?.name ?: selectedCategory
                    }
                    Text(
                        text = headerTitle,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Serif
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Notes",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            if (!hasActiveSession) {
                                pendingAccountAction = "sync notes"
                                showAccountRequiredDialog = true
                            } else {
                                isSyncingNotes = true
                                scope.launch {
                                    // 1. Authenticate & sync notes and folders with Supabase
                                    googleAccountManager.ensureAuthenticatedWithSupabase(context)
                                    val sResult = syncManager.syncNotes()

                                    // 2. Sync notes and folders with Google Drive if authorized
                                    val dResult = if (driveManager.hasDrivePermission(context)) {
                                        driveManager.syncNotesWithDrive(context)
                                    } else null

                                    // 3. Trigger fast background check for app updates
                                    appUpdateManager.checkForUpdates(context, isManualCheck = false)

                                    isSyncingNotes = false
                                    val activeNotes = notebooks.filter { !it.isDeleted }.size
                                    val activeFolders = folders.size
                                    if (sResult.isSuccess || (dResult != null && dResult.isSuccess)) {
                                        val count = sResult.getOrNull() ?: dResult?.getOrNull() ?: activeNotes
                                        Toast.makeText(context, "Synced $count notes and $activeFolders folders", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val err = sResult.exceptionOrNull()?.message ?: dResult?.exceptionOrNull()?.message ?: ""
                                        val userFriendlyMsg = when {
                                            err.contains("42501") || err.contains("row-level security", ignoreCase = true) ->
                                                "Notes and folders saved locally. Sign in with Google to sync across devices."
                                            err.contains("401") || err.contains("unauthorized", ignoreCase = true) ->
                                                "Sync session expired. Please tap your avatar to refresh."
                                            err.contains("Unable to resolve host") || err.contains("timeout", ignoreCase = true) ->
                                                "Network offline. Notes and folders saved safely on device."
                                            else ->
                                                "Notes and folders saved locally on device."
                                        }
                                        Toast.makeText(context, userFriendlyMsg, Toast.LENGTH_SHORT).show()
                                    }
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
                                text = { Text("Sort: Custom (Hold & Reorder)", color = if (currentSortOption == "Custom") GraphiteOrange else Color.White) },
                                onClick = {
                                    currentSortOption = "Custom"
                                    sortPrefs.edit().putString("notes_sort_order", "Custom").apply()
                                    moreMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort: Date Modified (Newest)", color = if (currentSortOption == "Date Modified (Newest)") GraphiteOrange else Color.White) },
                                onClick = {
                                    currentSortOption = "Date Modified (Newest)"
                                    sortPrefs.edit().putString("notes_sort_order", "Date Modified (Newest)").apply()
                                    moreMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort: Date Modified (Oldest)", color = if (currentSortOption == "Date Modified (Oldest)") GraphiteOrange else Color.White) },
                                onClick = {
                                    currentSortOption = "Date Modified (Oldest)"
                                    sortPrefs.edit().putString("notes_sort_order", "Date Modified (Oldest)").apply()
                                    moreMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort: Title (A-Z)", color = if (currentSortOption == "Title (A-Z)") GraphiteOrange else Color.White) },
                                onClick = {
                                    currentSortOption = "Title (A-Z)"
                                    sortPrefs.edit().putString("notes_sort_order", "Title (A-Z)").apply()
                                    moreMenuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort: Title (Z-A)", color = if (currentSortOption == "Title (Z-A)") GraphiteOrange else Color.White) },
                                onClick = {
                                    currentSortOption = "Title (Z-A)"
                                    sortPrefs.edit().putString("notes_sort_order", "Title (Z-A)").apply()
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
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(if (showFoldersOnNotesScreen) "Hide Folders on Screen" else "Show Folders on Screen", color = Color.White)
                                        Icon(
                                            imageVector = if (showFoldersOnNotesScreen) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            tint = GraphiteOrange,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    showFoldersOnNotesScreen = !showFoldersOnNotesScreen
                                    sortPrefs.edit().putBoolean("show_folders_on_screen", showFoldersOnNotesScreen).apply()
                                    moreMenuExpanded = false
                                    Toast.makeText(
                                        context,
                                        if (showFoldersOnNotesScreen) "Folders shown on notes screen" else "Folders hidden from notes screen",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                            val currentActiveFolder = folders.find { it.id == selectedCategory }
                            DropdownMenuItem(
                                text = { Text(if (currentActiveFolder != null) "New Subfolder" else "New Folder", color = GraphiteOrange) },
                                onClick = {
                                    moreMenuExpanded = false
                                    selectedParentFolderForSubfolder = currentActiveFolder
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

            // In-app Update Notification Banner
            if (latestAvailableUpdate != null && selectedCategory != "Templates" && selectedCategory != "Trash") {
                val update = latestAvailableUpdate!!
                val shortSha = if (update.commitSha.length >= 7) update.commitSha.take(7) else update.commitSha
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { appUpdateManager.openUpdate(context, update) },
                    color = Color(0xFF26190B),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GraphiteOrange.copy(alpha = 0.8f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(GraphiteOrange.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = GraphiteOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🚀 Graphite Update Available ($shortSha)",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = update.releaseNotes.ifBlank { "Tap to download and install the latest build." },
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            onClick = { appUpdateManager.openUpdate(context, update) },
                            colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Update", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Quick Creation Action Cards Row (Quick Note, Notebook, Voice Note)
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
                            if (!hasActiveSession) {
                                pendingAccountAction = "create a quick note"
                                showAccountRequiredDialog = true
                            } else {
                                val quickNote = GraphiteRepository.createNotebook(
                                    title = "Quick Note",
                                    mimeType = "text/keep",
                                    initialPreviewLines = listOf("Tap to start typing your note..."),
                                    cover = NotebookCover(category = "NONE")
                                )
                                onOpenNotebook(quickNote.id)
                            }
                        }
                    )
                    QuickActionCard(
                        title = "New Notebook",
                        icon = Icons.Default.Book,
                        iconTint = Color(0xFF81C784),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (!hasActiveSession) {
                                pendingAccountAction = "create a notebook"
                                showAccountRequiredDialog = true
                            } else {
                                showCreationOptionsSheet = true
                            }
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

            // View: Templates Section OR Notebooks (Grid/List)
            if (selectedCategory == "Templates") {
                TemplatesSection(
                    onCreateFromTemplate = { title, paperStyle, cover ->
                        if (!hasActiveSession) {
                            pendingAccountAction = "create a notebook from template"
                            showAccountRequiredDialog = true
                        } else {
                            val newNote = GraphiteRepository.createNotebook(
                                title = title,
                                paperStyle = paperStyle,
                                cover = cover ?: NotebookCover()
                            )
                            onOpenNotebook(newNote.id)
                        }
                    },
                    onImportCustomTemplate = {
                        fileImportLauncher.launch(
                            arrayOf(
                                "application/pdf",
                                "image/*",
                                "*/*"
                            )
                        )
                    }
                )
            } else {
                val isSelectionMode = selectedNotebookIds.isNotEmpty()
                val toggleNoteSelection = { noteId: String ->
                    selectedNotebookIds = if (noteId in selectedNotebookIds) {
                        selectedNotebookIds - noteId
                    } else {
                        selectedNotebookIds + noteId
                    }
                }

                val activeFolder = folders.find { it.id == selectedCategory }
                val isInsideFolder = activeFolder != null
                val subfoldersInsideCurrentFolder = if (isInsideFolder && activeFolder != null) {
                    folders.filter { it.parentId == activeFolder.id }
                } else emptyList()

                val foldersToShow = if (hasActiveSession && showFoldersOnNotesScreen && searchQuery.isBlank()) {
                    if (isInsideFolder && activeFolder != null) {
                        subfoldersInsideCurrentFolder
                    } else if (selectedCategory == "Starred") {
                        folders.filter { it.isStarred }
                    } else if (selectedCategory == "All Notes") {
                        folders.filter { it.parentId == null }
                    } else emptyList()
                } else emptyList()

                val hasFoldersToShow = foldersToShow.isNotEmpty()

                // Filter / Category Chips Row matching Image 1
                if (selectedCategory != "Trash") {
                    if (isInsideFolder && activeFolder != null) {
                        val parentFolder = if (activeFolder.parentId != null) {
                            folders.find { it.id == activeFolder.parentId }
                        } else null
                        val backTargetCategory = parentFolder?.id ?: "All Notes"
                        val backButtonLabel = parentFolder?.name ?: "All Notes"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E1E22))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { selectedCategory = backTargetCategory }
                                    .padding(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to $backButtonLabel",
                                    tint = GraphiteOrange,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(backButtonLabel, color = GraphiteOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { handleUploadWholeFolder(activeFolder) },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color(0xFF1B5E20),
                                        contentColor = Color(0xFF81C784)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Upload Folder to Drive", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                IconButton(
                                    onClick = { folderForOptionsDialog = activeFolder },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = "Folder Settings", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = selectedCategory == "All Notes",
                                    onClick = { selectedCategory = "All Notes" },
                                    label = { Text("All", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GraphiteOrange,
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFF1E1E1E),
                                        labelColor = Color.LightGray
                                    ),
                                    border = null,
                                    shape = RoundedCornerShape(16.dp)
                                )

                                FilterChip(
                                    selected = selectedCategory == "Starred",
                                    onClick = { selectedCategory = if (selectedCategory == "Starred") "All Notes" else "Starred" },
                                    label = { Text("★ Favorites", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GraphiteOrange,
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFF1E1E1E),
                                        labelColor = Color.LightGray
                                    ),
                                    border = null,
                                    shape = RoundedCornerShape(16.dp)
                                )

                                if (hasActiveSession) {
                                    FilterChip(
                                        selected = showFoldersOnNotesScreen,
                                        onClick = {
                                            showFoldersOnNotesScreen = !showFoldersOnNotesScreen
                                            sortPrefs.edit().putBoolean("show_folders_on_screen", showFoldersOnNotesScreen).apply()
                                            Toast.makeText(
                                                context,
                                                if (showFoldersOnNotesScreen) "Folders shown on notes screen" else "Folders hidden from notes screen",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        label = {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Icon(
                                                    imageVector = if (showFoldersOnNotesScreen) Icons.Default.Folder else Icons.Default.FolderOff,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(if (showFoldersOnNotesScreen) "Folders" else "Folders: Off", fontSize = 12.sp)
                                            }
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFF263238),
                                            selectedLabelColor = Color(0xFF64B5F6),
                                            selectedLeadingIconColor = Color(0xFF64B5F6),
                                            containerColor = Color(0xFF1E1E1E),
                                            labelColor = Color.Gray
                                        ),
                                        border = BorderStroke(1.dp, if (showFoldersOnNotesScreen) Color(0xFF1976D2) else Color(0xFF333333)),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "↓ ${currentSortOption.substringBefore(" ")}",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { moreMenuExpanded = true }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                                IconButton(
                                    onClick = { isListView = !isListView },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isListView) Icons.Default.GridView else Icons.Default.ViewList,
                                        contentDescription = "Toggle View",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        selectedNotebookIds = if (selectedNotebookIds.isEmpty()) {
                                            setOfNotNull(sortedNotebooks.firstOrNull()?.id)
                                        } else {
                                            emptySet()
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircleOutline,
                                        contentDescription = "Selection Mode",
                                        tint = if (selectedNotebookIds.isNotEmpty()) GraphiteOrange else Color.LightGray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (sortedNotebooks.isEmpty() && foldersToShow.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF222226)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (selectedCategory == "Recordings") Icons.Default.GraphicEq else Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = if (selectedCategory == "Recordings") Color(0xFF00BCD4) else Color.Gray,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (!hasActiveSession) "Account Required" else if (selectedCategory == "Recordings") "No audio recordings yet" else if (isInsideFolder) "Folder is empty" else "No notebooks found",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (!hasActiveSession) "Sign in with Google to sync notes across devices or use a Guest Account for offline notes." else if (selectedCategory == "Recordings") "Voice notes and audio clips recorded in your notebooks will appear here." else if (isInsideFolder) "Drag notes into this folder or tap New Notebook to create one." else "Tap New Notebook or Quick Note above to create one.",
                                color = Color.Gray,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            if (!hasActiveSession) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        pendingAccountAction = "access your notes"
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
                } else if (isListView) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        if (hasFoldersToShow) {
                            items(foldersToShow, key = { "folder_list_${it.id}" }) { folder ->
                                val noteCount = notebooks.count { it.folderId == folder.id && !it.isDeleted }
                                FolderListRow(
                                    folder = folder,
                                    noteCount = noteCount,
                                    isHoveredForDrop = (hoveredFolderId == folder.id),
                                    onClick = { selectedCategory = folder.id },
                                    onLongClick = { folderForOptionsDialog = folder },
                                    onUploadFolder = { handleUploadWholeFolder(folder) },
                                    onOpenOptions = { folderForOptionsDialog = folder },
                                    onDeleteFolder = {
                                        GraphiteRepository.deleteFolder(folder.id)
                                        Toast.makeText(context, "Folder deleted", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        folderBoundsMap[folder.id] = coords.boundsInRoot()
                                    }
                                )
                            }
                        }
                        items(sortedNotebooks, key = { "note_${it.id}" }) { notebook ->
                            NotebookListRow(
                                notebook = notebook,
                                isSelected = notebook.id in selectedNotebookIds,
                                isSelectionMode = isSelectionMode,
                                isCustomSortActive = currentSortOption == "Custom",
                                onMoveCustomOrder = { delta -> moveNoteInCustomOrder(notebook.id, delta) },
                                onClick = {
                                    if (isSelectionMode) {
                                        toggleNoteSelection(notebook.id)
                                    } else {
                                        handleOpenNote(notebook)
                                    }
                                },
                                onLongClick = {
                                    toggleNoteSelection(notebook.id)
                                },
                                onDragStart = { rootPos ->
                                    isDraggingNote = true
                                    draggedNotebook = notebook
                                    dragTouchPositionInRoot = rootPos
                                    totalDragDistance = 0f
                                },
                                onDrag = { dragAmount ->
                                    dragTouchPositionInRoot += dragAmount
                                    totalDragDistance += dragAmount.getDistance()
                                    hoveredFolderId = folderBoundsMap.entries.firstOrNull { (_, rect) ->
                                        rect.contains(dragTouchPositionInRoot)
                                    }?.key
                                },
                                onDragEnd = {
                                    if (hoveredFolderId != null && draggedNotebook != null) {
                                        handleDropOnFolder(hoveredFolderId!!)
                                    } else if (totalDragDistance < 15f) {
                                        toggleNoteSelection(notebook.id)
                                    }
                                    isDraggingNote = false
                                    draggedNotebook = null
                                    hoveredFolderId = null
                                    totalDragDistance = 0f
                                },
                                onDragCancel = {
                                    isDraggingNote = false
                                    draggedNotebook = null
                                    hoveredFolderId = null
                                    totalDragDistance = 0f
                                },
                                onOpenSplitScreen = if (onOpenSplitScreen != null) { { splitScreenDocA = notebook; showSplitDocPicker = true } } else null,
                                onSetPassword = { noteToChangePassword = notebook },
                                onToggleStar = { GraphiteRepository.toggleStarNotebook(notebook.id) },
                                onToggleBookmark = { GraphiteRepository.toggleBookmarkNotebook(notebook.id) },
                                onRename = { showRenameDialogForNote = notebook },
                                onChangeCover = { showCoverPickerForNote = notebook },
                                onMoveFolder = { showMoveFolderDialogForNote = notebook },
                                onRestore = { GraphiteRepository.restoreNotebook(notebook.id) },
                                onPermanentlyDelete = { notesToDelete = listOf(notebook) },
                                onDelete = { notesToDelete = listOf(notebook) },
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
                        columns = GridCells.Adaptive(minSize = 135.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        if (hasFoldersToShow) {
                            items(foldersToShow, key = { "folder_${it.id}" }) { folder ->
                                val noteCount = notebooks.count { it.folderId == folder.id && !it.isDeleted }
                                FolderThumbnailCard(
                                    folder = folder,
                                    noteCount = noteCount,
                                    isHoveredForDrop = (hoveredFolderId == folder.id),
                                    onClick = { selectedCategory = folder.id },
                                    onLongClick = { folderForOptionsDialog = folder },
                                    onUploadFolder = { handleUploadWholeFolder(folder) },
                                    onOpenOptions = { folderForOptionsDialog = folder },
                                    onDeleteFolder = {
                                        GraphiteRepository.deleteFolder(folder.id)
                                        Toast.makeText(context, "Folder deleted", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        folderBoundsMap[folder.id] = coords.boundsInRoot()
                                    }
                                )
                            }
                        }

                        items(sortedNotebooks, key = { "note_${it.id}" }) { notebook ->
                            NotebookThumbnailCard(
                                notebook = notebook,
                                isSelected = notebook.id in selectedNotebookIds,
                                isSelectionMode = isSelectionMode,
                                isCustomSortActive = currentSortOption == "Custom",
                                onMoveCustomOrder = { delta -> moveNoteInCustomOrder(notebook.id, delta) },
                                onClick = {
                                    if (isSelectionMode) {
                                        toggleNoteSelection(notebook.id)
                                    } else {
                                        handleOpenNote(notebook)
                                    }
                                },
                                onLongClick = {
                                    toggleNoteSelection(notebook.id)
                                },
                                onDragStart = { rootPos ->
                                    isDraggingNote = true
                                    draggedNotebook = notebook
                                    dragTouchPositionInRoot = rootPos
                                    totalDragDistance = 0f
                                },
                                onDrag = { dragAmount ->
                                    dragTouchPositionInRoot += dragAmount
                                    totalDragDistance += dragAmount.getDistance()
                                    hoveredFolderId = folderBoundsMap.entries.firstOrNull { (_, rect) ->
                                        rect.contains(dragTouchPositionInRoot)
                                    }?.key
                                },
                                onDragEnd = {
                                    if (hoveredFolderId != null && draggedNotebook != null) {
                                        handleDropOnFolder(hoveredFolderId!!)
                                    } else if (totalDragDistance < 15f) {
                                        toggleNoteSelection(notebook.id)
                                    }
                                    isDraggingNote = false
                                    draggedNotebook = null
                                    hoveredFolderId = null
                                    totalDragDistance = 0f
                                },
                                onDragCancel = {
                                    isDraggingNote = false
                                    draggedNotebook = null
                                    hoveredFolderId = null
                                    totalDragDistance = 0f
                                },
                                onOpenSplitScreen = if (onOpenSplitScreen != null) { { splitScreenDocA = notebook; showSplitDocPicker = true } } else null,
                                onSetPassword = { noteToChangePassword = notebook },
                                onToggleStar = { GraphiteRepository.toggleStarNotebook(notebook.id) },
                                onToggleBookmark = { GraphiteRepository.toggleBookmarkNotebook(notebook.id) },
                                onRename = { showRenameDialogForNote = notebook },
                                onChangeCover = { showCoverPickerForNote = notebook },
                                onMoveFolder = { showMoveFolderDialogForNote = notebook },
                                onRestore = { GraphiteRepository.restoreNotebook(notebook.id) },
                                onPermanentlyDelete = { notesToDelete = listOf(notebook) },
                                onDelete = { notesToDelete = listOf(notebook) },
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
    }

    // Floating Drag-and-Drop Note Overlay
    if (isDraggingNote && draggedNotebook != null) {
        val note = draggedNotebook!!
        val count = if (note.id in selectedNotebookIds && selectedNotebookIds.size > 1) selectedNotebookIds.size else 1
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(100f)
        ) {
            Card(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dragTouchPositionInRoot.x - 70.dp.toPx()).toInt(),
                            (dragTouchPositionInRoot.y - 70.dp.toPx()).toInt()
                        )
                    }
                    .size(width = 145.dp, height = 90.dp)
                    .rotate(-3f)
                    .shadow(16.dp, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF222226)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(2.dp, if (hoveredFolderId != null) GraphiteOrange else Color(0xFF64B5F6))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isPdf = note.mimeType?.contains("pdf") == true || note.filePath?.endsWith(".pdf") == true
                        Icon(
                            imageVector = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.Description,
                            contentDescription = null,
                            tint = if (hoveredFolderId != null) GraphiteOrange else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        if (count > 1) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(GraphiteOrange)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("$count", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(
                        text = note.title,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (hoveredFolderId != null) "Drop to move inside" else "Drag over folder to drop",
                        color = if (hoveredFolderId != null) GraphiteOrange else Color.LightGray,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // Upload Entire Folder Progress Dialog
    if (isUploadingFolder && uploadingFolderInfo != null) {
        val (f, prog) = uploadingFolderInfo!!
        val (curr, total, title) = prog ?: Triple(0, 1, "")
        AlertDialog(
            onDismissRequest = {},
            containerColor = GraphiteSurfaceVariant,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = GraphiteOrange, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Text("Uploading \"${f.name}\"", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Uploading $curr of $total items to Google Drive:",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LinearProgressIndicator(
                        progress = { if (total > 0) curr.toFloat() / total.toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = GraphiteOrange,
                        trackColor = Color(0xFF333333)
                    )
                }
            },
            confirmButton = {}
        )
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
        // Orange Circular (+) FAB matching PDF Page 9
        FloatingActionButton(
            onClick = { showCreationOptionsSheet = true },
            containerColor = GraphiteOrange,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .size(56.dp)
                .shadow(8.dp, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New Notebook or Document",
                modifier = Modifier.size(28.dp)
            )
        }
    }

    if (showNewNotebookDialog) {
        NewHandwrittenNotebookModal(
            onDismiss = { showNewNotebookDialog = false },
            onCreate = { title, cover, template, password ->
                val newNote = GraphiteRepository.createNotebook(
                    title = title,
                    cover = cover,
                    paperStyle = template,
                    password = password
                )
                showNewNotebookDialog = false
                onOpenNotebook(newNote.id)
            }
        )
    }

    pendingUnlockNote?.let { note ->
        NotebookUnlockDialog(
            notebook = note,
            onDismiss = { pendingUnlockNote = null },
            onSuccess = {
                pendingUnlockNote = null
                onOpenNotebook(note.id)
            }
        )
    }

    noteToChangePassword?.let { note ->
        SetPasswordDialog(
            notebook = note,
            onDismiss = { noteToChangePassword = null },
            onSave = { newPwd ->
                GraphiteRepository.setNotebookPassword(note.id, newPwd)
                noteToChangePassword = null
                Toast.makeText(context, if (newPwd.isNullOrBlank()) "Password removed" else "Password updated", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showSplitDocPicker && splitScreenDocA != null && onOpenSplitScreen != null) {
        SplitScreenDocPickerDialog(
            firstDoc = splitScreenDocA!!,
            allNotes = sortedNotebooks,
            onDismiss = { showSplitDocPicker = false; splitScreenDocA = null },
            onSelectSecondDoc = { docB, orientation ->
                val docAId = splitScreenDocA!!.id
                val docBId = docB.id
                showSplitDocPicker = false
                splitScreenDocA = null
                onOpenSplitScreen(docAId, docBId, orientation)
            }
        )
    }

    if (showMultiSelectSplitDialog && selectedNotebookIds.size == 2 && onOpenSplitScreen != null) {
        val twoIds = selectedNotebookIds.toList()
        var selectedOrientation by remember { mutableStateOf(SplitLayoutOrientation.VERTICAL_DIVIDER) }
        AlertDialog(
            onDismissRequest = { showMultiSelectSplitDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Splitscreen, contentDescription = null, tint = GraphiteOrange, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Split View Layout", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Choose how to arrange the 2 documents:", color = Color.LightGray, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isSideBySide = selectedOrientation == SplitLayoutOrientation.VERTICAL_DIVIDER
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSideBySide) Color(0xFF2A2016) else Color(0xFF1E1E22))
                                .border(1.5.dp, if (isSideBySide) GraphiteOrange else Color(0xFF333338), RoundedCornerShape(8.dp))
                                .clickable { selectedOrientation = SplitLayoutOrientation.VERTICAL_DIVIDER }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.VerticalSplit, contentDescription = null, tint = if (isSideBySide) GraphiteOrange else Color.Gray, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Side-by-Side", color = if (isSideBySide) Color.White else Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        val isTopBottom = selectedOrientation == SplitLayoutOrientation.HORIZONTAL_DIVIDER
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isTopBottom) Color(0xFF2A2016) else Color(0xFF1E1E22))
                                .border(1.5.dp, if (isTopBottom) GraphiteOrange else Color(0xFF333338), RoundedCornerShape(8.dp))
                                .clickable { selectedOrientation = SplitLayoutOrientation.HORIZONTAL_DIVIDER }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.HorizontalSplit, contentDescription = null, tint = if (isTopBottom) GraphiteOrange else Color.Gray, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Top & Bottom", color = if (isTopBottom) Color.White else Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idA = twoIds[0]
                        val idB = twoIds[1]
                        selectedNotebookIds = emptySet()
                        showMultiSelectSplitDialog = false
                        onOpenSplitScreen(idA, idB, selectedOrientation)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
                ) {
                    Text("Open Split View", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMultiSelectSplitDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            parentFolder = selectedParentFolderForSubfolder,
            onDismiss = {
                showNewFolderDialog = false
                selectedParentFolderForSubfolder = null
            },
            onCreate = { name ->
                GraphiteRepository.addFolder(name, selectedParentFolderForSubfolder?.id, colorHex = 0xFFFFD54FL)
                showNewFolderDialog = false
                selectedParentFolderForSubfolder = null
            }
        )
    }

    if (folderForOptionsDialog != null) {
        FolderOptionsDialog(
            folder = folderForOptionsDialog!!,
            allFolders = folders,
            onDismiss = { folderForOptionsDialog = null },
            onUploadFolder = { handleUploadWholeFolder(folderForOptionsDialog!!) },
            onSave = { newName, newColorHex, newParentId ->
                GraphiteRepository.updateFolder(folderForOptionsDialog!!.id, newName, newColorHex, newParentId)
                folderForOptionsDialog = null
                Toast.makeText(context, "Folder updated", Toast.LENGTH_SHORT).show()
            },
            onDelete = {
                val fId = folderForOptionsDialog!!.id
                GraphiteRepository.deleteFolder(fId)
                if (selectedCategory == fId) {
                    selectedCategory = "All Notes"
                }
                folderForOptionsDialog = null
                Toast.makeText(context, "Folder deleted", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showMoveMultiFolderDialog && selectedNotebookIds.isNotEmpty()) {
        MoveNoteFolderDialog(
            folders = folders,
            currentFolderId = "",
            onDismiss = { showMoveMultiFolderDialog = false },
            onSelectFolder = { folderId ->
                val target = folders.find { it.id == folderId }
                val targetName = target?.name ?: "folder"
                val existingInTarget = notebooks.filter { it.folderId == folderId && !it.isDeleted }
                val notesToMoveList = notebooks.filter { it.id in selectedNotebookIds }
                val duplicate = notesToMoveList.find { n ->
                    existingInTarget.any { existing ->
                        existing.id != n.id && existing.title.trim().equals(n.title.trim(), ignoreCase = true)
                    }
                }
                if (duplicate != null && target != null) {
                    duplicateWarningDialogInfo = DuplicateFileWarningInfo(
                        targetFolder = target,
                        duplicateTitle = duplicate.title,
                        noteToMove = duplicate,
                        otherNoteIdsToMove = selectedNotebookIds - duplicate.id
                    )
                } else {
                    val count = selectedNotebookIds.size
                    GraphiteRepository.moveNotebooksToFolder(selectedNotebookIds, folderId)
                    Toast.makeText(context, "Moved $count notes to $targetName", Toast.LENGTH_SHORT).show()
                    selectedNotebookIds = emptySet()
                }
                showMoveMultiFolderDialog = false
            }
        )
    }

    if (showCreationOptionsSheet) {
        AlertDialog(
            onDismissRequest = { showCreationOptionsSheet = false },
            title = {
                Text(
                    text = "Create or Import",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showCreationOptionsSheet = false
                                showNewNotebookDialog = true
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Book, contentDescription = null, tint = Color(0xFF81C784), modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Handwritten Notebook", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Stylus canvas with custom cover & paper templates", color = Color.Gray, fontSize = 11.sp)
                        }
                    }


                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showCreationOptionsSheet = false
                                val count = notebooks.count { it.mimeType == "text/keep" } + 1
                                val textNote = GraphiteRepository.createNotebook(
                                    title = "Text Note $count",
                                    mimeType = "text/keep",
                                    initialPreviewLines = listOf("Tap to start typing your note..."),
                                    cover = NotebookCover(category = "NONE")
                                )
                                onOpenNotebook(textNote.id)
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Notes, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Text Note", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Rich text, checklists & typed markdown note", color = Color.Gray, fontSize = 11.sp)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showCreationOptionsSheet = false
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
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Import Documents", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Import single or multiple PDF, Word (.docx), PPTX or files", color = Color.Gray, fontSize = 11.sp)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showCreationOptionsSheet = false
                                val activeF = folders.find { it.id == selectedCategory }
                                selectedParentFolderForSubfolder = activeF
                                showNewFolderDialog = true
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val activeF = folders.find { it.id == selectedCategory }
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(if (activeF != null) "New Subfolder" else "New Folder", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(if (activeF != null) "Create a subfolder inside ${activeF.name}" else "Create a folder or subfolder for organizing notes", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCreationOptionsSheet = false }) {
                    Text("Close", color = Color.LightGray)
                }
            },
            containerColor = Color(0xFF222222)
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

    showCoverPickerForNote?.let { note ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showCoverPickerForNote = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            FullCoverPickerSheet(
                selectedCover = note.cover,
                onSelectCover = { newCover ->
                    GraphiteRepository.updateNotebookCover(note.id, newCover)
                    showCoverPickerForNote = null
                },
                onDismiss = { showCoverPickerForNote = null }
            )
        }
    }

    showMoveFolderDialogForNote?.let { note ->
        MoveNoteFolderDialog(
            folders = folders,
            currentFolderId = note.folderId,
            onDismiss = { showMoveFolderDialogForNote = null },
            onSelectFolder = { folderId ->
                val target = folders.find { it.id == folderId }
                val targetName = target?.name ?: "folder"
                val existingInTarget = notebooks.filter { it.folderId == folderId && !it.isDeleted }
                val duplicate = existingInTarget.find { existing ->
                    existing.id != note.id && existing.title.trim().equals(note.title.trim(), ignoreCase = true)
                }
                if (duplicate != null && target != null) {
                    duplicateWarningDialogInfo = DuplicateFileWarningInfo(
                        targetFolder = target,
                        duplicateTitle = note.title,
                        noteToMove = note,
                        otherNoteIdsToMove = emptySet()
                    )
                } else {
                    GraphiteRepository.moveNotebookToFolder(note.id, folderId)
                    Toast.makeText(context, "Moved \"${note.title}\" to $targetName", Toast.LENGTH_SHORT).show()
                }
                showMoveFolderDialogForNote = null
            }
        )
    }

    if (duplicateWarningDialogInfo != null) {
        val info = duplicateWarningDialogInfo!!
        AlertDialog(
            onDismissRequest = { duplicateWarningDialogInfo = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFFFB74D))
                    Text("File Already Exists", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "A note named \"${info.duplicateTitle}\" already exists in folder \"${info.targetFolder.name}\".",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Do you want to replace the existing file, keep both with an updated name, or cancel?",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        GraphiteRepository.replaceNoteInFolder(
                            folderId = info.targetFolder.id,
                            noteToMoveId = info.noteToMove.id,
                            existingNoteTitle = info.duplicateTitle
                        )
                        if (info.otherNoteIdsToMove.isNotEmpty()) {
                            GraphiteRepository.moveNotebooksToFolder(info.otherNoteIdsToMove, info.targetFolder.id)
                        }
                        Toast.makeText(context, "Replaced \"${info.duplicateTitle}\" in ${info.targetFolder.name}", Toast.LENGTH_SHORT).show()
                        selectedNotebookIds = emptySet()
                        duplicateWarningDialogInfo = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
                ) {
                    Text("Replace", color = Color.White)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val baseTitle = info.duplicateTitle.trim()
                            val existingTitles = notebooks.filter { it.folderId == info.targetFolder.id && !it.isDeleted }.map { it.title.trim().lowercase() }
                            var counter = 1
                            var newTitle = "$baseTitle ($counter)"
                            while (existingTitles.contains(newTitle.lowercase())) {
                                counter++
                                newTitle = "$baseTitle ($counter)"
                            }
                            GraphiteRepository.moveAndRenameNoteToFolder(
                                folderId = info.targetFolder.id,
                                noteToMoveId = info.noteToMove.id,
                                newTitle = newTitle
                            )
                            if (info.otherNoteIdsToMove.isNotEmpty()) {
                                GraphiteRepository.moveNotebooksToFolder(info.otherNoteIdsToMove, info.targetFolder.id)
                            }
                            Toast.makeText(context, "Moved as \"$newTitle\" to ${info.targetFolder.name}", Toast.LENGTH_SHORT).show()
                            selectedNotebookIds = emptySet()
                            duplicateWarningDialogInfo = null
                        },
                        border = BorderStroke(1.dp, Color(0xFF555555))
                    ) {
                        Text("Keep Both", color = Color.White)
                    }
                    TextButton(
                        onClick = { duplicateWarningDialogInfo = null }
                    ) {
                        Text("Cancel", color = Color.Gray)
                    }
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }

    // Delete Note Choices Dialog
    notesToDelete?.let { targetNotes ->
        DeleteNoteChoiceDialog(
            notes = targetNotes,
            isDeleting = isDeletingNotesCloud,
            onDismiss = {
                if (!isDeletingNotesCloud) notesToDelete = null
            },
            onDeleteDeviceOnly = {
                targetNotes.forEach { n ->
                    GraphiteRepository.permanentlyDeleteNotebook(n.id)
                }
                selectedNotebookIds = selectedNotebookIds - targetNotes.map { it.id }.toSet()
                notesToDelete = null
                Toast.makeText(context, if (targetNotes.size == 1) "Deleted from device" else "Deleted ${targetNotes.size} notes from device", Toast.LENGTH_SHORT).show()
            },
            onDeleteCloudAndDevice = {
                isDeletingNotesCloud = true
                scope.launch {
                    targetNotes.forEach { n ->
                        try {
                            syncManager.deleteNoteFromCloud(n.id)
                        } catch (_: Exception) {}
                        GraphiteRepository.permanentlyDeleteNotebook(n.id)
                    }
                    try {
                        val driveManager = com.graphite.app.GraphiteApplication.instance.googleDriveManager
                        val email = driveManager.getAuthorizedAccountEmail()
                        if (!email.isNullOrBlank() && driveManager.hasDrivePermission(context)) {
                            driveManager.backupNotesToDriveDirectly(context, email)
                        }
                    } catch (_: Exception) {}
                    isDeletingNotesCloud = false
                    selectedNotebookIds = selectedNotebookIds - targetNotes.map { it.id }.toSet()
                    notesToDelete = null
                    val msg = if (targetNotes.size == 1) {
                        "Deleted from device & Supabase cloud"
                    } else {
                        "Deleted ${targetNotes.size} notes from device & Supabase cloud"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (isImportingFile) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Importing Notes", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = GraphiteOrange, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(importProgressText, color = Color.LightGray, fontSize = 13.sp)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarItem(
    title: String,
    icon: ImageVector,
    count: Int? = null,
    badgeText: String? = null,
    isSelected: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
    iconTint: Color? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) GraphiteOrange else Color.Transparent)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint ?: (if (isSelected) Color.White else Color(0xFFB0B0B0)),
                modifier = Modifier.size(iconSize)
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
private fun PdfThumbnailImage(filePath: String, modifier: Modifier = Modifier) {
    PdfPageThumbnail(
        filePath = filePath,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        targetWidth = 280
    )
}

private fun getFolderColors(folder: FolderItem): Pair<Color, Color> {
    val base = folder.colorHex?.let { Color(it) } ?: Color(0xFFFFD54FL)
    return base to Color(0x77000000)
}

// Single continuous folder vector shape with top-left tab and rounded corners (no seams, no inner rectangles)
private val ModernFolderShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    val r = h * 0.12f
    val tabH = h * 0.16f
    val tabW = w * 0.44f
    val slope = w * 0.07f

    reset()
    // Start at bottom-left corner
    moveTo(r, h)
    // Bottom edge to bottom-right corner
    lineTo(w - r, h)
    quadraticTo(w, h, w, h - r)
    // Right edge up to shoulder corner
    lineTo(w, tabH + r)
    quadraticTo(w, tabH, w - r, tabH)
    // Shoulder to tab slope
    lineTo(tabW + slope, tabH)
    cubicTo(
        tabW + (slope * 0.5f), tabH,
        tabW + (slope * 0.2f), 0f,
        tabW, 0f
    )
    // Tab top edge to top-left corner
    lineTo(r, 0f)
    quadraticTo(0f, 0f, 0f, r)
    // Left edge down to bottom-left corner
    lineTo(0f, h - r)
    quadraticTo(0f, h, r, h)
    close()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderThumbnailCard(
    folder: FolderItem,
    noteCount: Int,
    isHoveredForDrop: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUploadFolder: () -> Unit,
    onOpenOptions: () -> Unit,
    onDeleteFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val (folderColor, _) = remember(folder.colorHex, folder.id) {
        getFolderColors(folder)
    }
    val isFolderUploaded = folder.isUploadedToDrive || !folder.driveFolderId.isNullOrBlank()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isHoveredForDrop) GraphiteOrange.copy(alpha = 0.25f) else Color.Transparent)
            .then(
                if (isHoveredForDrop) Modifier.border(2.5.dp, GraphiteOrange, RoundedCornerShape(12.dp))
                else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Folder Visual Representation - Single whole shape with no rectangles inside
        Box(
            modifier = Modifier
                .width(130.dp)
                .height(105.dp)
                .graphicsLayer {
                    if (isHoveredForDrop) {
                        scaleX = 1.05f
                        scaleY = 1.05f
                    }
                }
                .shadow(elevation = if (isHoveredForDrop) 6.dp else 2.dp, shape = ModernFolderShape)
                .clip(ModernFolderShape)
                .background(folderColor, ModernFolderShape)
                .border(
                    width = if (isHoveredForDrop) 2.5.dp else 1.dp,
                    color = if (isHoveredForDrop) GraphiteOrange else Color.Black.copy(alpha = 0.12f),
                    shape = ModernFolderShape
                )
        ) {
            // Star Toggle Button in Lower-Left of Folder (borderless, no outer circle)
            IconButton(
                onClick = { GraphiteRepository.toggleStarFolder(folder.id) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp, bottom = 4.dp)
                    .size(28.dp)
            ) {
                Icon(
                    imageVector = if (folder.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (folder.isStarred) "Starred" else "Star Folder",
                    tint = if (folder.isStarred) Color(0xFFFFB300) else Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Cloud Upload Badge Icon in Top-Right Shoulder of Folder (borderless, no outer circle)
            IconButton(
                onClick = onUploadFolder,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 4.dp)
                    .size(28.dp)
            ) {
                Icon(
                    imageVector = if (isFolderUploaded) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                    contentDescription = if (isFolderUploaded) "Folder backed up to Drive" else "Upload Folder to Google Drive",
                    tint = if (isFolderUploaded) Color(0xFF2E7D32) else Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Drop Hover Indicator Overlay
            if (isHoveredForDrop) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoveToInbox,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Drop Here",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Title Row with Down Chevron
        Box(contentAlignment = Alignment.Center) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { menuExpanded = true }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = folder.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Folder Options",
                    tint = Color.LightGray,
                    modifier = Modifier.size(16.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(GraphiteSurfaceVariant)
            ) {
                DropdownMenuItem(
                    text = { Text("Open Folder", color = Color.White) },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, tint = GraphiteOrange) },
                    onClick = {
                        menuExpanded = false
                        onClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (folder.isStarred) "Unstar Folder" else "Star Folder", color = Color.White) },
                    leadingIcon = {
                        Icon(
                            if (folder.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        GraphiteRepository.toggleStarFolder(folder.id)
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (isFolderUploaded) "Re-upload to Drive" else "Upload Whole Folder to Drive", color = Color.White) },
                    leadingIcon = { Icon(if (isFolderUploaded) Icons.Default.CloudDone else Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF4CAF50)) },
                    onClick = {
                        menuExpanded = false
                        onUploadFolder()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Folder Settings (Rename/Color)", color = Color.White) },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = Color.LightGray) },
                    onClick = {
                        menuExpanded = false
                        onOpenOptions()
                    }
                )
                HorizontalDivider(color = Color(0xFF333333))
                DropdownMenuItem(
                    text = { Text("Delete Folder", color = Color(0xFFFF5252)) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252)) },
                    onClick = {
                        menuExpanded = false
                        onDeleteFolder()
                    }
                )
            }
        }

        // Subtitle row with item count and cloud upload status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = if (isFolderUploaded) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                contentDescription = null,
                tint = if (isFolderUploaded) Color(0xFF4CAF50) else Color(0xFF757575),
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = "$noteCount ${if (noteCount == 1) "item" else "items"} • ${if (isFolderUploaded) "Backed up" else "Local"}",
                color = if (isFolderUploaded) Color(0xFF81C784) else Color.Gray,
                fontSize = 10.sp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderListRow(
    folder: FolderItem,
    noteCount: Int,
    isHoveredForDrop: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUploadFolder: () -> Unit,
    onOpenOptions: () -> Unit,
    onDeleteFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val (folderColor, _) = remember(folder.colorHex, folder.id) {
        getFolderColors(folder)
    }
    val isFolderUploaded = folder.isUploadedToDrive || !folder.driveFolderId.isNullOrBlank()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isHoveredForDrop) GraphiteOrange.copy(alpha = 0.25f) else Color(0xFF18181B))
            .then(
                if (isHoveredForDrop) Modifier.border(2.dp, GraphiteOrange, RoundedCornerShape(10.dp))
                else Modifier.border(1.dp, Color(0xFF2E2E32), RoundedCornerShape(10.dp))
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Folder Icon with soft tinted container (showing proper folder icon!)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(folderColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = folderColor,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = folder.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (folder.isStarred) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Starred",
                        tint = Color(0xFFFFD54F),
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isFolderUploaded) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                    contentDescription = null,
                    tint = if (isFolderUploaded) Color(0xFF4CAF50) else Color.Gray,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "$noteCount ${if (noteCount == 1) "item" else "items"} • ${if (isFolderUploaded) "Backed up" else "Local"}",
                    color = if (isFolderUploaded) Color(0xFF81C784) else Color.Gray,
                    fontSize = 11.sp
                )
            }
        }

        // Star Marking Button
        IconButton(
            onClick = { GraphiteRepository.toggleStarFolder(folder.id) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (folder.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (folder.isStarred) "Starred" else "Star Folder",
                tint = if (folder.isStarred) Color(0xFFFFD54F) else Color.Gray,
                modifier = Modifier.size(18.dp)
            )
        }

        // Cloud Status & Upload Button
        IconButton(
            onClick = onUploadFolder,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (isFolderUploaded) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                contentDescription = if (isFolderUploaded) "Folder backed up" else "Upload Folder to Drive",
                tint = if (isFolderUploaded) Color(0xFF4CAF50) else Color.Gray,
                modifier = Modifier.size(18.dp)
            )
        }

        // 3-dots Menu Button
        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Folder Options",
                    tint = Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(GraphiteSurfaceVariant)
            ) {
                DropdownMenuItem(
                    text = { Text("Open Folder", color = Color.White) },
                    leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null, tint = GraphiteOrange) },
                    onClick = {
                        menuExpanded = false
                        onClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (folder.isStarred) "Unstar Folder" else "Star Folder", color = Color.White) },
                    leadingIcon = {
                        Icon(
                            if (folder.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            tint = Color(0xFFFFD54F)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        GraphiteRepository.toggleStarFolder(folder.id)
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (isFolderUploaded) "Re-upload to Drive" else "Upload Whole Folder to Drive", color = Color.White) },
                    leadingIcon = { Icon(if (isFolderUploaded) Icons.Default.CloudDone else Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF4CAF50)) },
                    onClick = {
                        menuExpanded = false
                        onUploadFolder()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Folder Settings (Rename/Color)", color = Color.White) },
                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = Color.LightGray) },
                    onClick = {
                        menuExpanded = false
                        onOpenOptions()
                    }
                )
                HorizontalDivider(color = Color(0xFF333333))
                DropdownMenuItem(
                    text = { Text("Delete Folder", color = Color(0xFFFF5252)) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252)) },
                    onClick = {
                        menuExpanded = false
                        onDeleteFolder()
                    }
                )
            }
        }
    }
}

/**
 * Handles three distinct note item gestures:
 * 1. Quick tap (< 350ms): calls onClick (opens the note).
 * 2. Stationary hold (>= 380ms): calls onLongClick (selects the note / enters selection mode).
 * 3. Touch and move past touchSlop: starts immediate drag-and-drop into folders.
 */
private fun Modifier.notebookTapDragModifier(
    key: Any?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDragStart: ((Offset) -> Unit)? = null,
    onDrag: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null,
    getRootPosition: () -> Offset = { Offset.Zero }
): Modifier = this.pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startPos = down.position
        val touchSlop = viewConfiguration.touchSlop
        val longPressTimeout = 400L

        var isLongPressActive = false
        var isDragging = false
        val pointerId = down.id

        // Phase 1: Wait for 400ms hold, tap release, or scroll movement
        try {
            withTimeout(longPressTimeout) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                    if (!change.pressed) {
                        // Lifted before 400ms -> Tap!
                        val distance = (change.position - startPos).getDistance()
                        if (distance <= touchSlop && !down.isConsumed && !change.isConsumed) {
                            change.consume()
                            onClick()
                        }
                        return@withTimeout
                    }

                    val distance = (change.position - startPos).getDistance()
                    if (distance > touchSlop) {
                        // User swiped or moved finger before 400ms -> SCROLL!
                        // Do NOT consume pointer, yield immediately so list/grid scrolls smoothly.
                        return@withTimeout
                    }
                }
            }
        } catch (_: Exception) {
            // Held stationary for 400ms! Trigger long-press immediately while finger is still held down!
            isLongPressActive = true
            down.consume()
            onLongClick()
        }

        // Phase 2: If long press triggered, handle drag-and-drop or release
        if (isLongPressActive) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                if (!change.pressed) {
                    if (isDragging) {
                        onDragEnd?.invoke()
                    }
                    break
                }

                change.consume()
                val moveDist = (change.position - startPos).getDistance()
                if (onDragStart != null) {
                    if (!isDragging && moveDist > touchSlop) {
                        isDragging = true
                        val rootStart = getRootPosition() + change.position
                        onDragStart.invoke(rootStart)
                    }
                    if (isDragging) {
                        val dragAmount = change.position - change.previousPosition
                        onDrag?.invoke(dragAmount)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookThumbnailCard(
    notebook: NotebookItem,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    isCustomSortActive: Boolean = false,
    onMoveCustomOrder: ((Int) -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRename: () -> Unit,
    onChangeCover: (() -> Unit)? = null,
    onMoveFolder: () -> Unit,
    onRestore: () -> Unit,
    onPermanentlyDelete: () -> Unit,
    onDelete: () -> Unit,
    onUploadToDrive: () -> Unit,
    onOpenSplitScreen: (() -> Unit)? = null,
    onSetPassword: (() -> Unit)? = null,
    onDragStart: ((Offset) -> Unit)? = null,
    onDrag: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cardPosRef = remember { object { var x = 0f; var y = 0f } }
    val isPdf = notebook.mimeType?.contains("pdf", ignoreCase = true) == true ||
            notebook.filePath?.endsWith(".pdf", ignoreCase = true) == true
    val isDoc = notebook.filePath?.endsWith(".docx", ignoreCase = true) == true ||
            notebook.filePath?.endsWith(".pptx", ignoreCase = true) == true
    val isKeepText = notebook.mimeType == "text/keep" ||
            notebook.mimeType?.startsWith("text/") == true ||
            notebook.title.startsWith("Quick Note", ignoreCase = true) ||
            notebook.title.startsWith("Text Note", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFF2A2016) else Color.Transparent)
            .then(
                if (isSelected) Modifier.border(1.5.dp, GraphiteOrange, RoundedCornerShape(8.dp))
                else Modifier
            )
            .onGloballyPositioned { coords ->
                val tl = coords.boundsInRoot().topLeft
                cardPosRef.x = tl.x
                cardPosRef.y = tl.y
            }
            .notebookTapDragModifier(
                key = notebook.id,
                onClick = onClick,
                onLongClick = onLongClick,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
                getRootPosition = { Offset(cardPosRef.x, cardPosRef.y) }
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Document Thumbnail Sheet matching Page 4
        Card(
            modifier = Modifier
                .width(130.dp)
                .height(170.dp)
                .shadow(4.dp, RoundedCornerShape(6.dp)),
            colors = CardDefaults.cardColors(
                containerColor = if (isPdf) Color(0xFF211515) else if (isKeepText) Color(0xFF1E1E1E) else Color(0xFFFAFAFA)
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
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

                if (isPdf) {
                    if (!notebook.filePath.isNullOrEmpty() && (File(notebook.filePath).exists() || notebook.filePath.startsWith("content://"))) {
                        PdfThumbnailImage(notebook.filePath)
                    } else {
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
                    }
                } else if (notebook.mimeType == "application/x-graphite-voice") {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1E1710))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(GraphiteOrange)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("VOICE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Voice Note",
                            tint = GraphiteOrange,
                            modifier = Modifier.size(44.dp)
                        )

                        Text(
                            text = "${notebook.voiceNotes.size} clips",
                            color = Color(0xFFFFCC80),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (isKeepText) {
                    // Google Keep style checklist / text note thumbnail (NEVER shows artificial cover)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF333333))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text("NOTE", color = Color(0xFFFFB74D), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }
                            if (notebook.isStarred) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(14.dp))
                            }
                        }

                        if (notebook.previewLines.isEmpty()) {
                            Text(
                                text = "Tap to type note...",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )
                        } else {
                            notebook.previewLines.take(4).forEach { line ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    if (line.startsWith("[x] ")) {
                                        Icon(
                                            imageVector = Icons.Default.CheckBox,
                                            contentDescription = null,
                                            tint = GraphiteOrange,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = line.removePrefix("[x] "),
                                            color = Color.Gray,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                        )
                                    } else if (line.startsWith("[ ] ")) {
                                        Icon(
                                            imageVector = Icons.Default.CheckBoxOutlineBlank,
                                            contentDescription = null,
                                            tint = Color.LightGray,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = line.removePrefix("[ ] "),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            maxLines = 1
                                        )
                                    } else {
                                        Text(
                                            text = line,
                                            color = Color.LightGray,
                                            fontSize = 10.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
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
                } else if (notebook.cover.category != "NONE") {
                    NotebookCoverView(
                        cover = notebook.cover,
                        modifier = Modifier.fillMaxSize(),
                        isThumbnail = true
                    )
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

                // Lock Badge (top left when not in multi-selection mode)
                if (notebook.isLocked && !isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked Note",
                            tint = Color(0xFFFFD54F),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                // Quick Star Icon on bottom-left - High-contrast elevated circular button
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .pointerInput(notebook.id) {
                            detectTapGestures {
                                onToggleStar()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (notebook.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Star Notebook",
                        tint = if (notebook.isStarred) Color(0xFFFFD54F) else Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }

                // Cloud Upload Badge Icon (top right) - Differentiates Uploaded vs Local
                val isUploaded = notebook.isUploadedToDrive || !notebook.driveFileId.isNullOrBlank()
                IconButton(
                    onClick = onUploadToDrive,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(26.dp)
                        .background(
                            if (isUploaded) Color(0xCC1B5E20) else Color.Black.copy(alpha = 0.6f),
                            RoundedCornerShape(4.dp)
                        )
                ) {
                    Icon(
                        imageVector = if (isUploaded) Icons.Default.CloudDone else Icons.Default.CloudUpload,
                        contentDescription = if (isUploaded) "Backed up to Drive" else "Upload to Google Drive",
                        tint = if (isUploaded) Color(0xFF4CAF50) else Color(0xFFB0B0B0),
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
                    if (isCustomSortActive && onMoveCustomOrder != null) {
                        DropdownMenuItem(
                            text = { Text("Move Earlier / Left", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = GraphiteOrange) },
                            onClick = { menuExpanded = false; onMoveCustomOrder(-1) }
                        )
                        DropdownMenuItem(
                            text = { Text("Move Later / Right", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.ArrowForward, contentDescription = null, tint = GraphiteOrange) },
                            onClick = { menuExpanded = false; onMoveCustomOrder(1) }
                        )
                        HorizontalDivider(color = Color(0xFF333333))
                    }
                    DropdownMenuItem(
                        text = { Text(if (isPdf) "Open PDF" else "Open Note", color = Color.White) },
                        onClick = {
                            menuExpanded = false
                            onClick()
                        }
                    )
                    if (onOpenSplitScreen != null) {
                        DropdownMenuItem(
                            text = { Text("Open in Split Screen", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.VerticalSplit, contentDescription = null, tint = GraphiteOrange) },
                            onClick = {
                                menuExpanded = false
                                onOpenSplitScreen()
                            }
                        )
                    }
                    if (onSetPassword != null) {
                        DropdownMenuItem(
                            text = { Text(if (notebook.isLocked) "Change/Remove Password" else "Password Protection", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFFD54F)) },
                            onClick = {
                                menuExpanded = false
                                onSetPassword()
                            }
                        )
                    }
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
                    if (!isPdf) {
                        DropdownMenuItem(
                            text = { Text("Change Cover", color = Color.White) },
                            leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null, tint = GraphiteOrange) },
                            onClick = {
                                menuExpanded = false
                                onChangeCover?.invoke()
                            }
                        )
                    }
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
                    DropdownMenuItem(
                        text = { Text("Share", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = GraphiteOrange) },
                        onClick = {
                            menuExpanded = false
                            FileSharingHelper.shareNotebook(context, notebook)
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

        // Title and Timestamp matching Image 1 with down chevron and Cloud Status Indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { menuExpanded = true }
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = notebook.title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Options",
                tint = Color.LightGray,
                modifier = Modifier.size(15.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val isUploaded = notebook.isUploadedToDrive || !notebook.driveFileId.isNullOrBlank()
            Icon(
                imageVector = if (isUploaded) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                contentDescription = null,
                tint = if (isUploaded) Color(0xFF4CAF50) else Color(0xFF757575),
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = "${if (isUploaded) "Backed up" else "Local"} • ${notebook.updatedAtFormatted}",
                color = if (isUploaded) Color(0xFF81C784) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookListRow(
    notebook: NotebookItem,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    isCustomSortActive: Boolean = false,
    onMoveCustomOrder: ((Int) -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOpenSplitScreen: (() -> Unit)? = null,
    onSetPassword: (() -> Unit)? = null,
    onToggleStar: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRename: () -> Unit,
    onChangeCover: (() -> Unit)? = null,
    onMoveFolder: () -> Unit,
    onRestore: () -> Unit,
    onPermanentlyDelete: () -> Unit,
    onDelete: () -> Unit,
    onUploadToDrive: () -> Unit,
    onDragStart: ((Offset) -> Unit)? = null,
    onDrag: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null
) {
    var rowBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    val isPdf = notebook.mimeType?.contains("pdf", ignoreCase = true) == true ||
            notebook.filePath?.endsWith(".pdf", ignoreCase = true) == true
    val isKeepText = notebook.mimeType == "text/keep" ||
            notebook.mimeType?.startsWith("text/") == true ||
            notebook.title.startsWith("Quick Note", ignoreCase = true) ||
            notebook.title.startsWith("Text Note", ignoreCase = true)
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFF2A2016) else Color(0xFF141414))
            .then(
                if (isSelected) Modifier.border(1.5.dp, GraphiteOrange, RoundedCornerShape(8.dp))
                else Modifier
            )
            .onGloballyPositioned { coords ->
                rowBoundsInRoot = coords.boundsInRoot()
            }
            .notebookTapDragModifier(
                key = notebook.id,
                onClick = onClick,
                onLongClick = onLongClick,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
                getRootPosition = { rowBoundsInRoot.topLeft }
            )
            .padding(12.dp),
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

        // Miniature Cover Page / PDF First Page / Text lines preview card matching Grid View
        Card(
            modifier = Modifier
                .width(46.dp)
                .height(60.dp)
                .shadow(2.dp, RoundedCornerShape(4.dp)),
            colors = CardDefaults.cardColors(
                containerColor = if (isPdf) Color(0xFF211515) else if (isKeepText) Color(0xFF1E1E1E) else Color(0xFFFAFAFA)
            ),
            shape = RoundedCornerShape(4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isPdf) {
                    if (!notebook.filePath.isNullOrEmpty() && (File(notebook.filePath).exists() || notebook.filePath.startsWith("content://"))) {
                        PdfThumbnailImage(notebook.filePath)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(22.dp))
                        }
                    }
                } else if (notebook.mimeType == "application/x-graphite-voice") {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1710)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, tint = GraphiteOrange, modifier = Modifier.size(22.dp))
                    }
                } else if (isKeepText) {
                    Column(modifier = Modifier.fillMaxSize().padding(3.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF333333))
                                .padding(horizontal = 2.dp, vertical = 1.dp)
                        ) {
                            Text("NOTE", color = Color(0xFFFFB74D), fontSize = 6.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        notebook.previewLines.take(3).forEach { line ->
                            Text(
                                text = line.removePrefix("[x] ").removePrefix("[ ] "),
                                color = Color.LightGray,
                                fontSize = 6.sp,
                                maxLines = 1
                            )
                        }
                    }
                } else if (notebook.cover.category != "NONE") {
                    NotebookCoverView(
                        cover = notebook.cover,
                        modifier = Modifier.fillMaxSize(),
                        isThumbnail = true
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                        repeat(5) { lineIdx ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(if (lineIdx % 2 == 0) 0.65f else 0.9f)
                                    .height(1.dp)
                                    .background(Color(0xFFD0D0D0))
                            )
                            Spacer(modifier = Modifier.height(7.dp))
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))

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
                if (notebook.isLocked) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.Lock, contentDescription = "Locked", tint = Color(0xFFFFD54F), modifier = Modifier.size(14.dp))
                }
                if (notebook.isStarred) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(14.dp))
                }
            }
            val isUploaded = notebook.isUploadedToDrive || !notebook.driveFileId.isNullOrBlank()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isUploaded) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                    contentDescription = null,
                    tint = if (isUploaded) Color(0xFF4CAF50) else Color.Gray,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "${if (isPdf) "PDF • " else ""}${if (isUploaded) "Backed up • " else "Local • "}${notebook.updatedAtFormatted}",
                    color = if (isUploaded) Color(0xFF81C784) else Color.Gray,
                    fontSize = 11.sp
                )
            }
        }

        if (isCustomSortActive && onMoveCustomOrder != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMoveCustomOrder(-1) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up", tint = Color.LightGray, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { onMoveCustomOrder(1) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down", tint = Color.LightGray, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .pointerInput(notebook.id) {
                    detectTapGestures {
                        onToggleStar()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (notebook.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Star Note",
                tint = if (notebook.isStarred) Color(0xFFFFD54F) else Color.Gray,
                modifier = Modifier.size(20.dp)
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
                if (isCustomSortActive && onMoveCustomOrder != null) {
                    DropdownMenuItem(
                        text = { Text("Move Up", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = GraphiteOrange) },
                        onClick = { menuExpanded = false; onMoveCustomOrder(-1) }
                    )
                    DropdownMenuItem(
                        text = { Text("Move Down", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = GraphiteOrange) },
                        onClick = { menuExpanded = false; onMoveCustomOrder(1) }
                    )
                    HorizontalDivider(color = Color(0xFF333333))
                }
                DropdownMenuItem(
                    text = { Text(if (isPdf) "Open PDF" else "Open Note", color = Color.White) },
                    onClick = { menuExpanded = false; onClick() }
                )
                if (onOpenSplitScreen != null) {
                    DropdownMenuItem(
                        text = { Text("Open in Split Screen", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.VerticalSplit, contentDescription = null, tint = GraphiteOrange) },
                        onClick = { menuExpanded = false; onOpenSplitScreen() }
                    )
                }
                if (onSetPassword != null) {
                    DropdownMenuItem(
                        text = { Text(if (notebook.isLocked) "Change/Remove Password" else "Password Protection", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFFD54F)) },
                        onClick = { menuExpanded = false; onSetPassword() }
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (notebook.isStarred) "Unstar" else "Star", color = Color.White) },
                    onClick = { menuExpanded = false; onToggleStar() }
                )
                DropdownMenuItem(
                    text = { Text(if (notebook.isBookmarked) "Remove Bookmark" else "Bookmark", color = Color.White) },
                    onClick = { menuExpanded = false; onToggleBookmark() }
                )
                DropdownMenuItem(
                    text = { Text("Rename", color = Color.White) },
                    onClick = { menuExpanded = false; onRename() }
                )
                if (!isPdf) {
                    DropdownMenuItem(
                        text = { Text("Change Cover", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null, tint = GraphiteOrange) },
                        onClick = {
                            menuExpanded = false
                            onChangeCover?.invoke()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Move to Folder", color = Color.White) },
                    onClick = { menuExpanded = false; onMoveFolder() }
                )
                val isUploaded = notebook.isUploadedToDrive || !notebook.driveFileId.isNullOrBlank()
                DropdownMenuItem(
                    text = { Text(if (isUploaded) "Re-upload to Drive" else "Upload to Drive", color = if (isUploaded) Color(0xFF81C784) else GraphiteOrange) },
                    onClick = { menuExpanded = false; onUploadToDrive() }
                )
                DropdownMenuItem(
                    text = { Text("Share", color = Color.White) },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = GraphiteOrange) },
                    onClick = {
                        menuExpanded = false
                        FileSharingHelper.shareNotebook(context, notebook)
                    }
                )
                HorizontalDivider(color = Color(0xFF333333))
                if (notebook.isDeleted) {
                    DropdownMenuItem(
                        text = { Text("Restore", color = Color(0xFF81C784)) },
                        onClick = { menuExpanded = false; onRestore() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Permanently", color = Color(0xFFFF5252)) },
                        onClick = { menuExpanded = false; onPermanentlyDelete() }
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
private fun DeleteNoteChoiceDialog(
    notes: List<NotebookItem>,
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
                    text = if (notes.size == 1) "Delete Note" else "Delete ${notes.size} Notes",
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
                        Text("Deleting from Supabase cloud database and device...", color = Color.LightGray, fontSize = 13.sp)
                    }
                } else {
                    Text(
                        text = if (notes.size == 1) {
                            "Choose how you want to delete \"${notes.first().title}\":"
                        } else {
                            "Choose how you want to delete these ${notes.size} notes:"
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
                                Text("Removes the local note file. The note remains safe in your Supabase cloud.", color = Color.Gray, fontSize = 11.sp)
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
                                Text("Purges the note from Supabase cloud database across all devices and removes it from this device.", color = Color.Gray, fontSize = 11.sp)
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

data class TemplateModel(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val style: PaperStyle,
    val badge: String? = null,
    val cover: NotebookCover? = null
)

@Composable
private fun TemplatePaperPreview(
    item: TemplateModel,
    modifier: Modifier = Modifier
) {
    if (item.cover != null) {
        // Tabbed Notebook Preview
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(item.cover.colorHex))
                .shadow(3.dp, RoundedCornerShape(6.dp))
        ) {
            // Book spine line on left
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(8.dp)
                    .background(Color.Black.copy(alpha = 0.25f))
            )
            // Tab dividers on right edge
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                val tabColors = listOf(Color(0xFFFFB74D), Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFBA68C8))
                tabColors.forEach { c ->
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp))
                            .background(c)
                    )
                }
            }
            // Centered title badge on cover
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = item.title.take(12),
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
        return
    }

    val isLegal = item.style == PaperStyle.LEGAL
    val isEng = item.style == PaperStyle.ENGINEERING_GRID
    val sheetBg = when {
        isLegal -> Color(0xFFFFF9C4) // Legal yellow
        isEng -> Color(0xFF1E2D3D)  // Blueprint dark cyan
        else -> Color(0xFFFAFAFA)   // Clean white
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(sheetBg)
            .shadow(3.dp, RoundedCornerShape(6.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val lineColor = if (isEng) Color(0xFF4FC3F7).copy(alpha = 0.35f) else Color(0xFFD0D7DE)
            val marginColor = Color(0xFFE57373)

            when (item.style) {
                PaperStyle.RULED, PaperStyle.LEGAL -> {
                    val marginX = w * 0.18f
                    drawLine(
                        color = marginColor,
                        start = Offset(marginX, 0f),
                        end = Offset(marginX, h),
                        strokeWidth = 1.2f
                    )
                    val stepY = h / 9f
                    for (i in 1..8) {
                        val y = i * stepY
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1f
                        )
                    }
                }
                PaperStyle.GRID, PaperStyle.ENGINEERING_GRID -> {
                    val step = w / 8f
                    var x = step
                    while (x < w) {
                        drawLine(lineColor, Offset(x, 0f), Offset(x, h), 0.8f)
                        x += step
                    }
                    var y = step
                    while (y < h) {
                        drawLine(lineColor, Offset(0f, y), Offset(w, y), 0.8f)
                        y += step
                    }
                    if (isEng) {
                        drawRect(
                            color = Color(0xFF4FC3F7).copy(alpha = 0.2f),
                            topLeft = Offset(0f, 0f),
                            size = androidx.compose.ui.geometry.Size(w, h * 0.16f)
                        )
                    }
                }
                PaperStyle.DOTTED -> {
                    val step = w / 7f
                    var x = step
                    while (x < w) {
                        var y = step
                        while (y < h) {
                            drawCircle(lineColor, radius = 1.2f, center = Offset(x, y))
                            y += step
                        }
                        x += step
                    }
                }
                PaperStyle.CORNELL_A, PaperStyle.CORNELL_B -> {
                    val headerY = h * 0.14f
                    drawLine(lineColor, Offset(0f, headerY), Offset(w, headerY), 1.2f)
                    val summaryY = h * 0.78f
                    drawLine(lineColor, Offset(0f, summaryY), Offset(w, summaryY), 1.2f)
                    val cueX = w * 0.32f
                    drawLine(marginColor, Offset(cueX, headerY), Offset(cueX, summaryY), 1.2f)
                    val stepY = (summaryY - headerY) / 5f
                    for (i in 1..4) {
                        val y = headerY + i * stepY
                        drawLine(lineColor, Offset(cueX, y), Offset(w, y), 0.8f)
                    }
                }
                PaperStyle.HEXAGONAL_GRID -> {
                    val step = w / 5f
                    var x = step / 2
                    while (x < w) {
                        var y = step / 2
                        while (y < h) {
                            drawCircle(lineColor, radius = 2f, center = Offset(x, y))
                            y += step
                        }
                        x += step
                    }
                }
                PaperStyle.MUSIC_SCORE -> {
                    val staves = listOf(h * 0.25f, h * 0.65f)
                    staves.forEach { startY ->
                        val lineGap = 4f
                        for (i in 0 until 5) {
                            val y = startY + (i * lineGap)
                            drawLine(Color(0xFF424242), Offset(w * 0.1f, y), Offset(w * 0.9f, y), 1f)
                        }
                    }
                }
                PaperStyle.GUITAR_TABLATURE, PaperStyle.GUITAR_SCORE -> {
                    val startY = h * 0.35f
                    val lineGap = 4.5f
                    for (i in 0 until 6) {
                        val y = startY + (i * lineGap)
                        drawLine(Color(0xFF424242), Offset(w * 0.12f, y), Offset(w * 0.9f, y), 0.9f)
                    }
                }
                PaperStyle.BLANK -> {
                    drawLine(lineColor, Offset(w * 0.15f, h * 0.15f), Offset(w * 0.25f, h * 0.15f), 1.2f)
                    drawLine(lineColor, Offset(w * 0.15f, h * 0.15f), Offset(w * 0.15f, h * 0.25f), 1.2f)
                }
            }
        }
    }
}

@Composable
private fun TemplateFeaturedCard(
    title: String,
    subtitle: String,
    badge: String,
    gradient: Brush,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(260.dp)
            .height(115.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                Column {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateItemCard(
    item: TemplateModel,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF19191C)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFF28282E))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(105.dp),
                contentAlignment = Alignment.Center
            ) {
                TemplatePaperPreview(item = item, modifier = Modifier.fillMaxSize())

                if (item.badge != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(GraphiteOrange)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(item.badge, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Column {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.description,
                    color = Color.Gray,
                    fontSize = 10.sp,
                    maxLines = 2,
                    lineHeight = 12.sp
                )
            }

            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Use Template", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TemplatesSection(
    onCreateFromTemplate: (String, PaperStyle, NotebookCover?) -> Unit,
    onImportCustomTemplate: () -> Unit
) {
    var selectedTopTab by remember { mutableIntStateOf(0) } // 0: Discover, 1: Library, 2: Custom
    var selectedCategoryChip by remember { mutableStateOf("All") }

    val templateCatalog = remember {
        listOf(
            // Paper Essentials
            TemplateModel("ruled", "Ruled Paper", "Classic horizontal lines with margin", "Paper Essentials", PaperStyle.RULED, badge = "CLASSIC"),
            TemplateModel("grid", "Grid Paper", "Precision 5mm math & architectural grid", "Paper Essentials", PaperStyle.GRID, badge = "MATH"),
            TemplateModel("dot", "Dot Grid", "Matrix dot grid for bullet journaling", "Paper Essentials", PaperStyle.DOTTED, badge = "BUJO"),
            TemplateModel("blank", "Blank Canvas", "Unconstrained drawing & sketching", "Paper Essentials", PaperStyle.BLANK),
            TemplateModel("legal", "Legal Pad", "Canary yellow note with vertical margin rule", "Paper Essentials", PaperStyle.LEGAL, badge = "POPULAR"),
            TemplateModel("eng", "Engineering Grid", "Dense engineering computation grid", "Paper Essentials", PaperStyle.ENGINEERING_GRID, badge = "PRO"),
            TemplateModel("hex", "Hexagonal Grid", "Organic chemistry & isometric design", "Paper Essentials", PaperStyle.HEXAGONAL_GRID),

            // Study & Academic
            TemplateModel("cornell_a", "Cornell Notes", "Cue column, lecture body & summary block", "Study & Academic", PaperStyle.CORNELL_A, badge = "RECOMMENDED"),
            TemplateModel("cornell_b", "Cornell Review", "Wide lecture margin with chapter recap", "Study & Academic", PaperStyle.CORNELL_B),
            TemplateModel("study_session", "Study Session Tracker", "Subject goals, equations & pomodoro blocks", "Study & Academic", PaperStyle.RULED, badge = "EXAM"),
            TemplateModel("reading_log", "Reading & Research Log", "Citations, takeaways, key quotes & ratings", "Study & Academic", PaperStyle.RULED),

            // Planners & Bujo
            TemplateModel("daily_planner", "Daily Time-Block", "6am-10pm schedule with priority checkpoints", "Planners & Bujo", PaperStyle.GRID, badge = "DAILY"),
            TemplateModel("weekly_sprint", "Weekly Goal Sprint", "7-day column overview & weekly goals", "Planners & Bujo", PaperStyle.GRID, badge = "WEEKLY"),
            TemplateModel("monthly_overview", "Monthly Calendar & Habits", "Month calendar view with habit streaks", "Planners & Bujo", PaperStyle.GRID),
            TemplateModel("mind_map", "Mind Map Canvas", "Central topic node with radiating branches", "Planners & Bujo", PaperStyle.BLANK, badge = "IDEAS"),

            // Tabbed Notebooks
            TemplateModel("tabbed_cognac", "Tabbed Cognac Leather", "Rich leather portfolio with 5 divider tabs", "Tabbed Notebooks", PaperStyle.RULED, badge = "PREMIUM", cover = NotebookCover(category = "PALETTE", colorHex = 0xFF8D6E63, name = "Cognac")),
            TemplateModel("tabbed_teal", "Scandinavian Teal", "Minimalist journal with colored index tabs", "Tabbed Notebooks", PaperStyle.DOTTED, badge = "DESIGN", cover = NotebookCover(category = "PLAIN", colorHex = 0xFF45A5B5, name = "Teal")),
            TemplateModel("tabbed_slate", "Midnight Slate Folio", "Matte charcoal cover with tab dividers", "Tabbed Notebooks", PaperStyle.GRID, cover = NotebookCover(category = "PLAIN", colorHex = 0xFF37474F, name = "Slate")),

            // Music & Creative
            TemplateModel("music_staff", "Music Staff Score", "Standard 5-line musical staves for composing", "Music & Creative", PaperStyle.MUSIC_SCORE, badge = "MUSIC"),
            TemplateModel("guitar_tab", "Guitar Tablature", "6-line guitar tabs with chord diagrams", "Music & Creative", PaperStyle.GUITAR_TABLATURE, badge = "TABS"),
            TemplateModel("guitar_score", "Guitar Grand Score", "Standard notation paired with guitar tabs", "Music & Creative", PaperStyle.GUITAR_SCORE)
        )
    }

    val categories = listOf("All", "Paper Essentials", "Study & Academic", "Planners & Bujo", "Tabbed Notebooks", "Music & Creative")
    val filteredTemplates = if (selectedCategoryChip == "All") templateCatalog else templateCatalog.filter { it.category == selectedCategoryChip }

    // Custom Paper Studio state
    var customTitle by remember { mutableStateOf("Custom Paper Note") }
    var customColorIndex by remember { mutableIntStateOf(0) }
    var customStyle by remember { mutableStateOf(PaperStyle.RULED) }

    val customTints = listOf(
        Pair("Ivory", Color(0xFFFAFAFA)),
        Pair("Cream", Color(0xFFFFFDF5)),
        Pair("Sepia", Color(0xFFF4ECD8)),
        Pair("Night", Color(0xFF22242B)),
        Pair("Canary", Color(0xFFFFF9C4))
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Tab Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF19191C))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val tabs = listOf(
                Triple(0, "DISCOVER", Icons.Default.Explore),
                Triple(1, "MY LIBRARY", Icons.Default.Bookmarks),
                Triple(2, "CUSTOM", Icons.Default.AddCircleOutline)
            )
            tabs.forEach { (idx, label, icon) ->
                val isSelected = selectedTopTab == idx
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) GraphiteOrange else Color.Transparent)
                        .clickable { selectedTopTab = idx }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) Color.White else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else Color.Gray,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }
            }
        }

        when (selectedTopTab) {
            0 -> { // DISCOVER
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    // Highlights Carousel
                    item {
                        Text(
                            text = "Featured Highlights",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TemplateFeaturedCard(
                                title = "2026 Digital Diaries",
                                subtitle = "Organize, Plan & Reflect with structured annual layout",
                                badge = "FEATURED",
                                gradient = Brush.horizontalGradient(listOf(Color(0xFF4A148C), Color(0xFF7B1FA2))),
                                onClick = { onCreateFromTemplate("Digital Diary 2026", PaperStyle.GRID, null) }
                            )
                            TemplateFeaturedCard(
                                title = "Cornell Lecture System",
                                subtitle = "Systematic cue column, notes & recap layout",
                                badge = "ACADEMIC",
                                gradient = Brush.horizontalGradient(listOf(Color(0xFFE65100), Color(0xFFFF8F00))),
                                onClick = { onCreateFromTemplate("Cornell Lecture Notes", PaperStyle.CORNELL_A, null) }
                            )
                            TemplateFeaturedCard(
                                title = "Artist Freeform Canvas",
                                subtitle = "Boundless canvas designed for fluid stylus art",
                                badge = "CREATIVE",
                                gradient = Brush.horizontalGradient(listOf(Color(0xFF004D40), Color(0xFF00897B))),
                                onClick = { onCreateFromTemplate("Artist Sketchpad", PaperStyle.BLANK, null) }
                            )
                            TemplateFeaturedCard(
                                title = "Daily Time-Block Planner",
                                subtitle = "6am-10pm schedule with priority checkpoints",
                                badge = "PRODUCTIVITY",
                                gradient = Brush.horizontalGradient(listOf(Color(0xFF0D47A1), Color(0xFF1976D2))),
                                onClick = { onCreateFromTemplate("Daily Planner", PaperStyle.GRID, null) }
                            )
                        }
                    }

                    // Category Filter Pills
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            categories.forEach { cat ->
                                val isSelected = selectedCategoryChip == cat
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedCategoryChip = cat },
                                    label = { Text(cat, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = GraphiteOrange,
                                        selectedLabelColor = Color.White,
                                        containerColor = Color(0xFF1E1E22),
                                        labelColor = Color.LightGray
                                    ),
                                    border = null,
                                    shape = RoundedCornerShape(16.dp)
                                )
                            }
                        }
                    }

                    // Template Grid
                    item {
                        val chunked = filteredTemplates.chunked(3)
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            chunked.forEach { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        Box(modifier = Modifier.weight(1f)) {
                                            TemplateItemCard(
                                                item = item,
                                                onClick = { onCreateFromTemplate(item.title, item.style, item.cover) }
                                            )
                                        }
                                    }
                                    if (rowItems.size < 3) {
                                        repeat(3 - rowItems.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> { // MY LIBRARY
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    item {
                        Text(
                            text = "Saved & Frequently Used Presets",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "One-tap quick access to your most popular paper styles.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }

                    val libraryPresets = templateCatalog.take(6)
                    val chunked = libraryPresets.chunked(3)
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            chunked.forEach { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        Box(modifier = Modifier.weight(1f)) {
                                            TemplateItemCard(
                                                item = item,
                                                onClick = { onCreateFromTemplate(item.title, item.style, item.cover) }
                                            )
                                        }
                                    }
                                    if (rowItems.size < 3) {
                                        repeat(3 - rowItems.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            2 -> { // CUSTOM
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    // Import Custom Template Card (matching PDF Page 3)
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1E)),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF2A2A30))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(GraphiteOrange.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = GraphiteOrange,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                Text(
                                    text = "Import Custom Templates",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    text = "Import PDFs or images from your tablet storage to use as your own reusable custom paper.",
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )

                                Button(
                                    onClick = onImportCustomTemplate,
                                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                                ) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Import Now", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    // Custom Paper Studio Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1E)),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF2A2A30))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Tune, contentDescription = null, tint = GraphiteOrange, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Design Custom Paper", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }

                                OutlinedTextField(
                                    value = customTitle,
                                    onValueChange = { customTitle = it },
                                    label = { Text("Template Note Title", color = Color.Gray) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text("Paper Color Tint", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    customTints.forEachIndexed { idx, (label, color) ->
                                        val isSelected = customColorIndex == idx
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .clickable { customColorIndex = idx }
                                                .padding(4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(color)
                                                    .then(
                                                        if (isSelected) Modifier.border(2.dp, GraphiteOrange, CircleShape)
                                                        else Modifier.border(1.dp, Color.Gray, CircleShape)
                                                    )
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(label, color = if (isSelected) Color.White else Color.Gray, fontSize = 10.sp)
                                        }
                                    }
                                }

                                Text("Paper Ruling Style", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                val styles = listOf(
                                    Pair("Ruled", PaperStyle.RULED),
                                    Pair("Grid", PaperStyle.GRID),
                                    Pair("Dot Grid", PaperStyle.DOTTED),
                                    Pair("Cornell", PaperStyle.CORNELL_A),
                                    Pair("Blank", PaperStyle.BLANK)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    styles.forEach { (label, st) ->
                                        val isSelected = customStyle == st
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { customStyle = st },
                                            label = { Text(label, fontSize = 11.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = GraphiteOrange,
                                                selectedLabelColor = Color.White,
                                                containerColor = Color(0xFF222226),
                                                labelColor = Color.LightGray
                                            ),
                                            border = null,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        if (customTitle.isNotBlank()) {
                                            onCreateFromTemplate(customTitle.trim(), customStyle, null)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                                    modifier = Modifier.fillMaxWidth().height(42.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Create Notebook with Custom Style", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewNotebookDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, isTextNotebook: Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var isTextNotebook by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Notebook", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Notebook Title", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = GraphiteOrange,
                        unfocusedBorderColor = Color(0xFF444444)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Notebook Type",
                    color = Color.LightGray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Handwritten Option Card
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { isTextNotebook = false },
                        colors = CardDefaults.cardColors(
                            containerColor = if (!isTextNotebook) Color(0xFF2C241E) else Color(0xFF1E1E1E)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            if (!isTextNotebook) GraphiteOrange else Color(0xFF333333)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Draw,
                                contentDescription = null,
                                tint = if (!isTextNotebook) GraphiteOrange else Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Handwritten",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Stylus & Paper",
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                        }
                    }

                    // Text & Checklist Option Card
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { isTextNotebook = true },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isTextNotebook) Color(0xFF2C241E) else Color(0xFF1E1E1E)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            if (isTextNotebook) GraphiteOrange else Color(0xFF333333)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Checklist,
                                contentDescription = null,
                                tint = if (isTextNotebook) GraphiteOrange else Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Text & Lists",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Google Keep style",
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onCreate(title.trim(), isTextNotebook) },
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
    parentFolder: FolderItem? = null,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (parentFolder != null) "New Subfolder in \"${parentFolder.name}\"" else "New Folder", color = Color.White, fontWeight = FontWeight.Bold) },
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
private fun FolderOptionsDialog(
    folder: FolderItem,
    allFolders: List<FolderItem>,
    onDismiss: () -> Unit,
    onSave: (newName: String, newColorHex: Long?, newParentId: String?) -> Unit,
    onDelete: () -> Unit,
    onUploadFolder: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(folder.name) }
    var selectedColorHex by remember { mutableStateOf(folder.colorHex ?: 0xFFFFD54F) }
    var selectedParentId by remember { mutableStateOf(folder.parentId) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val presetColors = listOf(
        0xFFFFD54F, // Gold
        0xFFFF5722, // Deep Orange
        0xFFE91E63, // Pink
        0xFF9C27B0, // Purple
        0xFF2196F3, // Blue
        0xFF00BCD4, // Cyan
        0xFF4CAF50, // Green
        0xFF795548  // Brown
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Folder?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete \"${folder.name}\"? Notes inside will be moved to Unfiled.", color = Color.LightGray) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folder Settings", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder Name", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Folder Color", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    presetColors.forEach { cHex ->
                        val isSelected = selectedColorHex == cHex
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(cHex))
                                .then(
                                    if (isSelected) Modifier.border(2.dp, Color.White, CircleShape)
                                    else Modifier
                                )
                                .clickable { selectedColorHex = cHex }
                        )
                    }
                }

                Text("Location / Nesting", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                val candidateParents = allFolders.filter { it.id != folder.id && it.parentId == null }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedParentId == null) Color(0xFF2E2E32) else Color.Transparent)
                            .clickable { selectedParentId = null }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedParentId == null,
                            onClick = { selectedParentId = null },
                            colors = RadioButtonDefaults.colors(selectedColor = GraphiteOrange)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Root Level (Stand-alone folder)", color = Color.White, fontSize = 13.sp)
                    }

                    candidateParents.forEach { parent ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selectedParentId == parent.id) Color(0xFF2E2E32) else Color.Transparent)
                                .clickable { selectedParentId = parent.id }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedParentId == parent.id,
                                onClick = { selectedParentId = parent.id },
                                colors = RadioButtonDefaults.colors(selectedColor = GraphiteOrange)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Inside \"${parent.name}\"", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }

                if (onUploadFolder != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF142416),
                        border = BorderStroke(1.dp, Color(0xFF2E7D32)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onUploadFolder()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Upload Whole Folder to Drive", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Uploads all notes, PDFs, and files inside this folder", color = Color.LightGray, fontSize = 10.sp)
                            }
                        }
                    }
                }

                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete Folder", color = Color(0xFFFF5252), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val newParent = if (selectedParentId == null) "__ROOT__" else selectedParentId
                        onSave(name.trim(), selectedColorHex, newParent)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
            ) {
                Text("Save", color = Color.White)
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

@Composable
fun NotebookUnlockDialog(
    notebook: NotebookItem,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var enteredPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Locked Notebook", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "\"${notebook.title}\" is protected with a password. Enter password to unlock.",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = enteredPassword,
                    onValueChange = {
                        enteredPassword = it
                        errorMessage = null
                    },
                    label = { Text("Password", color = Color.Gray) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GraphiteOrange,
                        unfocusedBorderColor = GraphiteOutline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                if (errorMessage != null) {
                    Text(errorMessage!!, color = Color(0xFFFF5252), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (notebook.password == enteredPassword) {
                        onSuccess()
                    } else {
                        errorMessage = "Incorrect password. Please try again."
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
            ) {
                Text("Unlock", color = Color.White)
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
fun SetPasswordDialog(
    notebook: NotebookItem,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var currentPasswordInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val hasExistingPassword = !notebook.password.isNullOrBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFFD54F), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(if (hasExistingPassword) "Password Protection" else "Set Note Password", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasExistingPassword) {
                    Text(
                        text = "This notebook currently has a password. Enter current password to change or remove it.",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = currentPasswordInput,
                        onValueChange = {
                            currentPasswordInput = it
                            errorMessage = null
                        },
                        label = { Text("Current Password", color = Color.Gray) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GraphiteOrange,
                            unfocusedBorderColor = GraphiteOutline,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }

                OutlinedTextField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        errorMessage = null
                    },
                    label = { Text(if (hasExistingPassword) "New Password (or blank to remove)" else "New Password", color = Color.Gray) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GraphiteOrange,
                        unfocusedBorderColor = GraphiteOutline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                if (newPassword.isNotBlank()) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            errorMessage = null
                        },
                        label = { Text("Confirm New Password", color = Color.Gray) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GraphiteOrange,
                            unfocusedBorderColor = GraphiteOutline,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }

                if (errorMessage != null) {
                    Text(errorMessage!!, color = Color(0xFFFF5252), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (hasExistingPassword && currentPasswordInput != notebook.password) {
                        errorMessage = "Current password does not match."
                        return@Button
                    }
                    if (newPassword.isNotBlank() && newPassword != confirmPassword) {
                        errorMessage = "Passwords do not match."
                        return@Button
                    }
                    onSave(newPassword.ifBlank { null })
                },
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
            ) {
                Text(if (hasExistingPassword && newPassword.isBlank()) "Remove Password" else "Save Password", color = Color.White)
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
fun SplitScreenDocPickerDialog(
    firstDoc: NotebookItem,
    allNotes: List<NotebookItem>,
    onDismiss: () -> Unit,
    onSelectSecondDoc: (NotebookItem, SplitLayoutOrientation) -> Unit
) {
    var selectedSplitOrientation by remember { mutableStateOf(SplitLayoutOrientation.VERTICAL_DIVIDER) }
    val candidateNotes = remember(allNotes, firstDoc) {
        allNotes.filter { it.id != firstDoc.id && !it.isDeleted }
    }
    var searchQuery by remember { mutableStateOf("") }
    val filtered = candidateNotes.filter { it.title.contains(searchQuery, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Splitscreen, contentDescription = null, tint = GraphiteOrange, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Select Second Document", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                Text(
                    text = "Doc 1: ${firstDoc.title}",
                    color = GraphiteOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("Choose Split Layout:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isSideBySide = selectedSplitOrientation == SplitLayoutOrientation.VERTICAL_DIVIDER
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSideBySide) Color(0xFF2A2016) else Color(0xFF1E1E22))
                            .border(1.5.dp, if (isSideBySide) GraphiteOrange else Color(0xFF333338), RoundedCornerShape(8.dp))
                            .clickable { selectedSplitOrientation = SplitLayoutOrientation.VERTICAL_DIVIDER }
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerticalSplit, contentDescription = null, tint = if (isSideBySide) GraphiteOrange else Color.Gray, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Side-by-Side", color = if (isSideBySide) Color.White else Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    val isTopBottom = selectedSplitOrientation == SplitLayoutOrientation.HORIZONTAL_DIVIDER
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isTopBottom) Color(0xFF2A2016) else Color(0xFF1E1E22))
                            .border(1.5.dp, if (isTopBottom) GraphiteOrange else Color(0xFF333338), RoundedCornerShape(8.dp))
                            .clickable { selectedSplitOrientation = SplitLayoutOrientation.HORIZONTAL_DIVIDER }
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.HorizontalSplit, contentDescription = null, tint = if (isTopBottom) GraphiteOrange else Color.Gray, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Top & Bottom", color = if (isTopBottom) Color.White else Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search notes...", color = Color.Gray, fontSize = 13.sp) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GraphiteOrange,
                        unfocusedBorderColor = GraphiteOutline,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No other documents found", color = Color.Gray, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered) { doc ->
                            val isPdf = doc.mimeType?.contains("pdf", ignoreCase = true) == true ||
                                    doc.filePath?.endsWith(".pdf", ignoreCase = true) == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF222222))
                                    .clickable { onSelectSecondDoc(doc, selectedSplitOrientation) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (isPdf) Color(0xFFEF5350) else GraphiteOrange,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = doc.title,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = doc.updatedAtFormatted,
                                        color = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
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
