package com.graphite.app.core.auth

import com.graphite.app.core.security.InMemorySecureStorage
import com.graphite.app.core.supabase.SupabaseAuthManager
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GoogleAccountManagerTest {

    private lateinit var secureStorage: InMemorySecureStorage
    private lateinit var supabaseAuthManager: SupabaseAuthManager

    @Before
    fun setUp() {
        secureStorage = InMemorySecureStorage()
        supabaseAuthManager = SupabaseAuthManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun testInitialStateIsNullWhenStorageEmpty() {
        val manager = GoogleAccountManager(
            secureStorage = secureStorage,
            supabaseAuthManager = supabaseAuthManager
        )

        assertNull(manager.currentUser.value)
    }

    @Test
    fun testRestoresGoogleProfileFromStorage() {
        secureStorage.putString("google_user_email", "ayush@gmail.com")
        secureStorage.putString("google_user_display_name", "Ayush")
        secureStorage.putString("google_user_photo_url", "https://example.com/avatar.jpg")
        secureStorage.putString("google_user_id_token", "sample_google_id_token_xyz")

        val manager = GoogleAccountManager(
            secureStorage = secureStorage,
            supabaseAuthManager = supabaseAuthManager
        )

        val user = manager.currentUser.value
        assertNotNull(user)
        assertEquals("ayush@gmail.com", user?.email)
        assertEquals("Ayush", user?.displayName)
        assertEquals("https://example.com/avatar.jpg", user?.photoUrl)
        assertEquals("sample_google_id_token_xyz", user?.idToken)
    }

    @Test
    fun testSetProfileDirectlyPersistsAndBridgesToSupabase() {
        val manager = GoogleAccountManager(
            secureStorage = secureStorage,
            supabaseAuthManager = supabaseAuthManager
        )

        val profile = GoogleProfile(
            email = "dev@google.com",
            displayName = "Developer Account",
            photoUrl = "https://google.com/dev.png",
            idToken = "token_abc_123"
        )

        manager.setProfileDirectly(profile)

        // Check Google state
        assertEquals(profile, manager.currentUser.value)
        assertEquals("dev@google.com", secureStorage.getString("google_user_email"))
        assertEquals("Developer Account", secureStorage.getString("google_user_display_name"))
        assertEquals("token_abc_123", secureStorage.getString("google_user_id_token"))

        // Verify automatic bridge to Supabase Auth for Postgres RLS access
        assertTrue(supabaseAuthManager.isAuthenticated())
        assertEquals("dev@google.com", supabaseAuthManager.currentUserId)
        assertEquals("token_abc_123", supabaseAuthManager.currentSession?.accessToken)
    }

    @Test
    fun testClearLocalSessionRemovesStorageAndClearsSupabaseSession() {
        val manager = GoogleAccountManager(
            secureStorage = secureStorage,
            supabaseAuthManager = supabaseAuthManager
        )

        val profile = GoogleProfile(
            email = "test@google.com",
            displayName = "Tester",
            idToken = "jwt_test"
        )
        manager.setProfileDirectly(profile)
        assertTrue(supabaseAuthManager.isAuthenticated())

        manager.clearLocalSession()

        // Google profile is cleared
        assertNull(manager.currentUser.value)
        assertNull(secureStorage.getString("google_user_email"))
        assertNull(secureStorage.getString("google_user_display_name"))
        assertNull(secureStorage.getString("google_user_id_token"))

        // Supabase session is cleared as well
        assertFalse(supabaseAuthManager.isAuthenticated())
    }

    @Test
    fun testSwitchAccountIsolatesNotesWorkspaceWithoutLeakingData() {
        val manager = GoogleAccountManager(
            secureStorage = secureStorage,
            supabaseAuthManager = supabaseAuthManager
        )

        // 1. User A signs in
        val userA = GoogleProfile(
            email = "alice@example.com",
            displayName = "Alice",
            photoUrl = "https://example.com/alice.png",
            idToken = "token_alice"
        )
        manager.setProfileDirectly(userA)
        val noteA = com.graphite.app.data.repository.GraphiteRepository.createNotebook("Alice Secret Note")
        assertEquals(1, com.graphite.app.data.repository.GraphiteRepository.notebooks.value.size)
        assertEquals("Alice Secret Note", com.graphite.app.data.repository.GraphiteRepository.notebooks.value[0].title)

        // 2. User B signs in (Account Switch)
        val userB = GoogleProfile(
            email = "bob@example.com",
            displayName = "Bob",
            photoUrl = "https://example.com/bob.png",
            idToken = "token_bob"
        )
        manager.setProfileDirectly(userB)

        // User B's workspace must NOT see Alice's note!
        val bNotes = com.graphite.app.data.repository.GraphiteRepository.notebooks.value
        assertEquals("Bob's workspace must not contain Alice's notes", 0, bNotes.size)

        // Bob creates his own note
        val noteB = com.graphite.app.data.repository.GraphiteRepository.createNotebook("Bob Note")
        assertEquals(1, com.graphite.app.data.repository.GraphiteRepository.notebooks.value.size)
        assertEquals("Bob Note", com.graphite.app.data.repository.GraphiteRepository.notebooks.value[0].title)

        // 3. Switch back to Alice
        manager.setProfileDirectly(userA)
        val aliceNotesAgain = com.graphite.app.data.repository.GraphiteRepository.notebooks.value
        assertEquals(1, aliceNotesAgain.size)
        assertEquals("Alice Secret Note", aliceNotesAgain[0].title)
    }

    @Test
    fun testSignOutClearsActiveWorkspaceWithoutDeletingSavedNotes() {
        val manager = GoogleAccountManager(
            secureStorage = secureStorage,
            supabaseAuthManager = supabaseAuthManager
        )

        val user = GoogleProfile(
            email = "claire@example.com",
            displayName = "Claire",
            idToken = "token_claire"
        )
        manager.setProfileDirectly(user)
        com.graphite.app.data.repository.GraphiteRepository.createNotebook("Claire Project Plan")
        assertEquals(1, com.graphite.app.data.repository.GraphiteRepository.notebooks.value.size)

        // Sign out
        manager.clearLocalSession()

        // Visible workspace is cleared
        assertEquals(0, com.graphite.app.data.repository.GraphiteRepository.notebooks.value.size)

        // Logging back in restores Claire's notes safely
        manager.setProfileDirectly(user)
        assertEquals(1, com.graphite.app.data.repository.GraphiteRepository.notebooks.value.size)
        assertEquals("Claire Project Plan", com.graphite.app.data.repository.GraphiteRepository.notebooks.value[0].title)
    }

    @Test
    fun testAvatarUrlExtractionFromUserMetadata() {
        val userWithAvatarUrl = com.graphite.app.core.supabase.SupabaseUser(
            id = "user-1",
            email = "avatar@test.com",
            userMetadata = mapOf(
                "avatar_url" to kotlinx.serialization.json.JsonPrimitive("https://lh3.googleusercontent.com/photo.jpg"),
                "full_name" to kotlinx.serialization.json.JsonPrimitive("Avatar User")
            )
        )
        assertEquals("https://lh3.googleusercontent.com/photo.jpg", userWithAvatarUrl.avatarUrl)
        assertEquals("Avatar User", userWithAvatarUrl.fullName)

        val userWithPicture = com.graphite.app.core.supabase.SupabaseUser(
            id = "user-2",
            email = "pic@test.com",
            userMetadata = mapOf(
                "picture" to kotlinx.serialization.json.JsonPrimitive("https://google.com/pic.png")
            )
        )
        assertEquals("https://google.com/pic.png", userWithPicture.avatarUrl)

        val userWithoutAvatar = com.graphite.app.core.supabase.SupabaseUser(
            id = "user-3",
            email = "noavatar@test.com",
            userMetadata = emptyMap()
        )
        assertNull(userWithoutAvatar.avatarUrl)
    }
}
