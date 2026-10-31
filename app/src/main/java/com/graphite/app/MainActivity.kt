package com.graphite.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.graphite.app.core.navigation.GraphiteBottomNavBar
import com.graphite.app.core.navigation.NavigationTab
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteTheme
import com.graphite.app.feature.diagnostic.StylusDiagnosticScreen
import com.graphite.app.feature.noteshelf.NoteshelfScreen
import com.graphite.app.feature.reader.screens.BookshelfScreen
import com.graphite.app.feature.reader.screens.MeScreen
import com.graphite.app.feature.reader.screens.ReaderViewScreen
import com.graphite.app.feature.reader.screens.ReadingScreen
import com.graphite.app.feature.workspace.CanvasEditorScreen

import android.content.Intent
import com.graphite.app.core.drive.GoogleDriveUploadWorkflow
import com.graphite.app.data.repository.GraphiteRepository
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.launch
import com.graphite.app.core.importer.ImportDestination
import com.graphite.app.core.importer.ImportDestinationDialog
import com.graphite.app.core.importer.ImportResult
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.graphite.app.feature.workspace.QuickDocumentSwitcherDialog
import com.graphite.app.feature.workspace.VoiceNotebookScreen
import com.graphite.app.feature.workspace.model.SplitLayoutOrientation

sealed interface ActiveDestination {
    data class MainTab(val tab: NavigationTab) : ActiveDestination
    data class ReaderView(val bookId: String) : ActiveDestination
    data class CanvasEditor(val notebookId: String) : ActiveDestination
    data class SplitScreen(
        val docAId: String,
        val docBId: String,
        val orientation: SplitLayoutOrientation = SplitLayoutOrientation.VERTICAL_DIVIDER
    ) : ActiveDestination
    data object Diagnostics : ActiveDestination
}

class MainActivity : ComponentActivity() {

