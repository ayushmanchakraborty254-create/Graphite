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
import androidx.compose.foundation.lazy.LazyRow
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

    // Google Drive 2-Way Sync & Cloud Folder state
    var showDriveSyncDialog by remember { mutableStateOf(false) }
    var showLinkCloudFolderBrowser by remember { mutableStateOf(false) }
    val isSyncingDrive by driveManager.isSyncingBookshelf.collectAsState()
    val activeDriveFolder by driveManager.activeBookshelfFolder.collectAsState()
    val linkedCloudFolders by driveManager.linkedCloudFolders.collectAsState()
    var isSyncingCloudFolder by remember { mutableStateOf(false) }

    val syncCloudFolder: (DriveFolder) -> Unit = { folder ->
        isSyncingCloudFolder = true
        scope.launch {
            Toast.makeText(context, "Scanning Google Drive folder \"${folder.name}\"...", Toast.LENGTH_SHORT).show()
            val res = driveManager.importDriveCloudFolder(context, folder)
            isSyncingCloudFolder = false
            if (res.isSuccess) {
                val count = res.getOrDefault(0)
                Toast.makeText(context, "Synced \"${folder.name}\": $count books ready to stream (0 MB storage used)", Toast.LENGTH_LONG).show()
                selectedCategory = "☁ ${folder.name}"
            } else {
                val err = res.exceptionOrNull()?.message ?: "Unknown error"
                Toast.makeText(context, "Failed to sync \"${folder.name}\": $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Book Selection & Deletion state
    var selectedBookIds by remember { mutableStateOf(setOf<String>()) }
    var booksToDelete by remember { mutableStateOf<List<Book>?>(null) }
    var isDeletingBooks by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        GraphiteRepository.cleanBookDuplicatesAndGhosts()
    }

    LaunchedEffect(googleUser?.email, isGuestMode) {
        selectedCategory = "All Books"
        selectedBookIds = emptySet()
        driveManager.refreshBookshelfFolders()
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

    var isAuthorizingCloudPicker by remember { mutableStateOf(false) }

    val cloudPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        isAuthorizingCloudPicker = false
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val data = activityResult.data
            if (data != null) {
                scope.launch {
                    var pickedFolder: DriveFolder? = null
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
                            pickedFolder = res.getOrNull()
                        }
                    } catch (_: Exception) {}

                    if (pickedFolder == null) {
                        val fallback = driveManager.handlePickerResult(
                            context = context,
                            intent = data,
                            notebook = null
                        )
                        if (fallback.isSuccess) {
                            pickedFolder = fallback.getOrNull()
                        }
                    }

                    if (pickedFolder != null) {
                        showLinkCloudFolderBrowser = false
                        Toast.makeText(context, "Linking \"${pickedFolder.name}\" from Drive...", Toast.LENGTH_SHORT).show()
                        val importRes = driveManager.importDriveCloudFolder(context, pickedFolder)
                        if (importRes.isSuccess) {
                            val count = importRes.getOrDefault(0)
                            Toast.makeText(context, "Linked \"${pickedFolder.name}\" with $count books (0 MB storage used)", Toast.LENGTH_LONG).show()
                            selectedCategory = "☁ ${pickedFolder.name}"
                        } else {
                            Toast.makeText(context, "Failed: ${importRes.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "No folder selected in Drive Picker", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val launchCloudPicker: () -> Unit = {
        isAuthorizingCloudPicker = true
        scope.launch {
            try {
                val authClient = Identity.getAuthorizationClient(context)
                val request = driveManager.buildPickerAuthorizationRequest()
                authClient.authorize(request)
                    .addOnSuccessListener { authResult ->
                        if (authResult.hasResolution()) {
                            val pendingIntent = authResult.pendingIntent
                            if (pendingIntent != null) {
                                cloudPickerLauncher.launch(
                                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                                )
                            } else {
                                isAuthorizingCloudPicker = false
                                Toast.makeText(context, "Picker authorization resolution missing", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            isAuthorizingCloudPicker = false
                            scope.launch {
                                val res = driveManager.handlePickerAuthorizationResult(
                                    context = context,
                                    result = authResult,
                                    notebook = null
                                )
                                if (res.isSuccess) {
                                    val chosen = res.getOrNull()
                                    if (chosen != null) {
                                        showLinkCloudFolderBrowser = false
                                        Toast.makeText(context, "Linking \"${chosen.name}\" from Drive...", Toast.LENGTH_SHORT).show()
                                        val importRes = driveManager.importDriveCloudFolder(context, chosen)
                                        if (importRes.isSuccess) {
                                            val count = importRes.getOrDefault(0)
                                            Toast.makeText(context, "Linked \"${chosen.name}\" with $count books (0 MB storage used)", Toast.LENGTH_LONG).show()
                                            selectedCategory = "☁ ${chosen.name}"
                                        } else {
                                            Toast.makeText(context, "Failed: ${importRes.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        isAuthorizingCloudPicker = false
                        Toast.makeText(context, "Google Drive Picker error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
            } catch (e: Exception) {
                isAuthorizingCloudPicker = false
                Toast.makeText(context, "Error starting Google Drive Picker: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val standardCategories = listOf("All Books", "Favorites", "Unread", "Completed")
    val categories = remember(googleUser, activeDriveFolder, linkedCloudFolders) {
        val list = standardCategories.toMutableList()
        if (googleUser != null) {
            activeDriveFolder?.let { list.add("🔄 ${it.name}") }
            linkedCloudFolders.forEach { list.add("☁ ${it.name}") }
        }
        list
    }

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
                            val matchesCategory = when {
                                selectedCategory == "Completed" -> book.progressPercent >= 100
                                selectedCategory == "Unread" -> book.progressPercent == 0
                                selectedCategory == "Favorites" -> book.isFavorite
                                selectedCategory.startsWith("🔄 ") -> {
                                    val folderName = selectedCategory.removePrefix("🔄 ")
                                    book.driveFolderId == activeDriveFolder?.id ||
                                            book.driveFolderName.equals(folderName, ignoreCase = true) ||
                                            !book.isCloudOnly
                                }
                                selectedCategory.startsWith("☁ ") -> {
                                    val folderName = selectedCategory.removePrefix("☁ ")
                                    val folder = linkedCloudFolders.find { it.name.equals(folderName, ignoreCase = true) }
                                    book.driveFolderId == folder?.id || (book.driveFolderName != null && book.driveFolderName.equals(folderName, ignoreCase = true))
                                }
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

            // Interactive Drive Folder Chips (when user has signed-in Google account and 2-way sync folder or linked cloud folders)
            if (googleUser != null && (activeDriveFolder != null || linkedCloudFolders.isNotEmpty())) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item {
                        val isSelected = selectedCategory == "All Books"
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) GraphiteOrange else Color(0xFF222222),
                            modifier = Modifier.clickable { selectedCategory = "All Books" }
                        ) {
                            Text(
                                text = "All Books (${books.size})",
                                color = if (isSelected) Color.White else Color.LightGray,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // 2-Way Sync Folder Chip
                    activeDriveFolder?.let { folder ->
                        item(key = "sync_${folder.id}") {
                            val catName = "🔄 ${folder.name}"
                            val isSelected = selectedCategory == catName
                            val count = books.count { it.driveFolderId == folder.id || (!it.isCloudOnly && it.driveFolderId == null) }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) Color(0xFF1B2E1E) else Color(0xFF161E17),
                                border = BorderStroke(1.dp, if (isSelected) Color(0xFF4CAF50) else Color(0xFF2E4E32)),
                                modifier = Modifier.clickable {
                                    selectedCategory = if (isSelected) "All Books" else catName
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudSync,
                                        contentDescription = null,
                                        tint = Color(0xFF81C784),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${folder.name} ($count)",
                                        color = if (isSelected) Color.White else Color(0xFFC8E6C9),
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Surface(
                                        color = Color(0xFF2E7D32).copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "2-Way",
                                            color = Color(0xFFA5D6A7),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Linked Cloud Folders (Zero Storage)
                    linkedCloudFolders.forEach { folder ->
                        item(key = "cloud_${folder.id}") {
                            val catName = "☁ ${folder.name}"
                            val isSelected = selectedCategory == catName
                            val count = books.count { it.driveFolderId == folder.id }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) Color(0xFF142436) else Color(0xFF121A24),
                                border = BorderStroke(1.dp, if (isSelected) Color(0xFF2196F3) else Color(0xFF223A52)),
                                modifier = Modifier.clickable {
                                    selectedCategory = if (isSelected) "All Books" else catName
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cloud,
                                        contentDescription = null,
                                        tint = Color(0xFF64B5F6),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${folder.name} ($count)",
                                        color = if (isSelected) Color.White else Color(0xFFBBDEFB),
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Surface(
                                        color = Color(0xFF1976D2).copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "0 MB",
                                            color = Color(0xFF90CAF9),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Add / Link Cloud Folder Chip
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, Color(0xFF1E88E5).copy(alpha = 0.6f)),
                            modifier = Modifier.clickable { showLinkCloudFolderBrowser = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Link Cloud Folder", color = Color(0xFF90CAF9), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            val activeCloudFolder = if (googleUser != null && selectedCategory.startsWith("☁ ")) {
                val fName = selectedCategory.removePrefix("☁ ")
                linkedCloudFolders.find { it.name.equals(fName, ignoreCase = true) }
            } else null

            if (activeCloudFolder != null) {
                Surface(
                    color = Color(0xFF0F1E2E),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF1E3A5F)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = activeCloudFolder.name,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Streams on demand • 0 MB local storage used",
                                    color = Color(0xFF90CAF9),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Button(
                            onClick = { syncCloudFolder(activeCloudFolder) },
                            enabled = !isSyncingCloudFolder,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isSyncingCloudFolder) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scanning...", fontSize = 11.sp, color = Color.White)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sync Files", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Filter & Sort
            val filteredBooks = books.filter { book ->
                val matchesCategory = when {
                    selectedCategory == "Completed" -> book.progressPercent >= 100
                    selectedCategory == "Unread" -> book.progressPercent == 0
                    selectedCategory == "Favorites" -> book.isFavorite
                    selectedCategory.startsWith("🔄 ") -> {
                        val folderName = selectedCategory.removePrefix("🔄 ")
                        book.driveFolderId == activeDriveFolder?.id ||
                                book.driveFolderName.equals(folderName, ignoreCase = true) ||
                                !book.isCloudOnly
                    }
                    selectedCategory.startsWith("☁ ") -> {
                        val folderName = selectedCategory.removePrefix("☁ ")
                        val folder = linkedCloudFolders.find { it.name.equals(folderName, ignoreCase = true) }
                        book.driveFolderId == folder?.id || (book.driveFolderName != null && book.driveFolderName.equals(folderName, ignoreCase = true))
                    }
                    else -> true
                }
                val matchesSearch = if (searchQuery.isBlank()) true else {
                    book.title.contains(searchQuery, ignoreCase = true) || book.author.contains(searchQuery, ignoreCase = true)
                }
                matchesCategory && matchesSearch
            }.let { list ->
                when (currentSortOption) {
                    "Title (A-Z)" -> list.sortedWith { a, b -> com.graphite.app.core.util.NaturalOrderComparator.compare(a.title, b.title) }
                    "Title (Z-A)" -> list.sortedWith { a, b -> com.graphite.app.core.util.NaturalOrderComparator.compare(b.title, a.title) }
                    "Most Recent" -> list.sortedByDescending { it.lastReadTimeMillis }
                    "Reading Progress" -> list.sortedByDescending { it.progressPercent }
                    "Author" -> list.sortedWith { a, b -> com.graphite.app.core.util.NaturalOrderComparator.compare(a.author, b.author) }
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
                    if (googleUser != null && activeCloudFolder != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No books found in \"${activeCloudFolder.name}\"",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap the button below to scan this Google Drive folder and list its PDF books for instant on-demand streaming (0 MB local storage footprint).",
                                color = Color(0xFF90CAF9),
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { syncCloudFolder(activeCloudFolder) },
                                enabled = !isSyncingCloudFolder,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                            ) {
                                if (isSyncingCloudFolder) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Scanning Drive Files...", color = Color.White)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Fetch & Stream Books from Drive", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
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
                                    val currentSynced = driveManager.getBookshelfSyncedFolder()
                                    if (currentSynced == null) {
                                        showDriveSyncDialog = true
                                        Toast.makeText(context, "Please configure a Google Drive sync folder first", Toast.LENGTH_SHORT).show()
                                    } else {
                                        scope.launch {
                                            val res = driveManager.uploadBookToSyncedDriveFolder(context, book)
                                            val msg = if (res is DriveUploadResult.Success) "Uploaded to \"${currentSynced.name}\"" else "Upload failed"
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onDownloadToDevice = {
                                    scope.launch {
                                        Toast.makeText(context, "Downloading \"${book.title}\" for offline reading...", Toast.LENGTH_SHORT).show()
                                        val res = driveManager.downloadCloudBookToDevice(context, book)
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "Downloaded to device for offline reading", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Download failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                        }
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
                                    val currentSynced = driveManager.getBookshelfSyncedFolder()
                                    if (currentSynced == null) {
                                        showDriveSyncDialog = true
                                        Toast.makeText(context, "Please configure a Google Drive sync folder first", Toast.LENGTH_SHORT).show()
                                    } else {
                                        scope.launch {
                                            val res = driveManager.uploadBookToSyncedDriveFolder(context, book)
                                            val msg = if (res is DriveUploadResult.Success) "Uploaded to \"${currentSynced.name}\"" else "Upload failed"
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onDownloadToDevice = {
                                    scope.launch {
                                        Toast.makeText(context, "Downloading \"${book.title}\" for offline reading...", Toast.LENGTH_SHORT).show()
                                        val res = driveManager.downloadCloudBookToDevice(context, book)
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "Downloaded to device for offline reading", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Download failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                        }
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

    // Google Drive Link Cloud Folder Browser Dialog
    if (showLinkCloudFolderBrowser) {
        DriveFolderBrowserDialog(
            driveManager = driveManager,
            initialFolder = DriveFolder.ROOT,
            onFolderSelected = { chosen ->
                showLinkCloudFolderBrowser = false
                scope.launch {
                    Toast.makeText(context, "Linking \"${chosen.name}\" from Drive...", Toast.LENGTH_SHORT).show()
                    val res = driveManager.importDriveCloudFolder(context, chosen)
                    if (res.isSuccess) {
                        val count = res.getOrDefault(0)
                        Toast.makeText(context, "Linked \"${chosen.name}\" with $count books (0 MB storage used)", Toast.LENGTH_LONG).show()
                        selectedCategory = "☁ ${chosen.name}"
                    } else {
                        Toast.makeText(context, "Failed to link folder: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onLaunchPicker = { launchCloudPicker() },
            isAuthorizingPicker = isAuthorizingCloudPicker,
            onDismiss = { showLinkCloudFolderBrowser = false }
        )
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
    val linkedCloudFolders by driveManager.linkedCloudFolders.collectAsState()
    val isSyncing by driveManager.isSyncingBookshelf.collectAsState()
    val syncStatusMsg by driveManager.bookshelfSyncStatus.collectAsState()

    var showDriveFolderBrowser by remember { mutableStateOf(false) }
    var showLinkCloudFolderBrowserInDialog by remember { mutableStateOf(false) }
    var showCustomFolderDialog by remember { mutableStateOf(false) }
    var customFolderInput by remember { mutableStateOf("") }
    var isAuthorizingPicker by remember { mutableStateOf(false) }
    var isPickingForCloudFolder by remember { mutableStateOf(false) }

    val handlePickerFolderChosen = { chosen: DriveFolder ->
        if (isPickingForCloudFolder) {
            showLinkCloudFolderBrowserInDialog = false
            scope.launch {
                Toast.makeText(context, "Linking \"${chosen.name}\" from Drive...", Toast.LENGTH_SHORT).show()
                val importRes = driveManager.importDriveCloudFolder(context, chosen)
                if (importRes.isSuccess) {
                    val count = importRes.getOrDefault(0)
                    Toast.makeText(context, "Linked \"${chosen.name}\" with $count books (0 MB storage used)", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Failed: ${importRes.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            driveManager.addCuratedBookshelfFolder(chosen)
            driveManager.setBookshelfSyncedFolder(chosen)
            showDriveFolderBrowser = false
            Toast.makeText(context, "Added to synced folders: ${chosen.name}", Toast.LENGTH_SHORT).show()
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        isAuthorizingPicker = false
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val data = activityResult.data
            if (data != null) {
                scope.launch {
                    var pickedFolder: DriveFolder? = null
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
                            pickedFolder = res.getOrNull()
                        }
                    } catch (_: Exception) {}

                    if (pickedFolder == null) {
                        val fallback = driveManager.handlePickerResult(
                            context = context,
                            intent = data,
                            notebook = null
                        )
                        if (fallback.isSuccess) {
                            pickedFolder = fallback.getOrNull()
                        }
                    }

                    if (pickedFolder != null) {
                        handlePickerFolderChosen(pickedFolder)
                    } else {
                        Toast.makeText(context, "No folder selected in Drive Picker", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val launchDialogPicker = { forCloudFolder: Boolean ->
        isPickingForCloudFolder = forCloudFolder
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
                                        handlePickerFolderChosen(chosen)
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

                // 2-WAY SYNC SECTION (OFFLINE STORAGE)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = Color(0xFF81C784), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "2-Way Sync Folder (Offline Storage)",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        text = "PDFs here are downloaded to your device for offline reading, and local books are uploaded here.",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )

                    if (activeFolder == null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E1E1E),
                            border = BorderStroke(1.dp, Color(0xFF333333)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDriveFolderBrowser = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("No 2-Way Sync Folder Set", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("Tap to select or create a Drive folder for offline sync", color = Color.Gray, fontSize = 11.sp)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF162318),
                            border = BorderStroke(1.dp, Color(0xFF2E7D32)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(activeFolder?.name ?: "", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("2-Way Synced • Offline Available", color = Color(0xFFA5D6A7), fontSize = 10.sp)
                                }
                                OutlinedButton(
                                    onClick = { showDriveFolderBrowser = true },
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GraphiteOrange)
                                ) {
                                    Text("Change", fontSize = 11.sp)
                                }
                            }
                        }
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
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New Folder Name", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = { showDriveFolderBrowser = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray)
                        ) {
                            Icon(Icons.Default.DriveFolderUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Browse Drive", fontSize = 11.sp)
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF2A2A2A))

                // LINKED CLOUD FOLDERS SECTION (0 MB LOCAL STORAGE)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Linked Cloud Folders (0 MB Local Storage)",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                    Text(
                        text = "Browse and read PDFs on demand without taking any device storage.",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )

                    if (linkedCloudFolders.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF131B24),
                            border = BorderStroke(1.dp, Color(0xFF1E2D3D)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showLinkCloudFolderBrowserInDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CloudQueue, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("No Cloud Folders Linked", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("Tap to link a Drive folder with books (0 MB storage)", color = Color(0xFF90CAF9), fontSize = 11.sp)
                                }
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF64B5F6))
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            linkedCloudFolders.forEach { cloudFolder ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF131D28),
                                    border = BorderStroke(1.dp, Color(0xFF1E3A5F)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(cloudFolder.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            Text("Cloud Library • Stream on demand (0 MB)", color = Color(0xFF90CAF9), fontSize = 10.sp)
                                        }
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    Toast.makeText(context, "Scanning \"${cloudFolder.name}\" in Drive...", Toast.LENGTH_SHORT).show()
                                                    val res = driveManager.importDriveCloudFolder(context, cloudFolder)
                                                    if (res.isSuccess) {
                                                        val c = res.getOrDefault(0)
                                                        Toast.makeText(context, "Synced ${cloudFolder.name}: $c books found (0 MB storage)", Toast.LENGTH_LONG).show()
                                                    } else {
                                                        val err = res.exceptionOrNull()?.message ?: "Sync failed"
                                                        Toast.makeText(context, "Error: $err", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Sync Folder", tint = Color(0xFF64B5F6), modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = {
                                                driveManager.removeLinkedCloudFolder(cloudFolder.id)
                                                Toast.makeText(context, "Unlinked ${cloudFolder.name}", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Unlink", tint = Color.LightGray, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showLinkCloudFolderBrowserInDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A334E))
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF64B5F6))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Link Folder", color = Color.White, fontSize = 11.sp)
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        Toast.makeText(context, "Scanning all linked cloud folders...", Toast.LENGTH_SHORT).show()
                                        val res = driveManager.syncAllLinkedCloudFolders(context)
                                        if (res.isSuccess) {
                                            val c = res.getOrDefault(0)
                                            Toast.makeText(context, "Synced all folders: $c books ready to stream (0 MB storage)", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Sync failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sync All", color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (activeFolder == null) {
                        showDriveFolderBrowser = true
                        Toast.makeText(context, "Please select a 2-way sync folder first", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        val report = driveManager.syncBookshelfWithDrive(context, activeFolder?.id)
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
            initialFolder = activeFolder ?: DriveFolder.ROOT,
            onFolderSelected = { chosen ->
                driveManager.addCuratedBookshelfFolder(chosen)
                driveManager.setBookshelfSyncedFolder(chosen)
                showDriveFolderBrowser = false
                Toast.makeText(context, "Sync destination set to: ${chosen.name}", Toast.LENGTH_SHORT).show()
            },
            onLaunchPicker = { launchDialogPicker(false) },
            isAuthorizingPicker = isAuthorizingPicker && !isPickingForCloudFolder,
            onDismiss = { showDriveFolderBrowser = false }
        )
    }

    if (showLinkCloudFolderBrowserInDialog) {
        DriveFolderBrowserDialog(
            driveManager = driveManager,
            initialFolder = DriveFolder.ROOT,
            onFolderSelected = { chosen ->
                showLinkCloudFolderBrowserInDialog = false
                scope.launch {
                    Toast.makeText(context, "Linking \"${chosen.name}\" from Drive...", Toast.LENGTH_SHORT).show()
                    val res = driveManager.importDriveCloudFolder(context, chosen)
                    if (res.isSuccess) {
                        val count = res.getOrDefault(0)
                        Toast.makeText(context, "Linked \"${chosen.name}\" with $count books (0 MB storage used)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to link folder: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onLaunchPicker = { launchDialogPicker(true) },
            isAuthorizingPicker = isAuthorizingPicker && isPickingForCloudFolder,
            onDismiss = { showLinkCloudFolderBrowserInDialog = false }
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
    onDownloadToDevice: () -> Unit = {},
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
            if (!book.driveFolderName.isNullOrBlank() || book.isCloudOnly) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!book.driveFolderName.isNullOrBlank()) {
                        Surface(
                            color = Color(0xFF1E2D3D),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(book.driveFolderName ?: "", color = Color(0xFFB0BEC5), fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                    if (book.isCloudOnly) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            color = Color(0xFF132238),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(0.5.dp, Color(0xFF1E88E5))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(11.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Cloud • 0 MB", color = Color(0xFF90CAF9), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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
                if (book.isCloudOnly) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download to Device", color = Color(0xFF90CAF9))
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onDownloadToDevice()
                        }
                    )
                } else {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = GraphiteOrange, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Upload to Google Drive", color = GraphiteOrange)
                            }
                        },
                        onClick = {
                            menuExpanded = false
                            onUploadToDrive()
                        }
                    )
                }
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
    onDownloadToDevice: () -> Unit = {},
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
            if (book.isCloudOnly) {
                Surface(
                    color = Color(0xFF132238).copy(alpha = 0.92f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(0.5.dp, Color(0xFF1E88E5)),
                    modifier = Modifier.padding(4.dp).align(Alignment.BottomStart)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(10.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("0 MB", color = Color(0xFF90CAF9), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!book.driveFolderName.isNullOrBlank()) {
                    Text(
                        text = "📁 ${book.driveFolderName}",
                        color = Color(0xFF64B5F6),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "${book.progressPercent}%",
                    color = Color(0xFF757575),
                    fontSize = 11.sp
                )
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = Color(0xFF757575),
                        modifier = Modifier.size(16.dp)
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
                    if (book.isCloudOnly) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download to Device", color = Color(0xFF90CAF9))
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onDownloadToDevice()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = GraphiteOrange, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Upload to Google Drive", color = GraphiteOrange)
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onUploadToDrive()
                            }
                        )
                    }
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
