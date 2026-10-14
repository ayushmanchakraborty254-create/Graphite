package com.graphite.app.core.drive

import com.graphite.app.data.model.NotebookItem

data class DriveFolder(
    val id: String,
    val name: String
) {
    companion object {
        val ROOT = DriveFolder("root", "My Drive (Root)")
    }
}

sealed interface DriveAuthStatus {
    data object NotAuthorized : DriveAuthStatus
    data object Authorizing : DriveAuthStatus
    data class Authorized(val accountEmail: String) : DriveAuthStatus
}

sealed interface DriveUploadResult {
    data class Success(
        val fileId: String,
        val fileName: String,
        val folderId: String? = null,
        val folderName: String? = null,
        val webViewLink: String? = null
    ) : DriveUploadResult

    data class Error(
        val message: String,
        val requiresReauth: Boolean = false
    ) : DriveUploadResult
}

sealed interface DriveUploadState {
    data object Idle : DriveUploadState

    /**
     * User needs to choose a destination folder before uploading.
     */
    data class SelectingFolder(
        val notebook: NotebookItem,
        val availableFolders: List<DriveFolder> = emptyList(),
        val selectedFolder: DriveFolder? = null
    ) : DriveUploadState

    /**
     * User has selected a destination folder; waiting for final confirmation before starting upload.
     */
    data class AwaitingConfirmation(
        val notebook: NotebookItem,
        val fileName: String,
        val selectedFolder: DriveFolder
    ) : DriveUploadState

    /**
     * Actively uploading to Google Drive. Prevents duplicate requests.
     */
    data class Uploading(
        val notebookTitle: String,
        val destinationFolder: DriveFolder
    ) : DriveUploadState

    /**
     * Upload finished successfully.
     */
    data class Success(
        val result: DriveUploadResult.Success
    ) : DriveUploadState

    /**
     * Upload failed.
     */
    data class Error(
        val message: String,
        val retryNotebook: NotebookItem? = null,
        val retryFolder: DriveFolder? = null
    ) : DriveUploadState
}
