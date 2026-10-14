package com.graphite.app.core.importer

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.graphite.app.core.drive.ExportedNotebookPayload
import com.graphite.app.core.supabase.InkSerialization
import com.graphite.app.core.supabase.SupabaseAuthManager
import com.graphite.app.core.supabase.SupabaseSyncManager
import com.graphite.app.data.model.Book
import com.graphite.app.data.model.BookCoverType
import com.graphite.app.data.model.NotebookItem
import com.graphite.app.feature.workspace.model.PaperStyle
import com.graphite.app.data.repository.GraphiteRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.UUID

enum class ImportDestination {
    AUTO,
    NOTES,
    BOOKSHELF
}

sealed interface ImportResult {
    data class SuccessNotebook(
        val notebook: NotebookItem,
        val syncedToCloud: Boolean,
        val message: String = "Note imported successfully"
    ) : ImportResult

    data class SuccessBook(
        val book: Book,
        val pageCount: Int,
        val message: String = "PDF document imported to Bookshelf ($pageCount pages)"
    ) : ImportResult

    data class Error(
        val message: String
    ) : ImportResult
}

/**
 * Handles importing existing PDFs and Graphite note files from tablet storage
 * via Android's Storage Access Framework (SAF).
 *
 * CRITICAL ARCHITECTURAL CONSTRAINTS:
 * 1. Requires no broad storage permissions (no MANAGE_EXTERNAL_STORAGE).
 * 2. Reads via ContentResolver and handles content URIs correctly.
 * 3. Validates file signatures (%PDF- header) and deserializes genuine note formats.
 * 4. Never falsely converts PDF pages into handwriting strokes.
 * 5. Disambiguates duplicate file names to avoid overwriting existing items.
 * 6. Synchronizes imported notes to Supabase when authenticated; PDFs are stored locally.
 * 7. NEVER automatically uploads imported files to Google Drive.
 * 8. Strictly separates Notes imports (notebooks only) from Bookshelf imports (books only).
 */
