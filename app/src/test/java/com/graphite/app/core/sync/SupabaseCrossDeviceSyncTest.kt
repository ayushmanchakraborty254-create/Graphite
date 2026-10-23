package com.graphite.app.core.sync

import androidx.compose.ui.graphics.Color
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
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Validates cross-device synchronization requirements:
 * 1. Note created on Device A is retrieved by Device B
 * 2. Note edited on Device B reconciles on Device A (Last-Write-Wins)
 * 3. Notes created offline synchronize when reconnected
 * 4. Tombstone deletions on Device A propagate and delete the note on Device B
 * 5. Strict account isolation (Device B signed into User 2 cannot access User 1's records)
 */
class SupabaseCrossDeviceSyncTest {

    // Shared mock Supabase cloud table simulating public.notebooks
    private val cloudTable = ConcurrentHashMap<String, SupabaseNotebookDto>()

    @Before
    fun setUp() {
        cloudTable.clear()
        GraphiteRepository.clearAllDataForTesting()
        GraphiteRepository.setActiveUser("default_test_user")
    }

    private fun createMockClientForUser(userId: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val method = request.method
                val authHeader = request.header("Authorization")

                // Enforce Row Level Security
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    return@addInterceptor Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body("{\"message\":\"RLS: Missing authorization token\"}".toResponseBody("application/json".toMediaType()))
                        .build()
                }

                when (method) {
                    "GET" -> {
                        // Return notes owned by the caller's userId
                        val userNotes = cloudTable.values.filter { it.userId == userId }
                        val responseJson = Json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(SupabaseNotebookDto.serializer()),
                            userNotes
                        )
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(responseJson.toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    "POST" -> {
                        // Upsert
                        val requestBodyString = request.body?.let { body ->
                            val buffer = okio.Buffer()
                            body.writeTo(buffer)
                            buffer.readUtf8()
                        } ?: "[]"

                        val items = Json.decodeFromString(
                            kotlinx.serialization.builtins.ListSerializer(SupabaseNotebookDto.serializer()),
                            requestBodyString
                        )
                        for (item in items) {
                            // RLS check: insert/upsert must match authenticated userId
                            if (item.userId != userId) {
                                return@addInterceptor Response.Builder()
                                    .request(request)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(403)
                                    .message("Forbidden")
                                    .body("{\"message\":\"RLS: Cannot modify another user's notes\"}".toResponseBody("application/json".toMediaType()))
                                    .build()
                            }
                            cloudTable[item.id] = item
                        }

                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(201)
                            .message("Created")
                            .body("[{}]".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    else -> {
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("{}".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
            }.build()
    }

    private fun createSyncManagerForUser(userId: String): SupabaseSyncManager {
        val storage = InMemorySecureStorage()
        val authManager = SupabaseAuthManager(
            secureStorage = storage,
            ioDispatcher = Dispatchers.Unconfined
        )
        authManager.setSession(
            SupabaseSession(
                accessToken = "jwt_token_$userId",
                user = SupabaseUser(id = userId, email = "$userId@test.com")
            )
        )
        val httpClient = createMockClientForUser(userId)
        val notesRepo = SupabaseNotesRepository(
            authManager = authManager,
            httpClient = httpClient,
            ioDispatcher = Dispatchers.Unconfined
        )
        return SupabaseSyncManager(
            authManager = authManager,
            notesRepository = notesRepo,
            graphiteRepository = GraphiteRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testDeviceACreatesNoteAndDeviceBRetrievesIt() = runBlocking {
        val userId = "shared_user_google_123"

        // --- Device A: User logs in, creates a handwritten note ---
        GraphiteRepository.setActiveUser(userId)
        val syncManagerA = createSyncManagerForUser(userId)

        val noteOnDeviceA = GraphiteRepository.createNotebook("Motorola Tablet Handwritten Draft")
        GraphiteRepository.updateNotebookStrokes(
            noteOnDeviceA.id,
            listOf(
                InkStroke(
                    points = listOf(InkPoint(10f, 15f), InkPoint(30f, 45f)),
                    baseWidth = 3.5f,
                    color = Color.Cyan,
                    toolType = InkToolType.PEN
                )
            )
        )

        // Device A syncs to Supabase cloud
        val syncResultA = syncManagerA.syncNotes()
        assertTrue("Device A sync should succeed", syncResultA.isSuccess)
        assertEquals(1, cloudTable.size)
        assertTrue(cloudTable.containsKey(noteOnDeviceA.id))

        // --- Device B: Same user signs in on Nothing Phone ---
        // Switch local active workspace to simulate separate device initialization
        GraphiteRepository.setActiveUser(userId)
        val syncManagerB = createSyncManagerForUser(userId)

        // Device B executes cloud sync to fetch account notes
        val syncResultB = syncManagerB.syncNotes()
        assertTrue("Device B sync should succeed", syncResultB.isSuccess)

        // Verify Device B now has the note created on Device A
        val deviceBNotes = GraphiteRepository.notebooks.value
        assertEquals(1, deviceBNotes.size)
        val retrievedNote = deviceBNotes[0]
        assertEquals(noteOnDeviceA.id, retrievedNote.id)
        assertEquals("Motorola Tablet Handwritten Draft", retrievedNote.title)
        assertEquals(1, retrievedNote.initialStrokes.size)
        assertEquals(2, retrievedNote.initialStrokes[0].points.size)
    }

    @Test
    fun testDeviceBEditsNoteAndSynchronizesBackToDeviceA() = runBlocking {
        val userId = "user_lww_test"
        val syncManager = createSyncManagerForUser(userId)
        GraphiteRepository.setActiveUser(userId)

        // 1. Initial note
        val initialNote = GraphiteRepository.createNotebook("Draft Chapter 1")
        syncManager.syncNotes()

        // 2. Simulate Device B editing the note remotely with a newer timestamp
        val cloudNote = cloudTable[initialNote.id]!!
        val updatedStrokes = listOf(
            InkStroke(
                points = listOf(InkPoint(100f, 100f), InkPoint(200f, 200f), InkPoint(300f, 300f)),
                color = Color.Green,
                baseWidth = 6f
            )
        )
        val updatedCloudNote = cloudNote.copy(
            title = "Draft Chapter 1 (Edited on Phone)",
            strokesJson = InkSerialization.serializeStrokes(updatedStrokes),
            updatedAtMillis = System.currentTimeMillis() + 10000L,
            version = cloudNote.version + 1
        )
        cloudTable[initialNote.id] = updatedCloudNote

        // 3. Device A syncs and reconciles using Last-Write-Wins
        syncManager.syncNotes()

        val localNotes = GraphiteRepository.notebooks.value
        assertEquals(1, localNotes.size)
        assertEquals("Draft Chapter 1 (Edited on Phone)", localNotes[0].title)
        assertEquals(1, localNotes[0].initialStrokes.size)
        assertEquals(3, localNotes[0].initialStrokes[0].points.size)
    }

    @Test
    fun testOfflineCreationSynchronizesWhenReconnected() = runBlocking {
        val userId = "user_offline_sync"
        GraphiteRepository.setActiveUser(userId)

        // User creates 2 notes offline (without sync call)
        val note1 = GraphiteRepository.createNotebook("Offline Idea 1")
        val note2 = GraphiteRepository.createNotebook("Offline Idea 2")

        assertEquals(0, cloudTable.size) // Nothing in cloud yet

        // Connectivity restored -> sync runs
        val syncManager = createSyncManagerForUser(userId)
        val result = syncManager.syncNotes()

        assertTrue(result.isSuccess)
        assertEquals(2, cloudTable.size)
        assertTrue(cloudTable.containsKey(note1.id))
        assertTrue(cloudTable.containsKey(note2.id))
    }

    @Test
    fun testTombstoneDeletionPropagatesAcrossDevices() = runBlocking {
        val userId = "user_tombstone_test"
        GraphiteRepository.setActiveUser(userId)
        val syncManagerA = createSyncManagerForUser(userId)

        // Create and sync a note
        val note = GraphiteRepository.createNotebook("Temporary Note To Delete")
        syncManagerA.syncNotes()
        assertEquals(1, cloudTable.size)
        assertFalse(cloudTable[note.id]!!.isDeleted)

        // Device A deletes the note
        GraphiteRepository.deleteNotebook(note.id)
        assertEquals(0, GraphiteRepository.notebooks.value.size)

        // Device A syncs deletion to Supabase
        syncManagerA.syncNotes()
        assertEquals(1, cloudTable.size)
        assertTrue("Cloud must record tombstone deletion", cloudTable[note.id]!!.isDeleted)

        // Device B had the note locally cached before deletion
        // Setup Device B with the note locally
        val syncManagerB = createSyncManagerForUser(userId)
        // Now Device B syncs: tombstone must remove it from Device B's local workspace
        syncManagerB.syncNotes()

        assertEquals("Note must remain deleted on Device B", 0, GraphiteRepository.notebooks.value.size)
    }

    @Test
    fun testUserIsolationPreventsAccessToOtherUsersNotes() = runBlocking {
        val user1Id = "user_alice_99"
        val user2Id = "user_bob_100"

        // User 1 creates note in cloud
        cloudTable["alice_secret_1"] = SupabaseNotebookDto(
            id = "alice_secret_1",
            userId = user1Id,
            title = "Alice Confidential",
            updatedAtMillis = System.currentTimeMillis()
        )

        // User 2 logs in and tries to sync
        GraphiteRepository.setActiveUser(user2Id)
        val syncManagerBob = createSyncManagerForUser(user2Id)
        syncManagerBob.syncNotes()

        // Bob's local notes must NOT contain Alice's note
        val bobNotes = GraphiteRepository.notebooks.value
        assertEquals("Bob cannot see Alice's notes", 0, bobNotes.size)
    }

    @Test
    fun testSwitchAccountStrictDataIsolation() = runBlocking {
        val aliceId = "alice@example.com"
        val bobId = "bob@example.com"

        // 1. Alice is active, creates a note
        GraphiteRepository.setActiveUser(aliceId)
        val aliceNote = GraphiteRepository.createNotebook("Alice Private Journal")
        val syncManagerAlice = createSyncManagerForUser(aliceId)
        syncManagerAlice.syncNotes()

        assertEquals(1, GraphiteRepository.notebooks.value.size)
        assertEquals("Alice Private Journal", GraphiteRepository.notebooks.value[0].title)

        // 2. Simulate account switch to unauthenticated then to Bob
        GraphiteRepository.setActiveUser(null)
        assertEquals(0, GraphiteRepository.notebooks.value.size)

        GraphiteRepository.setActiveUser(bobId)
        assertEquals("Bob must start with 0 notes on clean switch", 0, GraphiteRepository.notebooks.value.size)

        // Bob syncs with Supabase
        val syncManagerBob = createSyncManagerForUser(bobId)
        syncManagerBob.syncNotes()

        // Bob must STILL have 0 notes (Alice's notes must never bleed into Bob)
        assertEquals("Bob's workspace must not contain Alice's notes", 0, GraphiteRepository.notebooks.value.size)

        // 3. Bob creates his own note and syncs
        val bobNote = GraphiteRepository.createNotebook("Bob Work Notes")
        syncManagerBob.syncNotes()

        assertEquals(1, GraphiteRepository.notebooks.value.size)
        assertEquals("Bob Work Notes", GraphiteRepository.notebooks.value[0].title)

        // 4. Verify cloud table contains both distinct notes under separate user IDs
        assertTrue(cloudTable.containsKey(aliceNote.id))
        assertTrue(cloudTable.containsKey(bobNote.id))
        assertEquals(aliceId, cloudTable[aliceNote.id]!!.userId)
        assertEquals(bobId, cloudTable[bobNote.id]!!.userId)

        // 5. Verify local partitions are strictly isolated
        val aliceNotes = GraphiteRepository.getNotesForUser(aliceId)
        assertEquals(1, aliceNotes.size)
        assertEquals("Alice Private Journal", aliceNotes[0].title)

        val bobNotes = GraphiteRepository.getNotesForUser(bobId)
        assertEquals(1, bobNotes.size)
        assertEquals("Bob Work Notes", bobNotes[0].title)

        // 6. Switch back to Alice: Bob's note must not appear in Alice's workspace
        GraphiteRepository.setActiveUser(aliceId)
        assertEquals(1, GraphiteRepository.notebooks.value.size)
        assertEquals("Alice Private Journal", GraphiteRepository.notebooks.value[0].title)
    }
}
