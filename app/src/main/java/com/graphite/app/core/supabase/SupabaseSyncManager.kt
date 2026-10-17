package com.graphite.app.core.supabase

import com.graphite.app.data.model.NotebookItem
import com.graphite.app.data.repository.GraphiteRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SupabaseSyncStatus {
    data object Idle : SupabaseSyncStatus
    data object Syncing : SupabaseSyncStatus
    data class Success(val syncedCount: Int, val timestampMillis: Long = System.currentTimeMillis()) : SupabaseSyncStatus
    data class Error(val message: String) : SupabaseSyncStatus
    data object Unauthenticated : SupabaseSyncStatus
}

class SupabaseSyncManager(
    private val authManager: SupabaseAuthManager,
    private val notesRepository: SupabaseNotesRepository,
    private val graphiteRepository: GraphiteRepository = GraphiteRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val syncScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + ioDispatcher)
    private var autoSyncJob: kotlinx.coroutines.Job? = null

    private val _syncStatus = MutableStateFlow<SupabaseSyncStatus>(SupabaseSyncStatus.Idle)
    val syncStatus: StateFlow<SupabaseSyncStatus> = _syncStatus.asStateFlow()

    /**
     * Silently triggers automatic background synchronization for notes with debouncing.
     * Safe to call on note edits, app startup, tab navigation, or note deletions.
     */
    fun triggerAutoSync(debounceMs: Long = 300L) {
        if (!authManager.isAuthenticated()) return
        autoSyncJob?.cancel()
        autoSyncJob = syncScope.launch {
            if (debounceMs > 0) {
                kotlinx.coroutines.delay(debounceMs)
            }
            try {
                syncNotes()
            } catch (_: Exception) {
                // Background auto-sync is silent and non-blocking
            }
        }
    }

    /**
     * Executes two-way cross-device synchronization between local repository and Supabase.
     *
     * SECURITY INVARIANTS & POLICIES:
     * 1. Requires active authentication under Postgres Row Level Security (RLS).
     * 2. Notes are strictly partitioned by authenticated Supabase user ID.
     * 3. Deterministic conflict handling (Last-Write-Wins based on updatedAtMillis).
     * 4. Deletions are propagated safely across devices using tombstone reconciliation.
     * 5. Offline-first: works offline and reconciles when connectivity resumes.
     * 6. Completely isolated from Google Drive: NEVER invokes Google Drive API or creates Drive files.
     */
    suspend fun syncNotes(): Result<Int> = withContext(ioDispatcher) {
        val freshSessionResult = authManager.getFreshSession()
        if (freshSessionResult.isFailure && !authManager.isAuthenticated()) {
            _syncStatus.value = SupabaseSyncStatus.Unauthenticated
            return@withContext Result.failure(
                SecurityException("Authentication required: Please sign in to sync notes with Supabase.")
            )
        }

        _syncStatus.value = SupabaseSyncStatus.Syncing

        try {
            // 1. Fetch remote notes from Supabase for this user first
            val remoteNotesResult = notesRepository.fetchNotes()
            if (remoteNotesResult.isFailure) {
                val ex = remoteNotesResult.exceptionOrNull()
                _syncStatus.value = SupabaseSyncStatus.Error(ex?.message ?: "Failed to fetch remote notes")
                return@withContext Result.failure(ex ?: Exception("Fetch failed"))
            }

            val remoteNotes = remoteNotesResult.getOrThrow()
            val remoteMap = remoteNotes.associateBy { it.id }

            // 2. Identify local notes that are genuinely newer than remote or local-only (prevent resurrecting cloud tombstones)
            val localNotes = graphiteRepository.getAllNotesForActiveUser()
            val pendingLocalNotesToUpload = localNotes.filter { local ->
                val remote = remoteMap[local.id]
                if (remote == null) {
                    !local.isDeleted
                } else if (remote.isDeleted) {
                    false // Never re-upload if cloud already has a deletion tombstone
                } else {
                    local.updatedAtMillis > remote.updatedAtMillis || (local.mimeType != null && remote.mimeType == null)
                }
            }

            // 3. Upload only newer/dirty local notes to Supabase via idempotent upsert
            // (Avoids blindly overwriting newer remote edits with stale local versions)
            if (pendingLocalNotesToUpload.isNotEmpty()) {
                notesRepository.upsertNotes(pendingLocalNotesToUpload).getOrThrow()
            }

            // 4. Reconcile remote changes into local workspace (Last-Write-Wins and Tombstones)
            graphiteRepository.reconcileRemoteNotes(remoteNotes)

            val activeNotesCount = graphiteRepository.getAllNotesForActiveUser().count { !it.isDeleted }
            _syncStatus.value = SupabaseSyncStatus.Success(activeNotesCount)
            Result.success(activeNotesCount)
        } catch (e: Exception) {
            val msg = e.message ?: "Failed to sync notes with Supabase"
            _syncStatus.value = SupabaseSyncStatus.Error(msg)
            Result.failure(e)
        }
    }

    suspend fun deleteNoteFromCloud(notebookId: String): Result<Unit> = withContext(ioDispatcher) {
        notesRepository.deleteNote(notebookId)
    }

    fun resetStatus() {
        _syncStatus.value = SupabaseSyncStatus.Idle
    }
}
