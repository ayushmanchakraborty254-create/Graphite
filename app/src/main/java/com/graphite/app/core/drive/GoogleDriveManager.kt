package com.graphite.app.core.drive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.graphite.app.core.config.GraphiteConfig
import com.graphite.app.core.security.SecureStorage
import com.graphite.app.core.supabase.InkSerialization
import com.graphite.app.data.model.NotebookItem
import com.graphite.app.data.repository.GraphiteRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

import com.graphite.app.data.model.Book
import com.graphite.app.data.model.BookCoverType
import com.graphite.app.core.importer.DocumentContentParser
import java.io.File

private object SafeLogger {
    fun d(tag: String, message: String) {
        try {
            android.util.Log.d(tag, message)
        } catch (_: Throwable) {
            println("[$tag] DEBUG: $message")
        }
    }
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        try {
            android.util.Log.e(tag, message, throwable)
        } catch (_: Throwable) {
            println("[$tag] ERROR: $message ${throwable?.message ?: ""}")
        }
    }
}

@Serializable
data class ExportedNotebookPayload(
    val id: String,
    val title: String,
    val folderId: String,
    val paperStyle: String,
    val previewLines: List<String>,
    val strokesJson: String,
    val exportedAtMillis: Long = System.currentTimeMillis(),
    val appVersion: String = "1.0.0"
)

data class DriveRemoteFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long = 0L
)

data class BookshelfSyncReport(
    val isSuccess: Boolean,
    val folderId: String,
    val folderName: String,
    val downloadedCount: Int,
    val uploadedCount: Int,
    val message: String
)

/**
 * Pluggable Drive API v3 upload delegate to facilitate testing on JVM
 * while using real Google Drive API v3 on Android devices.
 */
interface DriveApiV3Uploader {
    suspend fun upload(
        accountEmail: String,
        fileName: String,
        mimeType: String,
        contentBytes: ByteArray,
        folderId: String? = null
    ): DriveUploadResult

    suspend fun listFolders(
        accountEmail: String
    ): Result<List<DriveFolder>> = listSubfolders(accountEmail, "root")

    suspend fun listSubfolders(
        accountEmail: String,
        parentFolderId: String = "root"
    ): Result<List<DriveFolder>> = Result.success(emptyList())

    suspend fun createFolder(
        accountEmail: String,
        folderName: String,
        parentFolderId: String? = null
    ): Result<DriveFolder> = Result.success(DriveFolder("folder_${System.currentTimeMillis()}", folderName))

    suspend fun getFolder(
        accountEmail: String,
        folderId: String
    ): Result<DriveFolder> = Result.success(
        DriveFolder(folderId, if (folderId == "root" || folderId.isBlank()) "My Drive (Root)" else "Selected Drive Folder")
    )

    suspend fun listFiles(
        accountEmail: String,
        folderId: String
    ): Result<List<DriveRemoteFile>> = Result.success(emptyList())

    suspend fun downloadFile(
        accountEmail: String,
        fileId: String
    ): Result<ByteArray> = Result.success(ByteArray(0))

    suspend fun updateFile(
        accountEmail: String,
        fileId: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<Unit> = Result.success(Unit)

    suspend fun findFileByName(
        accountEmail: String,
        fileName: String,
        parentFolderId: String? = null
    ): Result<DriveRemoteFile?> = Result.success(null)

    suspend fun deleteFile(
        accountEmail: String,
        fileId: String
    ): Result<Unit> = Result.success(Unit)
}

class DefaultDriveApiV3Uploader(
    private val context: Context,
    private val secureStorage: SecureStorage? = null
) : DriveApiV3Uploader {

    private fun getDriveService(accountEmail: String): Drive {
        // Strict minimum scope: https://www.googleapis.com/auth/drive.file
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(GraphiteConfig.DRIVE_SCOPE_FILE)
        )
        val signInAccount = GoogleSignIn.getLastSignedInAccount(context)
        val storage = secureStorage ?: try { com.graphite.app.GraphiteApplication.instance.secureStorage } catch (_: Exception) { null }

        val resolvedEmail = if (accountEmail.isNotBlank() && accountEmail != "authorized_user" && accountEmail != "null") {
            accountEmail
        } else {
            signInAccount?.account?.name?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
                ?: signInAccount?.email?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
                ?: storage?.getString("google_user_email")?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
                ?: storage?.getString("google_account_email")?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
                ?: storage?.getString(GoogleDriveManager.KEY_DRIVE_ACCOUNT_EMAIL)?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
                ?: try { com.graphite.app.GraphiteApplication.instance.googleAccountManager.currentUser.value?.email?.takeIf { it.isNotBlank() && it != "null" } } catch (_: Exception) { null }
                ?: ""
        }

        if (resolvedEmail.isBlank() || resolvedEmail == "authorized_user" || resolvedEmail == "null") {
            throw IllegalStateException("Google account authorization required. Please sign in with your Google account in Graphite.")
        }

        // Auto-heal storage so future operations have the genuine email
        storage?.putString(GoogleDriveManager.KEY_DRIVE_ACCOUNT_EMAIL, resolvedEmail)
        storage?.putString("google_user_email", resolvedEmail)
        storage?.putString("google_account_email", resolvedEmail)

        val googleAccount = signInAccount?.account?.takeIf { !it.name.isNullOrBlank() }
            ?: Account(resolvedEmail, "com.google")

        credential.selectedAccount = googleAccount
        credential.selectedAccountName = resolvedEmail

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Graphite").build()
    }

    override suspend fun upload(
        accountEmail: String,
        fileName: String,
        mimeType: String,
        contentBytes: ByteArray,
        folderId: String?
    ): DriveUploadResult = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(accountEmail)

            val fileMetadata = DriveFile().apply {
                name = fileName
                description = "Uploaded manually from Graphite Android"
                if (!folderId.isNullOrBlank() && folderId != "root") {
                    parents = listOf(folderId)
                }
            }

            val mediaContent = ByteArrayContent(mimeType, contentBytes)

            val createdFile = driveService.files()
                .create(fileMetadata, mediaContent)
                .setSupportsAllDrives(true)
                .setFields("id, name, webViewLink, parents")
                .execute()

