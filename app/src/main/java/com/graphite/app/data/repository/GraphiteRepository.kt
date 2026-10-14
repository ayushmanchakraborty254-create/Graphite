package com.graphite.app.data.repository

import com.graphite.app.data.model.*
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkStroke
import com.graphite.app.feature.workspace.model.InkToolType
import com.graphite.app.feature.workspace.model.PaperStyle
import com.graphite.app.feature.workspace.model.WorkspaceColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val _books = MutableStateFlow(
        listOf(
            Book(
                id = "book_treasure_island",
                title = "Treasure Island",
                author = "Robert Louis Stevenson",
                progressPercent = 1,
                coverType = BookCoverType.TREASURE_ISLAND,
                totalChapters = 34,
                currentChapter = 1,
                content = sampleTreasureIslandContent
            ),
            Book(
                id = "book_ce_ppt",
                title = "CE Ppt",
                author = "Ayushman Chakraborty",
                progressPercent = 50,
                coverType = BookCoverType.CE_PPT,
                totalChapters = 12,
                currentChapter = 6,
                content = sampleCePptContent
            )
        )
    )
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    // Isolated notebook collections partitioned by user ID to guarantee strict multi-user privacy
    private val userNotebooksMap = mutableMapOf<String, MutableList<NotebookItem>>().apply {
        put("default", createInitialNotebooks().toMutableList())
    }
    private var activeUserId: String? = "default"

    private val _notebooks = MutableStateFlow(userNotebooksMap["default"]?.toList() ?: emptyList())
    val notebooks: StateFlow<List<NotebookItem>> = _notebooks.asStateFlow()

    private val _folders = MutableStateFlow(
        listOf(
            FolderItem("my_notes", "My Notes", 9),
            FolderItem("lectures", "Lectures", 14),
            FolderItem("projects", "Projects", 16)
        )
    )
    val folders: StateFlow<List<FolderItem>> = _folders.asStateFlow()

    private val _stats = MutableStateFlow(ReadingStats(totalHoursRead = 0.1f, totalBooks = 2, notesCount = 39))
    val stats: StateFlow<ReadingStats> = _stats.asStateFlow()

    fun getActiveUserId(): String? = activeUserId

    /**
     * Switches the active authenticated user, strictly isolating the visible workspace.
     * When signing out (userId == null), clears private cached notes from the visible workspace.
     */
    fun setActiveUser(userId: String?) {
        activeUserId = userId
        if (userId == null) {
            _notebooks.value = emptyList()
            _stats.update { it.copy(notesCount = 0) }
        } else {
            val userNotes = userNotebooksMap.getOrPut(userId) { mutableListOf() }
            _notebooks.value = userNotes.filter { !it.isDeleted }
            _stats.update { it.copy(notesCount = _notebooks.value.size) }
        }
    }

    /**
     * Clears cached private data from the active user's visible workspace on sign out.
     */
    fun clearActiveUserWorkspace() {
        setActiveUser(null)
    }

    fun getBook(id: String): Book? = _books.value.find { it.id == id }

    fun updateBookProgress(id: String, progress: Int) {
        _books.update { list ->
            list.map {
                if (it.id == id) it.copy(progressPercent = progress.coerceIn(0, 100), lastReadTimeMillis = System.currentTimeMillis())
                else it
            }
        }
    }

    fun addBook(title: String, author: String, content: String) {
        val newBook = Book(
            title = disambiguateBookTitle(title),
            author = author,
            progressPercent = 0,
            coverType = BookCoverType.CUSTOM,
            content = content
        )
        addBook(newBook)
    }

    fun addBook(book: Book) {
        val disambiguatedBook = if (_books.value.any { it.title == book.title && it.id != book.id }) {
            book.copy(title = disambiguateBookTitle(book.title))
        } else {
            book
        }
        _books.update { listOf(disambiguatedBook) + it }
        _stats.update { it.copy(totalBooks = _books.value.size) }
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
        return finalNote
    }

    fun getNotebook(id: String): NotebookItem? = _notebooks.value.find { it.id == id }

    fun createNotebook(
        title: String,
        paperStyle: PaperStyle = PaperStyle.RULED,
        folderId: String = "my_notes",
        customId: String = UUID.randomUUID().toString()
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
            createdAtMillis = now,
            updatedAtMillis = now,
            updatedAtFormatted = formatter.format(Date(now)),
            previewLines = listOf("New handwritten page")
        )

        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        userList.add(0, newNotebook)
        _notebooks.value = userList.filter { !it.isDeleted }
        _stats.update { it.copy(notesCount = _notebooks.value.size) }
        return newNotebook
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
        }
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
    fun reconcileRemoteNotes(remoteNotes: List<NotebookItem>) {
        val currentUid = activeUserId ?: return
        val userList = userNotebooksMap.getOrPut(currentUid) { mutableListOf() }
        val localMap = userList.associateBy { it.id }.toMutableMap()

        for (remote in remoteNotes) {
            val local = localMap[remote.id]
            if (local == null) {
                // New note from another device
                localMap[remote.id] = remote
            } else {
                // Conflict handling: Last-Write-Wins based on timestamps
                if (remote.updatedAtMillis > local.updatedAtMillis) {
                    localMap[remote.id] = remote
                } else if (remote.isDeleted && !local.isDeleted && remote.updatedAtMillis >= local.updatedAtMillis) {
                    localMap[remote.id] = local.copy(isDeleted = true, updatedAtMillis = remote.updatedAtMillis)
                }
            }
        }

        userList.clear()
        userList.addAll(localMap.values.sortedByDescending { it.updatedAtMillis })
        _notebooks.value = userList.filter { !it.isDeleted }
        _stats.update { it.copy(notesCount = _notebooks.value.size) }
    }

    fun addFolder(name: String) {
        val newFolder = FolderItem(UUID.randomUUID().toString(), name, 0)
        _folders.update { it + newFolder }
    }

    private fun createInitialNotebooks(): List<NotebookItem> {
        val names = listOf(
            "Ass9 (2)" to "12/10/26 12:35 pm",
            "Ass1 (6)" to "12/10/26 12:27 pm",
            "Ass2 (2)" to "12/10/26 12:24 pm",
            "Ass4 (1)" to "12/10/26 12:24 pm",
            "Ass3 (4)" to "12/10/26 12:24 pm",
            "Ass5 (2)" to "12/10/26 12:23 pm",
            "Ass6 (3)" to "12/10/26 12:23 pm",
            "Ass13 (2)" to "12/10/26 12:13 pm",
            "Ass12 (2)" to "12/10/26 12:13 pm"
        )

        return names.mapIndexed { idx, (title, date) ->
            val sampleStrokes = if (idx == 0) {
                listOf(
                    InkStroke(
                        points = listOf(
                            InkPoint(120f, 220f, 0.6f),
                            InkPoint(260f, 220f, 0.7f),
                            InkPoint(400f, 220f, 0.5f)
                        ),
                        baseWidth = 4f,
                        color = WorkspaceColors.CoralOrange,
                        toolType = InkToolType.PEN
                    ),
                    InkStroke(
                        points = listOf(
                            InkPoint(120f, 320f, 0.5f),
                            InkPoint(350f, 320f, 0.6f)
                        ),
                        baseWidth = 4f,
                        color = WorkspaceColors.White,
                        toolType = InkToolType.PEN
                    )
                )
            } else emptyList()

            NotebookItem(
                id = "note_$idx",
                userId = "default",
                title = title,
                updatedAtFormatted = date,
                paperStyle = PaperStyle.RULED,
                previewLines = listOf(
                    "1) Write programs to demonstrate math module",
                    "2) Write programs to demonstrate random module",
                    "3) Write programs to demonstrate statistics module"
                ),
                initialStrokes = sampleStrokes
            )
        }
    }
}