    private val sharedUriToOpen = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
        } catch (_: Throwable) {}
        try {
            if (GraphiteApplication.instance.isGoogleAccountManagerInitialized) {
                GraphiteApplication.instance.googleAccountManager.restoreSession(this)
            }
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to restore session on create: ${e.message}", e)
        }
        try {
            handleDeepLinkIntent(intent)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to handle deep link on create: ${e.message}", e)
        }
        try {
            handleShareIntent(intent)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to handle share intent on create: ${e.message}", e)
        }
        setContent {

            GraphiteTheme {
                var currentDestination by remember {
                    mutableStateOf<ActiveDestination>(ActiveDestination.MainTab(NavigationTab.RECENT))
                }
                var selectedTab by rememberSaveable { mutableStateOf(NavigationTab.RECENT) }

                val isRootTab = currentDestination is ActiveDestination.MainTab
                val context = androidx.compose.ui.platform.LocalContext.current
                val sharedUri by sharedUriToOpen
                val coroutineScope = rememberCoroutineScope()
                var isImportingFile by remember { mutableStateOf(false) }

                val activeSharedUri = sharedUri
                if (activeSharedUri != null) {
                    ImportDestinationDialog(
                        uri = activeSharedUri,
                        isImporting = isImportingFile,
                        onSelectDestination = { dest ->
                            coroutineScope.launch {
                                isImportingFile = true
                                try {
                                    val result = when (dest) {
                                        ImportDestination.BOOKSHELF -> {
                                            GraphiteApplication.instance.fileImportManager.importToBookshelf(context, activeSharedUri)
                                        }
                                        else -> {
                                            GraphiteApplication.instance.fileImportManager.importToNotes(context, activeSharedUri)
                                        }
                                    }
                                    when (result) {
                                        is ImportResult.SuccessNotebook -> {
                                            selectedTab = NavigationTab.NOTESHELF
                                            currentDestination = ActiveDestination.CanvasEditor(result.notebook.id)
                                            Toast.makeText(context, "Saved to Noteshelf", Toast.LENGTH_SHORT).show()
                                        }
                                        is ImportResult.SuccessBook -> {
                                            selectedTab = NavigationTab.BOOKSHELF
                                            currentDestination = ActiveDestination.ReaderView(result.book.id)
                                            Toast.makeText(context, "Saved to Bookshelf", Toast.LENGTH_SHORT).show()
                                        }
                                        is ImportResult.Error -> {
                                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to open file: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isImportingFile = false
                                    sharedUriToOpen.value = null
                                }
                            }
                        },
                        onDismiss = {
                            sharedUriToOpen.value = null
                        }
                    )
                }

                val appUpdateManager = remember { GraphiteApplication.instance.appUpdateManager }
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            appUpdateManager.checkForUpdates(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }
                LaunchedEffect(Unit) {
                    while (true) {
                        appUpdateManager.checkForUpdates(context)
                        kotlinx.coroutines.delay(120_000L) // Auto re-check every 2 minutes while active
                    }
                }
                com.graphite.app.core.updater.AppUpdateModalDialog(updateManager = appUpdateManager, context = context)

                GoogleDriveUploadWorkflow(GraphiteApplication.instance.googleDriveManager)

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = GraphiteBlack,
                    bottomBar = {
                        if (isRootTab) {
                            GraphiteBottomNavBar(
                                selectedTab = selectedTab,
                                onTabSelected = { tab ->
                                    selectedTab = tab
                                    currentDestination = ActiveDestination.MainTab(tab)
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Crossfade(
                            targetState = currentDestination,
                            label = "destination_transition"
                        ) { destination ->
                            when (destination) {
                                is ActiveDestination.MainTab -> {
                                    when (destination.tab) {
                                        NavigationTab.RECENT -> {
                                            ReadingScreen(
                                                onOpenBook = { bookId ->
                                                    currentDestination = ActiveDestination.ReaderView(bookId)
                                                }
                                            )
                                        }
                                        NavigationTab.BOOKSHELF -> {
                                            BookshelfScreen(
                                                onOpenBook = { bookId ->
                                                    currentDestination = ActiveDestination.ReaderView(bookId)
                                                }
                                            )
                                        }
                                        NavigationTab.NOTESHELF -> {
                                            NoteshelfScreen(
                                                onOpenNotebook = { notebookId ->
                                                    currentDestination = ActiveDestination.CanvasEditor(notebookId)
                                                },
                                                onOpenSplitScreen = { docA, docB, orient ->
                                                    currentDestination = ActiveDestination.SplitScreen(docA, docB, orient)
                                                }
                                            )
                                        }
                                        NavigationTab.ME -> {
                                            MeScreen(
                                                onNavigateToNoteshelf = {
                                                    selectedTab = NavigationTab.NOTESHELF
                                                    currentDestination = ActiveDestination.MainTab(NavigationTab.NOTESHELF)
                                                }
                                            )
                                        }
                                    }
                                }
                                is ActiveDestination.ReaderView -> {
                                    BackHandler {
                                        currentDestination = ActiveDestination.MainTab(selectedTab)
                                    }
                                    ReaderViewScreen(
                                        bookId = destination.bookId,
                                        onNavigateBack = {
                                            currentDestination = ActiveDestination.MainTab(selectedTab)
                                        }
                                    )
                                }
                                is ActiveDestination.CanvasEditor -> {
                                    BackHandler {
                                        currentDestination = ActiveDestination.MainTab(selectedTab)
                                    }
                                    val openedNote = remember(destination.notebookId) {
                                        GraphiteRepository.getNotebook(destination.notebookId)
                                    }
                                    when (openedNote?.mimeType) {
                                        "text/keep" -> {
                                            com.graphite.app.feature.workspace.KeepTextEditorScreen(
                                                notebookId = destination.notebookId,
                                                onNavigateBack = {
                                                    currentDestination = ActiveDestination.MainTab(selectedTab)
                                                }
                                            )
                                        }
                                        "application/x-graphite-voice" -> {
                                            VoiceNotebookScreen(
                                                notebookId = destination.notebookId,
                                                onNavigateBack = {
                                                    currentDestination = ActiveDestination.MainTab(selectedTab)
                                                }
                                            )
                                        }
                                        else -> {
                                            CanvasEditorScreen(
                                                notebookId = destination.notebookId,
                                                onNavigateBack = {
                                                    currentDestination = ActiveDestination.MainTab(selectedTab)
                                                },
                                                onSplitScreen = { docA, docB, orient ->
                                                    currentDestination = ActiveDestination.SplitScreen(docA, docB, orient)
                                                },
                                                onSwitchDocument = { newDocId ->
                                                    currentDestination = ActiveDestination.CanvasEditor(newDocId)
                                                }
                                            )
                                        }
                                    }
                                }
                                is ActiveDestination.SplitScreen -> {
                                    var docAId by remember(destination) { mutableStateOf(destination.docAId) }
                                    var docBId by remember(destination) { mutableStateOf(destination.docBId) }

                                    BackHandler {
                                        // Pressing system back closes secondary document and restores doc A in normal single view
                                        currentDestination = ActiveDestination.CanvasEditor(docAId)
                                    }

                                    var currentOrientation by remember(destination) { mutableStateOf(destination.orientation) }
                                    var splitRatio by remember(destination) { mutableFloatStateOf(0.5f) }
                                    var isDraggingDivider by remember { mutableStateOf(false) }

                                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                        val totalWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                                        val totalHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                                        if (currentOrientation == SplitLayoutOrientation.VERTICAL_DIVIDER) {
                                            // Side-by-Side (Left & Right panes with draggable vertical divider)
                                            Row(modifier = Modifier.fillMaxSize()) {
                                                Box(
                                                    modifier = Modifier
                                                        .weight(splitRatio.coerceIn(0.15f, 0.85f))
                                                        .fillMaxHeight()
                                                ) {
                                                    SplitPaneDocScreen(
                                                        notebookId = docAId,
                                                        isInSplitScreen = true,
                                                        onNavigateBack = {
                                                            // Closing doc A opens doc B normally in single view!
                                                            currentDestination = ActiveDestination.CanvasEditor(docBId)
                                                        },
                                                        onSwitchDocument = { newId ->
                                                            docAId = newId
                                                        }
                                                    )
                                                }

                                                // Resizable Vertical Divider
                                                Box(
                                                    modifier = Modifier
                                                        .width(18.dp)
                                                        .fillMaxHeight()
                                                        .background(androidx.compose.ui.graphics.Color(0xFF141416))
                                                        .pointerInput(totalWidth) {
                                                            detectDragGestures(
                                                                onDragStart = { isDraggingDivider = true },
                                                                onDragEnd = { isDraggingDivider = false },
                                                                onDragCancel = { isDraggingDivider = false }
                                                            ) { change, dragAmount ->
                                                                change.consume()
                                                                val delta = dragAmount.x / totalWidth
                                                                splitRatio = (splitRatio + delta).coerceIn(0.15f, 0.85f)
                                                            }
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .width(2.dp)
                                                            .fillMaxHeight()
                                                            .background(if (isDraggingDivider) androidx.compose.ui.graphics.Color(0xFFFF7A30) else androidx.compose.ui.graphics.Color(0xFF44444C))
                                                    )
                                                    Column(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .background(if (isDraggingDivider) androidx.compose.ui.graphics.Color(0xFF332218) else androidx.compose.ui.graphics.Color(0xFF222226))
                                                            .border(1.dp, if (isDraggingDivider) androidx.compose.ui.graphics.Color(0xFFFF7A30) else androidx.compose.ui.graphics.Color(0xFF44444C), RoundedCornerShape(10.dp))
                                                            .padding(horizontal = 2.dp, vertical = 6.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .width(4.dp)
                                                                .height(26.dp)
                                                                .clip(RoundedCornerShape(2.dp))
                                                                .background(if (isDraggingDivider) androidx.compose.ui.graphics.Color(0xFFFF7A30) else androidx.compose.ui.graphics.Color(0xFF888890))
                                                        )
                                                        IconButton(
                                                            onClick = {
                                                                val temp = docAId
                                                                docAId = docBId
                                                                docBId = temp
                                                            },
                                                            modifier = Modifier.size(22.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                                                                contentDescription = "Swap Left and Right Panes",
                                                                tint = androidx.compose.ui.graphics.Color.LightGray,
                                                                modifier = Modifier.size(15.dp)
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                currentOrientation = SplitLayoutOrientation.HORIZONTAL_DIVIDER
                                                            },
                                                            modifier = Modifier.size(22.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.SwapVert,
                                                                contentDescription = "Switch to Top-Bottom Split",
                                                                tint = androidx.compose.ui.graphics.Color.LightGray,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .weight((1f - splitRatio).coerceIn(0.15f, 0.85f))
                                                        .fillMaxHeight()
                                                ) {
                                                    SplitPaneDocScreen(
                                                        notebookId = docBId,
                                                        isInSplitScreen = true,
                                                        onNavigateBack = {
                                                            // Closing doc B opens doc A normally in single view!
                                                            currentDestination = ActiveDestination.CanvasEditor(docAId)
                                                        },
                                                        onSwitchDocument = { newId ->
                                                            docBId = newId
                                                        }
                                                    )
                                                }
                                            }
                                        } else {
                                            // Top & Bottom (Upper & Lower panes with draggable horizontal divider)
                                            Column(modifier = Modifier.fillMaxSize()) {
                                                Box(
                                                    modifier = Modifier
                                                        .weight(splitRatio.coerceIn(0.15f, 0.85f))
                                                        .fillMaxWidth()
                                                ) {
                                                    SplitPaneDocScreen(
                                                        notebookId = docAId,
                                                        isInSplitScreen = true,
                                                        onNavigateBack = {
                                                            // Closing doc A opens doc B normally in single view!
                                                            currentDestination = ActiveDestination.CanvasEditor(docBId)
                                                        },
                                                        onSwitchDocument = { newId ->
                                                            docAId = newId
                                                        }
                                                    )
                                                }

                                                // Resizable Horizontal Divider
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(18.dp)
                                                        .background(androidx.compose.ui.graphics.Color(0xFF141416))
                                                        .pointerInput(totalHeight) {
                                                            detectDragGestures(
                                                                onDragStart = { isDraggingDivider = true },
                                                                onDragEnd = { isDraggingDivider = false },
                                                                onDragCancel = { isDraggingDivider = false }
                                                            ) { change, dragAmount ->
                                                                change.consume()
                                                                val delta = dragAmount.y / totalHeight
                                                                splitRatio = (splitRatio + delta).coerceIn(0.15f, 0.85f)
                                                            }
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(2.dp)
                                                            .background(if (isDraggingDivider) androidx.compose.ui.graphics.Color(0xFFFF7A30) else androidx.compose.ui.graphics.Color(0xFF44444C))
                                                    )
                                                    Row(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .background(if (isDraggingDivider) androidx.compose.ui.graphics.Color(0xFF332218) else androidx.compose.ui.graphics.Color(0xFF222226))
                                                            .border(1.dp, if (isDraggingDivider) androidx.compose.ui.graphics.Color(0xFFFF7A30) else androidx.compose.ui.graphics.Color(0xFF44444C), RoundedCornerShape(10.dp))
                                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .width(26.dp)
                                                                .height(4.dp)
                                                                .clip(RoundedCornerShape(2.dp))
                                                                .background(if (isDraggingDivider) androidx.compose.ui.graphics.Color(0xFFFF7A30) else androidx.compose.ui.graphics.Color(0xFF888890))
                                                        )
                                                        IconButton(
                                                            onClick = {
                                                                val temp = docAId
                                                                docAId = docBId
                                                                docBId = temp
                                                            },
                                                            modifier = Modifier.size(22.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                                                                contentDescription = "Swap Top and Bottom Panes",
                                                                tint = androidx.compose.ui.graphics.Color.LightGray,
                                                                modifier = Modifier.size(15.dp)
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                currentOrientation = SplitLayoutOrientation.VERTICAL_DIVIDER
                                                            },
                                                            modifier = Modifier.size(22.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.SwapHoriz,
                                                                contentDescription = "Switch to Side-by-Side Split",
                                                                tint = androidx.compose.ui.graphics.Color.LightGray,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .weight((1f - splitRatio).coerceIn(0.15f, 0.85f))
                                                        .fillMaxWidth()
                                                ) {
                                                    SplitPaneDocScreen(
                                                        notebookId = docBId,
                                                        isInSplitScreen = true,
                                                        onNavigateBack = {
                                                            // Closing doc B opens doc A normally in single view!
                                                            currentDestination = ActiveDestination.CanvasEditor(docAId)
                                                        },
                                                        onSwitchDocument = { newId ->
                                                            docBId = newId
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                is ActiveDestination.Diagnostics -> {
                                    BackHandler {
                                        currentDestination = ActiveDestination.MainTab(selectedTab)
                                    }
                                    StylusDiagnosticScreen(
                                        onNavigateBack = {
                                            currentDestination = ActiveDestination.MainTab(selectedTab)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLinkIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "graphite" && data.host == "oauth2callback") {
            GraphiteApplication.instance.googleDriveManager.handlePickerRedirect(data, null)
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val uri: Uri? = when (action) {
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } ?: intent.data
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            sharedUriToOpen.value = uri
        }
    }
}

@Composable
private fun SplitPaneDocScreen(
    notebookId: String,
    isInSplitScreen: Boolean,
    onNavigateBack: () -> Unit,
    onSwitchDocument: ((String) -> Unit)? = null
) {
    key(notebookId) {
        val openedNote = remember(notebookId) {
            GraphiteRepository.getNotebook(notebookId)
        }
        when (openedNote?.mimeType) {
            "text/keep" -> {
                com.graphite.app.feature.workspace.KeepTextEditorScreen(
                    notebookId = notebookId,
                    onNavigateBack = onNavigateBack
                )
            }
            "application/x-graphite-voice" -> {
                VoiceNotebookScreen(
                    notebookId = notebookId,
                    onNavigateBack = onNavigateBack
                )
            }
            else -> {
                CanvasEditorScreen(
                    notebookId = notebookId,
                    isInSplitScreen = isInSplitScreen,
                    onNavigateBack = onNavigateBack,
                    onSwitchDocument = onSwitchDocument
                )
            }
        }
    }
}

