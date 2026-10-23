package com.graphite.app.data.repository

import android.content.Context
import com.graphite.app.core.importer.DocumentContentParser
import com.graphite.app.data.model.*
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkStroke
import com.graphite.app.feature.workspace.model.InkToolType
import com.graphite.app.feature.workspace.model.PaperStyle
import com.graphite.app.feature.workspace.model.WorkspaceColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object GraphiteRepository {

    private val sampleTreasureIslandContent = """
        TREASURE ISLAND
        By Robert Louis Stevenson

        PART ONE—THE OLD BUCCANEER

        CHAPTER I: THE OLD SEA-DOG AT THE "ADMIRAL BENBOW"

        SQUIRE TRELAWNEY, Dr. Livesey, and the rest of these gentlemen having asked me to write down the whole particulars about Treasure Island, to the finish, keeping nothing back but the bearings of the island, and that only because there is still treasure not yet lifted, I take up my pen in the year of grace 17__ and go back to the time when my father kept the Admiral Benbow inn and the brown old seaman with the sabre cut first took up his lodging under our roof.

        I remember him as if it were yesterday, as he came plodding to the inn door, his sea-chest following behind him in a hand-barrow—a tall, strong, heavy, nut-brown man, his pig-tail falling over the shoulder of his soiled blue coat, his hands ragged and scarred, with black, broken nails, and the sabre cut across one cheek, a dirty, livid white. I remember him looking round the cove and whistling to himself as he did so, and then breaking out in that old sea-song that he sang so often afterwards:

        "Fifteen men on the dead man's chest—
        Yo-ho-ho, and a bottle of rum!"

        in the high, old tottering voice that seemed to have been tuned and broken at the capstan bars. Then he rapped on the door with a bit of stick like a handspike that he carried, and when my father appeared, called roughly for a glass of rum.
    """.trimIndent()

    private val sampleCePptContent = """
        CIVIL ENGINEERING & INFRASTRUCTURE
        Presentation Slides
        Author: Ayushman Chakraborty

        Slide 1: High-Performance Composite Materials in Modern Bridge Design
        • Carbon-fiber reinforced polymers (CFRP) vs Structural Steel.
        • Tensile strength to weight ratio considerations.
        • Dynamic load distribution under seismic excitation.

        Slide 2: Soil Mechanics & Deep Foundation Analysis
        • Non-linear strain degradation curves for cohesive clays.
        • Pile group efficiency factors and settlement predictions.
        • Lateral resistance modeling in saturated alluvial deposits.

        Slide 3: Environmental Impact & Sustainable Concrete
        • Geopolymer concrete utilizing industrial slag and fly ash.
        • Life-cycle carbon abatement strategies in megaprojects.
    """.trimIndent()

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    // Isolated notebook, book, and folder collections partitioned by user ID to guarantee strict multi-user privacy
    private val userNotebooksMap = mutableMapOf<String, MutableList<NotebookItem>>()
    private val userBooksMap = mutableMapOf<String, MutableList<Book>>()
    private val userFoldersMap = mutableMapOf<String, MutableList<FolderItem>>()
    private var activeUserId: String? = null

    private val _notebooks = MutableStateFlow<List<NotebookItem>>(emptyList())
    val notebooks: StateFlow<List<NotebookItem>> = _notebooks.asStateFlow()

    private val _folders = MutableStateFlow<List<FolderItem>>(emptyList())
    val folders: StateFlow<List<FolderItem>> = _folders.asStateFlow()

    private val _stats = MutableStateFlow(ReadingStats(totalHoursRead = 0.0f, totalBooks = 0, notesCount = 0))
    val stats: StateFlow<ReadingStats> = _stats.asStateFlow()

    fun getActiveUserId(): String? = activeUserId

    /**
     * Switches the active authenticated user, strictly isolating the visible workspace.
     * When signing out (userId == null), switches to the unauthenticated workspace:
     * clears notebooks, books, and folders completely.
     */
    fun setActiveUser(userId: String?) {
        activeUserId = userId

        if (userId == null) {
            // Unauthenticated state: completely clear visible notebooks, books, and folders!
            _notebooks.value = emptyList()
            _books.value = emptyList()
            _folders.value = emptyList()
            _stats.update {
                it.copy(notesCount = 0, totalBooks = 0)
            }
            return
        }

        val currentUid = userId

        // 1. Partitioned Notes: Load notes strictly for currentUid (NEVER migrate notes across accounts)
        val userNotes = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        for (i in userNotes.indices) {
            if (userNotes[i].folderId == "my_notes") {
                userNotes[i] = userNotes[i].copy(folderId = "unfiled")
            }
        }
        _notebooks.value = userNotes.filter { !it.isDeleted }

        // 2. Partitioned Books: Load books strictly for currentUid (NEVER migrate books across accounts)
        val userBooks = userBooksMap.getOrPut(currentUid) { mutableListOf() }
        _books.value = userBooks.toList()
        cleanBookDuplicatesAndGhosts()

        // 3. Partitioned Folders: Strictly isolate folders per account (no mock folders bleeding across accounts)
        val userFolders = userFoldersMap.getOrPut(currentUid) { mutableListOf() }
        userFolders.removeAll { it.id == "my_notes" || it.id == "lectures" || it.id == "projects" }
        userFoldersMap["default"]?.removeAll { it.id == "my_notes" || it.id == "lectures" || it.id == "projects" }
        _folders.value = userFolders.toList()

        _stats.update {
            it.copy(
                notesCount = _notebooks.value.size,
                totalBooks = _books.value.size
            )
        }
        saveToDisk()
    }

    /**
     * Clears cached private data and reverts to the "guest" workspace on sign out.
     */
    fun clearActiveUserWorkspace() {
        setActiveUser(null)
    }

    /**
     * Completely resets all in-memory repository state for testing.
     */
    fun clearAllDataForTesting() {
        userNotebooksMap.clear()
        userBooksMap.clear()
        userFoldersMap.clear()
        _notebooks.value = emptyList()
        _books.value = emptyList()
        _folders.value = emptyList()
        activeUserId = null
        _stats.value = ReadingStats(totalHoursRead = 0.0f, totalBooks = 0, notesCount = 0)
    }

    fun updateNotebookDriveStatus(notebookId: String, driveFileId: String?, isUploaded: Boolean = true) {
        val currentUid = activeUserId ?: "guest"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val idx = userList.indexOfFirst { it.id == notebookId }
        if (idx != -1) {
            val updated = userList[idx].copy(
                driveFileId = driveFileId,
                isUploadedToDrive = isUploaded,
                updatedAtMillis = System.currentTimeMillis()
            )
            userList[idx] = updated
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }

    fun getBook(id: String): Book? = _books.value.find { it.id == id }

    fun getNotebookAsBook(id: String): Book? {
        val directBook = _books.value.find { it.id == id }
        if (directBook != null) return directBook

        val note = getNotebook(id) ?: return null
        val ext = note.filePath?.substringAfterLast('.', "")?.lowercase() ?: ""
        val coverType = when {
            ext in listOf("pptx", "ppt") -> BookCoverType.CE_PPT
            else -> BookCoverType.CUSTOM
        }

        var parsedContent: String? = null
        var totalPages = maxOf(1, note.pageCount)

        if (!note.filePath.isNullOrBlank()) {
            val file = File(note.filePath)
            if (file.exists()) {
                try {
                    val bytes = file.readBytes()
                    val parsed = DocumentContentParser.parse(bytes, file.name, note.mimeType)
                    if (parsed.extractedText.isNotBlank()) {
                        parsedContent = parsed.extractedText
                    }
                    if (parsed.pageOrSlideCount > 0) {
                        totalPages = parsed.pageOrSlideCount
                    }
                } catch (_: Exception) {
                    // Fall back to note.previewLines
                }
            }
        }

        val resolvedContent = parsedContent ?: note.previewLines.joinToString("\n\n")

        return Book(
            id = note.id,
            title = note.title,
            author = if (note.mimeType == "application/pdf" || ext == "pdf") "PDF Document" else "Imported Document",
            progressPercent = 0,
            coverType = coverType,
            totalChapters = totalPages,
            currentChapter = 1,
            content = resolvedContent,
            filePath = note.filePath,
            mimeType = note.mimeType ?: if (ext == "pdf") "application/pdf" else "text/plain",
            isBookmarked = note.isBookmarked
        )
    }

    fun toggleBookmarkBook(id: String) {
        val currentUid = activeUserId ?: "guest"
        val userBookList = userBooksMap.getOrPut(currentUid) { mutableListOf() }
        for (i in userBookList.indices) {
            if (userBookList[i].id == id) {
                userBookList[i] = userBookList[i].copy(isBookmarked = !userBookList[i].isBookmarked)
            }
        }
        _books.value = userBookList.toList()
        saveToDisk()
    }

    fun toggleFavoriteBook(id: String) {
        val currentUid = activeUserId ?: "guest"
        val userBookList = userBooksMap.getOrPut(currentUid) { mutableListOf() }
        for (i in userBookList.indices) {
            if (userBookList[i].id == id) {
                userBookList[i] = userBookList[i].copy(isFavorite = !userBookList[i].isFavorite)
            }
        }
        _books.value = userBookList.toList()
        saveToDisk()
    }

    fun deleteBook(id: String) {
        val currentUid = activeUserId ?: "guest"
        val userBookList = userBooksMap.getOrPut(currentUid) { mutableListOf() }
        val currentBook = userBookList.find { it.id == id }
        currentBook?.filePath?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
        userBookList.removeAll { it.id == id }
        _books.value = userBookList.toList()
        _stats.update { it.copy(totalBooks = _books.value.size) }
        saveToDisk()
    }

    fun updateBookProgress(id: String, progress: Int) {
        val currentUid = activeUserId ?: "guest"
        val userBookList = userBooksMap.getOrPut(currentUid) { mutableListOf() }
        for (i in userBookList.indices) {
            if (userBookList[i].id == id) {
                userBookList[i] = userBookList[i].copy(
                    progressPercent = progress.coerceIn(0, 100),
                    lastReadTimeMillis = System.currentTimeMillis()
                )
            }
        }
        _books.value = userBookList.toList()
        saveToDisk()
    }

    fun addBook(title: String, author: String, content: String): Book {
        val newBook = Book(
            title = disambiguateBookTitle(title),
            author = author,
            progressPercent = 0,
            coverType = BookCoverType.CUSTOM,
            content = content,
            userId = activeUserId ?: "guest"
        )
        return addBook(newBook)
    }

    fun addBook(book: Book): Book {
        val currentUid = activeUserId ?: "guest"
        val userBookList = userBooksMap.getOrPut(currentUid) { mutableListOf() }
        val scopedBook = if (book.userId.isNullOrBlank()) book.copy(userId = currentUid) else book
        val normalized = normalizeBookTitle(scopedBook.title)
        val existingIndex = userBookList.indexOfFirst {
            it.id == scopedBook.id ||
            (scopedBook.driveFileId != null && it.driveFileId == scopedBook.driveFileId) ||
            normalizeBookTitle(it.title) == normalized
        }
        val resultingBook: Book
        if (existingIndex >= 0) {
            val existing = userBookList[existingIndex]
            val updated = existing.copy(
                filePath = scopedBook.filePath ?: existing.filePath,
                driveFileId = scopedBook.driveFileId ?: existing.driveFileId,
                driveFolderId = scopedBook.driveFolderId ?: existing.driveFolderId,
                driveFolderName = scopedBook.driveFolderName ?: existing.driveFolderName,
                mimeType = if (scopedBook.mimeType.isNotBlank()) scopedBook.mimeType else existing.mimeType,
                content = if (scopedBook.content.isNotBlank()) scopedBook.content else existing.content,
                totalChapters = if (scopedBook.totalChapters > 1) scopedBook.totalChapters else existing.totalChapters
            )
            userBookList[existingIndex] = updated
            resultingBook = updated
        } else {
            userBookList.add(0, scopedBook)
            resultingBook = scopedBook
        }
        _books.value = userBookList.toList()
        _stats.update { it.copy(totalBooks = _books.value.size) }
        saveToDisk()
        return resultingBook
    }

    fun disambiguateNoteTitle(baseTitle: String): String {
        val currentUid = activeUserId ?: "default"
        val existingTitles = (userNotebooksMap[currentUid] ?: emptyList())
            .filter { !it.isDeleted }
            .map { it.title }
            .toSet()
        if (!existingTitles.contains(baseTitle)) return baseTitle

        var counter = 1
        var candidate = "$baseTitle ($counter)"
        while (existingTitles.contains(candidate)) {
            counter++
            candidate = "$baseTitle ($counter)"
        }
        return candidate
    }

    fun disambiguateBookTitle(baseTitle: String): String {
        val existingTitles = _books.value.map { it.title }.toSet()
        if (!existingTitles.contains(baseTitle)) return baseTitle

        var counter = 1
        var candidate = "$baseTitle ($counter)"
        while (existingTitles.contains(candidate)) {
            counter++
            candidate = "$baseTitle ($counter)"
        }
        return candidate
    }

    fun addImportedNotebook(notebook: NotebookItem): NotebookItem {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val disambiguatedTitle = disambiguateNoteTitle(notebook.title)
        val finalNote = notebook.copy(
            id = UUID.randomUUID().toString(),
            userId = currentUid,
            title = disambiguatedTitle,
            createdAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis()
        )
        userList.add(0, finalNote)
        _notebooks.value = userList.filter { !it.isDeleted }
        _stats.update { it.copy(notesCount = _notebooks.value.size) }
        saveToDisk()
        return finalNote
    }

    fun getNotebook(id: String): NotebookItem? = _notebooks.value.find { it.id == id }

    fun createNotebook(
        title: String,
        paperStyle: PaperStyle = PaperStyle.RULED,
        folderId: String = "unfiled",
        customId: String = UUID.randomUUID().toString(),
        mimeType: String? = null,
        initialPreviewLines: List<String>? = null,
        cover: NotebookCover = NotebookCover(),
        password: String? = null
    ): NotebookItem {
        val now = System.currentTimeMillis()
        val formatter = SimpleDateFormat("dd/MM/yy hh:mm a", Locale.getDefault())
        val currentUid = activeUserId ?: "default"

        val newNotebook = NotebookItem(
            id = customId,
            userId = currentUid,
            title = title,
            folderId = folderId,
            paperStyle = paperStyle,
            mimeType = mimeType,
            createdAtMillis = now,
            updatedAtMillis = now,
            updatedAtFormatted = formatter.format(Date(now)),
            previewLines = initialPreviewLines ?: emptyList(),
            cover = cover,
            isLocked = !password.isNullOrBlank(),
            password = password
        )

        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        userList.add(0, newNotebook)
        _notebooks.value = userList.filter { !it.isDeleted }
        _stats.update { it.copy(notesCount = _notebooks.value.size) }
        saveToDisk()
        return newNotebook
    }

    fun setNotebookPassword(id: String, password: String?) {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val idx = userList.indexOfFirst { it.id == id }
        if (idx != -1) {
            val existing = userList[idx]
            userList[idx] = existing.copy(
                isLocked = !password.isNullOrBlank(),
                password = password,
                updatedAtMillis = System.currentTimeMillis()
            )
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }

    fun updateTextNotebook(id: String, title: String, lines: List<String>) {
        val now = System.currentTimeMillis()
        val formatter = SimpleDateFormat("dd/MM/yy hh:mm a", Locale.getDefault())
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val index = userList.indexOfFirst { it.id == id }
        if (index != -1) {
            val existing = userList[index]
            val updated = existing.copy(
                title = title,
                previewLines = lines,
                updatedAtMillis = now,
                updatedAtFormatted = formatter.format(Date(now)),
                version = existing.version + 1
            )
            userList[index] = updated
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
            try {
                com.graphite.app.GraphiteApplication.instance.supabaseSyncManager.triggerAutoSync(debounceMs = 500L)
            } catch (_: Exception) {}
        }
    }

    fun updateNotebookStrokes(id: String, strokes: List<InkStroke>) {
        val now = System.currentTimeMillis()
        val formatter = SimpleDateFormat("dd/MM/yy hh:mm a", Locale.getDefault())
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }

        val index = userList.indexOfFirst { it.id == id }
        if (index != -1) {
            val existing = userList[index]
            val updated = existing.copy(
                initialStrokes = strokes,
                updatedAtMillis = now,
                updatedAtFormatted = formatter.format(Date(now)),
                version = existing.version + 1
            )
            userList[index] = updated
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }

    fun updateNotebookLines(id: String, lines: List<String>) {
        val now = System.currentTimeMillis()
        val formatter = SimpleDateFormat("dd/MM/yy hh:mm a", Locale.getDefault())
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }

        val index = userList.indexOfFirst { it.id == id }
        if (index != -1) {
            val existing = userList[index]
            val updated = existing.copy(
                previewLines = lines,
                updatedAtMillis = now,
                updatedAtFormatted = formatter.format(Date(now)),
                version = existing.version + 1
            )
            userList[index] = updated
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }

    fun updateNotebookFilePath(id: String, filePath: String, mimeType: String? = null) {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val index = userList.indexOfFirst { it.id == id }
        if (index != -1) {
            val existing = userList[index]
            val updated = existing.copy(
                filePath = filePath,
                mimeType = mimeType ?: existing.mimeType,
                updatedAtMillis = System.currentTimeMillis()
            )
            userList[index] = updated
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }

    fun deleteNotebook(id: String) {
        val now = System.currentTimeMillis()
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }

        val index = userList.indexOfFirst { it.id == id }
        if (index != -1) {
            val existing = userList[index]
            // Tombstone marking for safe cross-device sync
            userList[index] = existing.copy(
                isDeleted = true,
                updatedAtMillis = now,
                version = existing.version + 1
            )
            _notebooks.value = userList.filter { !it.isDeleted }
            _stats.update { it.copy(notesCount = _notebooks.value.size) }
            saveToDisk()
        }
    }

    fun toggleStarNotebook(id: String) {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val idx = userList.indexOfFirst { it.id == id }
        if (idx != -1) {
            val existing = userList[idx]
            userList[idx] = existing.copy(isStarred = !existing.isStarred, updatedAtMillis = System.currentTimeMillis())
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }

    fun toggleBookmarkNotebook(id: String) {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val idx = userList.indexOfFirst { it.id == id }
        if (idx != -1) {
            val existing = userList[idx]
            userList[idx] = existing.copy(isBookmarked = !existing.isBookmarked, updatedAtMillis = System.currentTimeMillis())
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }

    fun renameNotebook(id: String, newTitle: String) {
        if (newTitle.isBlank()) return
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val idx = userList.indexOfFirst { it.id == id }
        if (idx != -1) {
            val existing = userList[idx]
            userList[idx] = existing.copy(title = newTitle.trim(), updatedAtMillis = System.currentTimeMillis())
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }

    fun moveNotebookToFolder(id: String, targetFolderId: String) {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val idx = userList.indexOfFirst { it.id == id }
        if (idx != -1) {
            val existing = userList[idx]
            userList[idx] = existing.copy(folderId = targetFolderId, updatedAtMillis = System.currentTimeMillis())
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }

    fun restoreNotebook(id: String) {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val idx = userList.indexOfFirst { it.id == id }
        if (idx != -1) {
            val existing = userList[idx]
            userList[idx] = existing.copy(isDeleted = false, updatedAtMillis = System.currentTimeMillis())
            _notebooks.value = userList.filter { !it.isDeleted }
            _stats.update { it.copy(notesCount = _notebooks.value.size) }
            saveToDisk()
        }
    }

    fun permanentlyDeleteNotebook(id: String) {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val toDelete = userList.find { it.id == id }
        toDelete?.filePath?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
        userList.removeAll { it.id == id }
        _notebooks.value = userList.filter { !it.isDeleted }
        _stats.update { it.copy(notesCount = _notebooks.value.size) }
        saveToDisk()
    }

    fun emptyTrash() {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        userList.removeAll { it.isDeleted }
        _notebooks.value = userList.filter { !it.isDeleted }
        _stats.update { it.copy(notesCount = _notebooks.value.size) }
        saveToDisk()
    }

    fun getAllNotesIncludingDeleted(): List<NotebookItem> {
        val currentUid = activeUserId ?: "default"
        return userNotebooksMap[currentUid]?.toList() ?: emptyList()
    }

    /**
     * Returns all local notes for the active user (including tombstones) to synchronize to Supabase.
     */
    fun getAllNotesForActiveUser(): List<NotebookItem> {
        val currentUid = activeUserId ?: return emptyList()
        return userNotebooksMap[currentUid]?.toList() ?: emptyList()
    }

    /**
     * Deterministic reconciliation of remote notes with local notes (Last-Write-Wins based on updatedAtMillis).
     * Handles new notes, edits, and tombstone deletions across devices.
     */
    fun reconcileRemoteNotes(remoteNotes: List<NotebookItem>, targetUserId: String? = null) {
        val currentUid = targetUserId ?: activeUserId ?: return
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val localMap = userList.associateBy { it.id }.toMutableMap()

        for (remote in remoteNotes) {
            // Strict account isolation: NEVER import/adopt a note belonging to a different user
            if (remote.userId != null && remote.userId != currentUid && remote.userId != "default" && remote.userId != "guest") {
                continue
            }

            val local = localMap[remote.id]
            if (remote.isDeleted) {
                // If remote note is marked deleted, remove local note and clean up any physical file
                if (local != null) {
                    local.filePath?.let { path ->
                        try {
                            val f = File(path)
                            if (f.exists()) f.delete()
                        } catch (_: Exception) {}
                    }
                    localMap.remove(remote.id)
                }
            } else if (local == null) {
                // New note from another device
                localMap[remote.id] = remote.copy(userId = currentUid)
            } else {
                // Preserve physical document files if local file exists on disk
                val hasLocalFile = !local.filePath.isNullOrBlank() && File(local.filePath).exists()
                val resolvedFilePath = if (hasLocalFile) local.filePath else (remote.filePath ?: local.filePath)
                val resolvedMimeType = if (hasLocalFile) local.mimeType else (remote.mimeType ?: local.mimeType)
                val resolvedFileName = if (hasLocalFile) local.originalFileName else (remote.originalFileName ?: local.originalFileName)
                val resolvedPageCount = maxOf(local.pageCount, remote.pageCount)

                // Conflict handling: Last-Write-Wins based on timestamps
                if (remote.updatedAtMillis > local.updatedAtMillis) {
                    localMap[remote.id] = remote.copy(
                        userId = currentUid,
                        filePath = resolvedFilePath,
                        mimeType = resolvedMimeType,
                        originalFileName = resolvedFileName,
                        pageCount = resolvedPageCount
                    )
                } else if (!hasLocalFile && remote.filePath != null) {
                    // Update local file pointer if remote brought a materialized file
                    localMap[remote.id] = local.copy(
                        filePath = remote.filePath,
                        mimeType = remote.mimeType ?: local.mimeType,
                        originalFileName = remote.originalFileName ?: local.originalFileName,
                        pageCount = resolvedPageCount
                    )
                }
            }
        }

        userList.clear()
        userList.addAll(localMap.values.sortedByDescending { it.updatedAtMillis })
        _notebooks.value = userList.filter { !it.isDeleted }
        _stats.update { it.copy(notesCount = _notebooks.value.size) }
        saveToDisk()
    }

    fun addFolder(name: String, parentId: String? = null, colorHex: Long? = null) {
        val currentUid = activeUserId ?: "default"
        val userList = userFoldersMap.getOrPut(currentUid) { mutableListOf() }
        val newFolder = FolderItem(UUID.randomUUID().toString(), name.trim(), 0, parentId = parentId, colorHex = colorHex, userId = currentUid)
        userList.add(newFolder)
        if (activeUserId != null) {
            _folders.value = userList.toList()
        }
        saveToDisk()
    }

    fun updateFolder(folderId: String, newName: String? = null, newColorHex: Long? = null, newParentId: String? = null) {
        val currentUid = activeUserId ?: "default"
        val userList = userFoldersMap.getOrPut(currentUid) { mutableListOf() }
        val idx = userList.indexOfFirst { it.id == folderId }
        if (idx != -1) {
            val f = userList[idx]
            val resolvedParent = if (newParentId == "__ROOT__") null else (newParentId ?: f.parentId)
            userList[idx] = f.copy(
                name = newName?.trim() ?: f.name,
                colorHex = newColorHex ?: f.colorHex,
                parentId = resolvedParent
            )
            if (activeUserId != null) {
                _folders.value = userList.toList()
            }
            saveToDisk()
        }
    }

    fun deleteFolder(folderId: String) {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        var modified = false
        for (i in userList.indices) {
            val note = userList[i]
            if (note.folderId == folderId) {
                userList[i] = note.copy(folderId = "unfiled", updatedAtMillis = System.currentTimeMillis())
                modified = true
            }
        }
        if (modified) {
            _notebooks.value = userList.filter { !it.isDeleted }
        }
        val folderList = userFoldersMap.getOrPut(currentUid) { mutableListOf() }
        folderList.removeAll { it.id == folderId || it.parentId == folderId }
        if (activeUserId != null) {
            _folders.value = folderList.toList()
        }
        saveToDisk()
    }

    fun moveNotebooksToFolder(notebookIds: Set<String>, folderId: String) {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        var modified = false
        for (i in userList.indices) {
            val note = userList[i]
            if (note.id in notebookIds) {
                userList[i] = note.copy(folderId = folderId, updatedAtMillis = System.currentTimeMillis())
                modified = true
            }
        }
        if (modified) {
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }

    fun addVoiceClipToNotebook(notebookId: String, clip: com.graphite.app.core.voice.VoiceClip) {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val idx = userList.indexOfFirst { it.id == notebookId }
        if (idx != -1) {
            val note = userList[idx]
            val updatedClips = note.voiceNotes + clip
            userList[idx] = note.copy(
                voiceNotes = updatedClips,
                hasRecordings = true,
                updatedAtMillis = System.currentTimeMillis()
            )
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }


    fun normalizeBookTitle(raw: String): String {
        var t = raw.trim()
        if (t.contains('.')) {
            t = t.substringBeforeLast('.')
        }
        // Remove trailing " (1)", " (2)", etc.
        t = t.replace(Regex("\\s*\\(\\d+\\)$"), "")
        // Replace underscores and hyphens with spaces
        t = t.replace('_', ' ').replace('-', ' ')
        // Remove extra spaces and lowercase
        return t.replace("\\s+".toRegex(), " ").trim().lowercase()
    }

    fun cleanBookDuplicatesAndGhosts() {
        _books.update { currentList ->
            val cleaned = mutableListOf<Book>()
            for (book in currentList) {
                // Filter out legacy mock books
                if (book.id == "book_treasure_island" || book.id == "book_ce_ppt") continue

                // Filter out non-PDF files if user requested PDF only (allow Drive-streamed books where filePath is null)
                val isNonPdf = (book.filePath != null && !book.filePath.endsWith(".pdf", ignoreCase = true)) ||
                        (book.mimeType.isNotBlank() && book.mimeType != "application/pdf" && !book.mimeType.contains("pdf", ignoreCase = true))
                if (isNonPdf) continue

                // Filter out raw UUID / random Drive ID books from earlier erroneous sync (never delete normal hyphenated titles)
                val isRawIdTitle = book.title.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) ||
                        (book.title.length in 28..45 && book.title.matches(Regex("^[a-zA-Z0-9_]{28,}$")) && !book.title.contains(" ") && !book.title.contains("-"))
                if (isRawIdTitle) continue

                val normalized = normalizeBookTitle(book.title)
                val existing = cleaned.find { normalizeBookTitle(it.title) == normalized }
                if (existing == null) {
                    cleaned.add(book)
                } else {
                    if (existing.filePath.isNullOrBlank() && !book.filePath.isNullOrBlank()) {
                        cleaned.remove(existing)
                        cleaned.add(book)
                    }
                }
            }
            cleaned
        }
        _stats.update { it.copy(totalBooks = _books.value.size) }
        saveToDisk()
    }

    private var appContext: Context? = null

    fun initStorage(context: Context) {
        appContext = context.applicationContext
        loadFromDisk(context)
    }

    private fun loadFromDisk(context: Context) {
        try {
            val notesFile = File(context.filesDir, "graphite_notes_storage.json")
            if (notesFile.exists()) {
                val jsonStr = notesFile.readText(Charsets.UTF_8)
                val rootObj = JSONObject(jsonStr)
                val keys = rootObj.keys()
                while (keys.hasNext()) {
                    val uid = keys.next()
                    val arr = rootObj.getJSONArray(uid)
                    val list = mutableListOf<NotebookItem>()
                    for (i in 0 until arr.length()) {
                        val nb = notebookFromJson(arr.getJSONObject(i))
                        if (!nb.id.startsWith("note_") && !nb.title.startsWith("Ass")) {
                            list.add(nb)
                        }
                    }
                    if (list.isNotEmpty()) {
                        userNotebooksMap[uid] = list
                    }
                }
                if (activeUserId == null) {
                    _notebooks.value = emptyList()
                    _stats.update { it.copy(notesCount = 0) }
                } else {
                    val currentUid = activeUserId!!
                    _notebooks.value = (userNotebooksMap[currentUid] ?: emptyList()).filter { !it.isDeleted }
                    _stats.update { it.copy(notesCount = _notebooks.value.size) }
                }
            }

            val booksFile = File(context.filesDir, "graphite_books_storage.json")
            if (booksFile.exists()) {
                val jsonStr = booksFile.readText(Charsets.UTF_8).trim()
                if (jsonStr.startsWith("{")) {
                    val rootObj = JSONObject(jsonStr)
                    val keys = rootObj.keys()
                    while (keys.hasNext()) {
                        val uid = keys.next()
                        val arr = rootObj.getJSONArray(uid)
                        val list = mutableListOf<Book>()
                        for (i in 0 until arr.length()) {
                            val b = bookFromJson(arr.getJSONObject(i))
                            if (b.id != "book_treasure_island" && b.id != "book_ce_ppt") {
                                list.add(b)
                            }
                        }
                        if (list.isNotEmpty()) {
                            userBooksMap[uid] = list
                        }
                    }
                } else if (jsonStr.startsWith("[")) {
                    val arr = JSONArray(jsonStr)
                    val list = mutableListOf<Book>()
                    for (i in 0 until arr.length()) {
                        val b = bookFromJson(arr.getJSONObject(i))
                        if (b.id != "book_treasure_island" && b.id != "book_ce_ppt") {
                            list.add(b)
                        }
                    }
                    userBooksMap["guest"] = list
                    val currentUid = activeUserId ?: "guest"
                    if (currentUid != "guest") {
                        userBooksMap[currentUid] = list.toMutableList()
                    }
                }
                if (activeUserId == null) {
                    _books.value = emptyList()
                    _stats.update { it.copy(totalBooks = 0) }
                } else {
                    val currentUid = activeUserId!!
                    _books.value = userBooksMap[currentUid] ?: emptyList()
                    _stats.update { it.copy(totalBooks = _books.value.size) }
                }
            }
            val foldersFile = File(context.filesDir, "graphite_folders_storage.json")
            if (foldersFile.exists()) {
                try {
                    val rawText = foldersFile.readText(Charsets.UTF_8).trim()
                    if (rawText.startsWith("{")) {
                        val rootObj = JSONObject(rawText)
                        userFoldersMap.clear()
                        for (uid in rootObj.keys()) {
                            val arr = rootObj.getJSONArray(uid)
                            val list = mutableListOf<FolderItem>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val fid = obj.getString("id")
                                if (fid == "my_notes" || fid == "lectures" || fid == "projects") continue
                                list.add(
                                    FolderItem(
                                        id = fid,
                                        name = obj.getString("name"),
                                        noteCount = obj.optInt("noteCount", 0),
                                        parentId = if (obj.has("parentId") && !obj.isNull("parentId")) obj.getString("parentId") else null,
                                        colorHex = if (obj.has("colorHex") && !obj.isNull("colorHex")) obj.getLong("colorHex") else null,
                                        userId = if (obj.has("userId") && !obj.isNull("userId")) obj.getString("userId") else uid
                                    )
                                )
                            }
                            userFoldersMap[uid] = list
                        }
                    } else if (rawText.startsWith("[")) {
                        val arr = JSONArray(rawText)
                        val loaded = mutableListOf<FolderItem>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val fid = obj.getString("id")
                            if (fid == "my_notes" || fid == "lectures" || fid == "projects") continue
                            loaded.add(
                                FolderItem(
                                    id = fid,
                                    name = obj.getString("name"),
                                    noteCount = obj.optInt("noteCount", 0),
                                    parentId = if (obj.has("parentId") && !obj.isNull("parentId")) obj.getString("parentId") else null,
                                    colorHex = if (obj.has("colorHex") && !obj.isNull("colorHex")) obj.getLong("colorHex") else null
                                )
                            )
                        }
                        userFoldersMap["default"] = loaded
                    }
                    if (activeUserId != null) {
                        _folders.value = userFoldersMap[activeUserId] ?: emptyList()
                    } else {
                        _folders.value = emptyList()
                    }
                } catch (_: Exception) {}
            }
            cleanBookDuplicatesAndGhosts()
        } catch (e: Exception) {
            android.util.Log.e("GraphiteRepository", "Failed to load storage from disk", e)
        }
    }

    fun saveToDisk() {
        val context = appContext ?: return
        try {
            val notesFile = File(context.filesDir, "graphite_notes_storage.json")
            val rootObj = JSONObject()
            for ((uid, list) in userNotebooksMap) {
                val arr = JSONArray()
                for (item in list) {
                    arr.put(notebookToJson(item))
                }
                rootObj.put(uid, arr)
            }
            notesFile.writeText(rootObj.toString(), Charsets.UTF_8)

            val booksFile = File(context.filesDir, "graphite_books_storage.json")
            val booksRootObj = JSONObject()
            for ((uid, list) in userBooksMap) {
                val arr = JSONArray()
                for (b in list) {
                    arr.put(bookToJson(b))
                }
                booksRootObj.put(uid, arr)
            }
            booksFile.writeText(booksRootObj.toString(), Charsets.UTF_8)

            val foldersFile = File(context.filesDir, "graphite_folders_storage.json")
            val foldersRootObj = JSONObject()
            for ((uid, list) in userFoldersMap) {
                val arr = JSONArray()
                for (f in list) {
                    arr.put(JSONObject().apply {
                        put("id", f.id)
                        put("name", f.name)
                        put("noteCount", f.noteCount)
                        if (f.parentId != null) put("parentId", f.parentId)
                        if (f.colorHex != null) put("colorHex", f.colorHex)
                        if (f.userId != null) put("userId", f.userId)
                    })
                }
                foldersRootObj.put(uid, arr)
            }
            foldersFile.writeText(foldersRootObj.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("GraphiteRepository", "Failed to save storage to disk", e)
        }
    }

    fun getNotesForUser(userId: String): List<NotebookItem> {
        return userNotebooksMap[userId]?.filter { !it.isDeleted } ?: emptyList()
    }

    fun exportNotesAsJson(userId: String? = null): String {
        val uid = userId ?: activeUserId ?: return "[]"
        val userNotes = userNotebooksMap[uid] ?: emptyList()
        val arr = JSONArray()
        for (item in userNotes) {
            arr.put(notebookToJson(item))
        }
        return try {
            arr.toString(2) ?: arr.toString() ?: "[]"
        } catch (_: Throwable) {
            "[]"
        }
    }

    fun importNotesFromJson(jsonString: String, userId: String? = null): Int {
        val uid = userId ?: activeUserId ?: return 0
        val arr = JSONArray(jsonString)
        val importedList = mutableListOf<NotebookItem>()
        for (i in 0 until arr.length()) {
            importedList.add(notebookFromJson(arr.getJSONObject(i)))
        }
        reconcileRemoteNotes(importedList, targetUserId = uid)
        saveToDisk()
        return importedList.size
    }

    fun notebookToJson(item: NotebookItem): JSONObject {
        return JSONObject().apply {
            put("id", item.id)
            put("title", item.title)
            put("folderId", item.folderId)
            put("pageCount", item.pageCount)
            put("createdAtMillis", item.createdAtMillis)
            put("updatedAtMillis", item.updatedAtMillis)
            put("updatedAtFormatted", item.updatedAtFormatted)
            put("paperStyle", item.paperStyle.name)
            put("isStarred", item.isStarred)
            put("isDeleted", item.isDeleted)
            put("isBookmarked", item.isBookmarked)
            put("hasPhotos", item.hasPhotos)
            put("hasRecordings", item.hasRecordings)
            put("isLocked", item.isLocked)
            put("version", item.version)
            put("userId", item.userId)
            val arr = JSONArray()
            item.previewLines.forEach { arr.put(it) }
            put("previewLines", arr)
            put("filePath", item.filePath)
            put("mimeType", item.mimeType)
            put("originalFileName", item.originalFileName)
            put("password", item.password)
            put("driveFileId", item.driveFileId)
            put("isUploadedToDrive", item.isUploadedToDrive)

            // Cover
            val coverObj = JSONObject().apply {
                put("category", item.cover.category)
                put("colorHex", item.cover.colorHex)
                put("name", item.cover.name)
                item.cover.bandColorHex?.let { put("bandColorHex", it) }
                item.cover.patternType?.let { put("patternType", it) }
                item.cover.customImageUri?.let { put("customImageUri", it) }
            }
            put("cover", coverObj)

            // Voice notes
            val voiceArr = JSONArray()
            item.voiceNotes.forEach { clip ->
                val cObj = JSONObject().apply {
                    put("id", clip.id)
                    put("filePath", clip.filePath)
                    put("title", clip.title)
                    put("durationSeconds", clip.durationSeconds)
                    put("createdAtFormatted", clip.createdAtFormatted)
                    put("transcription", clip.transcription)
                    put("pageIndex", clip.pageIndex)
                }
                voiceArr.put(cObj)
            }
            put("voiceNotes", voiceArr)
        }
    }

    fun notebookFromJson(json: JSONObject): NotebookItem {
        val previewArr = json.optJSONArray("previewLines")
        val preview = mutableListOf<String>()
        if (previewArr != null) {
            for (i in 0 until previewArr.length()) {
                preview.add(previewArr.optString(i))
            }
        }
        val pwd = if (json.has("password") && !json.isNull("password")) json.getString("password") else null

        val coverObj = json.optJSONObject("cover")
        val cover = if (coverObj != null) {
            NotebookCover(
                category = coverObj.optString("category", "PLAIN"),
                colorHex = coverObj.optLong("colorHex", 0xFF45A5B5),
                name = coverObj.optString("name", "Teal"),
                bandColorHex = if (coverObj.has("bandColorHex")) coverObj.optLong("bandColorHex") else null,
                patternType = if (coverObj.has("patternType")) coverObj.optString("patternType") else null,
                customImageUri = if (coverObj.has("customImageUri")) coverObj.optString("customImageUri") else null
            )
        } else NotebookCover()

        val voiceNotes = mutableListOf<com.graphite.app.core.voice.VoiceClip>()
        val voiceArr = json.optJSONArray("voiceNotes")
        if (voiceArr != null) {
            for (i in 0 until voiceArr.length()) {
                val cObj = voiceArr.optJSONObject(i) ?: continue
                voiceNotes.add(
                    com.graphite.app.core.voice.VoiceClip(
                        id = cObj.optString("id", UUID.randomUUID().toString()),
                        filePath = cObj.optString("filePath", ""),
                        title = cObj.optString("title", "Voice Note"),
                        durationSeconds = cObj.optInt("durationSeconds", 1),
                        createdAtFormatted = cObj.optString("createdAtFormatted", ""),
                        transcription = if (cObj.has("transcription") && !cObj.isNull("transcription")) cObj.getString("transcription") else null,
                        pageIndex = cObj.optInt("pageIndex", 0)
                    )
                )
            }
        }

        return NotebookItem(
            id = json.optString("id", UUID.randomUUID().toString()),
            title = json.optString("title", "Untitled Note"),
            folderId = json.optString("folderId", "unfiled").let { if (it == "my_notes") "unfiled" else it },
            pageCount = json.optInt("pageCount", 1),
            createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis()),
            updatedAtMillis = json.optLong("updatedAtMillis", System.currentTimeMillis()),
            updatedAtFormatted = json.optString("updatedAtFormatted", "12/10/26 12:35 pm"),
            paperStyle = try { PaperStyle.valueOf(json.optString("paperStyle", "RULED")) } catch (_: Exception) { PaperStyle.RULED },
            isStarred = json.optBoolean("isStarred", false),
            isDeleted = json.optBoolean("isDeleted", false),
            isBookmarked = json.optBoolean("isBookmarked", false),
            hasPhotos = json.optBoolean("hasPhotos", false),
            hasRecordings = json.optBoolean("hasRecordings", false) || voiceNotes.isNotEmpty(),
            isLocked = json.optBoolean("isLocked", false) || !pwd.isNullOrBlank(),
            version = json.optLong("version", 1L),
            userId = if (json.has("userId") && !json.isNull("userId")) json.getString("userId") else null,
            previewLines = preview,
            filePath = if (json.has("filePath") && !json.isNull("filePath")) json.getString("filePath") else null,
            mimeType = if (json.has("mimeType") && !json.isNull("mimeType")) json.getString("mimeType") else null,
            originalFileName = if (json.has("originalFileName") && !json.isNull("originalFileName")) json.getString("originalFileName") else null,
            cover = cover,
            voiceNotes = voiceNotes,
            password = pwd,
            driveFileId = if (json.has("driveFileId") && !json.isNull("driveFileId")) json.getString("driveFileId") else null,
            isUploadedToDrive = json.optBoolean("isUploadedToDrive", false)
        )
    }

    fun deleteVoiceClipFromNotebook(notebookId: String, clipId: String) {
        val currentUid = activeUserId ?: "default"
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val idx = userList.indexOfFirst { it.id == notebookId }
        if (idx != -1) {
            val note = userList[idx]
            val updatedClips = note.voiceNotes.filter { it.id != clipId }
            userList[idx] = note.copy(
                voiceNotes = updatedClips,
                hasRecordings = updatedClips.isNotEmpty(),
                updatedAtMillis = System.currentTimeMillis()
            )
            _notebooks.value = userList.filter { !it.isDeleted }
            saveToDisk()
        }
    }

    fun bookToJson(book: Book): JSONObject {
        return JSONObject().apply {
            put("id", book.id)
            put("title", book.title)
            put("author", book.author)
            put("progressPercent", book.progressPercent)
            put("coverType", book.coverType.name)
            put("lastReadTimeMillis", book.lastReadTimeMillis)
            put("totalChapters", book.totalChapters)
            put("currentChapter", book.currentChapter)
            put("content", book.content)
            put("filePath", book.filePath)
            put("mimeType", book.mimeType)
            put("isBookmarked", book.isBookmarked)
            put("isFavorite", book.isFavorite)
            put("driveFileId", book.driveFileId)
            put("userId", book.userId)
            book.driveFolderId?.let { put("driveFolderId", it) }
            book.driveFolderName?.let { put("driveFolderName", it) }
        }
    }

    fun bookFromJson(json: JSONObject): Book {
        return Book(
            id = json.optString("id", UUID.randomUUID().toString()),
            title = json.optString("title", "Untitled Book"),
            author = json.optString("author", "Unknown Author"),
            progressPercent = json.optInt("progressPercent", 0),
            coverType = try { BookCoverType.valueOf(json.optString("coverType", "TREASURE_ISLAND")) } catch (_: Exception) { BookCoverType.CUSTOM },
            lastReadTimeMillis = json.optLong("lastReadTimeMillis", System.currentTimeMillis()),
            totalChapters = json.optInt("totalChapters", 1),
            currentChapter = json.optInt("currentChapter", 1),
            content = json.optString("content", ""),
            filePath = if (json.has("filePath") && !json.isNull("filePath")) json.getString("filePath") else null,
            mimeType = json.optString("mimeType", "application/pdf"),
            isBookmarked = json.optBoolean("isBookmarked", false),
            isFavorite = json.optBoolean("isFavorite", false),
            driveFileId = if (json.has("driveFileId") && !json.isNull("driveFileId")) json.getString("driveFileId") else null,
            userId = if (json.has("userId") && !json.isNull("userId")) json.getString("userId") else null,
            driveFolderId = if (json.has("driveFolderId") && !json.isNull("driveFolderId")) json.getString("driveFolderId") else null,
            driveFolderName = if (json.has("driveFolderName") && !json.isNull("driveFolderName")) json.getString("driveFolderName") else null
        )
    }
}
