package com.graphite.app.core.drive

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.auth.api.identity.Identity
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteOutline
import com.graphite.app.core.theme.GraphiteSurfaceVariant
import com.graphite.app.data.model.NotebookItem
import kotlinx.coroutines.launch

/**
 * Polished, professional Google Drive upload experience faithfully matching Page 1 of the design spec:
 * 1. Top bar: Close 'X' button on the left, "Upload to Drive" title, and "Upload" pill button on the right.
 * 2. Left side: Large visual preview card of the note rendered as a PDF document.
 * 3. Right side:
 *    - Outlined "File name" field with clear 'X' icon (default: ${notebook.title}.pdf).
 *    - Outlined "Location" selector (folder icon, destination name, dropdown arrow) opening in-app folder picker.
 *    - Outlined "Account" selector (Google avatar, authorized email, dropdown arrow).
 * 4. Folder browser allows directly selecting any folder or navigating into it.
 * 5. Notes are uploaded strictly as genuine PDF files (application/pdf).
 */
@Composable
fun GoogleDriveUploadWorkflow(
    driveManager: GoogleDriveManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploadState by driveManager.uploadState.collectAsState()

    var showFolderBrowser by remember { mutableStateOf(false) }
    var activeFolderOverride by remember { mutableStateOf<DriveFolder?>(null) }
    var isAuthorizingPicker by remember { mutableStateOf(false) }

    // Launcher for official Google Identity Services mobile Picker
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        isAuthorizingPicker = false
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val data = activityResult.data
            if (data == null) {
                driveManager.setUploadError("No response received from Google Drive Picker")
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                val currentNotebook = (driveManager.uploadState.value as? DriveUploadState.SelectingFolder)?.notebook
                    ?: (driveManager.uploadState.value as? DriveUploadState.AwaitingConfirmation)?.notebook

                var result: Result<DriveFolder>? = null

                // 1. Extract Google Identity Services AuthorizationResult if present
                try {
                    val authClient = Identity.getAuthorizationClient(context)
                    val authResult = authClient.getAuthorizationResultFromIntent(data)
                    authResult.toGoogleSignInAccount()?.email?.let { email ->
                        driveManager.saveAuthorizedAccount(email)
                    }
                    result = driveManager.handlePickerAuthorizationResult(
                        context = context,
                        result = authResult,
                        notebook = currentNotebook
                    )
                } catch (_: Exception) {}

                // 2. Fall back to generic picker result handler if not handled by AuthorizationResult
                if (result == null || result.isFailure) {
                    val fallbackResult = driveManager.handlePickerResult(
                        context = context,
                        intent = data,
                        notebook = currentNotebook
                    )
                    if (fallbackResult.isSuccess || result == null) {
                        result = fallbackResult
                    }
                }

                if (result.isSuccess) {
                    activeFolderOverride = result.getOrNull()
                    showFolderBrowser = false
                } else {
                    val errorMsg = result.exceptionOrNull()?.message
                        ?: "No folder selected in Google Drive Picker"
                    driveManager.setUploadError(errorMsg, currentNotebook)
                }
            }
        }
    }

    when (val state = uploadState) {
        is DriveUploadState.Idle -> {
            showFolderBrowser = false
            activeFolderOverride = null
        }

        is DriveUploadState.SelectingFolder, is DriveUploadState.AwaitingConfirmation -> {
            val notebook = if (state is DriveUploadState.SelectingFolder) state.notebook else (state as DriveUploadState.AwaitingConfirmation).notebook
            val currentFolder: DriveFolder? = activeFolderOverride
                ?: (state as? DriveUploadState.AwaitingConfirmation)?.selectedFolder
                ?: (state as? DriveUploadState.SelectingFolder)?.selectedFolder
            val rawName = notebook.title.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val defaultSafeFileName = if (rawName.endsWith(".pdf", ignoreCase = true)) rawName else "$rawName.pdf"
            val initialFileName = if (state is DriveUploadState.AwaitingConfirmation) state.fileName else defaultSafeFileName
            val accountEmail = driveManager.getAuthorizedAccountEmail()

            if (showFolderBrowser) {
                DriveFolderBrowserDialog(
                    driveManager = driveManager,
                    initialFolder = currentFolder,
                    onFolderSelected = { chosen ->
                        activeFolderOverride = chosen
                        driveManager.onFolderSelected(notebook, chosen, initialFileName)
                        showFolderBrowser = false
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
                                                driveManager.setUploadError("Picker authorization resolution missing")
                                            }
                                        } else {
                                            isAuthorizingPicker = false
                                            scope.launch {
                                                val res = driveManager.handlePickerAuthorizationResult(
                                                    context = context,
                                                    result = authResult,
                                                    notebook = notebook
                                                )
                                                if (res.isSuccess) {
                                                    activeFolderOverride = res.getOrNull()
                                                    showFolderBrowser = false
                                                }
                                            }
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        isAuthorizingPicker = false
                                        driveManager.setUploadError("Failed to launch Google Drive Picker: ${e.message}")
                                    }
                            } catch (e: Exception) {
                                isAuthorizingPicker = false
                                driveManager.setUploadError("Error starting Google Drive Picker: ${e.message}")
                            }
                        }
                    },
                    isAuthorizingPicker = isAuthorizingPicker,
                    onDismiss = { showFolderBrowser = false }
                )
            } else {
                UploadToDriveDialog(
                    notebook = notebook,
                    initialFileName = initialFileName,
                    destinationFolder = currentFolder,
                    accountEmail = accountEmail,
                    onChooseFolder = { showFolderBrowser = true },
                    onConfirmUpload = { chosenFolder, chosenName ->
                        scope.launch {
                            driveManager.confirmAndUpload(
                                context = context,
                                notebook = notebook,
                                folder = chosenFolder,
                                customFileName = chosenName
                            )
                        }
                    },
                    onCancel = {
                        driveManager.cancelUpload()
                    }
                )
            }
        }

        is DriveUploadState.Uploading -> {
            UploadingProgressDialog(
                notebookTitle = state.notebookTitle,
                folderName = state.destinationFolder.name
            )
        }

        is DriveUploadState.Success -> {
            UploadSuccessDialog(
                result = state.result,
                onDismiss = { driveManager.resetUploadState() }
            )
        }

        is DriveUploadState.Error -> {
            UploadErrorDialog(
                state = state,
                onRetry = {
                    if (state.retryNotebook != null && state.retryFolder != null) {
                        scope.launch {
                            driveManager.confirmAndUpload(
                                context = context,
                                notebook = state.retryNotebook,
                                folder = state.retryFolder
                            )
                        }
                    } else {
                        driveManager.resetUploadState()
                    }
                },
                onDismiss = { driveManager.resetUploadState() }
            )
        }
    }
}

