package com.graphite.app.feature.noteshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.auth.GoogleProfileTopBarIcon
import com.graphite.app.core.drive.DriveUploadState
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteOutline
import com.graphite.app.core.theme.GraphiteSurfaceVariant
import com.graphite.app.data.model.NotebookItem
import com.graphite.app.data.repository.GraphiteRepository
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

    val fileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            isImportingFile = true
            scope.launch {
                val result = fileImportManager.importToNotes(context, uri)
                isImportingFile = false
                when (result) {
                    is com.graphite.app.core.importer.ImportResult.SuccessNotebook -> {
                        importStatusMessage = if (result.syncedToCloud) {
                            "Imported \"${result.notebook.title}\" (synced to cloud)"
                        } else {
                            "Imported \"${result.notebook.title}\""
                        }
                        onOpenNotebook(result.notebook.id)
                    }
                    is com.graphite.app.core.importer.ImportResult.SuccessBook -> {
                        importStatusMessage = "Imported \"${result.book.title}\""
                    }
                    is com.graphite.app.core.importer.ImportResult.Error -> {
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
        if (authResult.isSuccess && selectedNotebookForUpload != null) {
            scope.launch {
                driveManager.startUploadFlow(context, selectedNotebookForUpload!!)
            }
        }
    }
    var selectedCategory by remember { mutableStateOf("All Notes") }
    var sidebarVisible by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }

    var showNewNotebookDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(GraphiteBlack)
            .statusBarsPadding()
    ) {
        // Left Sidebar ("Noteshelf" Navigation Pane) matching Page 4
        if (sidebarVisible) {
            Column(
                modifier = Modifier
                    .width(240.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF0F0F0F))
                    .border(width = 0.5.dp, color = GraphiteOutline)
                    .padding(horizontal = 14.dp, vertical = 16.dp)
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
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.Gray
                            )
                        }
                    }
                }

                // Primary Smart Categories
                SidebarItem(
                    title = "All Notes",
                    icon = Icons.Default.Home,
                    count = notebooks.size,
                    isSelected = selectedCategory == "All Notes",
                    onClick = { selectedCategory = "All Notes" }
                )
                SidebarItem(
                    title = "Starred",
                    icon = Icons.Default.StarBorder,
                    isSelected = selectedCategory == "Starred",
                    onClick = { selectedCategory = "Starred" }
                )
                SidebarItem(
                    title = "Unfiled",
                    icon = Icons.Default.Inbox,
                    isSelected = selectedCategory == "Unfiled",
                    onClick = { selectedCategory = "Unfiled" }
                )
                SidebarItem(
                    title = "Trash",
                    icon = Icons.Default.DeleteOutline,
                    isSelected = selectedCategory == "Trash",
                    onClick = { selectedCategory = "Trash" }
                )
                SidebarItem(
                    title = "Templates",
                    icon = Icons.Default.Palette,
                    badgeText = "New",
                    isSelected = selectedCategory == "Templates",
                    onClick = { selectedCategory = "Templates" }
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
                    SidebarItem(
                        title = folder.name,
                        icon = Icons.Default.Folder,
                        isSelected = selectedCategory == folder.id,
                        onClick = { selectedCategory = folder.id }
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
                SidebarItem(title = "Photos", icon = Icons.Default.Image, onClick = {})
                SidebarItem(title = "Recordings", icon = Icons.Default.Mic, onClick = {})
                SidebarItem(title = "Bookmarks", icon = Icons.Default.BookmarkBorder, onClick = {})
            }
        }

        // Right Main Content Pane matching Page 4
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            // Main Top Bar: Sidebar toggle, "All Notes", Action buttons (+) (Search) (More)
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
                    Text(
                        text = selectedCategory,
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
                    IconButton(onClick = { /* More options */ }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color.White
                        )
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
                            arrayOf("application/pdf", "application/json", "application/octet-stream", "*/*")
                        )
                    }
                )
            }

            // Notebook Cards Grid (Multi-column with live previews)
            val filteredNotebooks = notebooks.filter { note ->
                if (searchQuery.isBlank()) true
                else note.title.contains(searchQuery, ignoreCase = true)
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredNotebooks) { notebook ->
                    NotebookThumbnailCard(
                        notebook = notebook,
                        onClick = { onOpenNotebook(notebook.id) },
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
    onUploadToDrive: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Document Thumbnail Sheet (Lined white document preview matching Page 4)
        Card(
            modifier = Modifier
                .width(130.dp)
                .height(170.dp)
                .shadow(4.dp, RoundedCornerShape(6.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
            shape = RoundedCornerShape(6.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                ) {
                    // Subtle horizontal lined preview
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
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Title and Timestamp matching Page 4 ("Ass9 (2)" / "12/10/26 12:35 pm")
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
private fun NewNotebookDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Notebook", color = Color.White) },
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
                onClick = { if (title.isNotBlank()) onCreate(title) },
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
        title = { Text("New Folder", color = Color.White) },
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
                onClick = { if (name.isNotBlank()) onCreate(name) },
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
