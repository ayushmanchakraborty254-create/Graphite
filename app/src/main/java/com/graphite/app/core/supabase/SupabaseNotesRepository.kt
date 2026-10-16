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
import java.io.File
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

    /**
     * Validates that the user is authenticated and retrieves a fresh, unexpired session.
     */
    private suspend fun getAuthenticatedSession(): SupabaseSession {
        val freshResult = authManager.getFreshSession()
        val session = freshResult.getOrNull() ?: authManager.currentSession
        if (session == null || !authManager.isAuthenticated()) {
            throw SecurityException(
                "Row Level Security Policy Violation: User must be authenticated before accessing notes."
            )
        }
        return session
    }

    /**
     * Executes an authenticated HTTP request, retrying once automatically with a refreshed token
     * if Supabase/PostgREST responds with HTTP 401 (e.g. PGRST301 JWT expired).
     */
    private suspend fun executeWithRetry(
        buildRequest: (token: String) -> Request
    ): okhttp3.Response {
        val session = getAuthenticatedSession()
        val initialResponse = httpClient.newCall(buildRequest(session.accessToken)).execute()
        if (initialResponse.code != 401) {
            return initialResponse
        }

        // HTTP 401 received: Attempt forced token refresh and single retry
        initialResponse.close()
        val refreshResult = authManager.refreshSession(force = true)
        val refreshedSession = refreshResult.getOrNull()
            ?: throw IOException("Session expired (HTTP 401) and token refresh failed: ${refreshResult.exceptionOrNull()?.message ?: "Unknown error"}")

        return httpClient.newCall(buildRequest(refreshedSession.accessToken)).execute()
    }

    suspend fun fetchNotes(): Result<List<NotebookItem>> = withContext(ioDispatcher) {
        try {
            executeWithRetry { token ->
                Request.Builder()
                    .url("$baseUrl/rest/v1/notebooks?select=*")
                    .header("apikey", publishableKey)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .get()
                    .build()
            }.use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code} fetching notes: $body")
                    )
                }

                val dtoList = json.decodeFromString<List<SupabaseNotebookDto>>(body)
                val context = try { com.graphite.app.GraphiteApplication.instance } catch (_: Throwable) { null }

                val notebooks = dtoList.map { dto ->
                    var resolvedMimeType: String? = null
                    var resolvedFileName: String? = null
                    var resolvedPageCount = 1
                    var resolvedFilePath: String? = null
                    val cleanPreviewLines = mutableListOf<String>()

                    for (line in dto.previewLines) {
                        if (line.startsWith("[GRAPHITE_DOC:")) {
                            val content = line.removePrefix("[GRAPHITE_DOC:").removeSuffix("]")
                            val parts = content.split(';')
                            for (p in parts) {
                                if (p.startsWith("mime=")) resolvedMimeType = p.removePrefix("mime=")
                                if (p.startsWith("name=")) resolvedFileName = p.removePrefix("name=")
                                if (p.startsWith("pages=")) resolvedPageCount = p.removePrefix("pages=").toIntOrNull() ?: 1
                            }
                        } else if (line.startsWith("[GRAPHITE_DATA:") && context != null) {
                            val b64 = line.removePrefix("[GRAPHITE_DATA:").removeSuffix("]")
                            try {
                                val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                                val notesDir = File(context.filesDir, "imported_notes").apply { mkdirs() }
                                val safeName = "${dto.id}_${(resolvedFileName ?: "document.pdf").replace("[^a-zA-Z0-9._-]".toRegex(), "_")}"
                                val targetFile = File(notesDir, safeName)
                                if (!targetFile.exists() || targetFile.length() != bytes.size.toLong()) {
                                    targetFile.writeBytes(bytes)
                                }
                                resolvedFilePath = targetFile.absolutePath
                            } catch (_: Exception) {}
                        } else {
                            cleanPreviewLines.add(line)
                        }
                    }

                    // Check if matching local file already exists in imported_notes
                    if (resolvedFilePath == null && context != null && resolvedFileName != null) {
                        val notesDir = File(context.filesDir, "imported_notes")
                        val possibleFile = File(notesDir, "${dto.id}_${resolvedFileName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}")
                        if (possibleFile.exists()) {
                            resolvedFilePath = possibleFile.absolutePath
                        }
                    }

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
                        previewLines = cleanPreviewLines,
                        initialStrokes = InkSerialization.deserializeStrokes(dto.strokesJson),
                        isDeleted = dto.isDeleted,
                        version = dto.version,
                        filePath = resolvedFilePath,
                        mimeType = resolvedMimeType,
                        originalFileName = resolvedFileName,
                        pageCount = resolvedPageCount
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
            val session = getAuthenticatedSession()
            val userId = authManager.currentUserId ?: session.user.id

            val dtoList = notebooks.map { notebook ->
                val effectivePreviewLines = if (!notebook.filePath.isNullOrBlank() && notebook.mimeType != null) {
                    val f = File(notebook.filePath)
                    val fileMetaHeader = "[GRAPHITE_DOC:mime=${notebook.mimeType};name=${notebook.originalFileName ?: f.name};pages=${notebook.pageCount}]"
                    val existingCleanPreview = notebook.previewLines.filter { !it.startsWith("[GRAPHITE_DOC:") && !it.startsWith("[GRAPHITE_DATA:") }
                    val linesWithMeta = mutableListOf(fileMetaHeader)
                    // If file exists and size is <= 8MB, embed Base64 so second device materializes file
                    if (f.exists() && f.length() in 1..(8 * 1024 * 1024)) {
                        try {
                            val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                            linesWithMeta.add("[GRAPHITE_DATA:$b64]")
                        } catch (_: Exception) {}
                    }
                    linesWithMeta.addAll(existingCleanPreview)
                    linesWithMeta
                } else {
                    notebook.previewLines
                }

                SupabaseNotebookDto(
                    id = notebook.id,
                    userId = userId,
                    title = notebook.title,
                    folderId = notebook.folderId,
                    paperStyle = notebook.paperStyle.name,
                    previewLines = effectivePreviewLines,
                    strokesJson = InkSerialization.serializeStrokes(notebook.initialStrokes),
                    updatedAt = notebook.updatedAtFormatted,
                    updatedAtMillis = notebook.updatedAtMillis,
                    isDeleted = notebook.isDeleted,
                    version = notebook.version
                )
            }

            val payload = json.encodeToString(dtoList)

            executeWithRetry { token ->
                Request.Builder()
                    .url("$baseUrl/rest/v1/notebooks")
                    .header("apikey", publishableKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .header("Prefer", "resolution=merge-duplicates,return=representation")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
            }.use { response ->
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
            executeWithRetry { token ->
                Request.Builder()
                    .url("$baseUrl/rest/v1/notebooks?id=eq.$notebookId")
                    .header("apikey", publishableKey)
                    .header("Authorization", "Bearer $token")
                    .delete()
                    .build()
            }.use { response ->
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
