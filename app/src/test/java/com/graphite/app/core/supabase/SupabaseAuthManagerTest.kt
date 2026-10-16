package com.graphite.app.core.supabase

import com.graphite.app.core.security.InMemorySecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SupabaseAuthManagerTest {

    private lateinit var secureStorage: InMemorySecureStorage

    @Before
    fun setUp() {
        secureStorage = InMemorySecureStorage()
    }

    @Test
    fun testInitialStateIsUnauthenticatedWhenStorageEmpty() {
        val authManager = SupabaseAuthManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )

        assertFalse(authManager.isAuthenticated())
        assertNull(authManager.currentSession)
        assertEquals(SupabaseAuthState.Unauthenticated, authManager.authState.value)
    }

    @Test
    fun testRestoresSessionFromSecureStorage() {
        secureStorage.putString("supabase_access_token", "saved_jwt_access_token")
        secureStorage.putString("supabase_user_id", "user-uuid-123")
        secureStorage.putString("supabase_user_email", "ayush@graphite.com")
        secureStorage.putLong("supabase_expires_at", System.currentTimeMillis() + 3600_000L)

        val authManager = SupabaseAuthManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )

        assertTrue(authManager.isAuthenticated())
        assertEquals("user-uuid-123", authManager.currentUserId)
        assertEquals("ayush@graphite.com", authManager.currentSession?.user?.email)
        assertEquals("saved_jwt_access_token", authManager.currentSession?.accessToken)
        assertTrue(authManager.authState.value is SupabaseAuthState.Authenticated)
    }

    @Test
    fun testSuccessfulSignInStoresTokensInSecureStorage() = runBlocking {
        val mockSuccessJson = """
            {
                "access_token": "mock_new_access_token",
                "token_type": "bearer",
                "expires_in": 3600,
                "refresh_token": "mock_refresh_token",
                "user": {
                    "id": "uuid-999",
                    "email": "test@graphite.app"
                }
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(mockSuccessJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        val authManager = SupabaseAuthManager(
            secureStorage = secureStorage,
            httpClient = mockClient,
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = authManager.signInWithEmail("test@graphite.app", "password123")

        assertTrue(result.isSuccess)
        val session = result.getOrThrow()
        assertEquals("uuid-999", session.user.id)
        assertEquals("mock_new_access_token", session.accessToken)

        // Verify SecureStorage persistence
        assertEquals("mock_new_access_token", secureStorage.getString("supabase_access_token"))
        assertEquals("uuid-999", secureStorage.getString("supabase_user_id"))
        assertEquals("test@graphite.app", secureStorage.getString("supabase_user_email"))
        assertEquals("mock_refresh_token", secureStorage.getString("supabase_refresh_token"))
    }

    @Test
    fun testSignOutClearsSecureStorageAndResetsState() = runBlocking {
        val session = SupabaseSession(
            accessToken = "token_to_clear",
            refreshToken = "refresh_to_clear",
            user = SupabaseUser(id = "user-1", email = "u1@graphite.com")
        )

        val authManager = SupabaseAuthManager(
            secureStorage = secureStorage,
            ioDispatcher = Dispatchers.Unconfined
        )
        authManager.setSession(session)
        assertTrue(authManager.isAuthenticated())

        authManager.signOut()

        assertFalse(authManager.isAuthenticated())
        assertNull(secureStorage.getString("supabase_access_token"))
        assertNull(secureStorage.getString("supabase_user_id"))
        assertEquals(SupabaseAuthState.Unauthenticated, authManager.authState.value)
    }

    @Test
    fun testRefreshSessionSuccessfullyUpdatesToken() = runBlocking {
        secureStorage.putString("supabase_access_token", "expired_token")
        secureStorage.putString("supabase_refresh_token", "valid_refresh_token")
        secureStorage.putString("supabase_user_id", "user-uuid-123")
        secureStorage.putString("supabase_user_email", "ayush@graphite.com")
        secureStorage.putLong("supabase_expires_at", System.currentTimeMillis() - 10_000L)

        val refreshResponseJson = """
            {
                "access_token": "freshly_refreshed_access_token",
                "token_type": "bearer",
                "expires_in": 3600,
                "refresh_token": "new_rotated_refresh_token",
                "user": {
                    "id": "user-uuid-123",
                    "email": "ayush@graphite.com"
                }
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(refreshResponseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        val authManager = SupabaseAuthManager(
            secureStorage = secureStorage,
            httpClient = mockClient,
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = authManager.refreshSession()
        assertTrue(result.isSuccess)
        val refreshed = result.getOrThrow()
        assertEquals("freshly_refreshed_access_token", refreshed.accessToken)
        assertEquals("new_rotated_refresh_token", refreshed.refreshToken)
        assertEquals("freshly_refreshed_access_token", secureStorage.getString("supabase_access_token"))
        assertEquals("new_rotated_refresh_token", secureStorage.getString("supabase_refresh_token"))
    }

    @Test
    fun testGetFreshSessionTriggersRefreshWhenExpired() = runBlocking {
        secureStorage.putString("supabase_access_token", "expired_token")
        secureStorage.putString("supabase_refresh_token", "valid_refresh_token")
        secureStorage.putString("supabase_user_id", "user-uuid-123")
        secureStorage.putLong("supabase_expires_at", System.currentTimeMillis() - 100_000L)

        val refreshResponseJson = """
            {
                "access_token": "auto_refreshed_token",
                "token_type": "bearer",
                "expires_in": 3600
            }
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(refreshResponseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

        val authManager = SupabaseAuthManager(
            secureStorage = secureStorage,
            httpClient = mockClient,
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = authManager.getFreshSession()
        assertTrue(result.isSuccess)
        assertEquals("auto_refreshed_token", result.getOrThrow().accessToken)
        assertEquals("valid_refresh_token", result.getOrThrow().refreshToken)
    }
}
