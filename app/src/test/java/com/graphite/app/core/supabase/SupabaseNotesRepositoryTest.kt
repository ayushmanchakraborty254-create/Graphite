package com.graphite.app.core.supabase

import androidx.compose.ui.graphics.Color
import com.graphite.app.core.security.InMemorySecureStorage
import com.graphite.app.data.model.NotebookItem
import com.graphite.app.feature.workspace.model.InkPoint
import com.graphite.app.feature.workspace.model.InkStroke
import com.graphite.app.feature.workspace.model.InkToolType
import com.graphite.app.feature.workspace.model.PaperStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SupabaseNotesRepositoryTest {

    private lateinit var secureStorage: InMemorySecureStorage
    private lateinit var authManager: SupabaseAuthManager

    @Before
    fun setUp() {
        secureStorage = InMemorySecureStorage()
        authManager = SupabaseAuthManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testUnauthenticatedUserIsBlockedByRowLevelSecurity() = runBlocking {
        val repository = SupabaseNotesRepository(
            authManager = authManager,
            ioDispatcher = Dispatchers.Unconfined
        )

        // Attempting to fetch notes without authentication must fail with SecurityException
        val fetchResult = repository.fetchNotes()
        assertTrue("Fetch should fail when unauthenticated", fetchResult.isFailure)
        assertTrue(
            "Exception should be SecurityException enforcing RLS",
            fetchResult.exceptionOrNull() is SecurityException
        )

        // Attempting to upsert note without authentication must fail with SecurityException
        val sampleNote = NotebookItem(title = "Security Test Note")
        val upsertResult = repository.upsertNote(sampleNote)
        assertTrue("Upsert should fail when unauthenticated", upsertResult.isFailure)
        assertTrue(
            "Exception should be SecurityException enforcing RLS",
            upsertResult.exceptionOrNull() is SecurityException
        )

        // Attempting to delete note without authentication must fail with SecurityException
        val deleteResult = repository.deleteNote("note-123")
        assertTrue("Delete should fail when unauthenticated", deleteResult.isFailure)
        assertTrue(
            "Exception should be SecurityException enforcing RLS",
            deleteResult.exceptionOrNull() is SecurityException
        )
    }

    @Test
    fun testAuthenticatedUserAttachesHeadersAndParsesNotes() = runBlocking {
        // Set active authenticated session
        val validSession = SupabaseSession(
            accessToken = "valid_user_jwt_token",
            user = SupabaseUser(id = "user_456", email = "author@graphite.com")
        )
        authManager.setSession(validSession)

        val sampleStrokes = listOf(
            InkStroke(
                points = listOf(InkPoint(10f, 20f), InkPoint(30f, 40f)),
                baseWidth = 5f,
                color = Color.Yellow,
                toolType = InkToolType.PEN
            )
        )
        val serializedStrokes = InkSerialization.serializeStrokes(sampleStrokes)

        var capturedAuthHeader: String? = null
        var capturedApiKeyHeader: String? = null

        val mockResponseBody = """
            [
                {
                    "id": "note_supabase_1",
                    "user_id": "user_456",
                    "title": "Cloud Note 1",
                    "folder_id": "my_notes",
                    "paper_style": "GRID",
                    "preview_lines": ["Line 1", "Line 2"],
                    "strokes_json": ${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), serializedStrokes)},
                    "updated_at": "13/10/26 10:00 pm"
                }
            ]
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                capturedAuthHeader = request.header("Authorization")
                capturedApiKeyHeader = request.header("apikey")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mockResponseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        val repository = SupabaseNotesRepository(
            authManager = authManager,
            httpClient = mockClient,
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = repository.fetchNotes()

        assertTrue(result.isSuccess)
        val notes = result.getOrThrow()
        assertEquals(1, notes.size)

        val note = notes[0]
        assertEquals("note_supabase_1", note.id)
        assertEquals("Cloud Note 1", note.title)
        assertEquals(PaperStyle.GRID, note.paperStyle)
        assertEquals(1, note.initialStrokes.size)
        assertEquals(2, note.initialStrokes[0].points.size)

        // Verify strictly formatted authentication headers
        assertEquals("Bearer valid_user_jwt_token", capturedAuthHeader)
        assertNotNull(capturedApiKeyHeader)
    }

    @Test
    fun test401ResponseTriggersAutomaticTokenRefreshAndRetry() = runBlocking {
        secureStorage.putString("supabase_access_token", "expired_jwt_token")
        secureStorage.putString("supabase_refresh_token", "valid_refresh_token")
        secureStorage.putString("supabase_user_id", "user_123")
        secureStorage.putString("supabase_user_email", "ayush@graphite.com")
        secureStorage.putLong("supabase_expires_at", System.currentTimeMillis() + 3600_000L)

        val refreshResponseJson = """
            {
                "access_token": "newly_refreshed_jwt_token",
                "token_type": "bearer",
                "expires_in": 3600,
                "refresh_token": "valid_refresh_token",
                "user": {
                    "id": "user_123",
                    "email": "ayush@graphite.com"
                }
            }
        """.trimIndent()

        val notesResponseJson = """
            [
                {
                    "id": "note_101",
                    "user_id": "user_123",
                    "title": "Recovered Note",
                    "folder_id": "folder_1",
                    "paper_style": "RULED",
                    "preview_lines": [],
                    "strokes_json": "[]",
                    "updated_at": "16/10/26 4:00 pm"
                }
            ]
        """.trimIndent()

        val capturedTokens = mutableListOf<String>()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.contains("/auth/v1/token")) {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(refreshResponseJson.toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (url.contains("/rest/v1/notebooks")) {
                    val authHeader = request.header("Authorization") ?: ""
                    capturedTokens.add(authHeader)
                    if (authHeader == "Bearer expired_jwt_token") {
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(401)
                            .message("Unauthorized")
                            .body("""{"code":"PGRST301","details":"No suitable key or JWT expired"}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    } else {
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(notesResponseJson.toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                } else {
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
            }.build()

        val customAuthManager = SupabaseAuthManager(
            secureStorage = secureStorage,
            httpClient = mockClient,
            ioDispatcher = Dispatchers.Unconfined
        )

        val repository = SupabaseNotesRepository(
            authManager = customAuthManager,
            httpClient = mockClient,
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = repository.fetchNotes()
        assertTrue("fetchNotes should succeed after 401 retry", result.isSuccess)
        val notes = result.getOrThrow()
        assertEquals(1, notes.size)
        assertEquals("note_101", notes[0].id)

        // Verify that it initially tried with the expired token and then automatically retried with the refreshed token
        assertEquals(listOf("Bearer expired_jwt_token", "Bearer newly_refreshed_jwt_token"), capturedTokens)
        assertEquals("newly_refreshed_jwt_token", secureStorage.getString("supabase_access_token"))
    }
}