/**
 * "Upload to Drive" screen matching Page 1 of the attached specification.
 */
@Composable
private fun UploadToDriveDialog(
    notebook: NotebookItem,
    initialFileName: String,
    destinationFolder: DriveFolder?,
    accountEmail: String?,
    onChooseFolder: () -> Unit,
    onConfirmUpload: (DriveFolder, String) -> Unit,
    onCancel: () -> Unit
) {
    var fileNameText by remember(initialFileName) {
        val base = if (initialFileName.endsWith(".pdf", ignoreCase = true)) initialFileName else "$initialFileName.pdf"
        mutableStateOf(base)
    }

    val previewBitmap = remember(notebook) {
        NotePdfGenerator.generatePreviewBitmap(notebook, 480, 640)
    }

    val isUploadReady = destinationFolder != null && fileNameText.isNotBlank()

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp)),
            color = Color(0xFF141414),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top App Bar matching Page 1 of PDF
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White
                            )
                        }
                        Text(
                            text = "Upload to Drive",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Top Upload pill button
                    Button(
                        onClick = {
                            val trimmed = fileNameText.trim()
                            val finalName = if (trimmed.endsWith(".pdf", ignoreCase = true)) trimmed else "$trimmed.pdf"
                            if (destinationFolder != null) {
                                onConfirmUpload(destinationFolder, finalName)
                            }
                        },
                        enabled = isUploadReady,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8AB4F8), // Google Drive blue pill
                            contentColor = Color(0xFF0F172A),
                            disabledContainerColor = Color(0xFF2C2C2E),
                            disabledContentColor = Color(0xFF666666)
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Upload",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF262626), thickness = 0.5.dp)

                // Main body: Two columns on tablet landscape
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Column: Note Preview Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1C1C1E))
                            .border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = "Note Preview",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            NoteFallbackPreview(notebook)
                        }
                    }

                    // Right Column: File name, Location, Account, Actions
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        // File name OutlinedTextField
                        OutlinedTextField(
                            value = fileNameText,
                            onValueChange = { fileNameText = it },
                            label = { Text("File name", color = Color(0xFFAAAAAA), fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.PictureAsPdf,
                                    contentDescription = null,
                                    tint = Color(0xFFEF5350)
                                )
                            },
                            trailingIcon = {
                                if (fileNameText.isNotEmpty()) {
                                    IconButton(onClick = { fileNameText = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = "Clear",
                                            tint = Color(0xFF888888)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF8AB4F8),
                                unfocusedBorderColor = Color(0xFF444444),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color(0xFF1A1A1A),
                                unfocusedContainerColor = Color(0xFF1A1A1A)
                            )
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // Location OutlinedSelectableRow with explicit Change/Select action
                        OutlinedSelectableRow(
                            label = "Location",
                            icon = Icons.Default.Folder,
                            iconTint = if (destinationFolder != null) Color(0xFF64B5F6) else Color(0xFFFFB74D),
                            valueText = when {
                                destinationFolder == null -> "Choose destination folder..."
                                destinationFolder.id == "root" -> "My Drive (Root)"
                                else -> destinationFolder.name
                            },
                            actionText = if (destinationFolder != null) "Change" else "Select",
                            isHighlighted = destinationFolder == null,
                            onClick = onChooseFolder
                        )

                        if (destinationFolder == null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB74D),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Please choose a destination folder to enable upload",
                                    color = Color(0xFFFFB74D),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Account OutlinedSelectableRow
                        OutlinedSelectableRow(
                            label = "Account",
                            icon = Icons.Default.AccountCircle,
                            iconTint = Color(0xFF81C784),
                            valueText = accountEmail ?: "ayushman.chakraborty254@gmail.com",
                            onClick = { /* Account profile */ }
                        )

                        Spacer(modifier = Modifier.height(26.dp))

                        // Bottom Actions: Cancel & Upload
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = onCancel,
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray),
                                border = BorderStroke(1.dp, Color(0xFF444444)),
                                modifier = Modifier.height(42.dp)
                            ) {
                                Text("Cancel", fontSize = 13.sp)
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Button(
                                onClick = {
                                    val trimmed = fileNameText.trim()
                                    val finalName = if (trimmed.endsWith(".pdf", ignoreCase = true)) trimmed else "$trimmed.pdf"
                                    if (destinationFolder != null) {
                                        onConfirmUpload(destinationFolder, finalName)
                                    }
                                },
                                enabled = isUploadReady,
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF8AB4F8),
                                    contentColor = Color(0xFF0F172A),
                                    disabledContainerColor = Color(0xFF2C2C2E),
                                    disabledContentColor = Color(0xFF666666)
                                ),
                                modifier = Modifier.height(42.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Upload", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlinedSelectableRow(
    label: String,
    icon: ImageVector,
    iconTint: Color,
    valueText: String,
    onClick: () -> Unit,
    actionText: String? = null,
    isHighlighted: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Color(0xFFAAAAAA),
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF1A1A1A),
            border = BorderStroke(
                1.dp,
                if (isHighlighted) Color(0xFFFFB74D) else Color(0xFF444444)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = valueText,
                        color = if (isHighlighted) Color(0xFFFFB74D) else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (actionText != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isHighlighted) Color(0xFFFFB74D).copy(alpha = 0.2f) else Color(0xFF2C2C2E)
                        ) {
                            Text(
                                text = actionText,
                                color = if (isHighlighted) Color(0xFFFFB74D) else Color(0xFF8AB4F8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Dropdown",
                        tint = Color(0xFFAAAAAA),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteFallbackPreview(notebook: NotebookItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.PictureAsPdf,
            contentDescription = null,
            tint = Color(0xFFEF5350),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = notebook.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "PDF Document • ${notebook.initialStrokes.size} strokes",
            color = Color.Gray,
            fontSize = 12.sp
        )
    }
}

/**
 * Google Drive Folder Browser supporting subfolder navigation, folder selection,
 * creating new folders, and launching the official Google Drive mobile Picker.
 *
 * Implements reliable native Drive API v3 folder listing & selection:
 * - Lists actual folders using Drive API v3 (mimeType = 'application/vnd.google-apps.folder').
 * - Displays folders separately from files.
 * - Allows opening folders to navigate deeper.
 * - Explicit "Select this folder" button for the currently browsed folder.
 * - Direct row-level "Select" button to choose a folder immediately without opening.
 * - Supports navigation back to parent folder and direct jump back to My Drive root.
 * - Displays selected folder name upon returning to upload dialog.
 */
@Composable
fun DriveFolderBrowserDialog(
    driveManager: GoogleDriveManager,
    initialFolder: DriveFolder? = null,
    onFolderSelected: (DriveFolder) -> Unit,
    onLaunchPicker: () -> Unit,
    isAuthorizingPicker: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Navigation stack for hierarchical folder navigation
    var currentFolder by remember { mutableStateOf(initialFolder ?: DriveFolder.ROOT) }
    var folderStack by remember { mutableStateOf(listOf<DriveFolder>()) }

    var subfolders by remember { mutableStateOf<List<DriveFolder>>(emptyList()) }
    var isLoadingFolders by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    // Load subfolders whenever currentFolder changes
    LaunchedEffect(currentFolder.id) {
        isLoadingFolders = true
        loadError = null
        val result = driveManager.fetchSubfolders(context, currentFolder.id)
        if (result.isSuccess) {
            subfolders = result.getOrDefault(emptyList())
        } else {
            loadError = result.exceptionOrNull()?.message ?: "Failed to load folders"
            subfolders = emptyList()
        }
        isLoadingFolders = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (folderStack.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    val previous = folderStack.last()
                                    folderStack = folderStack.dropLast(1)
                                    currentFolder = previous
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to parent",
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = if (currentFolder.id == "root") "My Drive" else currentFolder.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                // Breadcrumb path & My Drive jump shortcut
                if (currentFolder.id != "root" || folderStack.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = {
                                folderStack = emptyList()
                                currentFolder = DriveFolder.ROOT
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = null,
                                tint = Color(0xFF8AB4F8),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("My Drive", color = Color(0xFF8AB4F8), fontSize = 11.sp)
                        }
                        Text(">", color = Color.Gray, fontSize = 11.sp)
                        folderStack.filter { it.id != "root" }.forEach { ancestor ->
                            TextButton(
                                onClick = {
                                    val index = folderStack.indexOf(ancestor)
                                    if (index >= 0) {
                                        folderStack = folderStack.take(index)
                                        currentFolder = ancestor
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text(ancestor.name, color = Color(0xFF8AB4F8), fontSize = 11.sp, maxLines = 1)
                            }
                            Text(">", color = Color.Gray, fontSize = 11.sp)
                        }
                        Text(
                            text = currentFolder.name,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Primary action: Select current folder explicitly
                Button(
                    onClick = { onFolderSelected(currentFolder) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
                ) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Select this folder (\"${if (currentFolder.id == "root") "My Drive" else currentFolder.name}\")",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Google Picker alternative button
                OutlinedButton(
                    onClick = onLaunchPicker,
                    enabled = !isAuthorizingPicker,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64B5F6))
                ) {
                    if (isAuthorizingPicker) {
                        CircularProgressIndicator(
                            color = Color(0xFF64B5F6),
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isAuthorizingPicker) "Opening Picker..." else "Browse with Google Drive Picker",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "Tip: In Google Picker, press & hold (long-press) a folder to select it (single tap opens it), then tap 'Select' at the bottom.",
                    color = Color(0xFFAAAAAA),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )

                HorizontalDivider(color = GraphiteOutline, thickness = 0.5.dp)

                // Subfolders list (only actual folders, mimeType = application/vnd.google-apps.folder)
                if (isLoadingFolders) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = GraphiteOrange,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                    }
                } else if (subfolders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (loadError != null) loadError!! else "No subfolders in this location",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(subfolders) { folder ->
                            val navigateIntoFolder = {
                                folderStack = folderStack + currentFolder
                                currentFolder = folder
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF222222))
                                    .clickable(onClick = navigateIntoFolder)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = Color(0xFF64B5F6),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = folder.name,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Direct Select action: choose this specific folder as destination
                                    Button(
                                        onClick = { onFolderSelected(folder) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF2E7D32),
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Select", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Open action: navigate into subfolders
                                    OutlinedButton(
                                        onClick = navigateIntoFolder,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64B5F6)),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Open", fontSize = 11.sp)
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Create new folder inside current folder
                if (showCreateFolderDialog) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            placeholder = { Text("Folder name", color = Color.Gray, fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newFolderName.isNotBlank()) {
                                    scope.launch {
                                        val result = driveManager.createNewFolder(
                                            context = context,
                                            folderName = newFolderName.trim(),
                                            parentFolderId = currentFolder.id
                                        )
                                        if (result.isSuccess) {
                                            val created = result.getOrThrow()
                                            subfolders = subfolders + created
                                            onFolderSelected(created)
                                        }
                                        showCreateFolderDialog = false
                                        newFolderName = ""
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
                        ) {
                            Text("Create", fontSize = 12.sp)
                        }
                    }
                } else {
                    TextButton(
                        onClick = { showCreateFolderDialog = true },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp), tint = GraphiteOrange)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New Folder here", fontSize = 12.sp, color = GraphiteOrange)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onFolderSelected(currentFolder) },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange)
            ) {
                Text(
                    text = if (currentFolder.id == "root") "Select My Drive" else "Select \"${currentFolder.name}\"",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray, fontSize = 12.sp)
            }
        },
        containerColor = GraphiteSurfaceVariant,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun UploadingProgressDialog(
    notebookTitle: String,
    folderName: String
) {
    AlertDialog(
        onDismissRequest = {}, // Disable dismiss during active network transfer
        title = {
            Text(
                text = "Uploading to Google Drive",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    color = GraphiteOrange,
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Uploading \"$notebookTitle\" as PDF...",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Destination: $folderName",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {},
        containerColor = GraphiteSurfaceVariant,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun UploadSuccessDialog(
    result: DriveUploadResult.Success,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF81C784),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Upload Complete",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Successfully uploaded \"${result.fileName}\" as PDF to Google Drive.",
                    color = Color.White,
                    fontSize = 13.sp
                )
                result.folderName?.let { folder ->
                    Text(
                        text = "Saved in: $folder",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Done", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = GraphiteSurfaceVariant,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun UploadErrorDialog(
    state: DriveUploadState.Error,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Upload Failed",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        },
        text = {
            Text(
                text = state.message,
                color = Color.LightGray,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            if (state.retryNotebook != null && state.retryFolder != null) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Retry", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("OK", color = Color.White, fontSize = 12.sp)
                }
            }
        },
        dismissButton = {
            if (state.retryNotebook != null && state.retryFolder != null) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color.Gray, fontSize = 12.sp)
                }
            }
        },
        containerColor = GraphiteSurfaceVariant,
        shape = RoundedCornerShape(16.dp)
    )
}
