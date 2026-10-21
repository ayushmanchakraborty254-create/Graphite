package com.graphite.app.core.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.graphite.app.core.config.GraphiteConfig
import com.graphite.app.core.security.SecureStorage
import com.graphite.app.core.supabase.SupabaseAuthManager
import com.graphite.app.core.supabase.SupabaseSession
import com.graphite.app.core.supabase.SupabaseUser
import com.graphite.app.data.repository.GraphiteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GoogleProfile(
    val email: String,
    val displayName: String,
    val photoUrl: String? = null,
    val idToken: String? = null
)

class GoogleAccountManager(
    private val secureStorage: SecureStorage,
    private val supabaseAuthManager: SupabaseAuthManager? = null,
    private val graphiteRepository: GraphiteRepository = GraphiteRepository,
    private val webClientId: String = GraphiteConfig.googleWebClientId
) {

    private val _currentUser = MutableStateFlow<GoogleProfile?>(null)
    val currentUser: StateFlow<GoogleProfile?> = _currentUser.asStateFlow()

    private val _isGuestMode = MutableStateFlow(false)
    val isGuestMode: StateFlow<Boolean> = _isGuestMode.asStateFlow()

    init {
        restoreFromStorage()
    }

    fun hasActiveSession(): Boolean = _currentUser.value != null || _isGuestMode.value

    fun enableGuestMode() {
        secureStorage.putBoolean("is_guest_mode", true)
        _isGuestMode.value = true
        _currentUser.value = null
        graphiteRepository.setActiveUser("guest")
    }

    fun disableGuestMode() {
        secureStorage.putBoolean("is_guest_mode", false)
        _isGuestMode.value = false
        graphiteRepository.setActiveUser(null)
    }

    private fun restoreFromStorage() {
        val email = secureStorage.getString(KEY_GOOGLE_EMAIL)?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: secureStorage.getString("google_account_email")?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: secureStorage.getString("google_drive_authorized_account_email")?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
        val name = secureStorage.getString(KEY_GOOGLE_NAME)
        val photoUrl = secureStorage.getString(KEY_GOOGLE_PHOTO)
        val idToken = secureStorage.getString(KEY_GOOGLE_ID_TOKEN)
        val isGuestSaved = secureStorage.getBoolean("is_guest_mode", false)

        if (!email.isNullOrBlank()) {
            val displayName = if (!name.isNullOrBlank()) name else email.substringBefore("@")
            _currentUser.value = GoogleProfile(
                email = email,
                displayName = displayName,
                photoUrl = photoUrl,
                idToken = idToken
            )
            _isGuestMode.value = false
            graphiteRepository.setActiveUser(email)
            try {
                com.graphite.app.GraphiteApplication.instance.googleDriveManager.saveAuthorizedAccount(email)
            } catch (_: Exception) {}

            val deterministicUuid = java.util.UUID.nameUUIDFromBytes(email.toByteArray()).toString()
            supabaseAuthManager?.setSession(
                SupabaseSession(
                    accessToken = idToken ?: GraphiteConfig.supabasePublishableKey,
                    user = SupabaseUser(id = deterministicUuid, email = email)
                )
            )
        } else if (isGuestSaved) {
            _currentUser.value = null
            _isGuestMode.value = true
            graphiteRepository.setActiveUser("guest")
        } else {
            _currentUser.value = null
            _isGuestMode.value = false
            graphiteRepository.setActiveUser(null)
        }
    }

    fun getGoogleSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .requestProfile()
            .requestScopes(com.google.android.gms.common.api.Scope(GraphiteConfig.DRIVE_SCOPE_FILE))
            .build()
    }

    fun getSignInIntent(context: Context): Intent {
        val client = GoogleSignIn.getClient(context, getGoogleSignInOptions())
        return client.signInIntent
    }

    fun launchSwitchAccount(context: Context, launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
        val client = GoogleSignIn.getClient(context, getGoogleSignInOptions())
        client.signOut().addOnCompleteListener {
            val intent = client.signInIntent
            launcher.launch(intent)
        }
    }

    fun handleSignInResult(data: Intent?): Result<GoogleProfile> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)

            val rawEmail = (account.account?.name ?: account.email)?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            val email = rawEmail ?: "user@gmail.com"
            val name = account.displayName ?: email.substringBefore("@")
            val photoUrl = account.photoUrl?.toString()
            val idToken = account.idToken

            val profile = GoogleProfile(
                email = email,
                displayName = name,
                photoUrl = photoUrl,
                idToken = idToken
            )

            // Persist to SecureStorage
            secureStorage.putString(KEY_GOOGLE_EMAIL, email)
            secureStorage.putString("google_account_email", email)
            secureStorage.putString(KEY_GOOGLE_NAME, name)
            secureStorage.putString(KEY_GOOGLE_PHOTO, photoUrl)
            secureStorage.putString(KEY_GOOGLE_ID_TOKEN, idToken)
            secureStorage.putString("google_drive_authorized_account_email", email)
            secureStorage.putBoolean("is_guest_mode", false)

            _currentUser.value = profile
            _isGuestMode.value = false
            graphiteRepository.setActiveUser(email)
            try {
                com.graphite.app.GraphiteApplication.instance.googleDriveManager.saveAuthorizedAccount(email)
            } catch (_: Exception) {}

            // Automatically bridge to Supabase Auth under the hood so RLS is authenticated
            if (supabaseAuthManager != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    if (!idToken.isNullOrBlank()) {
                        try {
                            val authResult = supabaseAuthManager.signInWithGoogleIdToken(idToken)
                            if (authResult.isSuccess) {
                                val session = authResult.getOrThrow()
                                if (photoUrl.isNullOrBlank() && !session.user.avatarUrl.isNullOrBlank()) {
                                    val updatedProfile = profile.copy(photoUrl = session.user.avatarUrl)
                                    secureStorage.putString(KEY_GOOGLE_PHOTO, session.user.avatarUrl)
                                    _currentUser.value = updatedProfile
                                }
                                return@launch
                            }
                        } catch (_: Exception) {}
                    }

                    // Fallback to deterministic UUID based on Google email for valid UUID foreign keys
                    val deterministicUuid = java.util.UUID.nameUUIDFromBytes(email.toByteArray()).toString()
                    supabaseAuthManager.setSession(
                        SupabaseSession(
                            accessToken = GraphiteConfig.supabasePublishableKey,
                            user = SupabaseUser(id = deterministicUuid, email = email)
                        )
                    )
                }
            }

            // Restore any notes previously backed up to Google Drive
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    com.graphite.app.GraphiteApplication.instance.googleDriveManager.restoreNotesFromDrive(com.graphite.app.GraphiteApplication.instance)
                } catch (_: Exception) {
                    // Ignore restore errors on sign in
                }
            }

            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun setProfileDirectly(profile: GoogleProfile) {
        secureStorage.putString(KEY_GOOGLE_EMAIL, profile.email)
        secureStorage.putString("google_account_email", profile.email)
        secureStorage.putString(KEY_GOOGLE_NAME, profile.displayName)
        secureStorage.putString(KEY_GOOGLE_PHOTO, profile.photoUrl)
        secureStorage.putString(KEY_GOOGLE_ID_TOKEN, profile.idToken)
        secureStorage.putString("google_drive_authorized_account_email", profile.email)
        secureStorage.putBoolean("is_guest_mode", false)
        _currentUser.value = profile
        _isGuestMode.value = false

        val userUid = profile.email
        graphiteRepository.setActiveUser(userUid)
        try {
            com.graphite.app.GraphiteApplication.instance.googleDriveManager.saveAuthorizedAccount(profile.email)
        } catch (_: Exception) {}

        supabaseAuthManager?.setSession(
            SupabaseSession(
                accessToken = profile.idToken ?: "google_token",
                user = SupabaseUser(id = userUid, email = profile.email)
            )
        )
    }

    fun signOut(context: Context, onComplete: () -> Unit = {}) {
        val client = GoogleSignIn.getClient(context, getGoogleSignInOptions())
        client.signOut().addOnCompleteListener {
            clearLocalSession()
            onComplete()
        }
    }

    fun clearLocalSession() {
        secureStorage.remove(KEY_GOOGLE_EMAIL)
        secureStorage.remove("google_account_email")
        secureStorage.remove("google_drive_authorized_account_email")
        secureStorage.remove(KEY_GOOGLE_NAME)
        secureStorage.remove(KEY_GOOGLE_PHOTO)
        secureStorage.remove(KEY_GOOGLE_ID_TOKEN)
        secureStorage.putBoolean("is_guest_mode", false)
        _currentUser.value = null
        _isGuestMode.value = false
        supabaseAuthManager?.clearSession()
        graphiteRepository.setActiveUser(null)
    }

    companion object {
        private const val KEY_GOOGLE_EMAIL = "google_user_email"
        private const val KEY_GOOGLE_NAME = "google_user_display_name"
        private const val KEY_GOOGLE_PHOTO = "google_user_photo_url"
        private const val KEY_GOOGLE_ID_TOKEN = "google_user_id_token"
    }
}
