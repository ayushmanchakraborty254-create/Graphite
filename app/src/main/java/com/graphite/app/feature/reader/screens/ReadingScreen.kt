package com.graphite.app.feature.reader.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.auth.AccountRequiredDialog
import com.graphite.app.core.auth.GoogleProfileTopBarIcon
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteSurfaceVariant
import com.graphite.app.data.model.Book
import com.graphite.app.data.repository.GraphiteRepository
import com.graphite.app.feature.reader.components.BookCover

@Composable
fun ReadingScreen(
    onOpenBook: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val books by GraphiteRepository.books.collectAsState()
    val context = LocalContext.current
    val googleAccountManager = remember { GraphiteApplication.instance.googleAccountManager }
    val googleUser by googleAccountManager.currentUser.collectAsState()
    val isGuestMode by googleAccountManager.isGuestMode.collectAsState()
    val hasActiveSession = googleUser != null || isGuestMode

    var showAccountRequiredDialog by remember { mutableStateOf(false) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        googleAccountManager.handleSignInResult(result.data)
    }

    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var isGridView by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        GraphiteRepository.cleanBookDuplicatesAndGhosts()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GraphiteBlack)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // Top Header matching Page 1
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Reading",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSearchBar = !showSearchBar }) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Reading",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { isGridView = !isGridView }) {
                    Icon(
                        imageVector = if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                        contentDescription = "Switch View Mode",
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
                placeholder = { Text("Search recents...", color = Color.Gray) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GraphiteOrange,
                    unfocusedBorderColor = Color(0xFF333333),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }

        // Recent Books List or Grid (Strictly books that have been opened / read)
        val recentBooks = remember(books) {
            books.filter { it.hasBeenRead && it.lastReadTimeMillis > 0L }
                .sortedByDescending { it.lastReadTimeMillis }
        }
        val filteredBooks = if (searchQuery.isBlank()) recentBooks else recentBooks.filter {
            it.title.contains(searchQuery, ignoreCase = true) || it.author.contains(searchQuery, ignoreCase = true)
        }

        if (filteredBooks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
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
                        text = if (!hasActiveSession) "Account Required" else if (searchQuery.isNotBlank()) "No matching recent books" else "No recent books",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (!hasActiveSession) "Sign in with Google to sync your reading library, or use a guest account to read offline." else if (searchQuery.isNotBlank()) "Try a different search query" else "Books you open and read will appear here",
                        color = Color(0xFF9E9E9E),
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (!hasActiveSession) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { showAccountRequiredDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Sign In / Guest Account", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        } else if (isGridView) {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredBooks.size, key = { filteredBooks[it].id }) { idx ->
                    val book = filteredBooks[idx]
                    RecentBookGridCard(
                        book = book,
                        onOpen = { onOpenBook(book.id) }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(filteredBooks, key = { it.id }) { book ->
                    RecentBookCard(
                        book = book,
                        onOpen = { onOpenBook(book.id) }
                    )
                }
            }
        }
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
            actionDescription = "access your reading library"
        )
    }
}

@Composable
private fun RecentBookGridCard(
    book: Book,
    onOpen: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                BookCover(
                    book = book,
                    modifier = Modifier
                        .width(90.dp)
                        .height(125.dp)
                        .align(Alignment.Center)
                )
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(GraphiteSurfaceVariant)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open Reader", color = Color.White) },
                            onClick = {
                                menuExpanded = false
                                onOpen()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Mark as Completed", color = Color.White) },
                            onClick = {
                                GraphiteRepository.updateBookProgress(book.id, 100)
                                menuExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Remove from Recents", color = Color(0xFFFF8A80)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = Color(0xFFFF8A80),
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                GraphiteRepository.removeBookFromRecents(book.id)
                                menuExpanded = false
                                android.widget.Toast.makeText(context, "Removed from Recents", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }

            Text(
                text = book.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )

            Button(
                onClick = onOpen,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GraphiteOrange),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("${book.progressPercent}%", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RecentBookCard(
    book: Book,
    onOpen: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Book Cover Thumbnail
        BookCover(
            book = book,
            modifier = Modifier
                .width(68.dp)
                .height(96.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Book Information & Continue Button
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = book.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = book.author,
                color = Color(0xFF9E9E9E),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Orange "Continue (X%)" Pill Button matching Page 1
            Button(
                onClick = onOpen,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GraphiteOrange
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Continue (${book.progressPercent}%)",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 3-dots Menu
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = Color(0xFF888888)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(GraphiteSurfaceVariant)
            ) {
                DropdownMenuItem(
                    text = { Text("Open Reader", color = Color.White) },
                    onClick = {
                        menuExpanded = false
                        onOpen()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Book Details", color = Color.White) },
                    onClick = { menuExpanded = false }
                )
                DropdownMenuItem(
                    text = { Text("Mark as Completed", color = Color.White) },
                    onClick = {
                        GraphiteRepository.updateBookProgress(book.id, 100)
                        menuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Remove from Recents", color = Color(0xFFFF8A80)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = Color(0xFFFF8A80),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        GraphiteRepository.removeBookFromRecents(book.id)
                        menuExpanded = false
                        android.widget.Toast.makeText(context, "Removed from Recents", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}
