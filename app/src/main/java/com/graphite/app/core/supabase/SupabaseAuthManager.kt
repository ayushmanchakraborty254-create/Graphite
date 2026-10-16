package com.graphite.app.core.supabase

import com.graphite.app.core.config.GraphiteConfig
import com.graphite.app.core.security.SecureStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SupabaseAuthManager(
    private val secureStorage: SecureStorage,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val baseUrl: String = GraphiteConfig.supabaseUrl,
    private val publishableKey: String = GraphiteConfig.supabasePublishableKey
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val refreshMutex = Mutex()
    private val _authState = MutableStateFlow<SupabaseAuthState>(SupabaseAuthState.Unauthenticated)
    val authState: StateFlow<SupabaseAuthState> = _authState.asStateFlow()

    init {
        restoreSession()
    }

    val currentSession: SupabaseSession?
        get() = (_authState.value as? SupabaseAuthState.Authenticated)?.session

    val currentUserId: String?
        get() = currentSession?.user?.id

    fun isAuthenticated(): Boolean = currentSession != null

    private fun restoreSession() {
        val token = secureStorage.getString(KEY_ACCESS_TOKEN)
        val userId = secureStorage.getString(KEY_USER_ID)
        val email = secureStorage.getString(KEY_USER_EMAIL)
        val refreshToken = secureStorage.getString(KEY_REFRESH_TOKEN)
        val expiresAt = secureStorage.getLong(KEY_EXPIRES_AT, 0L)

        if (!token.isNullOrBlank() && !userId.isNullOrBlank()) {
            val session = SupabaseSession(
                accessToken = token,
                refreshToken = refreshToken,
                user = SupabaseUser(id = userId, email = email),
                expiresAtMillis = expiresAt
            )
            _authState.value = SupabaseAuthState.Authenticated(session)
        } else if (!refreshToken.isNullOrBlank() && !userId.isNullOrBlank()) {
            val session = SupabaseSession(
                accessToken = "",
                refreshToken = refreshToken,
                user = SupabaseUser(id = userId, email = email),
                expiresAtMillis = 0L
            )
            _authState.value = SupabaseAuthState.Authenticated(session)
        } else {
            _authState.value = SupabaseAuthState.Unauthenticated
        }
    }

    /**
     * Returns an active and unexpired SupabaseSession.
     * If the current session has expired (or is close to expiring), it automatically
     * refreshes the session in the background via Supabase GoTrue.
     */
    suspend fun getFreshSession(): Result<SupabaseSession> = withContext(ioDispatcher) {
        val session = currentSession
        if (session == null) {
            val refreshToken = secureStorage.getString(KEY_REFRESH_TOKEN)
            if (!refreshToken.isNullOrBlank()) {
                return@withContext refreshSession()
            }
            return@withContext Result.failure(IllegalStateException("User is not authenticated"))
        }

        if (session.isExpired || session.accessToken.isBlank()) {
            return@withContext refreshSession()
        }

        Result.success(session)
    }

    /**
     * Explicitly refreshes the current session using the stored refresh_token.
     * Thread-safe through Mutex lock to prevent duplicate concurrent refresh requests.
     * Set [force] to true to force refresh regardless of local clock expiration (e.g. after HTTP 401).
     */
    suspend fun refreshSession(force: Boolean = false): Result<SupabaseSession> = withContext(ioDispatcher) {
        refreshMutex.withLock {
            val current = currentSession
            if (!force && current != null && !current.isExpired && current.accessToken.isNotBlank()) {
                return@withLock Result.success(current)
            }

            val refreshToken = current?.refreshToken
                ?: secureStorage.getString(KEY_REFRESH_TOKEN)

            if (refreshToken.isNullOrBlank()) {
                return@withLock Result.failure(
                    IllegalStateException("No refresh token available to refresh session")
                )
            }

            try {
                val payload = buildJsonObject {
                    put("refresh_token", refreshToken)
                }.toString()

                val request = Request.Builder()
                    .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
                    .header("apikey", publishableKey)
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                executeAuthRequest(request)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<SupabaseSession> = withContext(ioDispatcher) {
        _authState.value = SupabaseAuthState.Loading
        try {
            val payload = buildJsonObject {
                put("email", email)
                put("password", password)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/auth/v1/token?grant_type=password")
                .header("apikey", publishableKey)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            executeAuthRequest(request)
        } catch (e: Exception) {
            _authState.value = SupabaseAuthState.Error(e.message ?: "Authentication failed")
            Result.failure(e)
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<SupabaseSession> = withContext(ioDispatcher) {
        _authState.value = SupabaseAuthState.Loading
        try {
            val payload = buildJsonObject {
                put("email", email)
                put("password", password)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/auth/v1/signup")
                .header("apikey", publishableKey)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            executeAuthRequest(request)
        } catch (e: Exception) {
            _authState.value = SupabaseAuthState.Error(e.message ?: "Signup failed")
            Result.failure(e)
        }
    }

    suspend fun signInWithGoogleIdToken(idToken: String): Result<SupabaseSession> = withContext(ioDispatcher) {
        _authState.value = SupabaseAuthState.Loading
        try {
            val payload = buildJsonObject {
                put("provider", "google")
                put("id_token", idToken)
            }.toString()

            val request = Request.Builder()
                .url("$baseUrl/auth/v1/token?grant_type=id_token")
                .header("apikey", publishableKey)
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            executeAuthRequest(request)
        } catch (e: Exception) {
            _authState.value = SupabaseAuthState.Error(e.message ?: "Google authentication with Supabase failed")
            Result.failure(e)
        }
    }

    /**
     * Sets an active session directly (e.g. from existing OAuth flow or tests)
     */
    fun setSession(session: SupabaseSession) {
        saveSessionToStorage(session)
        _authState.value = SupabaseAuthState.Authenticated(session)
    }

    suspend fun signOut() = withContext(ioDispatcher) {
        val currentToken = currentSession?.accessToken
        if (!currentToken.isNullOrBlank()) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/auth/v1/logout")
                    .header("apikey", publishableKey)
                    .header("Authorization", "Bearer $currentToken")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().close()
            } catch (_: Exception) {
                // Ignore remote logout network errors, proceed with local clean-up
            }
        }
        clearSession()
    }

    fun clearSession() {
        secureStorage.remove(KEY_ACCESS_TOKEN)
        secureStorage.remove(KEY_REFRESH_TOKEN)
        secureStorage.remove(KEY_USER_ID)
        secureStorage.remove(KEY_USER_EMAIL)
        secureStorage.remove(KEY_EXPIRES_AT)
        _authState.value = SupabaseAuthState.Unauthenticated
    }

    private fun executeAuthRequest(request: Request): Result<SupabaseSession> {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(body) ?: "HTTP ${response.code}: Authentication request failed"
                val exception = IOException(errorMsg)
                if (response.code in listOf(400, 401) &&
                    (body.contains("invalid_grant", ignoreCase = true) ||
                     body.contains("refresh_token_not_found", ignoreCase = true))
                ) {
                    clearSession()
                } else {
                    _authState.value = SupabaseAuthState.Error(errorMsg)
                }
                return Result.failure(exception)
            }

            val decodedSession = json.decodeFromString<SupabaseSession>(body)
            val current = currentSession
            val finalUser = if (decodedSession.user.id.isNotBlank() && decodedSession.user.id != "user") {
                decodedSession.user
            } else {
                current?.user ?: SupabaseUser(
                    id = secureStorage.getString(KEY_USER_ID) ?: "user",
                    email = secureStorage.getString(KEY_USER_EMAIL)
                )
            }
            val finalRefreshToken = decodedSession.refreshToken?.takeIf { it.isNotBlank() }
                ?: current?.refreshToken
                ?: secureStorage.getString(KEY_REFRESH_TOKEN)

            val session = decodedSession.copy(
                user = finalUser,
                refreshToken = finalRefreshToken
            )
            saveSessionToStorage(session)
            _authState.value = SupabaseAuthState.Authenticated(session)
            return Result.success(session)
        }
    }

    private fun saveSessionToStorage(session: SupabaseSession) {
        val existingRefreshToken = secureStorage.getString(KEY_REFRESH_TOKEN)
        val existingUserId = secureStorage.getString(KEY_USER_ID)
        val existingEmail = secureStorage.getString(KEY_USER_EMAIL)

        val refreshTokenToSave = session.refreshToken?.takeIf { it.isNotBlank() } ?: existingRefreshToken
        val userIdToSave = session.user.id.takeIf { it.isNotBlank() && it != "user" } ?: existingUserId ?: session.user.id
        val emailToSave = session.user.email?.takeIf { it.isNotBlank() } ?: existingEmail

        secureStorage.putString(KEY_ACCESS_TOKEN, session.accessToken)
        secureStorage.putString(KEY_REFRESH_TOKEN, refreshTokenToSave)
        secureStorage.putString(KEY_USER_ID, userIdToSave)
        secureStorage.putString(KEY_USER_EMAIL, emailToSave)
        secureStorage.putLong(KEY_EXPIRES_AT, session.expiresAtMillis)
    }

    private fun parseErrorMessage(body: String): String? {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["error_description"]?.jsonPrimitive?.content
                ?: obj["msg"]?.jsonPrimitive?.content
                ?: obj["message"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "supabase_access_token"
        private const val KEY_REFRESH_TOKEN = "supabase_refresh_token"
        private const val KEY_USER_ID = "supabase_user_id"
        private const val KEY_USER_EMAIL = "supabase_user_email"
        private const val KEY_EXPIRES_AT = "supabase_expires_at"
    }
}
