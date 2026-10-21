package com.graphite.app.core.auth

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.graphite.app.GraphiteApplication
import com.graphite.app.core.theme.GraphiteSurfaceVariant

val GoogleBlue = Color(0xFF4285F4)
val GoogleRed = Color(0xFFEA4335)
val GoogleYellow = Color(0xFFFBBC05)
val GoogleGreen = Color(0xFF34A853)

val GoogleGradient = Brush.sweepGradient(
    listOf(GoogleBlue, GoogleRed, GoogleYellow, GoogleGreen, GoogleBlue)
)

/**
 * Top right Google Profile Icon.
 * Self-contained: launches Google Sign-In pop-up on click if signed out,
 * and displays the Google Account profile pop-up on click if signed in.
 */
@Composable
fun GoogleProfileTopBarIcon(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val googleAccountManager = remember { GraphiteApplication.instance.googleAccountManager }
    val googleUser by googleAccountManager.currentUser.collectAsState()
    var showProfileDialog by remember { mutableStateOf(false) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        googleAccountManager.handleSignInResult(result.data)
    }

    if (googleUser != null) {
        val user = googleUser!!
        val initial = user.displayName.firstOrNull()?.uppercaseChar()?.toString()
            ?: user.email.firstOrNull()?.uppercaseChar()?.toString()
            ?: "G"

        // Signed in with Google: Avatar with Google multi-color ring & G badge
        Box(
            modifier = modifier
                .size(34.dp)
                .clip(CircleShape)
                .border(2.dp, GoogleGradient, CircleShape)
                .background(Color(0xFF222222))
                .clickable { showProfileDialog = true },
            contentAlignment = Alignment.Center
        ) {
            if (!user.photoUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = user.photoUrl,
                    contentDescription = user.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    loading = {
                        Text(
                            text = initial,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    error = {
                        Text(
                            text = initial,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            } else {
                Text(
                    text = initial,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Subtle Google "G" badge at bottom right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "G",
                    color = GoogleBlue,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    } else {
        // Not signed in: Sleek Google Sign-In button
        Surface(
            modifier = modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    val intent = googleAccountManager.getSignInIntent(context)
                    googleSignInLauncher.launch(intent)
                },
            color = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF383838))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "G",
                        color = GoogleBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Text(
                    text = "Sign in",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    if (showProfileDialog && googleUser != null) {
        GoogleAccountProfileDialog(
            user = googleUser!!,
            onDismiss = { showProfileDialog = false },
            onSignOut = {
                googleAccountManager.signOut(context)
                showProfileDialog = false
            },
            onSwitchAccount = {
                showProfileDialog = false
                googleAccountManager.launchSwitchAccount(context, googleSignInLauncher)
            }
        )
    }
}

/**
 * Google Account Profile pop-up modal dialog.
 * Contains EXACTLY two account-management options:
 * 1. Switch account
 * 2. Sign out of Google
 * Free of unwanted technical or developer status messages.
 */
@Composable
fun GoogleAccountProfileDialog(
    user: GoogleProfile,
    onDismiss: () -> Unit,
    onSignOut: () -> Unit,
    onSwitchAccount: () -> Unit
) {
    val initial = user.displayName.firstOrNull()?.uppercaseChar()?.toString()
        ?: user.email.firstOrNull()?.uppercaseChar()?.toString()
        ?: "G"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Google Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "Google",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Account",
                        fontSize = 18.sp,
                        color = Color.LightGray
                    )
                }

                // Profile Avatar with Google colored ring & image loading
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(3.dp, GoogleGradient, CircleShape)
                        .background(Color(0xFF262626)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!user.photoUrl.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model = user.photoUrl,
                            contentDescription = user.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                            loading = {
                                Text(
                                    text = initial,
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            error = {
                                Text(
                                    text = initial,
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                    } else {
                        Text(
                            text = initial,
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Name and Email
                Text(
                    text = user.displayName,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = user.email,
                    color = Color.Gray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                // EXACTLY TWO account-management options
                // Option 1: Switch Account
                OutlinedButton(
                    onClick = onSwitchAccount,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwitchAccount,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Switch account", fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Option 2: Sign Out of Google
                Button(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2E2E))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign out of Google", color = Color(0xFFFF5252), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.Gray)
            }
        },
        containerColor = GraphiteSurfaceVariant,
        shape = RoundedCornerShape(16.dp)
    )
}
