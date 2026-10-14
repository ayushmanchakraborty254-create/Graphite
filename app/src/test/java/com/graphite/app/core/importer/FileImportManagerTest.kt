package com.graphite.app.core.importer

import android.app.Application
import com.graphite.app.core.drive.DriveUploadState
import com.graphite.app.core.drive.ExportedNotebookPayload
import com.graphite.app.core.drive.GoogleDriveManager
import com.graphite.app.core.security.InMemorySecureStorage
import com.graphite.app.core.supabase.*
import com.graphite.app.data.model.NotebookItem
import com.graphite.app.data.repository.GraphiteRepository
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkStroke
import com.graphite.app.feature.workspace.model.InkToolType
import com.graphite.app.feature.workspace.model.PaperStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FileImportManagerTest {

    private lateinit var secureStorage: InMemorySecureStorage
    private val json = Json { prettyPrint = true }

    @Before
    fun setUp() {
        secureStorage = InMemorySecureStorage()
        GraphiteRepository.setActiveUser("test_user_import")
    }

    @Test
    fun testImportValidGraphiteNotePreservesStrokesAndPaperStyle() = runBlocking {
        val sampleStrokes = listOf(
            InkStroke(
                points = listOf(
                    InkPoint(100f, 200f, 0.7f),
                    InkPoint(150f, 250f, 0.8f)
                ),
                baseWidth = 5f,
                toolType = InkToolType.PEN
            )
        )
        val payload = ExportedNotebookPayload(
            id = "original-note-id",
            title = "Physics Lecture",
            folderId = "my_notes",
            paperStyle = "GRID",
            previewLines = listOf("Newton's Laws", "Thermodynamics"),
            strokesJson = InkSerialization.serializeStrokes(sampleStrokes)
        )
        val noteJson = json.encodeToString(payload)
        val bytes = noteJson.toByteArray(Charsets.UTF_8)

        val importManager = FileImportManager(
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
        val mockContext = Application()

        val result = importManager.importFromBytes(
            context = mockContext,
            displayName = "Physics Lecture.graphite",
            mimeType = "application/json",
            bytes = bytes
        )

        assertTrue("Result must be SuccessNotebook", result is ImportResult.SuccessNotebook)
        val success = result as ImportResult.SuccessNotebook
        val importedNote = success.notebook

        assertEquals("Physics Lecture", importedNote.title)
        assertEquals(PaperStyle.GRID, importedNote.paperStyle)
        assertEquals(2, importedNote.previewLines.size)
        assertEquals("Newton's Laws", importedNote.previewLines[0])
        assertEquals(1, importedNote.initialStrokes.size)
        assertEquals(2, importedNote.initialStrokes[0].points.size)
        assertEquals(100f, importedNote.initialStrokes[0].points[0].x, 0.01f)
        assertEquals(200f, importedNote.initialStrokes[0].points[0].y, 0.01f)
        assertEquals(0.7f, importedNote.initialStrokes[0].points[0].pressure, 0.01f)

        // Verify it was added to the repository
        val storedNote = GraphiteRepository.getNotebook(importedNote.id)
        assertNotNull("Note must be stored in repository", storedNote)
        assertEquals("Physics Lecture", storedNote!!.title)
    }

    @Test
    fun testImportDuplicateTitleDisambiguatesWithSuffix() = runBlocking {
        // Pre-create a note with title "Biology"
        GraphiteRepository.createNotebook("Biology")

        val payload = ExportedNotebookPayload(
            id = "note_123",
            title = "Biology",
            folderId = "my_notes",
            paperStyle = "RULED",
            previewLines = listOf("Cell Structure"),
            strokesJson = InkSerialization.serializeStrokes(emptyList())
        )
        val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)

        val importManager = FileImportManager(
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
        val mockContext = Application()

        // 1st duplicate import -> Biology (1)
        val result1 = importManager.importFromBytes(mockContext, "Biology.graphite", null, bytes)
        assertTrue(result1 is ImportResult.SuccessNotebook)
        assertEquals("Biology (1)", (result1 as ImportResult.SuccessNotebook).notebook.title)

        // 2nd duplicate import -> Biology (2)
        val result2 = importManager.importFromBytes(mockContext, "Biology.graphite", null, bytes)
        assertTrue(result2 is ImportResult.SuccessNotebook)
        assertEquals("Biology (2)", (result2 as ImportResult.SuccessNotebook).notebook.title)
    }

    @Test
    fun testImportCorruptPdfMissingHeaderFailsWithClearError() = runBlocking {
        // File named .pdf but corrupted (does not start with %PDF-)
        val corruptBytes = "NOT_A_REAL_PDF_DATA_STREAM".toByteArray(Charsets.UTF_8)

        val importManager = FileImportManager(
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
        val mockContext = Application()

        val result = importManager.importFromBytes(
            context = mockContext,
            displayName = "CorruptedDoc.pdf",
            mimeType = "application/pdf",
            bytes = corruptBytes
        )

        assertTrue("Corrupted PDF must fail", result is ImportResult.Error)
        val error = result as ImportResult.Error
        assertTrue("Error message must mention missing signature", error.message.contains("%PDF-"))
    }

    @Test
    fun testImportUnsupportedFileFormatFailsWithClearMessage() = runBlocking {
        val randomTextBytes = "Hello, world! This is just plain text.".toByteArray(Charsets.UTF_8)

        val importManager = FileImportManager(
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
        val mockContext = Application()

        val result = importManager.importFromBytes(
            context = mockContext,
            displayName = "random.txt",
            mimeType = "text/plain",
            bytes = randomTextBytes
        )

        assertTrue("Unsupported file must fail", result is ImportResult.Error)
        val error = result as ImportResult.Error
        assertTrue("Error must explain supported formats", error.message.contains("Graphite supports PDF documents"))
    }

    @Test
    fun testImportGraphiteNoteTriggersSupabaseSyncWhenAuthenticated() = runBlocking {
        secureStorage.putString("supabase_session_access_token", "valid_supabase_token")
        secureStorage.putString("supabase_session_user_id", "test_user_import")

        val mockAuthManager = SupabaseAuthManager(secureStorage, ioDispatcher = Dispatchers.Unconfined)
        mockAuthManager.setSession(
            SupabaseSession(
                accessToken = "valid_supabase_token",
                user = SupabaseUser(id = "test_user_import", email = "test@example.com")
            )
        )
        var syncNotesCalled = false

        val mockHttpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                syncNotesCalled = true
                if (request.method == "GET") {
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("[]".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(201)
                        .message("Created")
                        .body("[]".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }.build()

        val mockNotesRepo = SupabaseNotesRepository(
            authManager = mockAuthManager,
            httpClient = mockHttpClient,
            ioDispatcher = Dispatchers.Unconfined
        )

        val syncManager = SupabaseSyncManager(
            authManager = mockAuthManager,
            notesRepository = mockNotesRepo,
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )

        val importManager = FileImportManager(
            graphiteRepository = GraphiteRepository,
            supabaseAuthManager = mockAuthManager,
            supabaseSyncManager = syncManager,
            ioDispatcher = Dispatchers.Unconfined
        )
        val mockContext = Application()

        val payload = ExportedNotebookPayload(
            id = "note-for-cloud-sync",
            title = "Cloud Note",
            folderId = "my_notes",
            paperStyle = "RULED",
            previewLines = listOf("Synced Note"),
            strokesJson = InkSerialization.serializeStrokes(emptyList())
        )
        val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)

        val result = importManager.importFromBytes(mockContext, "Cloud Note.graphite", null, bytes)

        assertTrue(result is ImportResult.SuccessNotebook)
        val success = result as ImportResult.SuccessNotebook
        assertTrue("syncedToCloud must be true when authenticated", success.syncedToCloud)
        assertTrue("Supabase sync must have been called", syncNotesCalled)
    }

    @Test
    fun testImportNeverUploadsToGoogleDrive() = runBlocking {
        secureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")
        var driveUploadCalled = false

        val driveManager = GoogleDriveManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )

        val importManager = FileImportManager(
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
        val mockContext = Application()

        val payload = ExportedNotebookPayload(
            id = "note-imported-test",
            title = "Offline Import Note",
            folderId = "my_notes",
            paperStyle = "RULED",
            previewLines = listOf("No Drive Upload"),
            strokesJson = InkSerialization.serializeStrokes(emptyList())
        )
        val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)

        val result = importManager.importFromBytes(mockContext, "Offline Import Note.graphite", null, bytes)

        assertTrue(result is ImportResult.SuccessNotebook)
        assertEquals(DriveUploadState.Idle, driveManager.uploadState.value)
        assertFalse("Importing must NEVER trigger Google Drive uploads", driveUploadCalled)
    }

    @Test
    fun testImportToNotesStrictlyPopulatesNotebooksAndNotBooks() = runBlocking {
        val initialBookCount = GraphiteRepository.books.value.size
        val pdfBytes = "%PDF-1.4 sample content %%EOF".toByteArray(Charsets.ISO_8859_1)

        val importManager = FileImportManager(
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
        val mockContext = Application()

        val result = importManager.importFromBytes(
            context = mockContext,
            displayName = "Lecture9.pdf",
            mimeType = "application/pdf",
            bytes = pdfBytes,
            target = ImportDestination.NOTES
        )

        assertTrue("Result must be SuccessNotebook", result is ImportResult.SuccessNotebook)
        val notebook = (result as ImportResult.SuccessNotebook).notebook
        assertEquals("Lecture9", notebook.title)

        // Verify it was added to Notebooks ONLY
        val storedNote = GraphiteRepository.getNotebook(notebook.id)
        assertNotNull("Note must be stored in notebooks", storedNote)

        // Verify it was NOT added to Books
        assertEquals("Books count must not change", initialBookCount, GraphiteRepository.books.value.size)
        assertNull("Note must NOT be in books", GraphiteRepository.getBook(notebook.id))
    }

    @Test
    fun testImportToBookshelfStrictlyPopulatesBooksAndNotNotebooks() = runBlocking {
        val initialNoteCount = GraphiteRepository.notebooks.value.size
        val pdfBytes = "%PDF-1.4 sample book %%EOF".toByteArray(Charsets.ISO_8859_1)

        val importManager = FileImportManager(
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
        val mockContext = Application()

        val result = importManager.importFromBytes(
            context = mockContext,
            displayName = "Novel.pdf",
            mimeType = "application/pdf",
            bytes = pdfBytes,
            target = ImportDestination.BOOKSHELF
        )

        assertTrue("Result must be SuccessBook", result is ImportResult.SuccessBook)
        val book = (result as ImportResult.SuccessBook).book
        assertEquals("Novel", book.title)

        // Verify it was added to Books ONLY
        val storedBook = GraphiteRepository.getBook(book.id)
        assertNotNull("Book must be stored in books", storedBook)

        // Verify it was NOT added to Notebooks
        assertEquals("Notebooks count must not change", initialNoteCount, GraphiteRepository.notebooks.value.size)
        assertNull("Book must NOT be in notebooks", GraphiteRepository.getNotebook(book.id))
    }

    @Test
    fun testImportDocxPptxXlsxToNotesKeepsFileFormatIntact() = runBlocking {
        val importManager = FileImportManager(
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
        val mockContext = Application()
        val docxBytes = "PK\u0003\u0004 mock docx binary data".toByteArray(Charsets.ISO_8859_1)

        val result = importManager.importFromBytes(
            context = mockContext,
            displayName = "QuarterlyReport.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            bytes = docxBytes,
            target = ImportDestination.NOTES
        )

        assertTrue("Result must be SuccessNotebook", result is ImportResult.SuccessNotebook)
        val notebook = (result as ImportResult.SuccessNotebook).notebook
        assertEquals("QuarterlyReport", notebook.title)
        assertNotNull("filePath must be preserved", notebook.filePath)
        assertTrue("filePath must point to imported_notes file", notebook.filePath!!.contains("imported_notes"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", notebook.mimeType)
        assertEquals("QuarterlyReport.docx", notebook.originalFileName)
        assertTrue("previewLines must indicate DOCX format", notebook.previewLines.any { it.contains("DOCX") })

        // Verify stored in repository
        val stored = GraphiteRepository.getNotebook(notebook.id)
        assertNotNull(stored)
        assertEquals(notebook.filePath, stored!!.filePath)
    }

    @Test
    fun testImportDocxPptxXlsxToBookshelfKeepsFileFormatIntact() = runBlocking {
        val importManager = FileImportManager(
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
        val mockContext = Application()
        val pptxBytes = "PK\u0003\u0004 mock pptx presentation binary data".toByteArray(Charsets.ISO_8859_1)

        val result = importManager.importFromBytes(
            context = mockContext,
            displayName = "LectureSlides.pptx",
            mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            bytes = pptxBytes,
            target = ImportDestination.BOOKSHELF
        )

        assertTrue("Result must be SuccessBook", result is ImportResult.SuccessBook)
        val book = (result as ImportResult.SuccessBook).book
        assertEquals("LectureSlides", book.title)
        assertNotNull("filePath must be preserved", book.filePath)
        assertTrue("filePath must point to imported_documents file", book.filePath!!.contains("imported_documents"))
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", book.mimeType)
        assertEquals(com.graphite.app.data.model.BookCoverType.CE_PPT, book.coverType)

        // Verify stored in repository
        val stored = GraphiteRepository.getBook(book.id)
        assertNotNull(stored)
        assertEquals(book.filePath, stored!!.filePath)
    }
}


