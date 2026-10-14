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

sealed interface ActiveDestination {
    data class MainTab(val tab: NavigationTab) : ActiveDestination
    data class ReaderView(val bookId: String) : ActiveDestination
    data class CanvasEditor(val notebookId: String) : ActiveDestination
    data object Diagnostics : ActiveDestination
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDeepLinkIntent(intent)
        setContent {
            GraphiteTheme {
                var currentDestination by remember {
                    mutableStateOf<ActiveDestination>(ActiveDestination.MainTab(NavigationTab.RECENT))
                }
                var selectedTab by rememberSaveable { mutableStateOf(NavigationTab.RECENT) }

                val isRootTab = currentDestination is ActiveDestination.MainTab

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
                                                    val note = GraphiteRepository.getNotebook(notebookId)
                                                    val ext = note?.filePath?.substringAfterLast('.', "")?.lowercase() ?: ""
                                                    val isDoc = !note?.filePath.isNullOrBlank()
                                                        || note?.mimeType?.contains("pdf", ignoreCase = true) == true
                                                        || note?.mimeType?.contains("word", ignoreCase = true) == true
                                                        || note?.mimeType?.contains("presentation", ignoreCase = true) == true
                                                        || note?.mimeType?.contains("sheet", ignoreCase = true) == true
                                                        || ext in listOf("pdf", "docx", "doc", "pptx", "ppt", "xlsx", "xls", "txt", "csv")
                                                    if (isDoc) {
                                                        currentDestination = ActiveDestination.ReaderView(notebookId)
                                                    } else {
                                                        currentDestination = ActiveDestination.CanvasEditor(notebookId)
                                                    }
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
                                    CanvasEditorScreen(
                                        notebookId = destination.notebookId,
                                        onNavigateBack = {
                                            currentDestination = ActiveDestination.MainTab(selectedTab)
                                        }
                                    )
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
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "graphite" && data.host == "oauth2callback") {
            GraphiteApplication.instance.googleDriveManager.handlePickerRedirect(data, null)
        }
    }
}