class FileImportManager(
    private val graphiteRepository: GraphiteRepository = GraphiteRepository,
    private val supabaseAuthManager: SupabaseAuthManager? = null,
    private val supabaseSyncManager: SupabaseSyncManager? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Strictly imports a document (PDF or .graphite) into Noteshelf (notebooks).
     * NEVER adds to Bookshelf or Recents.
     */
    suspend fun importToNotes(
        context: Context,
        uri: Uri
    ): ImportResult = importFromUri(context, uri, target = ImportDestination.NOTES)

    /**
     * Strictly imports a document (PDF) into Bookshelf (books).
     * NEVER adds to Noteshelf.
     */
    suspend fun importToBookshelf(
        context: Context,
        uri: Uri
    ): ImportResult = importFromUri(context, uri, target = ImportDestination.BOOKSHELF)

    /**
     * Imports a document selected by the user via ActivityResultContracts.OpenDocument.
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        target: ImportDestination = ImportDestination.AUTO
    ): ImportResult = withContext(ioDispatcher) {
        try {
            // Persist URI read permission if supported by the provider
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some providers (e.g. virtual or temporary) do not support persistable permissions
            }

            var displayName = "Imported_Document"
            var fileSize = 0L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        cursor.getString(nameIndex)?.let { displayName = it }
                    }
                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }

            // Reject files larger than 50MB to prevent memory exhaustion
            if (fileSize > 50 * 1024 * 1024) {
                return@withContext ImportResult.Error("File is too large (${fileSize / (1024 * 1024)}MB). Maximum import size is 50MB.")
            }

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.Error("Unable to open input stream for selected file.")

            val bytes = inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) {
                return@withContext ImportResult.Error("The selected file is empty (0 bytes).")
            }

            val mimeType = context.contentResolver.getType(uri)
            importFromBytes(
                context = context,
                displayName = displayName,
                mimeType = mimeType,
                bytes = bytes,
                target = target
            )
        } catch (e: Exception) {
            ImportResult.Error("Failed to import file: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * Inspects byte content and MIME type, routes to PDF or Graphite Note deserializer,
     * and persists into local library according to the designated target.
     */
    suspend fun importFromBytes(
        context: Context,
        displayName: String,
        mimeType: String?,
        bytes: ByteArray,
        target: ImportDestination = ImportDestination.AUTO
    ): ImportResult = withContext(ioDispatcher) {
        val isPdfByMime = mimeType?.equals("application/pdf", ignoreCase = true) == true
        val isPdfByExtension = displayName.endsWith(".pdf", ignoreCase = true)
        val isPdfByHeader = isPdfHeader(bytes)
        val isPdf = isPdfByMime || isPdfByExtension || isPdfByHeader

        when (target) {
            ImportDestination.NOTES -> {
                // Strictly importing into Noteshelf
                val noteResult = tryImportGraphiteNote(displayName, bytes)
                if (noteResult != null) {
                    return@withContext noteResult
                }
                // Import any document (PDF, DOCX, PPTX, XLSX, etc.) into notes with format intact
                return@withContext importDocumentAsNotebook(context, displayName, mimeType, bytes, isPdf)
            }

            ImportDestination.BOOKSHELF -> {
                // Strictly importing into Bookshelf (PDF, DOCX, PPTX, XLSX, etc.)
                return@withContext importDocumentToBookshelf(context, displayName, mimeType, bytes, isPdf)
            }

            ImportDestination.AUTO -> {
                if (isPdf) {
                    return@withContext importDocumentToBookshelf(context, displayName, mimeType, bytes, true)
                }
                val noteResult = tryImportGraphiteNote(displayName, bytes)
                if (noteResult != null) {
                    return@withContext noteResult
                }
                val ext = displayName.substringAfterLast('.', "").lowercase()
                if (ext in listOf("docx", "doc", "pptx", "ppt", "xlsx", "xls", "rtf", "csv")) {
                    return@withContext importDocumentToBookshelf(context, displayName, mimeType, bytes, false)
                }
                ImportResult.Error(
                    "Unsupported file format: \"$displayName\". Graphite supports PDF documents (.pdf), Word (.docx), PowerPoint (.pptx), Excel (.xlsx), and Graphite notes (.graphite)."
                )
            }
        }
    }

    /**
     * Imports a document (PDF, DOCX, PPTX, XLSX, etc.) as a Note in Noteshelf (notebooks),
     * keeping the original file format intact in app storage and avoiding adding it to Bookshelf or Recents.
     */
    private suspend fun importDocumentAsNotebook(
        context: Context,
        displayName: String,
        mimeType: String?,
        bytes: ByteArray,
        isPdf: Boolean
    ): ImportResult {
        if (isPdf && !isPdfHeader(bytes)) {
            return ImportResult.Error("Invalid or corrupted PDF file: missing \"%PDF-\" signature header.")
        }

        val notesDir = File(context.filesDir, "imported_notes").apply { mkdirs() }
        val ext = displayName.substringAfterLast('.', "")
        val safeFileName = "${UUID.randomUUID()}_${displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
        val targetFile = File(notesDir, safeFileName)

        try {
            targetFile.writeBytes(bytes)

            var pageCount = 1
            if (isPdf) {
                try {
                    val pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    pageCount = renderer.pageCount
                    renderer.close()
                    pfd.close()
                } catch (_: Exception) {
                    // If PdfRenderer fails (e.g. in JVM test environment), default to 1 page
                }
            }

            val rawTitle = if (displayName.contains('.')) displayName.substringBeforeLast('.') else displayName
            val disambiguatedTitle = graphiteRepository.disambiguateNoteTitle(rawTitle)
            val resolvedMimeType = mimeType ?: resolveMimeType(displayName)

            val importedNote = NotebookItem(
                id = UUID.randomUUID().toString(),
                title = disambiguatedTitle,
                paperStyle = PaperStyle.RULED,
                previewLines = listOf(displayName, if (isPdf) "$pageCount pages" else "Format: ${ext.uppercase()}"),
                initialStrokes = emptyList(),
                pageCount = pageCount,
                createdAtMillis = System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis(),
                filePath = targetFile.absolutePath,
                mimeType = resolvedMimeType,
                originalFileName = displayName
            )

            val savedNote = graphiteRepository.addImportedNotebook(importedNote)

            var syncedToCloud = false
            if (supabaseAuthManager?.isAuthenticated() == true && supabaseSyncManager != null) {
                val syncResult = supabaseSyncManager.syncNotes()
                syncedToCloud = syncResult.isSuccess
            }

            return ImportResult.SuccessNotebook(
                notebook = savedNote,
                syncedToCloud = syncedToCloud,
                message = "Imported note \"${savedNote.title}\" (${if (isPdf) "$pageCount pages" else ext.uppercase()})"
            )
        } catch (e: Exception) {
            if (targetFile.exists()) targetFile.delete()
            return ImportResult.Error("Error saving note file to app storage: ${e.message}")
        }
    }

    /**
     * Imports a document (PDF, DOCX, PPTX, XLSX, etc.) into Bookshelf,
     * keeping the original file format intact in app storage.
     */
    private fun importDocumentToBookshelf(
        context: Context,
        displayName: String,
        mimeType: String?,
        bytes: ByteArray,
        isPdf: Boolean
    ): ImportResult {
        if (isPdf && !isPdfHeader(bytes)) {
            return ImportResult.Error("Invalid or corrupted PDF file: missing \"%PDF-\" signature header.")
        }

        val docsDir = File(context.filesDir, "imported_documents").apply { mkdirs() }
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val safeFileName = "${UUID.randomUUID()}_${displayName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
        val targetFile = File(docsDir, safeFileName)

        try {
            targetFile.writeBytes(bytes)

            var pageCount = 1
            if (isPdf) {
                try {
                    val pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    pageCount = renderer.pageCount
                    renderer.close()
                    pfd.close()
                } catch (_: Exception) {
                    // If PdfRenderer is unavailable (e.g. in JVM unit tests), default to 1 page
                }
            }

            val rawTitle = if (displayName.contains('.')) displayName.substringBeforeLast('.') else displayName
            val disambiguatedTitle = graphiteRepository.disambiguateBookTitle(rawTitle)
            val resolvedMimeType = mimeType ?: resolveMimeType(displayName)

            val coverType = when {
                ext in listOf("pptx", "ppt") -> BookCoverType.CE_PPT
                else -> BookCoverType.CUSTOM
            }

            val importedBook = Book(
                id = UUID.randomUUID().toString(),
                title = disambiguatedTitle,
                author = "Imported Document",
                progressPercent = 0,
                coverType = coverType,
                totalChapters = pageCount,
                currentChapter = 1,
                content = "Imported Document: $displayName ($resolvedMimeType)",
                filePath = targetFile.absolutePath,
                mimeType = resolvedMimeType
            )

            graphiteRepository.addBook(importedBook)

            return ImportResult.SuccessBook(
                book = importedBook,
                pageCount = pageCount,
                message = "Imported \"$disambiguatedTitle\" (${ext.uppercase()}) to Bookshelf"
            )
        } catch (e: Exception) {
            if (targetFile.exists()) targetFile.delete()
            return ImportResult.Error("Error saving document to app storage: ${e.message}")
        }
    }

    private fun resolveMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "ppt" -> "application/vnd.ms-powerpoint"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "graphite" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    /**
     * Deserializes a genuine Graphite notebook payload, preserving handwriting strokes,
     * paper styles, and metadata.
     */
    private suspend fun tryImportGraphiteNote(
        displayName: String,
        bytes: ByteArray
    ): ImportResult? {
        val text = try {
            bytes.toString(Charsets.UTF_8).trim()
        } catch (_: Exception) {
            return null
        }

        if (!text.startsWith("{") || !text.endsWith("}")) {
            return null
        }

        return try {
            val payload = json.decodeFromString<ExportedNotebookPayload>(text)
            val strokes = InkSerialization.deserializeStrokes(payload.strokesJson)

            val paperStyle = PaperStyle.entries.find {
                it.name.equals(payload.paperStyle, ignoreCase = true)
            } ?: PaperStyle.RULED

            val baseTitle = payload.title.ifBlank {
                displayName.removeSuffix(".graphite").removeSuffix(".json")
            }

            val importedNote = NotebookItem(
                id = UUID.randomUUID().toString(),
                title = baseTitle,
                paperStyle = paperStyle,
                previewLines = payload.previewLines.ifEmpty { listOf("Imported Note") },
                initialStrokes = strokes,
                pageCount = 1,
                createdAtMillis = System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis()
            )

            val savedNote = graphiteRepository.addImportedNotebook(importedNote)

            // Sync with Supabase if online and authenticated
            var syncedToCloud = false
            if (supabaseAuthManager?.isAuthenticated() == true && supabaseSyncManager != null) {
                val syncResult = supabaseSyncManager.syncNotes()
                syncedToCloud = syncResult.isSuccess
            }

            ImportResult.SuccessNotebook(
                notebook = savedNote,
                syncedToCloud = syncedToCloud
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks if byte array starts with "%PDF-" signature (0x25 0x50 0x44 0x46 0x2D).
     */
    private fun isPdfHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        return bytes[0] == 0x25.toByte() && // %
                bytes[1] == 0x50.toByte() && // P
                bytes[2] == 0x44.toByte() && // D
                bytes[3] == 0x46.toByte() && // F
                bytes[4] == 0x2D.toByte()    // -
    }
}
