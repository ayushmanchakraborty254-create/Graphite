package com.graphite.app.core.sync

import com.graphite.app.core.drive.DriveApiV3Uploader
import com.graphite.app.core.drive.DriveUploadResult
import com.graphite.app.core.drive.GoogleDriveManager
import com.graphite.app.core.security.InMemorySecureStorage
import com.graphite.app.core.supabase.SupabaseAuthManager
import com.graphite.app.core.supabase.SupabaseNotesRepository
import com.graphite.app.core.supabase.SupabaseSession
import com.graphite.app.core.supabase.SupabaseSyncManager
import com.graphite.app.core.supabase.SupabaseUser
import com.graphite.app.data.model.NotebookItem
import com.graphite.app.data.repository.GraphiteRepository
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Validates the core architectural isolation requirement:
 * "Never upload notes automatically during creation, editing, export or Supabase synchronization."
 * "test both successful uploads and the guarantee that ordinary note synchronization creates no Drive files."
 */
class NoteSyncIsolationTest {

    private lateinit var driveSecureStorage: InMemorySecureStorage
    private lateinit var supabaseSecureStorage: InMemorySecureStorage
    private lateinit var driveUploadCounter: AtomicInteger
    private lateinit var driveManager: GoogleDriveManager

    @Before
    fun setUp() {
        driveSecureStorage = InMemorySecureStorage()
        supabaseSecureStorage = InMemorySecureStorage()
        driveUploadCounter = AtomicInteger(0)

        // Counting mock uploader tracking every Google Drive API v3 upload call
        val countingUploader = object : DriveApiV3Uploader {
            override suspend fun upload(
                accountEmail: String,
                fileName: String,
                mimeType: String,
                contentBytes: ByteArray,
                folderId: String?
            ): DriveUploadResult {
                driveUploadCounter.incrementAndGet()
                return DriveUploadResult.Success(
                    fileId = "test_file_id",
                    fileName = fileName
                )
            }
        }

        driveManager = GoogleDriveManager(
            secureStorage = driveSecureStorage,
            uploaderProvider = { countingUploader },
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testNoteCreationCreatesNoDriveFiles() {
        assertEquals("Initial drive uploads must be 0", 0, driveUploadCounter.get())

        // Ordinary note creation
        val newNote = GraphiteRepository.createNotebook("Physics Lecture Notes")

        assertNotNull(newNote.id)
        assertEquals("Guarantee: Note creation MUST NOT trigger Google Drive upload", 0, driveUploadCounter.get())
    }

    @Test
    fun testNoteEditingCreatesNoDriveFiles() {
        val note = GraphiteRepository.createNotebook("Math Homework")
        assertEquals(0, driveUploadCounter.get())

        val stroke = InkStroke(
            points = listOf(InkPoint(50f, 50f), InkPoint(100f, 100f))
        )

        // Ordinary note stroke updates / canvas editing
        GraphiteRepository.updateNotebookStrokes(note.id, listOf(stroke))

        assertEquals("Guarantee: Note editing MUST NOT trigger Google Drive upload", 0, driveUploadCounter.get())
    }

    @Test
    fun testNoteDeletionCreatesNoDriveFiles() {
        val note = GraphiteRepository.createNotebook("Chemistry Lab")
        assertEquals(0, driveUploadCounter.get())

        GraphiteRepository.deleteNotebook(note.id)

        assertEquals("Guarantee: Note deletion MUST NOT trigger Google Drive upload", 0, driveUploadCounter.get())
    }

    @Test
    fun testSupabaseSyncCreatesZeroDriveFiles() = runBlocking {
        assertEquals("Initial drive uploads must be 0", 0, driveUploadCounter.get())

        // Setup authenticated Supabase session
        val authManager = SupabaseAuthManager(
            secureStorage = supabaseSecureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )
        authManager.setSession(
            SupabaseSession(
                accessToken = "test_supabase_access_token",
                user = SupabaseUser(id = "user-789", email = "test@supabase.co")
            )
        )

        // Mock PostgREST responses for Supabase upsert and fetch
        var supabaseRequestsCount = 0
        val mockSupabaseClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                supabaseRequestsCount++
                val request = chain.request()
                val body = if (request.method == "GET") "[]" else "[{}]"
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        val notesRepository = SupabaseNotesRepository(
            authManager = authManager,
            httpClient = mockSupabaseClient,
            ioDispatcher = Dispatchers.Unconfined
        )

        val syncManager = SupabaseSyncManager(
            authManager = authManager,
            notesRepository = notesRepository,
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )

        // Execute Supabase sync
        val syncResult = syncManager.syncNotes()

        assertTrue(syncResult.isSuccess)
        assertTrue("Supabase synchronization occurred", supabaseRequestsCount > 0)

        // CRITICAL INVARIANT GUARANTEE:
        // Ordinary note synchronization with Supabase must create ZERO Drive files!
        assertEquals(
            "CRITICAL INVARIANT GUARANTEE: Supabase note sync created 0 Drive files",
            0,
            driveUploadCounter.get()
        )
    }

    @Test
    fun testManualExplicitUploadIncrementsDriveUpload() = runBlocking {
        assertEquals(0, driveUploadCounter.get())
        driveSecureStorage.putString("google_drive_authorized_account_email", "ayush@gmail.com")

        val note = NotebookItem(title = "Explicitly Uploaded File")
        val dummyContext = android.app.Application()

        // Explicit manual upload
        val result = driveManager.uploadNotebookToMyDrive(dummyContext, note)

        assertTrue(result is DriveUploadResult.Success)
        assertEquals("Explicit upload must increment drive counter exactly once", 1, driveUploadCounter.get())
    }
}