            DriveUploadResult.Success(
                fileId = createdFile.id ?: "unknown_id",
                fileName = createdFile.name ?: fileName,
                folderId = folderId,
                folderName = if (folderId == "root" || folderId.isNullOrBlank()) "My Drive (Root)" else null,
                webViewLink = createdFile.webViewLink
            )
        } catch (e: Exception) {
            DriveUploadResult.Error(
                message = e.message ?: "Failed to upload file to Google Drive via Drive API v3",
                requiresReauth = e.message?.contains("401") == true || e.message?.contains("Auth") == true
            )
        }
    }

    override suspend fun listFolders(accountEmail: String): Result<List<DriveFolder>> =
        listSubfolders(accountEmail, "root")

    override suspend fun listSubfolders(
        accountEmail: String,
        parentFolderId: String
    ): Result<List<DriveFolder>> = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(accountEmail)
            val parentQuery = if (parentFolderId == "root" || parentFolderId.isBlank()) {
                "'root' in parents"
            } else {
                "'$parentFolderId' in parents"
            }
            val result = driveService.files().list()
                .setQ("$parentQuery and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setFields("files(id, name, mimeType)")
                .setPageSize(50)
                .execute()

            val folders = result.files?.map { DriveFolder(it.id, it.name) } ?: emptyList()
            Result.success(folders)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createFolder(
        accountEmail: String,
        folderName: String,
        parentFolderId: String?
    ): Result<DriveFolder> = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(accountEmail)
            val folderMetadata = DriveFile().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
                if (!parentFolderId.isNullOrBlank() && parentFolderId != "root") {
                    parents = listOf(parentFolderId)
                }
            }

            val created = driveService.files()
                .create(folderMetadata)
                .setSupportsAllDrives(true)
                .setFields("id, name")
                .execute()

            Result.success(DriveFolder(created.id, created.name))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getFolder(
        accountEmail: String,
        folderId: String
    ): Result<DriveFolder> = withContext(Dispatchers.IO) {
        try {
            if (folderId == "root" || folderId.isBlank()) {
                return@withContext Result.success(DriveFolder.ROOT)
            }
            val driveService = getDriveService(accountEmail)
            val file = driveService.files().get(folderId)
                .setSupportsAllDrives(true)
                .setFields("id, name, mimeType")
                .execute()
            if (file.mimeType != "application/vnd.google-apps.folder") {
                return@withContext Result.failure(
                    IllegalArgumentException("Selected item '${file.name}' is a file (${file.mimeType}), not a folder. Please choose a folder as the upload destination.")
                )
            }
            Result.success(DriveFolder(id = file.id ?: folderId, name = file.name ?: "Selected Drive Folder"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun listFiles(
        accountEmail: String,
        folderId: String
    ): Result<List<DriveRemoteFile>> = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(accountEmail)
            val parentQuery = if (folderId == "root" || folderId.isBlank()) {
                "'root' in parents"
            } else {
                "'$folderId' in parents"
            }
            val result = driveService.files().list()
                .setQ("$parentQuery and mimeType != 'application/vnd.google-apps.folder' and trashed = false")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setFields("files(id, name, mimeType, size)")
                .setPageSize(100)
                .execute()

            val files = result.files?.map {
                DriveRemoteFile(
                    id = it.id ?: "",
                    name = it.name ?: "Unnamed",
                    mimeType = it.mimeType ?: "application/octet-stream",
                    size = it.getSize() ?: 0L
                )
            } ?: emptyList()
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadFile(
        accountEmail: String,
        fileId: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(accountEmail)
            val inputStream = driveService.files().get(fileId)
                .setSupportsAllDrives(true)
                .executeMediaAsInputStream()
            val bytes = inputStream.use { it.readBytes() }
            Result.success(bytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateFile(
        accountEmail: String,
        fileId: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(accountEmail)
            val mediaContent = ByteArrayContent(mimeType, bytes)
            driveService.files().update(fileId, null, mediaContent)
                .setSupportsAllDrives(true)
                .execute()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun findFileByName(
        accountEmail: String,
        fileName: String,
        parentFolderId: String?
    ): Result<DriveRemoteFile?> = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(accountEmail)
            var q = "name = '$fileName' and trashed = false"
            if (!parentFolderId.isNullOrBlank()) {
                q += " and '$parentFolderId' in parents"
            }
            val result = driveService.files().list()
                .setQ(q)
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setFields("files(id, name, mimeType, size)")
                .setPageSize(1)
                .execute()
            val first = result.files?.firstOrNull()
            if (first != null) {
                Result.success(DriveRemoteFile(first.id ?: "", first.name ?: fileName, first.mimeType ?: "", first.getSize() ?: 0L))
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteFile(
        accountEmail: String,
        fileId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(accountEmail)
            driveService.files().delete(fileId).setSupportsAllDrives(true).execute()
            Result.success(Unit)
        } catch (e: Exception) {
            SafeLogger.e("DefaultDriveApiV3Uploader", "Failed to delete file from Google Drive: ${e.message}", e)
            Result.failure(e)
        }
    }
}

/**
 * Manages manual Google Drive uploads using Google Drive API v3 and Android OAuth authorization flow.
 *
 * CRITICAL ARCHITECTURAL CONSTRAINTS:
 * 1. Scope is strictly restricted to https://www.googleapis.com/auth/drive.file (minimum required scope).
 * 2. Authorization is requested ONLY when the user explicitly taps "Upload to Google Drive".
 * 3. User selects the destination folder and explicitly confirms filename and folder before uploading.
 * 4. NEVER uploads notes automatically during creation, editing, export, or Supabase synchronization.
 * 5. Contains NO client secrets; tokens and account state are stored securely via SecureStorage.
 */
class GoogleDriveManager(
    private val secureStorage: SecureStorage,
    private val uploaderProvider: (Context) -> DriveApiV3Uploader = { ctx -> DefaultDriveApiV3Uploader(ctx, secureStorage) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val json = Json { prettyPrint = true }

    private val _authStatus = MutableStateFlow<DriveAuthStatus>(DriveAuthStatus.NotAuthorized)
    val authStatus: StateFlow<DriveAuthStatus> = _authStatus.asStateFlow()

    private val _uploadState = MutableStateFlow<DriveUploadState>(DriveUploadState.Idle)
    val uploadState: StateFlow<DriveUploadState> = _uploadState.asStateFlow()

    private val _customFolders = MutableStateFlow<List<DriveFolder>>(emptyList())
    val customFolders: StateFlow<List<DriveFolder>> = _customFolders.asStateFlow()

    init {
        restoreAuthState()
    }

    private fun restoreAuthState() {
        val savedAccount = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: secureStorage.getString("google_user_email")?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: secureStorage.getString("google_account_email")?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: try { com.graphite.app.GraphiteApplication.instance.googleAccountManager.currentUser.value?.email?.takeIf { it.isNotBlank() && it != "null" } } catch (_: Exception) { null }
        if (!savedAccount.isNullOrBlank()) {
            saveAuthorizedAccount(savedAccount)
        }
    }

    /**
     * Minimum scope for Google Drive: only files created or opened by Graphite.
     */
    val driveScope: Scope = Scope(GraphiteConfig.DRIVE_SCOPE_FILE)

    /**
     * Builds GoogleSignInOptions for the Android OAuth authorization flow.
     * Note: Client secrets are NEVER used or bundled.
     */
    fun getGoogleSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(driveScope)
            .build()
    }

    /**
     * Checks whether the user has already authorized Drive access on this device.
     */
    fun hasDrivePermission(context: Context): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        val hasScope = account != null && GoogleSignIn.hasPermissions(account, driveScope)
        val resolvedEmail = (account?.account?.name ?: account?.email)?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: secureStorage.getString("google_user_email")?.takeIf { it.isNotBlank() && it != "null" }
            ?: secureStorage.getString("google_account_email")?.takeIf { it.isNotBlank() && it != "null" }
            ?: try { com.graphite.app.GraphiteApplication.instance.googleAccountManager.currentUser.value?.email?.takeIf { it.isNotBlank() && it != "null" } } catch (_: Exception) { null }

        if (!resolvedEmail.isNullOrBlank()) {
            if (_authStatus.value !is DriveAuthStatus.Authorized) {
                saveAuthorizedAccount(resolvedEmail)
            }
            return hasScope || account != null
        }
        return false
    }

    /**
     * Generates an intent to request Drive OAuth authorization from the user.
     * Should only be launched when the user explicitly taps "Upload to Google Drive".
     */
    fun createAuthorizationIntent(context: Context): Intent {
        _authStatus.value = DriveAuthStatus.Authorizing
        val client = GoogleSignIn.getClient(context, getGoogleSignInOptions())
        return client.signInIntent
    }

    /**
     * Handles the result of the OAuth authorization intent.
     */
    fun handleAuthorizationResult(data: Intent?): Result<String> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)

            if (!GoogleSignIn.hasPermissions(account, driveScope)) {
                _authStatus.value = DriveAuthStatus.NotAuthorized
                return Result.failure(SecurityException("User did not grant Google Drive permissions."))
            }

            val email = (account.account?.name ?: account.email)?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
                ?: secureStorage.getString("google_user_email")?.takeIf { it.isNotBlank() && it != "null" }
                ?: secureStorage.getString("google_account_email")?.takeIf { it.isNotBlank() && it != "null" }
                ?: try { com.graphite.app.GraphiteApplication.instance.googleAccountManager.currentUser.value?.email?.takeIf { it.isNotBlank() && it != "null" } } catch (_: Exception) { null }
            if (email.isNullOrBlank()) {
                _authStatus.value = DriveAuthStatus.NotAuthorized
                return Result.failure(IllegalStateException("No valid Google account email found."))
            }
            saveAuthorizedAccount(email)
            Result.success(email)
        } catch (e: Exception) {
            _authStatus.value = DriveAuthStatus.NotAuthorized
            Result.failure(e)
        }
    }

    fun saveAuthorizedAccount(email: String) {
        val cleanEmail = email.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" } ?: return
        secureStorage.putString(KEY_DRIVE_ACCOUNT_EMAIL, cleanEmail)
        secureStorage.putString("google_user_email", cleanEmail)
        secureStorage.putString("google_account_email", cleanEmail)
        _authStatus.value = DriveAuthStatus.Authorized(cleanEmail)
    }

    /**
     * Revokes local Drive authorization state.
     */
    fun disconnect() {
        secureStorage.remove(KEY_DRIVE_ACCOUNT_EMAIL)
        secureStorage.remove("google_drive_authorized_account_email")
        _authStatus.value = DriveAuthStatus.NotAuthorized
        _uploadState.value = DriveUploadState.Idle
    }

    /**
     * Step 1: Prepares the note for upload without uploading it yet,
     * loading available Google Drive destination folders and opening the folder picker.
     * Silent fallback to My Drive root is eliminated; selectedFolder is null by default.
     */
    suspend fun startUploadFlow(
        context: Context,
        notebook: NotebookItem,
        preselectedFolder: DriveFolder? = null
    ): Boolean = withContext(ioDispatcher) {
        val accountEmail = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: secureStorage.getString("google_user_email")?.takeIf { it.isNotBlank() && it != "null" }
            ?: secureStorage.getString("google_account_email")?.takeIf { it.isNotBlank() && it != "null" }
            ?: getAuthorizedAccountEmail()
        if (accountEmail.isNullOrBlank()) {
            return@withContext false // Caller should launch authorization intent
        }
        saveAuthorizedAccount(accountEmail)

        // Fetch folders if possible under drive.file, always including Root in available options
        val uploader = uploaderProvider(context)
        val fetchedFolders = uploader.listFolders(accountEmail).getOrDefault(emptyList())
        val combinedFolders = (listOf(DriveFolder.ROOT) + _customFolders.value + fetchedFolders).distinctBy { it.id }

        _uploadState.value = DriveUploadState.SelectingFolder(
            notebook = notebook,
            availableFolders = combinedFolders,
            selectedFolder = preselectedFolder
        )
        true
    }

    /**
     * Returns the currently authorized Google Account email, if any.
     */
    fun getAuthorizedAccountEmail(): String? {
        val saved = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
        if (!saved.isNullOrBlank()) return saved
        val googleUserEmail = secureStorage.getString("google_user_email")?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
        if (!googleUserEmail.isNullOrBlank()) return googleUserEmail
        val googleEmail = secureStorage.getString("google_account_email")?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
        if (!googleEmail.isNullOrBlank()) return googleEmail
        return try {
            com.graphite.app.GraphiteApplication.instance.googleAccountManager.currentUser.value?.email?.takeIf { it.isNotBlank() && it != "null" }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves the Google account email from all possible sources including GoogleSignIn.
     * Caches the resolved email into secure storage for subsequent calls.
     */
    fun getResolvedAccountEmail(context: Context? = null): String? {
        val email = getAuthorizedAccountEmail()
            ?: if (context != null) {
                try {
                    val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
                    lastAccount?.account?.name?.takeIf { it.isNotBlank() && it != "null" }
                        ?: lastAccount?.email?.takeIf { it.isNotBlank() && it != "null" }
                } catch (_: Exception) {
                    null
                }
            } else null
        if (!email.isNullOrBlank()) {
            saveAuthorizedAccount(email)
        }
        return email
    }

    /**
     * Step 2: User chose a destination folder. Show the final confirmation dialog
     * before any upload actually occurs.
     */
    fun onFolderSelected(
        notebook: NotebookItem,
        folder: DriveFolder,
        customFileName: String? = null
    ) {
        val baseName = (customFileName?.takeIf { it.isNotBlank() } ?: notebook.title)
            .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val safeFileName = if (baseName.endsWith(".pdf", ignoreCase = true)) baseName else "$baseName.pdf"
        _uploadState.value = DriveUploadState.AwaitingConfirmation(
            notebook = notebook,
            fileName = safeFileName,
            selectedFolder = folder
        )
    }

    /**
     * Step 3: User confirmed the upload. Uploads the note as a PDF into the selected folder.
     * Prevents duplicate uploads from repeated taps.
     * Explicitly rejects null destination folders.
     * Validates non-root folders against Drive API v3 to ensure only valid folders are targeted.
     */
    suspend fun confirmAndUpload(
        context: Context,
        notebook: NotebookItem,
        folder: DriveFolder?,
        customFileName: String? = null
    ): DriveUploadResult = withContext(ioDispatcher) {
        if (folder == null) {
            val errorMsg = "No destination folder selected. Please choose a destination folder before uploading."
            _uploadState.value = DriveUploadState.Error(
                message = errorMsg,
                retryNotebook = notebook,
                retryFolder = null
            )
            return@withContext DriveUploadResult.Error(errorMsg)
        }

        // Prevent duplicate concurrent uploads
        if (_uploadState.value is DriveUploadState.Uploading) {
            return@withContext DriveUploadResult.Error("Upload already in progress")
        }

        val accountEmail = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)
        if (accountEmail.isNullOrBlank()) {
            val error = DriveUploadResult.Error(
                message = "Google Drive authorization required. Please authorize before uploading.",
                requiresReauth = true
            )
            _uploadState.value = DriveUploadState.Error(error.message, retryNotebook = notebook, retryFolder = folder)
            return@withContext error
        }

        val uploader = uploaderProvider(context)

        // Validate folder MIME type and existence for non-root folders
        val validatedFolder = if (folder.id != "root" && folder.id.isNotBlank()) {
            val validation = uploader.getFolder(accountEmail, folder.id)
            if (validation.isFailure) {
                val errorMsg = validation.exceptionOrNull()?.message
                    ?: "Selected destination '${folder.name}' is invalid or not a folder."
                val error = DriveUploadResult.Error(errorMsg)
                _uploadState.value = DriveUploadState.Error(
                    message = errorMsg,
                    retryNotebook = notebook,
                    retryFolder = null
                )
                return@withContext error
            }
            validation.getOrThrow()
        } else {
            folder
        }

        _uploadState.value = DriveUploadState.Uploading(
            notebookTitle = notebook.title,
            destinationFolder = validatedFolder
        )

        try {
            val rawName = (customFileName?.takeIf { it.isNotBlank() } ?: notebook.title)
                .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val safeFileName = if (rawName.endsWith(".pdf", ignoreCase = true)) rawName else "$rawName.pdf"
            val pdfBytes = NotePdfGenerator.generatePdf(notebook)

            val result = uploader.upload(
                accountEmail = accountEmail,
                fileName = safeFileName,
                mimeType = "application/pdf",
                contentBytes = pdfBytes,
                folderId = validatedFolder.id
            )

            when (result) {
                is DriveUploadResult.Success -> {
                    val resolvedFolderName = result.folderName?.takeIf { it.isNotBlank() }
                        ?: validatedFolder.name.takeIf { it.isNotBlank() && it != "Selected Drive Folder" }
                        ?: folder.name
                    val enrichedSuccess = result.copy(
                        folderId = validatedFolder.id,
                        folderName = resolvedFolderName
                    )
                    _uploadState.value = DriveUploadState.Success(enrichedSuccess)
                    enrichedSuccess
                }
                is DriveUploadResult.Error -> {
                    _uploadState.value = DriveUploadState.Error(
                        message = result.message,
                        retryNotebook = notebook,
                        retryFolder = validatedFolder
                    )
                    result
                }
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Failed to upload note to Google Drive"
            val error = DriveUploadResult.Error(errorMsg)
            _uploadState.value = DriveUploadState.Error(
                message = errorMsg,
                retryNotebook = notebook,
                retryFolder = validatedFolder
            )
            error
        }
    }

    /**
     * Creates a new folder inside Google Drive and registers it as an available destination.
     */
    suspend fun createNewFolder(
        context: Context,
        folderName: String,
        parentFolderId: String? = null
    ): Result<DriveFolder> = withContext(ioDispatcher) {
        val accountEmail = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)
            ?: return@withContext Result.failure(IllegalStateException("Drive not authorized"))

        val uploader = uploaderProvider(context)
        val result = uploader.createFolder(accountEmail, folderName, parentFolderId)
        if (result.isSuccess) {
            val newFolder = result.getOrThrow()
            _customFolders.value = (_customFolders.value + newFolder).distinctBy { it.id }
        }
        result
    }

    /**
     * Builds the official Google Identity Services AuthorizationRequest for Google Drive Picker
     * in accordance with Google's mobile Picker documentation:
     * https://developers.google.com/workspace/drive/picker/guides/desktop-mobile-picker
     *
     * Invariants:
     * 1. Scope is strictly restricted to https://www.googleapis.com/auth/drive.file (minimum required scope).
     * 2. Opt-out of including previously granted scopes via setOptOutIncludingGrantedScopes(true).
     * 3. Prompt is set to AuthorizationRequest.Prompt.CONSENT.
     * 4. Resource parameters include PICKER_OAUTH_TRIGGER and PICKER_ALLOW_FOLDER_SELECTION.
     * 5. Pre-selects the user account when known.
     */
    fun buildPickerAuthorizationRequest(accountEmail: String? = null): AuthorizationRequest {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(driveScope))
            .setOptOutIncludingGrantedScopes(true)
            .setPrompt(AuthorizationRequest.Prompt.CONSENT)
            .addResourceParameter(
                AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER,
                "true"
            )
            .addResourceParameter(
                AuthorizationRequest.ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION,
                "true"
            )

        val email = accountEmail ?: secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)
        if (!email.isNullOrBlank()) {
            builder.setAccount(Account(email, "com.google"))
        }

        return builder.build()
    }

    /**
     * Handles the AuthorizationResult returned by Google Identity AuthorizationClient.
     * Extracts picked file/folder IDs and updates the upload state.
     */
    suspend fun handlePickerAuthorizationResult(
        context: Context,
        result: AuthorizationResult,
        notebook: NotebookItem? = null
    ): Result<DriveFolder> = withContext(ioDispatcher) {
        try {
            SafeLogger.d(
                TAG,
                "Picker authorization completed. Scopes granted: ${result.grantedScopes}, hasResolution: ${result.hasResolution()}"
            )

            val account = result.toGoogleSignInAccount()
            if (account?.email != null) {
                saveAuthorizedAccount(account.email!!)
            }

            val tokenParams = result.tokenResponseParams
            val pickedFileIds = tokenParams?.getString("picked_file_ids")
            val folderId = pickedFileIds?.split(",")?.firstOrNull()?.trim()
                ?: tokenParams?.getString("folder_id")
                ?: tokenParams?.getString("id")

            if (folderId.isNullOrBlank()) {
                SafeLogger.d(TAG, "No folder selected in picker result token parameters")
                val error = IllegalStateException("No folder was selected in Google Drive Picker")
                setUploadError(error.message ?: "No folder selected", notebook)
                return@withContext Result.failure(error)
            }

            val folderName = tokenParams?.getString("folder_name") ?: tokenParams?.getString("name")
            handlePickerSelectedFolder(context, folderId, folderName, notebook)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to handle picker authorization result: ${e.message}", e)
            val targetNotebook = notebook ?: (_uploadState.value as? DriveUploadState.SelectingFolder)?.notebook
                ?: (_uploadState.value as? DriveUploadState.AwaitingConfirmation)?.notebook
            setUploadError("Failed to process Google Drive selection: ${e.message}", targetNotebook)
            Result.failure(e)
        }
    }

    /**
     * Handles Google Drive Picker result from an ActivityResult Intent or URI.
     * Extracts the selected folder ID and name from the PICKED document/action,
     * or delegates to Google Identity AuthorizationResult when available.
     */
    suspend fun handlePickerResult(
        context: Context,
        intent: Intent?,
        notebook: NotebookItem? = null
    ): Result<DriveFolder> = withContext(ioDispatcher) {
        if (intent == null) {
            val error = IllegalArgumentException("No result returned from Google Drive Picker")
            setUploadError(error.message ?: "No result returned", notebook)
            return@withContext Result.failure(error)
        }

        // 0. Extract Google Identity Services AuthorizationResult if available
        try {
            val authClient = Identity.getAuthorizationClient(context)
            val authResult = authClient.getAuthorizationResultFromIntent(intent)
            return@withContext handlePickerAuthorizationResult(context, authResult, notebook)
        } catch (_: Exception) {
            // Not a Google Identity Services intent, continue to document/extras fallback
        }

        // 1. Check for standard Google Picker JSON document or extras
        val documentExtra = intent.getStringExtra("picker_document")
            ?: intent.getStringExtra("document")
            ?: intent.getStringExtra("picker_result")
            ?: intent.getStringExtra("docs")

        if (!documentExtra.isNullOrBlank()) {
            val docResult = parsePickerDocumentPayload(context, documentExtra, notebook)
            if (docResult != null) return@withContext docResult
        }

        // 2. Check for explicit Intent extras or Uri query parameters
        val folderId = intent.getStringExtra("folder_id")
            ?: intent.getStringExtra("picked_file_ids")?.split(",")?.firstOrNull()?.trim()
            ?: intent.getStringExtra("id")
            ?: intent.getStringExtra("doc_id")
            ?: intent.data?.getQueryParameter("folder_id")
            ?: intent.data?.getQueryParameter("picked_file_ids")?.split(",")?.firstOrNull()?.trim()
            ?: intent.data?.getQueryParameter("id")

        val folderName = intent.getStringExtra("folder_name")
            ?: intent.getStringExtra("name")
            ?: intent.data?.getQueryParameter("folder_name")
            ?: intent.data?.getQueryParameter("name")

        if (!folderId.isNullOrBlank()) {
            return@withContext handlePickerSelectedFolder(context, folderId, folderName, notebook)
        }

        val error = IllegalStateException("No folder was selected in Google Drive Picker")
        setUploadError(error.message ?: "No folder selected", notebook)
        Result.failure(error)
    }

    /**
     * Handles Google Picker document payload for PICKED action.
     * Supports standard google.picker.Action.PICKED JSON structure:
     * { "action": "picked", "docs": [ { "id": "...", "name": "...", "mimeType": "..." } ] }
     */
    suspend fun handlePickerDocumentPayload(
        context: Context,
        documentJson: String,
        notebook: NotebookItem? = null
    ): Result<DriveFolder> = withContext(ioDispatcher) {
        val result = parsePickerDocumentPayload(context, documentJson, notebook)
        result ?: Result.failure(IllegalArgumentException("Invalid or missing Google Picker PICKED document payload"))
    }

    private suspend fun parsePickerDocumentPayload(
        context: Context,
        payload: String,
        notebook: NotebookItem?
    ): Result<DriveFolder>? {
        return try {
            val root = json.parseToJsonElement(payload).jsonObject
            val action = root["action"]?.jsonPrimitive?.contentOrNull
                ?: root["ACTION"]?.jsonPrimitive?.contentOrNull

            // If action is specified, it must be "picked"
            if (action != null && !action.equals("picked", ignoreCase = true)) {
                return Result.failure(IllegalStateException("Picker action was '$action', not 'picked'"))
            }

            val docsArray = root["docs"]?.jsonArray ?: root["DOCUMENTS"]?.jsonArray
            val firstDoc = docsArray?.firstOrNull()?.jsonObject ?: root

            val folderId = firstDoc["id"]?.jsonPrimitive?.contentOrNull
                ?: firstDoc["ID"]?.jsonPrimitive?.contentOrNull
                ?: firstDoc["folder_id"]?.jsonPrimitive?.contentOrNull
                ?: return null

            val folderName = firstDoc["name"]?.jsonPrimitive?.contentOrNull
                ?: firstDoc["NAME"]?.jsonPrimitive?.contentOrNull
                ?: "Selected Drive Folder"

            val mimeType = firstDoc["mimeType"]?.jsonPrimitive?.contentOrNull
                ?: firstDoc["MIME_TYPE"]?.jsonPrimitive?.contentOrNull

            if (mimeType != null && mimeType != "application/vnd.google-apps.folder") {
                return Result.failure(
                    IllegalArgumentException("Selected item '$folderName' is a file ($mimeType), not a folder. Please choose a folder as the upload destination.")
                )
            }

            handlePickerSelectedFolder(context, folderId, folderName, notebook)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to parse Google Picker document payload: ${e.message}", e)
            null
        }
    }

    /**
     * Validates and sets the selected folder from Google Drive Picker.
     * Verifies that the selected item is a folder (application/vnd.google-apps.folder).
     * If the selected item is a file, rejects it and reports an explicit error without uploading.
     */
    suspend fun handlePickerSelectedFolder(
        context: Context,
        folderId: String,
        folderName: String? = null,
        notebook: NotebookItem? = null
    ): Result<DriveFolder> = withContext(ioDispatcher) {
        try {
            val targetNotebook = notebook
                ?: (_uploadState.value as? DriveUploadState.SelectingFolder)?.notebook
                ?: (_uploadState.value as? DriveUploadState.AwaitingConfirmation)?.notebook

            val accountEmail = getResolvedAccountEmail(context) ?: ""
            if (accountEmail.isBlank()) {
                val errorMsg = "Google account authorization required"
                if (targetNotebook != null) {
                    setUploadError(errorMsg, targetNotebook)
                }
                return@withContext Result.failure(IllegalStateException(errorMsg))
            }

            val uploader = uploaderProvider(context)
            val folderResult = uploader.getFolder(accountEmail, folderId)
            if (folderResult.isFailure) {
                val errorMsg = folderResult.exceptionOrNull()?.message
                    ?: "Selected item is a file, not a folder. Please choose a destination folder."
                SafeLogger.e(TAG, "Selected Drive item is not a folder: $errorMsg")
                if (targetNotebook != null) {
                    setUploadError(errorMsg, targetNotebook)
                }
                return@withContext Result.failure(folderResult.exceptionOrNull() ?: IllegalArgumentException(errorMsg))
            }
            val resolvedFolder = folderResult.getOrThrow()

            _customFolders.value = (_customFolders.value + resolvedFolder).distinctBy { it.id }
            addCuratedBookshelfFolder(resolvedFolder)

            if (targetNotebook != null) {
                onFolderSelected(targetNotebook, resolvedFolder)
            }

            Result.success(resolvedFolder)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to handle picker folder selection: ${e.message}", e)
            val targetNotebook = notebook ?: (_uploadState.value as? DriveUploadState.SelectingFolder)?.notebook
                ?: (_uploadState.value as? DriveUploadState.AwaitingConfirmation)?.notebook
            if (targetNotebook != null) {
                setUploadError("Failed to process Google Drive selection: ${e.message}", targetNotebook)
            }
            Result.failure(e)
        }
    }

    /**
     * Lists subfolders inside a parent folder in Google Drive for hierarchical folder navigation.
     */
    suspend fun fetchSubfolders(
        context: Context,
        parentFolderId: String = "root"
    ): Result<List<DriveFolder>> = withContext(ioDispatcher) {
        val accountEmail = getResolvedAccountEmail(context)
            ?: return@withContext Result.failure(IllegalStateException("Google Drive authorization required"))
        val uploader = uploaderProvider(context)
        uploader.listSubfolders(accountEmail, parentFolderId)
    }

    /**
     * Sets an explicit error state on the upload workflow.
     */
    fun setUploadError(message: String, notebook: NotebookItem? = null) {
        val targetNotebook = notebook ?: (_uploadState.value as? DriveUploadState.SelectingFolder)?.notebook
        _uploadState.value = DriveUploadState.Error(
            message = message,
            retryNotebook = targetNotebook
        )
    }

    /**
     * Builds the Google Picker OAuth URL for external web browsers or Custom Tabs.
     * Note: Native Android apps should use buildPickerAuthorizationRequest with Google Identity AuthorizationClient.
     */
    fun buildPickerUrl(state: String = ""): String {
        val webClientId = GraphiteConfig.googleWebClientId
        val redirectUri = Uri.encode("graphite://oauth2callback")
        val scope = Uri.encode(GraphiteConfig.DRIVE_SCOPE_FILE)

        return "https://accounts.google.com/o/oauth2/v2/auth?" +
                "client_id=$webClientId" +
                "&redirect_uri=$redirectUri" +
                "&response_type=code" +
                "&scope=$scope" +
                "&access_type=offline" +
                "&prompt=consent" +
                "&PICKER_ALLOW_FOLDER_SELECTION=true" +
                if (state.isNotBlank()) "&state=${Uri.encode(state)}" else ""
    }

    /**
     * Handles returning deep-link from the Google Picker mobile browser redirect.
     */
    fun handlePickerRedirect(uri: Uri, notebook: NotebookItem?): DriveFolder? {
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            SafeLogger.e(TAG, "Google Drive Picker redirect returned error: $error")
            val targetNotebook = notebook ?: (_uploadState.value as? DriveUploadState.SelectingFolder)?.notebook
            setUploadError("Google Drive authorization error: $error", targetNotebook)
            return null
        }

        val folderId = uri.getQueryParameter("folder_id")
            ?: uri.getQueryParameter("picked_file_ids")?.split(",")?.firstOrNull()?.trim()
            ?: uri.getQueryParameter("id")

        if (!folderId.isNullOrBlank()) {
            val folderName = uri.getQueryParameter("folder_name")
                ?: uri.getQueryParameter("name")
                ?: "Selected Drive Folder"

            val folder = DriveFolder(id = folderId, name = folderName)
            _customFolders.value = (_customFolders.value + folder).distinctBy { it.id }

            val targetNotebook = notebook ?: (_uploadState.value as? DriveUploadState.SelectingFolder)?.notebook
            if (targetNotebook != null) {
                onFolderSelected(targetNotebook, folder)
            }
            return folder
        }
        return null
    }

    /**
     * Upload notebook to an explicitly selected destination folder.
     * Silent fallback to My Drive root is strictly prevented: returns an actionable Error if destinationFolder is null.
     */
    suspend fun uploadSelectedNotebook(
        context: Context,
        notebook: NotebookItem,
        destinationFolder: DriveFolder? = null
    ): DriveUploadResult {
        if (destinationFolder == null) {
            val errorMsg = "No destination folder selected. Please choose a valid Google Drive folder."
            _uploadState.value = DriveUploadState.Error(
                message = errorMsg,
                retryNotebook = notebook,
                retryFolder = null
            )
            return DriveUploadResult.Error(errorMsg)
        }
        return confirmAndUpload(context, notebook, destinationFolder)
    }

    /**
     * Explicit "Upload to My Drive" action that intentionally uploads to My Drive root.
     * Kept strictly separate from the destination-folder selection workflow.
     */
    suspend fun uploadNotebookToMyDrive(
        context: Context,
        notebook: NotebookItem,
        customFileName: String? = null
    ): DriveUploadResult = confirmAndUpload(context, notebook, DriveFolder.ROOT, customFileName)

    /**
     * Cancels the active picker or confirmation dialog without uploading or creating any Drive file.
     */
    fun cancelUpload() {
        _uploadState.value = DriveUploadState.Idle
    }

    fun resetUploadState() {
        _uploadState.value = DriveUploadState.Idle
    }

    private val _isSyncingBookshelf = MutableStateFlow(false)
    val isSyncingBookshelf: StateFlow<Boolean> = _isSyncingBookshelf.asStateFlow()

    private val _bookshelfSyncStatus = MutableStateFlow<String?>(null)
    val bookshelfSyncStatus: StateFlow<String?> = _bookshelfSyncStatus.asStateFlow()

    val DEFAULT_BOOKSHELF_FOLDER = DriveFolder("default_graphite_bookshelf", "Graphite Bookshelf")

    private val _curatedBookshelfFolders = MutableStateFlow<List<DriveFolder>>(emptyList())
    val curatedBookshelfFolders: StateFlow<List<DriveFolder>> = _curatedBookshelfFolders.asStateFlow()

    private val _defaultBookshelfFolder = MutableStateFlow<DriveFolder>(DEFAULT_BOOKSHELF_FOLDER)
    val defaultBookshelfFolder: StateFlow<DriveFolder> = _defaultBookshelfFolder.asStateFlow()

    private val _activeBookshelfFolder = MutableStateFlow<DriveFolder>(DEFAULT_BOOKSHELF_FOLDER)
    val activeBookshelfFolder: StateFlow<DriveFolder> = _activeBookshelfFolder.asStateFlow()

    init {
        val def = getDefaultBookshelfFolder()
        _defaultBookshelfFolder.value = def
        _curatedBookshelfFolders.value = getCuratedBookshelfFolders()
        _activeBookshelfFolder.value = getBookshelfSyncedFolder()
    }

    fun getDefaultBookshelfFolder(): DriveFolder {
        val id = secureStorage.getString(KEY_BOOKSHELF_DEFAULT_FOLDER_ID)
        val name = secureStorage.getString(KEY_BOOKSHELF_DEFAULT_FOLDER_NAME)
        return if (!id.isNullOrBlank() && !name.isNullOrBlank()) {
            DriveFolder(id, name)
        } else {
            DEFAULT_BOOKSHELF_FOLDER
        }
    }

    fun setDefaultBookshelfFolder(folder: DriveFolder) {
        secureStorage.putString(KEY_BOOKSHELF_DEFAULT_FOLDER_ID, folder.id)
        secureStorage.putString(KEY_BOOKSHELF_DEFAULT_FOLDER_NAME, folder.name)
        _defaultBookshelfFolder.value = folder
        addCuratedBookshelfFolder(folder)
    }

    fun getCuratedBookshelfFolders(): List<DriveFolder> {
        val defaultFolder = getDefaultBookshelfFolder()
        val jsonStr = secureStorage.getString(KEY_BOOKSHELF_CURATED_FOLDERS)
        val list = if (!jsonStr.isNullOrBlank()) {
            try {
                Json.decodeFromString<List<DriveFolder>>(jsonStr)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val allFolders = listOf(defaultFolder) + list
        val result = mutableListOf<DriveFolder>()
        for (f in allFolders) {
            val existingIdx = result.indexOfFirst {
                it.id == f.id || it.name.trim().equals(f.name.trim(), ignoreCase = true)
            }
            if (existingIdx >= 0) {
                val existing = result[existingIdx]
                // If existing has a placeholder ID ("default_" or "custom_") but f has a real Drive ID, upgrade to real ID
                if ((existing.id.startsWith("default_") || existing.id.startsWith("custom_")) &&
                    (!f.id.startsWith("default_") && !f.id.startsWith("custom_"))
                ) {
                    result[existingIdx] = f
                }
            } else {
                result.add(f)
            }
        }
        return result
    }

    fun addCuratedBookshelfFolder(folder: DriveFolder) {
        val current = getCuratedBookshelfFolders().toMutableList()
        val existingIndex = current.indexOfFirst { it.id == folder.id || it.name.trim().equals(folder.name.trim(), ignoreCase = true) }
        if (existingIndex >= 0) {
            current[existingIndex] = folder
        } else {
            current.add(folder)
        }
        val encoded = Json.encodeToString(current)
        secureStorage.putString(KEY_BOOKSHELF_CURATED_FOLDERS, encoded)
        _curatedBookshelfFolders.value = current
    }

    fun removeCuratedBookshelfFolder(folderId: String) {
        val defaultFolder = getDefaultBookshelfFolder()
        if (folderId == defaultFolder.id || folderId == DEFAULT_BOOKSHELF_FOLDER.id) {
            return
        }
        val updated = getCuratedBookshelfFolders().filterNot { it.id == folderId }
        val encoded = Json.encodeToString(updated)
        secureStorage.putString(KEY_BOOKSHELF_CURATED_FOLDERS, encoded)
        _curatedBookshelfFolders.value = updated

        val active = getBookshelfSyncedFolder()
        if (active.id == folderId) {
            setBookshelfSyncedFolder(defaultFolder)
        }
    }

    fun getBookshelfSyncedFolder(): DriveFolder {
        val folderId = secureStorage.getString(KEY_BOOKSHELF_SYNC_FOLDER_ID)
        val folderName = secureStorage.getString(KEY_BOOKSHELF_SYNC_FOLDER_NAME)
        return if (!folderId.isNullOrBlank() && !folderName.isNullOrBlank()) {
            DriveFolder(folderId, folderName)
        } else {
            getDefaultBookshelfFolder()
        }
    }

    fun setBookshelfSyncedFolder(folder: DriveFolder?) {
        val target = folder ?: getDefaultBookshelfFolder()
        secureStorage.putString(KEY_BOOKSHELF_SYNC_FOLDER_ID, target.id)
        secureStorage.putString(KEY_BOOKSHELF_SYNC_FOLDER_NAME, target.name)
        _activeBookshelfFolder.value = target
        addCuratedBookshelfFolder(target)
    }

    fun resetBookshelfSyncedFolderToDefault() {
        val defaultFolder = getDefaultBookshelfFolder()
        setBookshelfSyncedFolder(defaultFolder)
    }

    suspend fun createOrConnectBookshelfFolder(
        context: Context,
        folderName: String
    ): Result<DriveFolder> = withContext(ioDispatcher) {
        val trimmed = folderName.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Folder name cannot be blank"))
        }

        val accountEmail = getResolvedAccountEmail(context)
        if (accountEmail.isNullOrBlank()) {
            val localFolder = DriveFolder("custom_$trimmed", trimmed)
            addCuratedBookshelfFolder(localFolder)
            setBookshelfSyncedFolder(localFolder)
            return@withContext Result.success(localFolder)
        }
        saveAuthorizedAccount(accountEmail)

        try {
            val uploader = uploaderProvider(context)
            val existingFolders = uploader.listFolders(accountEmail).getOrDefault(emptyList())
            val matched = existingFolders.find { it.name.equals(trimmed, ignoreCase = true) }
            val folder = if (matched != null) {
                matched
            } else {
                val created = uploader.createFolder(accountEmail, trimmed, "root")
                created.getOrDefault(DriveFolder("custom_$trimmed", trimmed))
            }
            addCuratedBookshelfFolder(folder)
            setBookshelfSyncedFolder(folder)
            Result.success(folder)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to connect or create folder: ${e.message}", e)
            val fallback = DriveFolder("custom_$trimmed", trimmed)
            addCuratedBookshelfFolder(fallback)
            setBookshelfSyncedFolder(fallback)
            Result.success(fallback)
        }
    }

    fun hasBookshelfSyncConfigured(): Boolean = getAuthorizedAccountEmail() != null

    fun getLastBookshelfSyncTimeMillis(): Long {
        val str = secureStorage.getString(KEY_BOOKSHELF_LAST_SYNC_TIME) ?: return 0L
        return str.toLongOrNull() ?: 0L
    }

    suspend fun syncBookshelfWithDrive(
        context: Context,
        targetFolderId: String? = null
    ): BookshelfSyncReport = withContext(ioDispatcher) {
        val accountEmail = getResolvedAccountEmail(context)
        if (accountEmail.isNullOrBlank()) {
            return@withContext BookshelfSyncReport(
                isSuccess = false,
                folderId = "",
                folderName = "",
                downloadedCount = 0,
                uploadedCount = 0,
                message = "Google Drive authorization required. Please authorize first."
            )
        }
        saveAuthorizedAccount(accountEmail)

        _isSyncingBookshelf.value = true
        _bookshelfSyncStatus.value = "Starting Drive sync..."

        try {
            val uploader = uploaderProvider(context)
            var currentFolder = if (!targetFolderId.isNullOrBlank()) {
                val f = uploader.getFolder(accountEmail, targetFolderId).getOrNull()
                    ?: DriveFolder(targetFolderId, getBookshelfSyncedFolder().name)
                setBookshelfSyncedFolder(f)
                f
            } else {
                getBookshelfSyncedFolder()
            }

            // Auto-create or bind folder by name if default or custom placeholder
            if (currentFolder.id.startsWith("default_") || currentFolder.id.startsWith("custom_") || currentFolder.id.isBlank()) {
                val targetName = currentFolder.name.ifBlank { "Graphite Bookshelf" }
                _bookshelfSyncStatus.value = "Finding or creating \"$targetName\" in Drive..."
                val existingFolders = uploader.listFolders(accountEmail).getOrDefault(emptyList())
                val existingBookshelf = existingFolders.find { it.name.equals(targetName, ignoreCase = true) }
                if (existingBookshelf != null) {
                    currentFolder = existingBookshelf
                } else {
                    val createResult = uploader.createFolder(accountEmail, targetName, "root")
                    currentFolder = createResult.getOrDefault(DriveFolder("folder_${targetName.replace(" ", "_")}", targetName))
                }
                setBookshelfSyncedFolder(currentFolder)
                if (targetName.equals("Graphite Bookshelf", ignoreCase = true)) {
                    setDefaultBookshelfFolder(currentFolder)
                }
                addCuratedBookshelfFolder(currentFolder)
            }

            val folderId = currentFolder.id
            val folderName = currentFolder.name

            // 1. PULL: List remote files in synced folder
            _bookshelfSyncStatus.value = "Checking files in $folderName..."
            val remoteFilesResult = uploader.listFiles(accountEmail, folderId)
            val remoteFiles = remoteFilesResult.getOrDefault(emptyList())

            var downloadedCount = 0
            val localBooks = GraphiteRepository.books.value
            val docsDir = File(context.filesDir, "imported_documents").apply { mkdirs() }

            for (remoteFile in remoteFiles) {
                val cleanName = remoteFile.name

                // Bookshelf strictly supports PDF files only
                val isPdf = cleanName.endsWith(".pdf", ignoreCase = true) ||
                        remoteFile.mimeType.equals("application/pdf", ignoreCase = true)
                if (!isPdf) {
                    continue
                }

                // Ignore note backups, doc attachments, or raw UUIDs
                if (cleanName.startsWith("doc_") || cleanName.equals("graphite_notes_backup.json", ignoreCase = true)) {
                    continue
                }
                val isRawId = cleanName.matches(Regex("^[a-zA-Z0-9_-]{20,}(\\..+)?$")) ||
                        cleanName.matches(Regex("^[0-9a-fA-F-]{36}(\\..+)?$"))
                if (isRawId) {
                    continue
                }

                val normalizedRemote = GraphiteRepository.normalizeBookTitle(cleanName)
                val existingLocal = localBooks.find { book ->
                    book.driveFileId == remoteFile.id ||
                            book.id == "drive_${remoteFile.id}" ||
                            GraphiteRepository.normalizeBookTitle(book.title) == normalizedRemote ||
                            (book.filePath != null && File(book.filePath).name.equals(cleanName, ignoreCase = true))
                }

                if (existingLocal != null) {
                    // Book already exists locally - ensure driveFileId is attached and skip re-downloading
                    if (existingLocal.driveFileId.isNullOrBlank()) {
                        GraphiteRepository.addBook(existingLocal.copy(driveFileId = remoteFile.id))
                    }
                } else {
                    _bookshelfSyncStatus.value = "Downloading $cleanName..."
                    val downloadResult = uploader.downloadFile(accountEmail, remoteFile.id)
                    if (downloadResult.isSuccess) {
                        val bytes = downloadResult.getOrThrow()
                        if (bytes.isNotEmpty()) {
                            val safeName = "${remoteFile.id}_${cleanName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
                            val targetFile = File(docsDir, safeName)
                            targetFile.writeBytes(bytes)

                            val parsed = DocumentContentParser.parse(bytes, cleanName, "application/pdf")
                            val rawBaseTitle = if (cleanName.endsWith(".pdf", ignoreCase = true)) cleanName.removeSuffix(".pdf") else cleanName
                            val displayTitle = rawBaseTitle.replace('_', ' ').replace('-', ' ').trim()

                            val newBook = Book(
                                id = "drive_${remoteFile.id}",
                                title = displayTitle,
                                author = "Google Drive Sync",
                                progressPercent = 0,
                                coverType = BookCoverType.CUSTOM,
                                totalChapters = parsed.pageOrSlideCount,
                                currentChapter = 1,
                                content = parsed.extractedText,
                                filePath = targetFile.absolutePath,
                                mimeType = "application/pdf",
                                driveFileId = remoteFile.id
                            )
                            GraphiteRepository.addBook(newBook)
                            downloadedCount++
                        }
                    }
                }
            }

            // 2. PUSH: Upload local PDF books that are not yet on Drive
            var uploadedCount = 0
            val updatedLocalBooks = GraphiteRepository.books.value
            for (book in updatedLocalBooks) {
                val isPdf = book.mimeType == "application/pdf" ||
                        (book.filePath != null && book.filePath.endsWith(".pdf", ignoreCase = true))
                if (!isPdf || book.filePath == null) continue

                val f = File(book.filePath)
                if (!f.exists()) continue

                val normalizedLocal = GraphiteRepository.normalizeBookTitle(book.title)
                val matchesRemote = remoteFiles.any { rf ->
                    rf.id == book.driveFileId ||
                            GraphiteRepository.normalizeBookTitle(rf.name) == normalizedLocal ||
                            rf.name.equals(f.name, ignoreCase = true)
                }

                if (!matchesRemote) {
                    _bookshelfSyncStatus.value = "Uploading ${book.title} to Drive..."
                    val rawName = book.title.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                    val fileName = if (rawName.endsWith(".pdf", ignoreCase = true)) rawName else "$rawName.pdf"

                    val uploadResult = uploader.upload(accountEmail, fileName, "application/pdf", f.readBytes(), folderId)
                    if (uploadResult is DriveUploadResult.Success) {
                        GraphiteRepository.addBook(book.copy(driveFileId = uploadResult.fileId))
                        uploadedCount++
                    }
                }
            }

            secureStorage.putString(KEY_BOOKSHELF_LAST_SYNC_TIME, System.currentTimeMillis().toString())
            _bookshelfSyncStatus.value = "Sync complete: $downloadedCount downloaded, $uploadedCount uploaded"
            _isSyncingBookshelf.value = false

            BookshelfSyncReport(
                isSuccess = true,
                folderId = folderId,
                folderName = folderName,
                downloadedCount = downloadedCount,
                uploadedCount = uploadedCount,
                message = "Synced with \"$folderName\": $downloadedCount imported from Drive, $uploadedCount uploaded."
            )
        } catch (e: Exception) {
            _isSyncingBookshelf.value = false
            _bookshelfSyncStatus.value = "Sync error: ${e.message}"
            BookshelfSyncReport(
                isSuccess = false,
                folderId = "",
                folderName = "",
                downloadedCount = 0,
                uploadedCount = 0,
                message = "Failed to sync: ${e.message ?: "Unknown error"}"
            )
        }
    }

    suspend fun uploadBookToSyncedDriveFolder(
        context: Context,
        book: Book
    ): DriveUploadResult? = withContext(ioDispatcher) {
        val isPdf = book.mimeType == "application/pdf" ||
                (book.filePath != null && book.filePath.endsWith(".pdf", ignoreCase = true))
        if (!isPdf || book.filePath == null) {
            return@withContext null
        }
        val f = File(book.filePath)
        if (!f.exists()) return@withContext null

        val accountEmail = getResolvedAccountEmail(context) ?: return@withContext null
        saveAuthorizedAccount(accountEmail)
        var syncedFolder = getBookshelfSyncedFolder()
        val uploader = uploaderProvider(context)
        if (syncedFolder.id.startsWith("default_") || syncedFolder.id.startsWith("custom_")) {
            val existing = uploader.listFolders(accountEmail).getOrDefault(emptyList())
                .find { it.name.equals(syncedFolder.name, ignoreCase = true) }
            if (existing != null) {
                syncedFolder = existing
                setBookshelfSyncedFolder(existing)
            } else {
                val created = uploader.createFolder(accountEmail, syncedFolder.name, "root").getOrNull()
                if (created != null) {
                    syncedFolder = created
                    setBookshelfSyncedFolder(created)
                }
            }
        }

        val rawName = book.title.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val fileName = if (rawName.endsWith(".pdf", ignoreCase = true)) rawName else "$rawName.pdf"
        val res = uploader.upload(accountEmail, fileName, "application/pdf", f.readBytes(), syncedFolder.id)
        if (res is DriveUploadResult.Success) {
            GraphiteRepository.addBook(book.copy(driveFileId = res.fileId))
        }
        res
    }

    suspend fun deleteBookFromDrive(
        context: Context,
        book: Book
    ): Result<Unit> = withContext(ioDispatcher) {
        val accountEmail = getResolvedAccountEmail(context)
        if (accountEmail.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Drive account not authorized"))
        }

        try {
            val uploader = uploaderProvider(context)
            var targetFileId = book.driveFileId

            // If driveFileId is missing, locate the file by name in the synced folder
            if (targetFileId.isNullOrBlank()) {
                val folder = getBookshelfSyncedFolder()
                val fileName = (book.filePath?.let { File(it).name } ?: "${book.title}.pdf")
                val found = uploader.findFileByName(
                    accountEmail = accountEmail,
                    fileName = fileName,
                    parentFolderId = folder.id.takeIf { !it.startsWith("default_") && !it.startsWith("custom_") }
                ).getOrNull()
                targetFileId = found?.id
            }

            if (!targetFileId.isNullOrBlank()) {
                uploader.deleteFile(accountEmail, targetFileId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to delete book from Drive: ${e.message}", e)
            Result.failure(e)
        }
    }

    private val _isSyncingNotes = MutableStateFlow(false)
    val isSyncingNotes: StateFlow<Boolean> = _isSyncingNotes.asStateFlow()

    private val _notesSyncStatus = MutableStateFlow<String?>(null)
    val notesSyncStatus: StateFlow<String?> = _notesSyncStatus.asStateFlow()

    suspend fun syncNotesWithDrive(context: Context): Result<Int> = withContext(ioDispatcher) {
        val accountEmail = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: secureStorage.getString("google_user_email")?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: secureStorage.getString("google_account_email")?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: getAuthorizedAccountEmail()?.takeIf { it.isNotBlank() && it != "authorized_user" && it != "null" }
            ?: try { com.graphite.app.GraphiteApplication.instance.googleAccountManager.currentUser.value?.email?.takeIf { it.isNotBlank() && it != "null" } } catch (_: Exception) { null }
            ?: GoogleSignIn.getLastSignedInAccount(context)?.account?.name?.takeIf { it.isNotBlank() && it != "null" }
            ?: GoogleSignIn.getLastSignedInAccount(context)?.email?.takeIf { it.isNotBlank() && it != "null" }

        if (accountEmail.isNullOrBlank()) {
            _isSyncingNotes.value = false
            _notesSyncStatus.value = "Google account authorization required"
            return@withContext Result.failure(IllegalStateException("Google account authorization required to sync notes."))
        }

        saveAuthorizedAccount(accountEmail)

        if (!hasDrivePermission(context)) {
            _isSyncingNotes.value = false
            _notesSyncStatus.value = "Google Drive authorization required"
            return@withContext Result.failure(IllegalStateException("Google Drive permission required. Please grant Drive permission to sync."))
        }

        _isSyncingNotes.value = true
        _notesSyncStatus.value = "Backing up notes to Google Drive..."

        try {
            val uploader = uploaderProvider(context)
            val fileName = "graphite_notes_backup.json"
            val mimeType = "application/json"

            // 0. Locate or create "Graphite Notes" folder in Google Drive
            val existingFolders = uploader.listFolders(accountEmail).getOrDefault(emptyList())
            val notesFolder = existingFolders.find { it.name.equals("Graphite Notes", ignoreCase = true) }
                ?: uploader.createFolder(accountEmail, "Graphite Notes", "root").getOrDefault(DriveFolder("folder_graphite_notes", "Graphite Notes"))
            val notesFolderId = notesFolder.id

            // 1. Check if backup file already exists in Drive (fail if network or auth error occurred)
            val remoteInFolder = uploader.findFileByName(accountEmail, fileName, notesFolderId)
            val existingRemote = if (remoteInFolder.isSuccess && remoteInFolder.getOrNull() != null) {
                remoteInFolder.getOrNull()
            } else {
                val remoteAnywhere = uploader.findFileByName(accountEmail, fileName)
                if (remoteAnywhere.isFailure && remoteInFolder.isFailure) {
                    val ex = remoteInFolder.exceptionOrNull() ?: remoteAnywhere.exceptionOrNull() ?: IOException("Failed to connect to Google Drive")
                    _isSyncingNotes.value = false
                    _notesSyncStatus.value = "Drive error: ${ex.message}"
                    return@withContext Result.failure(ex)
                }
                remoteAnywhere.getOrNull()
            }

            // 2. If existing, download and reconcile first (so notes from previous sessions/devices are not overwritten)
            if (existingRemote != null) {
                _notesSyncStatus.value = "Fetching existing remote notes..."
                val downloadRes = uploader.downloadFile(accountEmail, existingRemote.id)
                if (downloadRes.isSuccess) {
                    val remoteBytes = downloadRes.getOrThrow()
                    if (remoteBytes.isNotEmpty()) {
                        val remoteJson = remoteBytes.toString(Charsets.UTF_8)
                        GraphiteRepository.importNotesFromJson(remoteJson)
                    }
                } else {
                    val ex = downloadRes.exceptionOrNull() ?: IOException("Failed to download notes backup from Drive")
                    _isSyncingNotes.value = false
                    _notesSyncStatus.value = "Download failed: ${ex.message}"
                    return@withContext Result.failure(ex)
                }
            }

            // 3. Document files sync (PDF, DOCX, PPTX) for imported notes
            val notesDir = File(context.filesDir, "imported_notes").apply { mkdirs() }
            val currentNotes = GraphiteRepository.getAllNotesForActiveUser().filter { !it.isDeleted }
            val remoteFiles = uploader.listFiles(accountEmail, notesFolderId).getOrDefault(emptyList())

            // 3A. Upload local attached document files that aren't in Drive yet
            for (note in currentNotes) {
                val localPath = note.filePath
                if (!localPath.isNullOrBlank()) {
                    val localFile = File(localPath)
                    if (localFile.exists()) {
                        val remoteDocName = "doc_${note.id}_${localFile.name}"
                        val alreadyInDrive = remoteFiles.any { rf ->
                            rf.name.equals(remoteDocName, ignoreCase = true) || rf.name.equals(localFile.name, ignoreCase = true)
                        }
                        if (!alreadyInDrive) {
                            _notesSyncStatus.value = "Uploading ${note.title} document to Drive..."
                            val docMime = note.mimeType ?: "application/octet-stream"
                            uploader.upload(accountEmail, remoteDocName, docMime, localFile.readBytes(), notesFolderId)
                        }
                    }
                }
            }

            // 3B. Download remote document files for notes that have no local file on this device
            val updatedRemoteFiles = uploader.listFiles(accountEmail, notesFolderId).getOrDefault(emptyList())
            val notesToDownload = GraphiteRepository.getAllNotesForActiveUser().filter { !it.isDeleted }
            for (note in notesToDownload) {
                val localPath = note.filePath
                val localFileExists = !localPath.isNullOrBlank() && File(localPath).exists()
                if (!localFileExists && (!note.filePath.isNullOrBlank() || note.mimeType != null || note.pageCount > 1)) {
                    val expectedPrefix = "doc_${note.id}_"
                    val match = updatedRemoteFiles.find { it.name.startsWith(expectedPrefix, ignoreCase = true) }
                        ?: if (!note.filePath.isNullOrBlank()) {
                            val baseName = File(note.filePath).name
                            updatedRemoteFiles.find { it.name.equals(baseName, ignoreCase = true) }
                        } else null
                        ?: if (!note.filePath.isNullOrBlank()) {
                            uploader.findFileByName(accountEmail, "doc_${note.id}_" + File(note.filePath).name).getOrNull()
                                ?: uploader.findFileByName(accountEmail, File(note.filePath).name).getOrNull()
                        } else null

                    if (match != null) {
                        _notesSyncStatus.value = "Downloading ${note.title} document from Drive..."
                        val dlResult = uploader.downloadFile(accountEmail, match.id)
                        if (dlResult.isSuccess) {
                            val bytes = dlResult.getOrThrow()
                            if (bytes.isNotEmpty()) {
                                val cleanName = if (match.name.startsWith(expectedPrefix)) match.name.removePrefix(expectedPrefix) else match.name
                                val safeFile = File(notesDir, "${note.id}_${cleanName}")
                                safeFile.writeBytes(bytes)
                                GraphiteRepository.updateNotebookFilePath(note.id, safeFile.absolutePath, note.mimeType)
                            }
                        }
                    }
                }
            }

            // 4. Serialize all local notes (including updated filePaths)
            val mergedJson = GraphiteRepository.exportNotesAsJson()
            val uploadBytes = mergedJson.toByteArray(Charsets.UTF_8)

            if (existingRemote != null) {
                _notesSyncStatus.value = "Updating notes backup in Google Drive..."
                uploader.updateFile(accountEmail, existingRemote.id, mimeType, uploadBytes)
            } else {
                _notesSyncStatus.value = "Creating notes backup in Google Drive..."
                uploader.upload(accountEmail, fileName, mimeType, uploadBytes, notesFolderId)
            }

            val totalNotes = GraphiteRepository.getAllNotesForActiveUser().filter { !it.isDeleted }.size
            _notesSyncStatus.value = "Synced $totalNotes notes to Google Drive"
            _isSyncingNotes.value = false
            Result.success(totalNotes)
        } catch (e: Exception) {
            _isSyncingNotes.value = false
            _notesSyncStatus.value = "Notes sync failed: ${e.message}"
            Result.failure(e)
        }
    }

    suspend fun restoreNotesFromDrive(context: Context): Result<Int> = withContext(ioDispatcher) {
        val accountEmail = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)
            ?: getAuthorizedAccountEmail()
            ?: GoogleSignIn.getLastSignedInAccount(context)?.email
        if (accountEmail.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("Google account authorization required."))
        }
        syncNotesWithDrive(context)
    }

    companion object {
        private const val TAG = "GoogleDriveManager"
        const val KEY_DRIVE_ACCOUNT_EMAIL = "google_drive_authorized_account_email"
        private const val KEY_BOOKSHELF_SYNC_FOLDER_ID = "google_drive_bookshelf_sync_folder_id"
        private const val KEY_BOOKSHELF_SYNC_FOLDER_NAME = "google_drive_bookshelf_sync_folder_name"
        private const val KEY_BOOKSHELF_DEFAULT_FOLDER_ID = "google_drive_bookshelf_default_folder_id"
        private const val KEY_BOOKSHELF_DEFAULT_FOLDER_NAME = "google_drive_bookshelf_default_folder_name"
        private const val KEY_BOOKSHELF_CURATED_FOLDERS = "google_drive_bookshelf_curated_folders"
        private const val KEY_BOOKSHELF_LAST_SYNC_TIME = "google_drive_bookshelf_last_sync_time"
    }
}
