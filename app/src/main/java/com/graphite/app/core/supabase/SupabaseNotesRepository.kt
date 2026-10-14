package com.graphite.app.core.supabase

import com.graphite.app.core.config.GraphiteConfig
import com.graphite.app.data.model.NotebookItem
import com.graphite.app.feature.workspace.model.PaperStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SupabaseNotesRepository(
    private val authManager: SupabaseAuthManager,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val baseUrl: String = GraphiteConfig.supabaseUrl,
    private val publishableKey: String = GraphiteConfig.supabasePublishableKey
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    /**
     * Validates that the user is authenticated before executing any operations under RLS.
     */
    private fun requireAuthenticatedSession(): SupabaseSession {
        val session = authManager.currentSession
        if (session == null || !authManager.isAuthenticated()) {
            throw SecurityException(
                "Row Level Security Policy Violation: User must be authenticated before accessing notes."
            )
        }
        return session
    }

    suspend fun fetchNotes(): Result<List<NotebookItem>> = withContext(ioDispatcher) {
        try {
            val session = requireAuthenticatedSession()

            val request = Request.Builder()
                .url("$baseUrl/rest/v1/notebooks?select=*")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code} fetching notes: $body")
                    )
                }

                val dtoList = json.decodeFromString<List<SupabaseNotebookDto>>(body)
                val notebooks = dtoList.map { dto ->
                    NotebookItem(
                        id = dto.id,
                        userId = dto.userId,
                        title = dto.title,
                        folderId = dto.folderId,
                        updatedAtFormatted = dto.updatedAt ?: "Synced",
                        updatedAtMillis = if (dto.updatedAtMillis > 0) dto.updatedAtMillis else System.currentTimeMillis(),
                        paperStyle = try {
                            PaperStyle.valueOf(dto.paperStyle)
                        } catch (_: Exception) {
                            PaperStyle.RULED
                        },
                        previewLines = dto.previewLines,
                        initialStrokes = InkSerialization.deserializeStrokes(dto.strokesJson),
                        isDeleted = dto.isDeleted,
                        version = dto.version
                    )
                }
                Result.success(notebooks)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun upsertNote(notebook: NotebookItem): Result<NotebookItem> = withContext(ioDispatcher) {
        val result = upsertNotes(listOf(notebook))
        if (result.isSuccess) {
            Result.success(notebook)
        } else {
            Result.failure(result.exceptionOrNull() ?: IOException("Failed to upsert note"))
        }
    }

    suspend fun upsertNotes(notebooks: List<NotebookItem>): Result<List<NotebookItem>> = withContext(ioDispatcher) {
        if (notebooks.isEmpty()) return@withContext Result.success(emptyList())

        try {
            val session = requireAuthenticatedSession()

            val dtoList = notebooks.map { notebook ->
                SupabaseNotebookDto(
                    id = notebook.id,
                    userId = session.user.id,
                    title = notebook.title,
                    folderId = notebook.folderId,
                    paperStyle = notebook.paperStyle.name,
                    previewLines = notebook.previewLines,
                    strokesJson = InkSerialization.serializeStrokes(notebook.initialStrokes),
                    updatedAt = notebook.updatedAtFormatted,
                    updatedAtMillis = notebook.updatedAtMillis,
                    isDeleted = notebook.isDeleted,
                    version = notebook.version
                )
            }

            val payload = json.encodeToString(dtoList)

            val request = Request.Builder()
                .url("$baseUrl/rest/v1/notebooks")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=representation")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code} upserting notes: $body")
                    )
                }
                Result.success(notebooks)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteNote(notebookId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val session = requireAuthenticatedSession()

            val request = Request.Builder()
                .url("$baseUrl/rest/v1/notebooks?id=eq.$notebookId")
                .header("apikey", publishableKey)
                .header("Authorization", "Bearer ${session.accessToken}")
                .delete()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code} deleting note: $body")
                    )
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
