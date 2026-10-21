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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import com.graphite.app.feature.workspace.VoiceNotebookScreen

sealed interface ActiveDestination {
    data class MainTab(val tab: NavigationTab) : ActiveDestination
    data class ReaderView(val bookId: String) : ActiveDestination
    data class CanvasEditor(val notebookId: String) : ActiveDestination
    data class SplitScreen(val docAId: String, val docBId: String) : ActiveDestination
    data object Diagnostics : ActiveDestination
}

class MainActivity : ComponentActivity() {

    private val sharedUriToOpen = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLinkIntent(intent)
        handleShareIntent(intent)
        setContent {

            GraphiteTheme {
                var currentDestination by remember {
                    mutableStateOf<ActiveDestination>(ActiveDestination.MainTab(NavigationTab.RECENT))
                }
                var selectedTab by rememberSaveable { mutableStateOf(NavigationTab.RECENT) }

                val isRootTab = currentDestination is ActiveDestination.MainTab
                val context = androidx.compose.ui.platform.LocalContext.current
                val sharedUri by sharedUriToOpen

                LaunchedEffect(sharedUri) {
                    val uri = sharedUri ?: return@LaunchedEffect
                    try {
                        val result = GraphiteApplication.instance.fileImportManager.importToNotes(context, uri)
                        when (result) {
                            is com.graphite.app.core.importer.ImportResult.SuccessNotebook -> {
                                currentDestination = ActiveDestination.CanvasEditor(result.notebook.id)
                            }
                            is com.graphite.app.core.importer.ImportResult.SuccessBook -> {
                                currentDestination = ActiveDestination.ReaderView(result.book.id)
                            }
                            else -> {}
                        }
                    } catch (_: Exception) {}
                    sharedUriToOpen.value = null
                }

                val appUpdateManager = remember { GraphiteApplication.instance.appUpdateManager }
                LaunchedEffect(Unit) {
                    appUpdateManager.checkForUpdates(context)
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
                                                onOpenSplitScreen = { docA, docB ->
                                                    currentDestination = ActiveDestination.SplitScreen(docA, docB)
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
                                                }
                                            )
                                        }
                                    }
                                }
                                is ActiveDestination.SplitScreen -> {
                                    BackHandler {
                                        currentDestination = ActiveDestination.MainTab(selectedTab)
                                    }
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                            CanvasEditorScreen(
                                                notebookId = destination.docAId,
                                                onNavigateBack = {
                                                    currentDestination = ActiveDestination.MainTab(selectedTab)
                                                }
                                            )
                                        }
                                        // 2mm Distinct Split-Screen Separator Gap with Accent Center Line & Handle Pill
                                        Box(
                                            modifier = Modifier
                                                .width(8.dp)
                                                .fillMaxHeight()
                                                .background(androidx.compose.ui.graphics.Color(0xFF141416)),
                                            contentAlignment = androidx.compose.ui.Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(2.dp)
                                                    .fillMaxHeight()
                                                    .background(androidx.compose.ui.graphics.Color(0xFF55555C))
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .width(4.dp)
                                                    .height(36.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(Color(0xFF888890))
                                            )
                                        }
                                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                            CanvasEditorScreen(
                                                notebookId = destination.docBId,
                                                onNavigateBack = {
                                                    currentDestination = ActiveDestination.MainTab(selectedTab)
                                                }
                                            )
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
