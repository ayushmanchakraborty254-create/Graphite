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
}

class DefaultDriveApiV3Uploader(
    private val context: Context
) : DriveApiV3Uploader {

    private fun getDriveService(accountEmail: String): Drive {
        // Strict minimum scope: https://www.googleapis.com/auth/drive.file
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(GraphiteConfig.DRIVE_SCOPE_FILE)
        )
        credential.selectedAccountName = accountEmail

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
    private val uploaderProvider: (Context) -> DriveApiV3Uploader = { ctx -> DefaultDriveApiV3Uploader(ctx) },
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
        val savedAccount = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)
        if (!savedAccount.isNullOrBlank()) {
            _authStatus.value = DriveAuthStatus.Authorized(savedAccount)
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
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        val hasScope = GoogleSignIn.hasPermissions(account, driveScope)
        if (hasScope && account.email != null) {
            if (_authStatus.value !is DriveAuthStatus.Authorized) {
                saveAuthorizedAccount(account.email!!)
            }
            return true
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

            val email = account.email ?: "authorized_user"
            saveAuthorizedAccount(email)
            Result.success(email)
        } catch (e: Exception) {
            _authStatus.value = DriveAuthStatus.NotAuthorized
            Result.failure(e)
        }
    }

    fun saveAuthorizedAccount(email: String) {
        secureStorage.putString(KEY_DRIVE_ACCOUNT_EMAIL, email)
        _authStatus.value = DriveAuthStatus.Authorized(email)
    }

    /**
     * Revokes local Drive authorization state.
     */
    fun disconnect() {
        secureStorage.remove(KEY_DRIVE_ACCOUNT_EMAIL)
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
        val accountEmail = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)
        if (accountEmail.isNullOrBlank()) {
            return@withContext false // Caller should launch authorization intent
        }

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
    fun getAuthorizedAccountEmail(): String? = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)

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

            val accountEmail = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL) ?: ""
            val uploader = uploaderProvider(context)
            val folderResult = uploader.getFolder(accountEmail, folderId)
            if (folderResult.isFailure) {
                val errorMsg = folderResult.exceptionOrNull()?.message
                    ?: "Selected item is a file, not a folder. Please choose a destination folder."
                SafeLogger.e(TAG, "Selected Drive item is not a folder: $errorMsg")
                setUploadError(errorMsg, targetNotebook)
                return@withContext Result.failure(folderResult.exceptionOrNull() ?: IllegalArgumentException(errorMsg))
            }
            val resolvedFolder = folderResult.getOrThrow()

            _customFolders.value = (_customFolders.value + resolvedFolder).distinctBy { it.id }

            if (targetNotebook != null) {
                onFolderSelected(targetNotebook, resolvedFolder)
            }

            Result.success(resolvedFolder)
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Failed to handle picker folder selection: ${e.message}", e)
            val targetNotebook = notebook ?: (_uploadState.value as? DriveUploadState.SelectingFolder)?.notebook
                ?: (_uploadState.value as? DriveUploadState.AwaitingConfirmation)?.notebook
            setUploadError("Failed to process Google Drive selection: ${e.message}", targetNotebook)
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
        val accountEmail = secureStorage.getString(KEY_DRIVE_ACCOUNT_EMAIL)
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

    companion object {
        private const val TAG = "GoogleDriveManager"
        private const val KEY_DRIVE_ACCOUNT_EMAIL = "google_drive_authorized_account_email"
    }
}
