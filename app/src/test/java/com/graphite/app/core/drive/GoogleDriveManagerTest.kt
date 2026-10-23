package com.graphite.app.core.drive

import com.graphite.app.core.config.GraphiteConfig
import com.graphite.app.core.security.InMemorySecureStorage
import com.graphite.app.data.model.NotebookItem
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkStroke
import com.graphite.app.feature.workspace.model.PaperStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GoogleDriveManagerTest {

    private lateinit var secureStorage: InMemorySecureStorage

    @Before
    fun setUp() {
        secureStorage = InMemorySecureStorage()
    }

    @Test
    fun testScopeIsStrictlyDriveFile() {
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )

        assertEquals("https://www.googleapis.com/auth/drive.file", manager.driveScope.scopeUri)
    }

    @Test
    fun testUploadFailsWhenNotAuthorized() = runBlocking {
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )

        val note = NotebookItem(title = "My Note")
        // Use a dummy context in test
        val mockContext = android.app.Application()

        val result = manager.uploadNotebookToMyDrive(mockContext, note)

        assertTrue(result is DriveUploadResult.Error)
        val error = result as DriveUploadResult.Error
        assertTrue(error.requiresReauth)
        assertTrue(error.message.contains("authorization required"))
    }

    @Test
    fun testSuccessfulUploadOfSelectedNotebook() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")

        var capturedFileName: String? = null
        var capturedMimeType: String? = null
        var capturedAccount: String? = null
        var capturedBytes: ByteArray? = null

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult {
                capturedAccount = accountEmail
                capturedFileName = fileName
                capturedMimeType = mimeType
                capturedBytes = contentBytes
                return DriveUploadResult.Success(
                    fileId = "drive_file_id_12345",
                    fileName = fileName,
                    webViewLink = "https://drive.google.com/file/d/drive_file_id_12345/view"
                )
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )

        val noteToUpload = NotebookItem(
            id = "note-custom-99",
            title = "Ass9 (2)",
            paperStyle = PaperStyle.RULED,
            previewLines = listOf("Exercise 1"),
            initialStrokes = listOf(
                InkStroke(points = listOf(InkPoint(10f, 20f), InkPoint(30f, 40f)))
            )
        )

        val dummyContext = android.app.Application()
        val result = manager.uploadNotebookToMyDrive(dummyContext, noteToUpload)

        assertTrue("Upload should succeed", result is DriveUploadResult.Success)
        val success = result as DriveUploadResult.Success
        assertEquals("drive_file_id_12345", success.fileId)
        assertEquals("Ass9__2_.pdf", success.fileName)
        assertNotNull(success.webViewLink)

        assertEquals("ayush@gmail.com", capturedAccount)
        assertEquals("Ass9__2_.pdf", capturedFileName)
        assertEquals("application/pdf", capturedMimeType)
        assertNotNull(capturedBytes)
        assertTrue("Captured bytes must contain PDF header", capturedBytes!!.isNotEmpty())
    }

    @Test
    fun testStartUploadFlowTransitionsToSelectingFolder() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "Folder Test Note")
        val mockContext = android.app.Application()

        manager.startUploadFlow(mockContext, note)

        val state = manager.uploadState.value
        assertTrue("State should be SelectingFolder", state is DriveUploadState.SelectingFolder)
        val selecting = state as DriveUploadState.SelectingFolder
        assertEquals("Folder Test Note", selecting.notebook.title)
        assertFalse(selecting.availableFolders.isEmpty())
        assertEquals("root", selecting.availableFolders[0].id)
    }

    @Test
    fun testFolderSelectionTransitionsToAwaitingConfirmation() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "Confirmation Note")
        val mockContext = android.app.Application()

        manager.startUploadFlow(mockContext, note)
        val targetFolder = DriveFolder(id = "folder_chemistry_101", name = "Chemistry")
        manager.onFolderSelected(note, targetFolder)

        val state = manager.uploadState.value
        assertTrue("State should be AwaitingConfirmation", state is DriveUploadState.AwaitingConfirmation)
        val awaiting = state as DriveUploadState.AwaitingConfirmation
        assertEquals("Confirmation Note", awaiting.notebook.title)
        assertEquals("folder_chemistry_101", awaiting.selectedFolder.id)
        assertEquals("Chemistry", awaiting.selectedFolder.name)
    }

    @Test
    fun testCancelingFolderPickerResetsStateToIdleWithoutUploading() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var uploadCalled = false
        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult {
                uploadCalled = true
                return DriveUploadResult.Success("id", fileName)
            }
        }
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "Cancel Note")
        val mockContext = android.app.Application()

        manager.startUploadFlow(mockContext, note)
        assertTrue(manager.uploadState.value is DriveUploadState.SelectingFolder)

        // Cancel during folder selection
        manager.cancelUpload()

        assertEquals(DriveUploadState.Idle, manager.uploadState.value)
        assertFalse("Upload must NEVER be called when picker is cancelled", uploadCalled)
    }

    @Test
    fun testCancelingConfirmationDialogResetsStateToIdleWithoutUploading() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var uploadCalled = false
        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult {
                uploadCalled = true
                return DriveUploadResult.Success("id", fileName)
            }
        }
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "Cancel Confirmation Note")
        val mockContext = android.app.Application()

        manager.startUploadFlow(mockContext, note)
        manager.onFolderSelected(note, DriveFolder("folder_1", "Folder 1"))
        assertTrue(manager.uploadState.value is DriveUploadState.AwaitingConfirmation)

        // Cancel on confirmation dialog
        manager.cancelUpload()

        assertEquals(DriveUploadState.Idle, manager.uploadState.value)
        assertFalse("Upload must NEVER be called when confirmation is cancelled", uploadCalled)
    }

    @Test
    fun testConfirmAndUploadPassesFolderIdToDriveApiV3() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var capturedFolderId: String? = null
        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult {
                capturedFolderId = folderId
                return DriveUploadResult.Success(
                    fileId = "uploaded_file_id_999",
                    fileName = fileName,
                    folderName = "Calculus Folder",
                    webViewLink = "https://drive.google.com/file/d/uploaded_file_id_999/view"
                )
            }
        }
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "Calculus Notes")
        val mockContext = android.app.Application()

        manager.startUploadFlow(mockContext, note)
        val targetFolder = DriveFolder("calc_folder_id", "Calculus Folder")
        manager.onFolderSelected(note, targetFolder)
        manager.confirmAndUpload(mockContext, note, targetFolder)

        val state = manager.uploadState.value
        assertTrue("State should be Success", state is DriveUploadState.Success)
        assertEquals("Calculus Folder", (state as DriveUploadState.Success).result.folderName)
        assertEquals("calc_folder_id", capturedFolderId)
    }

    @Test
    fun testUploadFailureProvidesUsefulErrorMessageAndRetryAction() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var attempts = 0
        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult {
                attempts++
                return if (attempts == 1) {
                    DriveUploadResult.Error("Network socket timed out", requiresReauth = false)
                } else {
                    DriveUploadResult.Success("retry_file_id", fileName)
                }
            }
        }
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "Retry Note")
        val mockContext = android.app.Application()

        manager.startUploadFlow(mockContext, note)
        val targetFolder = DriveFolder.ROOT
        manager.onFolderSelected(note, targetFolder)
        manager.confirmAndUpload(mockContext, note, targetFolder)

        // 1st attempt fails
        val errState = manager.uploadState.value
        assertTrue("State should be Error", errState is DriveUploadState.Error)
        val error = errState as DriveUploadState.Error
        assertEquals("Network socket timed out", error.message)
        assertNotNull(error.retryNotebook)
        assertNotNull(error.retryFolder)

        // Trigger retry
        manager.confirmAndUpload(mockContext, error.retryNotebook!!, error.retryFolder!!)

        // 2nd attempt succeeds
        assertTrue("Retry should succeed", manager.uploadState.value is DriveUploadState.Success)
        assertEquals(2, attempts)
    }

    @Test
    fun testDisconnectRevokesLocalAuthorization() {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )

        assertTrue(manager.authStatus.value is DriveAuthStatus.Authorized)
        manager.disconnect()

        assertEquals(DriveAuthStatus.NotAuthorized, manager.authStatus.value)
        assertNull(secureStorage.getString("google_drive_authorized_account_email"))
    }

    @Test
    fun testBuildPickerAuthorizationRequestConfiguresDocumentedParameters() {
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )

        val request = manager.buildPickerAuthorizationRequest("ayush@gmail.com")

        assertNotNull("AuthorizationRequest must not be null", request)
        assertEquals(1, request.requestedScopes.size)
        assertEquals("https://www.googleapis.com/auth/drive.file", request.requestedScopes[0].scopeUri)
        assertTrue("optOutIncludingGrantedScopes must be true", request.optOutIncludingGrantedScopes)
        assertEquals(com.google.android.gms.auth.api.identity.AuthorizationRequest.Prompt.CONSENT, request.prompt)
    }

    @Test
    fun testBuildPickerAuthorizationRequestWithoutExplicitEmailUsesStoredAccount() {
        secureStorage.putString("google_drive_authorized_account_email", "stored@gmail.com")
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )

        val request = manager.buildPickerAuthorizationRequest()

        assertNotNull("AuthorizationRequest must not be null", request)
        assertTrue(request.optOutIncludingGrantedScopes)
        assertEquals(com.google.android.gms.auth.api.identity.AuthorizationRequest.Prompt.CONSENT, request.prompt)
    }

    @Test
    fun testPickerSelectedFolderDirectUpload() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var capturedFolderId: String? = null
        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult {
                capturedFolderId = folderId
                return DriveUploadResult.Success(
                    fileId = "uploaded_in_picker_folder_123",
                    fileName = fileName,
                    folderName = "Lecture Notes",
                    webViewLink = "https://drive.google.com/file/d/uploaded_in_picker_folder_123/view"
                )
            }
        }
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "History Notes")
        val mockContext = android.app.Application()

        // 1. User starts upload flow
        val flowStarted = manager.startUploadFlow(mockContext, note)
        assertTrue("Flow should start", flowStarted)
        assertTrue(manager.uploadState.value is DriveUploadState.SelectingFolder)

        // 2. Folder selected from Picker
        val pickerFolder = DriveFolder(id = "picker_folder_999", name = "Lecture Notes")
        manager.onFolderSelected(note, pickerFolder)

        val confirmationState = manager.uploadState.value
        assertTrue("State must be AwaitingConfirmation", confirmationState is DriveUploadState.AwaitingConfirmation)
        val awaiting = confirmationState as DriveUploadState.AwaitingConfirmation
        assertEquals("History_Notes.pdf", awaiting.fileName)
        assertEquals("picker_folder_999", awaiting.selectedFolder.id)
        assertEquals("Lecture Notes", awaiting.selectedFolder.name)

        // 3. Confirm and upload
        val result = manager.confirmAndUpload(mockContext, note, pickerFolder)
        assertTrue("Upload must succeed", result is DriveUploadResult.Success)
        val success = result as DriveUploadResult.Success
        assertEquals("uploaded_in_picker_folder_123", success.fileId)
        assertEquals("History_Notes.pdf", success.fileName)
        assertEquals("Lecture Notes", success.folderName)
        assertEquals("picker_folder_999", capturedFolderId)
        assertTrue("UploadState should be Success", manager.uploadState.value is DriveUploadState.Success)
    }

    @Test
    fun testSetUploadErrorSetsErrorMessageAndRetryState() {
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "Error Note")

        manager.setUploadError("Custom picker failure", note)

        val state = manager.uploadState.value
        assertTrue(state is DriveUploadState.Error)
        val error = state as DriveUploadState.Error
        assertEquals("Custom picker failure", error.message)
        assertEquals("Error Note", error.retryNotebook?.title)
    }

    @Test
    fun testSubfolderNavigationReturnsChildrenOfParent() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")

        val subfoldersMap = mapOf(
            "root" to listOf(
                DriveFolder("folder_work", "Work"),
                DriveFolder("folder_personal", "Personal")
            ),
            "folder_work" to listOf(
                DriveFolder("folder_projects", "Projects"),
                DriveFolder("folder_reports", "Reports")
            )
        )

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult = DriveUploadResult.Success("f_id", fileName)

            override suspend fun listSubfolders(
                accountEmail: String,
                parentFolderId: String
            ): Result<List<DriveFolder>> {
                return Result.success(subfoldersMap[parentFolderId] ?: emptyList())
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val mockContext = android.app.Application()

        // 1. Fetch root subfolders
        val rootFolders = manager.fetchSubfolders(mockContext, "root").getOrThrow()
        assertEquals(2, rootFolders.size)
        assertEquals("Work", rootFolders[0].name)
        assertEquals("Personal", rootFolders[1].name)

        // 2. Navigate into "Work" folder
        val workSubfolders = manager.fetchSubfolders(mockContext, "folder_work").getOrThrow()
        assertEquals(2, workSubfolders.size)
        assertEquals("Projects", workSubfolders[0].name)
        assertEquals("Reports", workSubfolders[1].name)
    }

    @Test
    fun testDistinguishFilesFromFolders_FileSelectionRejected() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult = DriveUploadResult.Success("id", fileName)

            override suspend fun getFolder(accountEmail: String, folderId: String): Result<DriveFolder> {
                // If user selected a file instead of a folder, reject it
                return if (folderId == "selected_image_file_id") {
                    Result.failure(IllegalArgumentException("Selected item 'photo.png' is a file (image/png), not a folder. Please choose a folder as the upload destination."))
                } else {
                    Result.success(DriveFolder(folderId, "Valid Folder"))
                }
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "My Note")
        val mockContext = android.app.Application()
        manager.startUploadFlow(mockContext, note)

        val result = manager.handlePickerSelectedFolder(mockContext, "selected_image_file_id", "photo.png", note)

        // Must fail and reject file
        assertTrue("Selecting a file must fail", result.isFailure)
        assertTrue("Error message must explain item is a file", result.exceptionOrNull()?.message?.contains("is a file") == true)

        // Upload state must be Error, NOT proceed to upload
        val state = manager.uploadState.value
        assertTrue("State must be Error", state is DriveUploadState.Error)
        assertTrue((state as DriveUploadState.Error).message.contains("is a file"))
    }

    @Test
    fun testDistinguishFilesFromFolders_FolderSelectionAccepted() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult = DriveUploadResult.Success("id", fileName)

            override suspend fun getFolder(accountEmail: String, folderId: String): Result<DriveFolder> {
                return Result.success(DriveFolder(folderId, "Design Assets Folder"))
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "Design Draft")
        val mockContext = android.app.Application()
        manager.startUploadFlow(mockContext, note)

        val result = manager.handlePickerSelectedFolder(mockContext, "valid_folder_123", "Design Assets Folder", note)

        assertTrue("Selecting a valid folder must succeed", result.isSuccess)
        val folder = result.getOrThrow()
        assertEquals("valid_folder_123", folder.id)
        assertEquals("Design Assets Folder", folder.name)

        // Upload state must now be AwaitingConfirmation with Upload button ready
        val state = manager.uploadState.value
        assertTrue("State must be AwaitingConfirmation", state is DriveUploadState.AwaitingConfirmation)
        val confirmation = state as DriveUploadState.AwaitingConfirmation
        assertEquals("Design Assets Folder", confirmation.selectedFolder.name)
        assertEquals("Design_Draft.pdf", confirmation.fileName)
    }

    @Test
    fun testCancelUploadResetsToIdleWithoutUploading() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var uploadCalled = false

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult {
                uploadCalled = true
                return DriveUploadResult.Success("file_id", fileName)
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "Cancelled Note")
        val mockContext = android.app.Application()

        manager.startUploadFlow(mockContext, note)
        val targetFolder = DriveFolder("target_folder_1", "Target Folder")
        manager.onFolderSelected(note, targetFolder)

        assertTrue(manager.uploadState.value is DriveUploadState.AwaitingConfirmation)

        // User clicks Cancel
        manager.cancelUpload()

        assertEquals(DriveUploadState.Idle, manager.uploadState.value)
        assertFalse("Upload must NOT have been called", uploadCalled)
    }

    @Test
    fun testUploadingToExactSelectedFolder() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var actualTargetFolderId: String? = null

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult {
                actualTargetFolderId = folderId
                return DriveUploadResult.Success(
                    fileId = "uploaded_exact_id",
                    fileName = fileName,
                    folderId = folderId,
                    folderName = "Exact Destination Folder"
                )
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "Strict Destination Note")
        val mockContext = android.app.Application()

        val exactFolder = DriveFolder(id = "exact_drive_folder_id_777", name = "Exact Destination Folder")
        manager.onFolderSelected(note, exactFolder)

        val result = manager.confirmAndUpload(mockContext, note, exactFolder)

        assertTrue(result is DriveUploadResult.Success)
        assertEquals("exact_drive_folder_id_777", actualTargetFolderId)
        val success = result as DriveUploadResult.Success
        assertEquals("exact_drive_folder_id_777", success.folderId)
        assertEquals("Exact Destination Folder", success.folderName)
    }

    @Test
    fun testSelectFolderWithoutOpeningIt() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )
        val note = NotebookItem(title = "Folder Row Select Note")

        // Directly select a folder without opening or navigating into it
        val directFolder = DriveFolder(id = "folder_direct_select_42", name = "Physics 101")
        manager.onFolderSelected(note, directFolder)

        val state = manager.uploadState.value
        assertTrue("State must transition to AwaitingConfirmation", state is DriveUploadState.AwaitingConfirmation)
        val confirmation = state as DriveUploadState.AwaitingConfirmation
        assertEquals("folder_direct_select_42", confirmation.selectedFolder.id)
        assertEquals("Physics 101", confirmation.selectedFolder.name)
        assertEquals("Folder_Row_Select_Note.pdf", confirmation.fileName)
    }

    @Test
    fun testNavigateIntoFolderAndSelectCurrentFolder() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        val subfolderItem = DriveFolder("subfolder_nested_101", "Lab Reports")

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(accountEmail: String, fileName: String, mimeType: String, contentBytes: ByteArray, folderId: String?): DriveUploadResult {
                return DriveUploadResult.Success("file_abc", fileName, folderId = folderId)
            }
            override suspend fun listSubfolders(accountEmail: String, parentFolderId: String): Result<List<DriveFolder>> {
                return if (parentFolderId == "parent_root") {
                    Result.success(listOf(subfolderItem))
                } else {
                    Result.success(emptyList())
                }
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val dummyContext = android.app.Application()

        // User navigates into parent_root and fetches subfolders
        val fetchResult = manager.fetchSubfolders(dummyContext, "parent_root")
        assertTrue(fetchResult.isSuccess)
        val folders = fetchResult.getOrThrow()
        assertEquals(1, folders.size)
        assertEquals("subfolder_nested_101", folders[0].id)

        // User opens and selects this current browsed folder
        val note = NotebookItem(title = "Chemistry Lab")
        manager.onFolderSelected(note, folders[0])

        val state = manager.uploadState.value
        assertTrue(state is DriveUploadState.AwaitingConfirmation)
        val confirmation = state as DriveUploadState.AwaitingConfirmation
        assertEquals("subfolder_nested_101", confirmation.selectedFolder.id)
        assertEquals("Lab Reports", confirmation.selectedFolder.name)
    }

    @Test
    fun testRejectFileAsFolderDestination() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var uploadAttempted = false

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(accountEmail: String, fileName: String, mimeType: String, contentBytes: ByteArray, folderId: String?): DriveUploadResult {
                uploadAttempted = true
                return DriveUploadResult.Success("file_123", fileName)
            }
            override suspend fun getFolder(accountEmail: String, folderId: String): Result<DriveFolder> {
                // Return failure simulating Drive API check detecting it's an image/doc, not a folder
                return Result.failure(IllegalArgumentException("Selected item 'photo.png' is a file (image/png), not a folder. Please choose a folder as the upload destination."))
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val dummyContext = android.app.Application()
        val note = NotebookItem(title = "Validation Note")

        val fileDestination = DriveFolder("file_id_photo_png", "photo.png")
        val result = manager.confirmAndUpload(dummyContext, note, fileDestination)

        assertTrue("Upload must fail when target destination is a file", result is DriveUploadResult.Error)
        val error = result as DriveUploadResult.Error
        assertTrue(error.message.contains("not a folder"))
        assertFalse("Actual Drive upload must NEVER be called for a file destination", uploadAttempted)

        val state = manager.uploadState.value
        assertTrue(state is DriveUploadState.Error)
        assertTrue((state as DriveUploadState.Error).message.contains("not a folder"))
    }

    @Test
    fun testPreventUploadWhenNoDestinationSelected() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var uploadAttempted = false

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(accountEmail: String, fileName: String, mimeType: String, contentBytes: ByteArray, folderId: String?): DriveUploadResult {
                uploadAttempted = true
                return DriveUploadResult.Success("file_123", fileName)
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val dummyContext = android.app.Application()
        val note = NotebookItem(title = "No Folder Note")

        // 1. confirmAndUpload with null folder
        val confirmResult = manager.confirmAndUpload(dummyContext, note, folder = null)
        assertTrue("Upload must fail when folder is null", confirmResult is DriveUploadResult.Error)
        val confirmError = confirmResult as DriveUploadResult.Error
        assertTrue(confirmError.message.contains("No destination folder selected"))
        assertFalse(uploadAttempted)

        // 2. uploadSelectedNotebook with null folder (no silent fallback to root!)
        val directResult = manager.uploadSelectedNotebook(dummyContext, note, destinationFolder = null)
        assertTrue("uploadSelectedNotebook must fail when destinationFolder is null", directResult is DriveUploadResult.Error)
        val directError = directResult as DriveUploadResult.Error
        assertTrue(directError.message.contains("No destination folder selected"))
        assertFalse("Drive upload must not be called when destination is unselected", uploadAttempted)
    }

    @Test
    fun testUploadToNestedFolderUsingCorrectParentId() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var passedFolderId: String? = null

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(accountEmail: String, fileName: String, mimeType: String, contentBytes: ByteArray, folderId: String?): DriveUploadResult {
                passedFolderId = folderId
                return DriveUploadResult.Success("nested_upload_id", fileName, folderId = folderId)
            }
            override suspend fun getFolder(accountEmail: String, folderId: String): Result<DriveFolder> {
                return Result.success(DriveFolder(folderId, "Calculus 2026"))
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val dummyContext = android.app.Application()
        val note = NotebookItem(title = "Calculus Lecture 1")

        val nestedFolder = DriveFolder("nested_folder_calc_987", "Calculus 2026")
        val result = manager.confirmAndUpload(dummyContext, note, nestedFolder)

        assertTrue(result is DriveUploadResult.Success)
        assertEquals("Selected nested folder ID must reach uploader unchanged", "nested_folder_calc_987", passedFolderId)
        assertNotEquals("Nested upload must not use root", "root", passedFolderId)
        assertNotEquals("Nested upload must not use folder name", "Calculus 2026", passedFolderId)
    }

    @Test
    fun testExplicitRootUploadToMyDrive() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var passedFolderId: String? = null

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(accountEmail: String, fileName: String, mimeType: String, contentBytes: ByteArray, folderId: String?): DriveUploadResult {
                passedFolderId = folderId
                return DriveUploadResult.Success("root_upload_id", fileName, folderId = folderId)
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val dummyContext = android.app.Application()
        val note = NotebookItem(title = "Explicit Root Note")

        val result = manager.uploadNotebookToMyDrive(dummyContext, note)

        assertTrue(result is DriveUploadResult.Success)
        assertEquals("Explicit root upload must target root", "root", passedFolderId)
        val state = manager.uploadState.value
        assertTrue(state is DriveUploadState.Success)
        assertEquals("root", (state as DriveUploadState.Success).result.folderId)
    }

    @Test
    fun testHandlePickerDocumentPayload_PickedActionExtractsFolderIdAndName() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(accountEmail: String, fileName: String, mimeType: String, contentBytes: ByteArray, folderId: String?): DriveUploadResult =
                DriveUploadResult.Success("id", fileName)

            override suspend fun getFolder(accountEmail: String, folderId: String): Result<DriveFolder> {
                return Result.success(DriveFolder(folderId, "PreExisting Drive Folder"))
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val dummyContext = android.app.Application()
        val note = NotebookItem(title = "Picker Test Note")
        manager.startUploadFlow(dummyContext, note)

        val pickerJson = """
            {
                "action": "picked",
                "docs": [
                    {
                        "id": "picker_folder_777",
                        "name": "PreExisting Drive Folder",
                        "mimeType": "application/vnd.google-apps.folder"
                    }
                ]
            }
        """.trimIndent()

        val result = manager.handlePickerDocumentPayload(dummyContext, pickerJson, note)

        assertTrue("handlePickerDocumentPayload must succeed for PICKED folder", result.isSuccess)
        val folder = result.getOrThrow()
        assertEquals("picker_folder_777", folder.id)
        assertEquals("PreExisting Drive Folder", folder.name)

        // Upload state must be AwaitingConfirmation
        val state = manager.uploadState.value
        assertTrue("State must be AwaitingConfirmation", state is DriveUploadState.AwaitingConfirmation)
        val confirmation = state as DriveUploadState.AwaitingConfirmation
        assertEquals("picker_folder_777", confirmation.selectedFolder.id)
        assertEquals("PreExisting Drive Folder", confirmation.selectedFolder.name)
    }

    @Test
    fun testHandlePickerResult_IntentExtrasExtractsFolder() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(accountEmail: String, fileName: String, mimeType: String, contentBytes: ByteArray, folderId: String?): DriveUploadResult =
                DriveUploadResult.Success("id", fileName)

            override suspend fun getFolder(accountEmail: String, folderId: String): Result<DriveFolder> {
                return Result.success(DriveFolder(folderId, "Intent Selected Folder"))
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val dummyContext = android.app.Application()
        val note = NotebookItem(title = "Intent Note")
        manager.startUploadFlow(dummyContext, note)

        val intent = object : android.content.Intent() {
            override fun getStringExtra(name: String): String? = when (name) {
                "folder_id" -> "intent_folder_999"
                "folder_name" -> "Intent Selected Folder"
                else -> null
            }
        }

        val result = manager.handlePickerResult(dummyContext, intent, note)

        assertTrue("handlePickerResult must succeed with Intent extras", result.isSuccess)
        val folder = result.getOrThrow()
        assertEquals("intent_folder_999", folder.id)
        assertEquals("Intent Selected Folder", folder.name)
    }

    @Test
    fun testPreExistingFolderCanBeSelectedValidatedAndUsedAsParentFolder() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var uploadedFolderId: String? = null

        val mockUploader = object : DriveApiV3Uploader {
            override suspend fun upload(accountEmail: String, fileName: String, mimeType: String, contentBytes: ByteArray, folderId: String?): DriveUploadResult {
                uploadedFolderId = folderId
                return DriveUploadResult.Success("file_id_888", fileName, folderId = folderId, folderName = "Pre-Existing Web Folder")
            }

            override suspend fun getFolder(accountEmail: String, folderId: String): Result<DriveFolder> {
                return Result.success(DriveFolder(folderId, "Pre-Existing Web Folder"))
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { mockUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
        val dummyContext = android.app.Application()
        val note = NotebookItem(title = "Pre-Existing Folder Note")

        // 1. Select the pre-existing folder
        val preExistingFolder = DriveFolder(id = "pre_existing_folder_888", name = "Pre-Existing Web Folder")
        manager.onFolderSelected(note, preExistingFolder)

        // 2. Confirm and upload
        val result = manager.confirmAndUpload(dummyContext, note, preExistingFolder)

        assertTrue("Upload must succeed", result is DriveUploadResult.Success)
        assertEquals("Pre-existing folder ID must reach uploader as parent", "pre_existing_folder_888", uploadedFolderId)
        assertNotEquals("Must NEVER fall back to root for pre-existing folder", "root", uploadedFolderId)
    }

    @Test
    fun testNotePdfGenerator_PreservesOriginalPdfBytesWhenImported() {
        val dummyPdfFile = java.io.File.createTempFile("imported_test", ".pdf")
        val samplePdfContent = "%PDF-1.4 original genuine pdf bytes %%EOF".toByteArray(Charsets.ISO_8859_1)
        dummyPdfFile.writeBytes(samplePdfContent)

        val importedNote = NotebookItem(
            title = "Genuine PDF Note",
            filePath = dummyPdfFile.absolutePath,
            mimeType = "application/pdf",
            originalFileName = "SampleDoc.pdf"
        )

        val generatedBytes = NotePdfGenerator.generatePdf(importedNote)
        assertEquals(String(samplePdfContent, Charsets.ISO_8859_1), String(generatedBytes, Charsets.ISO_8859_1))

        dummyPdfFile.delete()
    }

    @Test
    fun testBuildPickerAuthorizationRequest_VerifiesScopeAndDocumentedParameters() {
        secureStorage.putString("google_drive_authorized_account_email", "user@example.com")
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )

        val request = manager.buildPickerAuthorizationRequest()
        assertNotNull("AuthorizationRequest must not be null", request)
        assertTrue("Must request drive.file scope", request.requestedScopes.any { it.scopeUri.contains("drive.file") })
        assertTrue("Must opt out including granted scopes for picker isolation", request.optOutIncludingGrantedScopes)
        assertEquals(com.google.android.gms.auth.api.identity.AuthorizationRequest.Prompt.CONSENT, request.prompt)
    }

    @Test
    fun testHandlePickerResult_MissingSelection_FailsExplicitlyWithoutSilentFallbackToRoot() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "user@example.com")
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )
        val dummyContext = android.app.Application()
        val note = NotebookItem(title = "Note With Empty Picker")
        manager.startUploadFlow(dummyContext, note)

        // Empty intent with no folder_id, no picked_file_ids, no document payload
        val emptyIntent = object : android.content.Intent() {
            override fun getStringExtra(name: String): String? = null
        }

        val result = manager.handlePickerResult(dummyContext, emptyIntent, note)

        assertTrue("Result must be failure when no folder is selected", result.isFailure)
        val state = manager.uploadState.value
        assertTrue("Upload state must be Error when selection is missing", state is DriveUploadState.Error)
        val errorState = state as DriveUploadState.Error
        assertTrue("Error message must indicate no folder was selected", errorState.message.contains("No folder"))
        assertNotEquals("Must NEVER silently transition to root on missing selection", DriveFolder.ROOT, errorState.retryFolder)
    }

    @Test
    fun testHandlePickerResult_NullIntent_FailsExplicitly() = runBlocking {
        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )
        val dummyContext = android.app.Application()
        val note = NotebookItem(title = "Null Intent Note")
        manager.startUploadFlow(dummyContext, note)

        val result = manager.handlePickerResult(dummyContext, null, note)

        assertTrue("Null intent must return failure", result.isFailure)
        val state = manager.uploadState.value
        assertTrue("Upload state must be Error for null intent", state is DriveUploadState.Error)
    }

    @Test
    fun testImportDriveCloudFolder_ZeroLocalStorageStreaming() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "user@example.com")
        val fakeUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult = DriveUploadResult.Success(fileId = "remote_id", fileName = fileName)

            override suspend fun listFiles(accountEmail: String, folderId: String): Result<List<DriveRemoteFile>> {
                return Result.success(
                    listOf(
                        DriveRemoteFile("remote_pdf_1", "Quantum Mechanics.pdf", "application/pdf", 1024L),
                        DriveRemoteFile("remote_pdf_2", "Algorithms.pdf", "application/pdf", 2048L),
                        DriveRemoteFile("not_a_pdf", "notes.txt", "text/plain", 500L)
                    )
                )
            }

            override suspend fun downloadFile(accountEmail: String, fileId: String): Result<ByteArray> {
                return Result.success("streamed_pdf_bytes".toByteArray())
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { fakeUploader },
            ioDispatcher = Dispatchers.Unconfined
        )

        val folder = DriveFolder("drive_folder_123", "Science Books")
        val dummyContext = android.app.Application()
        val result = manager.importDriveCloudFolder(dummyContext, folder)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())

        val cloudBooks = com.graphite.app.data.repository.GraphiteRepository.books.value.filter { it.driveFolderId == "drive_folder_123" }
        assertEquals(2, cloudBooks.size)

        for (book in cloudBooks) {
            assertNull("Cloud books must NEVER have persistent local filePath (0 MB local footprint)", book.filePath)
            assertNotNull("Cloud book must retain driveFileId for streaming", book.driveFileId)
            assertTrue(book.isCloudOnly)
        }

        val streamRes = manager.streamFileBytes(dummyContext, cloudBooks.first().driveFileId!!)
        assertTrue("Streaming on demand must succeed", streamRes.isSuccess)
        assertEquals("streamed_pdf_bytes", String(streamRes.getOrNull()!!))
    }

    @Test
    fun testImportDriveCloudFolder_PropagatesListingFailure() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "user@example.com")
        val fakeUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult = DriveUploadResult.Success(fileId = "remote_id", fileName = fileName)

            override suspend fun listFiles(accountEmail: String, folderId: String): Result<List<DriveRemoteFile>> {
                return Result.failure(IllegalStateException("Drive 403 Forbidden - permission denied"))
            }
        }

        val manager = GoogleDriveManager(
            secureStorage = secureStorage,
            uploaderProvider = { fakeUploader },
            ioDispatcher = Dispatchers.Unconfined
        )

        val folder = DriveFolder("forbidden_folder", "Private Docs")
        val dummyContext = android.app.Application()
        val result = manager.importDriveCloudFolder(dummyContext, folder)

        assertTrue("Failure must be propagated to caller, never swallowed as 0", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Forbidden") == true || result.exceptionOrNull()?.message?.contains("permission denied") == true)
    }
}
