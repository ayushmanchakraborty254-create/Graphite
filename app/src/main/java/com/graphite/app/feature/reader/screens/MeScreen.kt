package com.graphite.app.feature.reader.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.auth.GoogleBlue
import com.graphite.app.core.auth.GoogleGreen
import com.graphite.app.core.auth.GoogleProfileTopBarIcon
import com.graphite.app.core.drive.DriveAuthStatus
import com.graphite.app.core.supabase.SupabaseAuthState
import com.graphite.app.core.supabase.SupabaseSyncStatus
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteOutline
import com.graphite.app.core.theme.GraphiteSurfaceVariant
import com.graphite.app.data.repository.GraphiteRepository
import kotlinx.coroutines.launch

@Composable
fun MeScreen(
    onNavigateToNoteshelf: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stats by GraphiteRepository.stats.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val googleAccountManager = remember { GraphiteApplication.instance.googleAccountManager }
    val googleUser by googleAccountManager.currentUser.collectAsState()
    val authManager = remember { GraphiteApplication.instance.supabaseAuthManager }
    val syncManager = remember { GraphiteApplication.instance.supabaseSyncManager }
    val driveManager = remember { GraphiteApplication.instance.googleDriveManager }

    val authState by authManager.authState.collectAsState()
    val syncStatus by syncManager.syncStatus.collectAsState()
    val driveAuthStatus by driveManager.authStatus.collectAsState()

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        googleAccountManager.handleSignInResult(result.data)
    }

    var showAboutDialog by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showSyncResultDialog by remember { mutableStateOf(false) }
    var isSyncingBoth by remember { mutableStateOf(false) }
    var syncResultMessage by remember { mutableStateOf<String?>(null) }

    val driveAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val authResult = driveManager.handleAuthorizationResult(result.data)
        if (authResult.isSuccess) {
            isSyncingBoth = true
            scope.launch {
                val driveResult = driveManager.syncNotesWithDrive(context)
                val supabaseResult = syncManager.syncNotes()
                isSyncingBoth = false

                val msg = buildString {
                    if (driveResult.isSuccess) {
                        appendLine("✓ Google Drive: Successfully backed up & synced ${driveResult.getOrNull()} notes.")
                    } else {
                        appendLine("• Google Drive: ${driveResult.exceptionOrNull()?.message ?: "Check connection"}")
                    }
                    if (supabaseResult.isSuccess) {
                        appendLine("✓ Supabase: Synced ${supabaseResult.getOrNull()} notes.")
                    }
                }
                syncResultMessage = msg.trim()
                showSyncResultDialog = true
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(GraphiteBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // Top Header with "Me" title and Google profile icon on the top right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Me",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif
            )
            GoogleProfileTopBarIcon()
        }

        // Top 2-Column Grid Cards matching Page 3
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Notes Card
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(115.dp)
                    .clickable(onClick = onNavigateToNoteshelf),
                colors = CardDefaults.cardColors(containerColor = GraphiteSurfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = "Notes",
                        tint = GraphiteOrange,
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "Notes",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${stats.notesCount} notes",
                            color = Color(0xFF9E9E9E),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Reading Stats Card
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(115.dp),
                colors = CardDefaults.cardColors(containerColor = GraphiteSurfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = "Reading Stats",
                        tint = Color(0xFF42A5F5),
                        modifier = Modifier.size(28.dp)
                    )
                    Column {
                        Text(
                            text = "Reading Stats",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${stats.totalHoursRead} hr",
                            color = Color(0xFF9E9E9E),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Section: Cloud Sync & Accounts
        Text(
            text = "Cloud Sync & Accounts",
            color = Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp),
            colors = CardDefaults.cardColors(containerColor = GraphiteSurfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Google Account Section (Row Level Security protected note sync)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "G",
                                    color = GoogleBlue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Google Account",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = if (googleUser != null) {
                                "Connected: ${googleUser!!.displayName ?: googleUser!!.email}"
                            } else {
                                "Sign in to sync your notes across devices"
                            },
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    if (googleUser != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    if (!driveManager.hasDrivePermission(context)) {
                                        val intent = driveManager.createAuthorizationIntent(context)
                                        driveAuthLauncher.launch(intent)
                                    } else {
                                        isSyncingBoth = true
                                        scope.launch {
                                            val driveResult = driveManager.syncNotesWithDrive(context)
                                            val supabaseResult = syncManager.syncNotes()
                                            isSyncingBoth = false

                                            val msg = buildString {
                                                if (driveResult.isSuccess) {
                                                    appendLine("✓ Google Drive: Successfully backed up & synced ${driveResult.getOrNull()} notes.")
                                                } else {
                                                    appendLine("• Google Drive: ${driveResult.exceptionOrNull()?.message ?: "Check connection"}")
                                                }
                                                if (supabaseResult.isSuccess) {
                                                    appendLine("✓ Supabase: Synced ${supabaseResult.getOrNull()} notes.")
                                                }
                                            }
                                            syncResultMessage = msg.trim()
                                            showSyncResultDialog = true
                                        }
                                    }
                                },
                                enabled = !isSyncingBoth,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFF00838F),
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                if (isSyncingBoth) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(if (isSyncingBoth) "Syncing..." else "Sync Notes", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { googleAccountManager.signOut(context) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text("Sign Out", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                val intent = googleAccountManager.getSignInIntent(context)
                                googleSignInLauncher.launch(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GoogleBlue),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Sign in with Google", fontSize = 12.sp, color = Color.White)
                        }
                    }
                }

                HorizontalDivider(color = GraphiteOutline, thickness = 0.5.dp)

                // 2. Google Drive Section (Manual Upload Isolation)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = GraphiteOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Google Drive",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = when (val drive = driveAuthStatus) {
                                is DriveAuthStatus.Authorized -> "Connected: ${drive.accountEmail}"
                                else -> "Sign in when exporting or uploading notes"
                            },
                            color = Color.Gray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    if (driveAuthStatus is DriveAuthStatus.Authorized) {
                        TextButton(
                            onClick = { driveManager.disconnect() }
                        ) {
                            Text("Disconnect", color = Color(0xFFFF5252), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Section: Settings / Profile Section matching Page 3
        Text(
            text = "General",
            color = Color.Gray,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = GraphiteSurfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingItemRow(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = "Feedback",
                    onClick = { showFeedbackDialog = true }
                )
                HorizontalDivider(color = GraphiteOutline, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                SettingItemRow(
                    icon = Icons.Default.Info,
                    title = "About Graphite",
                    onClick = { showAboutDialog = true }
                )
                HorizontalDivider(color = GraphiteOutline, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                SettingItemRow(
                    icon = Icons.Default.Settings,
                    title = "Setting",
                    onClick = { showSettingsDialog = true }
                )
            }
        }
    }



    // Dialog: Supabase Sync Result
    if (showSyncResultDialog) {
        AlertDialog(
            onDismissRequest = { showSyncResultDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudSync, contentDescription = null, tint = Color(0xFF00BCD4))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sync Notes", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                when (val status = syncStatus) {
                    is SupabaseSyncStatus.Syncing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Color(0xFF00BCD4), modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Synchronizing notes...", color = Color.LightGray)
                        }
                    }
                    is SupabaseSyncStatus.Success -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Successfully synced ${status.syncedCount} notes!",
                                color = Color(0xFF81C784),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Your notes are backed up and up to date across all devices.",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                        }
                    }
                    is SupabaseSyncStatus.Error -> {
                        Text(text = "Sync Failed: ${status.message}", color = Color(0xFFFF5252))
                    }
                    SupabaseSyncStatus.Unauthenticated -> {
                        Text(text = "Please sign in to sync your notes across devices.", color = Color(0xFFFF5252))
                    }
                    SupabaseSyncStatus.Idle -> {
                        Text(text = "Ready to sync notes.", color = Color.LightGray)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSyncResultDialog = false }) {
                    Text("OK", color = GraphiteOrange)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }

    // Dialogs
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About Graphite", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Graphite v1.0.0\nNative Android E-Book Reader & Handwritten Notes Application.\n\nOptimized for Motorola Moto Pad 60 Pro & Nothing Phone.",
                    color = Color.LightGray
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("OK", color = GraphiteOrange)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }

    if (showFeedbackDialog) {
        AlertDialog(
            onDismissRequest = { showFeedbackDialog = false },
            title = { Text("Feedback & Diagnostics", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Graphite uses native Android ink hardware APIs. Submit feedback or stylus logs for hardware calibration.",
                    color = Color.LightGray
                )
            },
            confirmButton = {
                TextButton(onClick = { showFeedbackDialog = false }) {
                    Text("Send", color = GraphiteOrange)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Application Settings", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Theme: Pure OLED Black", color = Color.LightGray)
                    Text("• Reading Font: Serif / System Sans-Serif", color = Color.LightGray)
                    Text("• Cloud Sync: Automatic across signed-in devices", color = Color.LightGray)
                    Text("• Google Drive: Export and backup notes on demand", color = Color.LightGray)
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Done", color = GraphiteOrange)
                }
            },
            containerColor = GraphiteSurfaceVariant
        )
    }

    if (showSyncResultDialog) {
        AlertDialog(
            onDismissRequest = { showSyncResultDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = GraphiteOrange)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cross-Device Sync", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = syncResultMessage ?: "Notes successfully synchronized with your Google Account.",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Your notes are safely preserved. Whenever you reinstall Graphite or sign in on another device, all your synced notes will restore automatically.",
                        color = Color(0xFFAAAAAA),
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSyncResultDialog = false }) {
                    Text("OK", color = GraphiteOrange)
                }
            },
            containerColor = GraphiteSurfaceVariant,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
private fun SettingItemRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFF757575),
            modifier = Modifier.size(20.dp)
        )
    }
}
