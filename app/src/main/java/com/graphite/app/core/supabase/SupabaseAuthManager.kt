package com.graphite.app.core.supabase

import com.graphite.app.core.config.GraphiteConfig
import com.graphite.app.core.security.SecureStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        } else {
            _authState.value = SupabaseAuthState.Unauthenticated
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
                _authState.value = SupabaseAuthState.Error(errorMsg)
                return Result.failure(exception)
            }

            val session = json.decodeFromString<SupabaseSession>(body)
            saveSessionToStorage(session)
            _authState.value = SupabaseAuthState.Authenticated(session)
            return Result.success(session)
        }
    }

    private fun saveSessionToStorage(session: SupabaseSession) {
        secureStorage.putString(KEY_ACCESS_TOKEN, session.accessToken)
        secureStorage.putString(KEY_REFRESH_TOKEN, session.refreshToken)
        secureStorage.putString(KEY_USER_ID, session.user.id)
        secureStorage.putString(KEY_USER_EMAIL, session.user.email)
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
