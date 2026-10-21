package com.graphite.app.data.model

import com.graphite.app.feature.workspace.model.InkStroke
import com.graphite.app.feature.workspace.model.PaperStyle
import java.util.UUID

data class Book(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String,
    val progressPercent: Int = 0,
    val coverType: BookCoverType = BookCoverType.TREASURE_ISLAND,
    val lastReadTimeMillis: Long = System.currentTimeMillis(),
    val totalChapters: Int = 34,
    val currentChapter: Int = 1,
    val content: String = "",
    val filePath: String? = null,
    val mimeType: String = "text/plain",
    val isBookmarked: Boolean = false,
    val isFavorite: Boolean = false,
    val driveFileId: String? = null,
    val userId: String? = null
)

enum class BookCoverType {
    TREASURE_ISLAND,
    CE_PPT,
    CUSTOM
}

data class NotebookCover(
    val category: String = "PLAIN", // "PLAIN", "NO_COVER", "CUSTOM", "BAND", "PALETTE", "COMPOSITION", "ABSTRACT", "RETRO"
    val colorHex: Long = 0xFF45A5B5, // Default Teal
    val name: String = "Teal",
    val bandColorHex: Long? = null,
    val stripeColorHexes: List<Long>? = null,
    val patternType: String? = null,
    val customImageUri: String? = null
)

data class NotebookItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val folderId: String = "my_notes",
    val pageCount: Int = 1,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val updatedAtFormatted: String = "12/10/26 12:35 pm",
    val paperStyle: PaperStyle = PaperStyle.RULED,
    val isStarred: Boolean = false,
    val isDeleted: Boolean = false,
    val isBookmarked: Boolean = false,
    val hasPhotos: Boolean = false,
    val hasRecordings: Boolean = false,
    val isLocked: Boolean = false,
    val version: Long = 1L,
    val userId: String? = null,
    val previewLines: List<String> = emptyList(),
    val initialStrokes: List<InkStroke> = emptyList(),
    val filePath: String? = null,
    val mimeType: String? = null,
    val originalFileName: String? = null,
    val cover: NotebookCover = NotebookCover(),
    val voiceNotes: List<com.graphite.app.core.voice.VoiceClip> = emptyList(),
    val password: String? = null,
    val driveFileId: String? = null,
    val isUploadedToDrive: Boolean = false
)

data class FolderItem(
    val id: String,
    val name: String,
    val noteCount: Int,
    val parentId: String? = null,
    val colorHex: Long? = null
)


data class ReadingStats(
    val totalHoursRead: Float = 0.1f,
    val totalBooks: Int = 2,
    val notesCount: Int = 39
)
