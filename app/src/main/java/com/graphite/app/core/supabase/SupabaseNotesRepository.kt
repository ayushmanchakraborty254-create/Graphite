package com.graphite.app.core.supabase

import com.graphite.app.core.config.GraphiteConfig
import com.graphite.app.data.model.NotebookItem
import com.graphite.app.feature.workspace.model.PaperStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
            val session = getAuthenticatedSession()
            val userId = authManager.currentUserId ?: session.user.id
            if (userId.isBlank()) {
                return@withContext Result.success(emptyList())
            }

            executeWithRetry { token ->
                Request.Builder()
                    .url("$baseUrl/rest/v1/notebooks?select=*&user_id=eq.$userId")
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

                // Strictly filter to ensure only notes belonging to the current authenticated user are processed
                val userOnlyDtoList = dtoList.filter { it.userId == userId }

                val notebooks = userOnlyDtoList.map { dto ->
                    var resolvedMimeType: String? = null
                    var resolvedFileName: String? = null
                    var resolvedPageCount = 1
                    var resolvedFilePath: String? = null
                    var resolvedIsStarred = false
                    var resolvedIsBookmarked = false
                    var resolvedIsLocked = false
                    var resolvedPassword: String? = null
                    var resolvedUpdatedAtMillis: Long? = null
                    var resolvedCreatedAtMillis: Long? = null
                    var resolvedCover = com.graphite.app.data.model.NotebookCover()
                    val resolvedVoiceNotes = mutableListOf<com.graphite.app.core.voice.VoiceClip>()
                    val cleanPreviewLines = mutableListOf<String>()

                    for (line in dto.previewLines) {
                        if (line.startsWith("[GRAPHITE_META:")) {
                            val content = line.removePrefix("[GRAPHITE_META:").removeSuffix("]")
                            val parts = content.split(';')
                            var covCat = "PLAIN"
                            var covCol = 0xFF45A5B5
                            var covName = "Teal"
                            var covBand: Long? = null
                            var covPat: String? = null
                            var covImg: String? = null

                            for (p in parts) {
                                if (p.startsWith("starred=")) resolvedIsStarred = p.removePrefix("starred=").toBoolean()
                                if (p.startsWith("bookmark=")) resolvedIsBookmarked = p.removePrefix("bookmark=").toBoolean()
                                if (p.startsWith("locked=")) resolvedIsLocked = p.removePrefix("locked=").toBoolean()
                                if (p.startsWith("pwd=")) resolvedPassword = p.removePrefix("pwd=")
                                if (p.startsWith("updated_millis=")) resolvedUpdatedAtMillis = p.removePrefix("updated_millis=").toLongOrNull()
                                if (p.startsWith("created_millis=")) resolvedCreatedAtMillis = p.removePrefix("created_millis=").toLongOrNull()
                                if (p.startsWith("cov_cat=")) covCat = p.removePrefix("cov_cat=")
                                if (p.startsWith("cov_col=")) covCol = p.removePrefix("cov_col=").toLongOrNull() ?: 0xFF45A5B5
                                if (p.startsWith("cov_name=")) covName = p.removePrefix("cov_name=")
                                if (p.startsWith("cov_band=")) covBand = p.removePrefix("cov_band=").toLongOrNull()
                                if (p.startsWith("cov_pat=")) covPat = p.removePrefix("cov_pat=")
                                if (p.startsWith("cov_img=")) covImg = p.removePrefix("cov_img=")
                            }
                            resolvedCover = com.graphite.app.data.model.NotebookCover(
                                category = covCat,
                                colorHex = covCol,
                                name = covName,
                                bandColorHex = covBand,
                                patternType = covPat,
                                customImageUri = covImg
                            )
                        } else if (line.startsWith("[GRAPHITE_VOICE:")) {
                            val content = line.removePrefix("[GRAPHITE_VOICE:").removeSuffix("]")
                            val parts = content.split(';')
                            var vId = java.util.UUID.randomUUID().toString()
                            var vPage = 0
                            var vTitle = "Voice Note"
                            var vDur = 1
                            var vCreated = ""
                            var vTrans: String? = null
                            var vData = ""
                            for (p in parts) {
                                if (p.startsWith("id=")) vId = p.removePrefix("id=")
                                if (p.startsWith("page=")) vPage = p.removePrefix("page=").toIntOrNull() ?: 0
                                if (p.startsWith("title=")) vTitle = p.removePrefix("title=")
                                if (p.startsWith("dur=")) vDur = p.removePrefix("dur=").toIntOrNull() ?: 1
                                if (p.startsWith("created=")) vCreated = p.removePrefix("created=")
                                if (p.startsWith("trans=")) {
                                    val t = p.removePrefix("trans=")
                                    if (t.isNotBlank()) vTrans = t
                                }
                                if (p.startsWith("data=")) vData = p.removePrefix("data=")
                            }
                            var localAudioPath = ""
                            if (context != null) {
                                val voiceDir = File(context.filesDir, "voice_notes").apply { mkdirs() }
                                val targetFile = File(voiceDir, "voice_${dto.id}_$vId.m4a")
                                if (vData.isNotBlank() && (!targetFile.exists() || targetFile.length() == 0L)) {
                                    try {
                                        val bytes = android.util.Base64.decode(vData, android.util.Base64.NO_WRAP)
                                        targetFile.writeBytes(bytes)
                                    } catch (_: Exception) {}
                                }
                                if (targetFile.exists()) {
                                    localAudioPath = targetFile.absolutePath
                                }
                            }
                            resolvedVoiceNotes.add(
                                com.graphite.app.core.voice.VoiceClip(
                                    id = vId,
                                    filePath = localAudioPath,
                                    title = vTitle,
                                    durationSeconds = vDur,
                                    createdAtFormatted = vCreated,
                                    transcription = vTrans,
                                    pageIndex = vPage
                                )
                            )
                        } else if (line.startsWith("[GRAPHITE_DOC:")) {
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

                    val finalUpdatedAtMillis = if (dto.updatedAtMillis > 0) {
                        dto.updatedAtMillis
                    } else if (resolvedUpdatedAtMillis != null && resolvedUpdatedAtMillis > 0) {
                        resolvedUpdatedAtMillis
                    } else {
                        try {
                            val sdf = java.text.SimpleDateFormat("dd/MM/yy hh:mm a", java.util.Locale.US)
                            sdf.parse(dto.updatedAt ?: "")?.time ?: 0L
                        } catch (_: Exception) {
                            0L
                        }
                    }
                    val finalCreatedAtMillis = resolvedCreatedAtMillis ?: finalUpdatedAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

                    NotebookItem(
                        id = dto.id,
                        userId = dto.userId,
                        title = dto.title,
                        folderId = dto.folderId,
                        updatedAtFormatted = dto.updatedAt ?: "Synced",
                        updatedAtMillis = if (finalUpdatedAtMillis > 0) finalUpdatedAtMillis else System.currentTimeMillis(),
                        createdAtMillis = finalCreatedAtMillis,
                        paperStyle = try {
                            PaperStyle.valueOf(dto.paperStyle)
                        } catch (_: Exception) {
                            PaperStyle.RULED
                        },
                        previewLines = cleanPreviewLines,
                        initialStrokes = InkSerialization.deserializeStrokes(dto.strokesJson),
                        isDeleted = dto.isDeleted,
                        isStarred = resolvedIsStarred,
                        isBookmarked = resolvedIsBookmarked,
                        isLocked = resolvedIsLocked,
                        password = resolvedPassword,
                        cover = resolvedCover,
                        voiceNotes = resolvedVoiceNotes,
                        hasRecordings = resolvedVoiceNotes.isNotEmpty(),
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
                val metaParts = mutableListOf<String>()
                metaParts.add("starred=${notebook.isStarred}")
                metaParts.add("bookmark=${notebook.isBookmarked}")
                metaParts.add("locked=${notebook.isLocked}")
                metaParts.add("updated_millis=${notebook.updatedAtMillis}")
                metaParts.add("created_millis=${notebook.createdAtMillis}")
                if (!notebook.password.isNullOrBlank()) metaParts.add("pwd=${notebook.password}")
                metaParts.add("cov_cat=${notebook.cover.category}")
                metaParts.add("cov_col=${notebook.cover.colorHex}")
                metaParts.add("cov_name=${notebook.cover.name}")
                notebook.cover.bandColorHex?.let { metaParts.add("cov_band=$it") }
                notebook.cover.patternType?.let { metaParts.add("cov_pat=$it") }
                notebook.cover.customImageUri?.let { metaParts.add("cov_img=$it") }
                val metaHeader = "[GRAPHITE_META:${metaParts.joinToString(";")}]"

                val existingCleanPreview = notebook.previewLines.filter {
                    !it.startsWith("[GRAPHITE_META:") && !it.startsWith("[GRAPHITE_VOICE:") && !it.startsWith("[GRAPHITE_DOC:") && !it.startsWith("[GRAPHITE_DATA:")
                }

                val effectivePreviewLines = mutableListOf(metaHeader)

                // Embed voice notes
                notebook.voiceNotes.forEach { clip ->
                    val f = File(clip.filePath)
                    val base64 = if (f.exists() && f.length() in 1..(6 * 1024 * 1024)) {
                        try {
                            android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                        } catch (_: Exception) { "" }
                    } else ""
                    val vLine = "[GRAPHITE_VOICE:id=${clip.id};page=${clip.pageIndex};title=${clip.title};dur=${clip.durationSeconds};created=${clip.createdAtFormatted};trans=${clip.transcription ?: ""};data=$base64]"
                    effectivePreviewLines.add(vLine)
                }

                if (notebook.mimeType != null) {
                    val f = notebook.filePath?.let { File(it) }
                    val namePart = if (f != null) ";name=${notebook.originalFileName ?: f.name}" else ""
                    val fileMetaHeader = "[GRAPHITE_DOC:mime=${notebook.mimeType}$namePart;pages=${notebook.pageCount}]"
                    effectivePreviewLines.add(fileMetaHeader)
                    // If file exists and size is <= 8MB, embed Base64 so second device materializes file
                    if (f != null && f.exists() && f.length() in 1..(8 * 1024 * 1024)) {
                        try {
                            val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
                            effectivePreviewLines.add("[GRAPHITE_DATA:$b64]")
                        } catch (_: Exception) {}
                    }
                }

                effectivePreviewLines.addAll(existingCleanPreview)

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
            val session = getAuthenticatedSession()
            val userId = authManager.currentUserId ?: session.user.id
            val now = System.currentTimeMillis()
            val patchBody = buildJsonObject {
                put("is_deleted", true)
                put("updated_at_millis", now)
                put("updated_at", "Deleted")
            }.toString()

            executeWithRetry { token ->
                Request.Builder()
                    .url("$baseUrl/rest/v1/notebooks?id=eq.$notebookId&user_id=eq.$userId")
                    .header("apikey", publishableKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .header("Prefer", "return=minimal")
                    .patch(patchBody.toRequestBody("application/json".toMediaType()))
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
