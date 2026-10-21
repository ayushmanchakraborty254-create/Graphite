package com.graphite.app

import android.app.Application
import com.graphite.app.core.drive.GoogleDriveManager
import com.graphite.app.core.security.SecureStorage
import com.graphite.app.core.supabase.SupabaseAuthManager
import com.graphite.app.core.supabase.SupabaseNotesRepository
import com.graphite.app.core.supabase.SupabaseSyncManager

class GraphiteApplication : Application() {

    lateinit var secureStorage: SecureStorage
        private set

    lateinit var supabaseAuthManager: SupabaseAuthManager
        private set

    lateinit var supabaseNotesRepository: SupabaseNotesRepository
        private set

    lateinit var supabaseSyncManager: SupabaseSyncManager
        private set

    lateinit var googleDriveManager: GoogleDriveManager
        private set

    lateinit var googleAccountManager: com.graphite.app.core.auth.GoogleAccountManager
        private set

    lateinit var fileImportManager: com.graphite.app.core.importer.FileImportManager
        private set

    lateinit var voiceRecorderManager: com.graphite.app.core.voice.VoiceRecorderManager
        private set

    lateinit var appUpdateManager: com.graphite.app.core.updater.AppUpdateManager
        private set

    override fun onCreate() {

        super.onCreate()
        instance = this

        secureStorage = try {
            SecureStorage.createEncrypted(this)
        } catch (_: Exception) {
            // Fallback for emulator / non-keystore environments
            SecureStorage.createInMemory()
        }

        supabaseAuthManager = SupabaseAuthManager(secureStorage)
        supabaseNotesRepository = SupabaseNotesRepository(supabaseAuthManager)
        supabaseSyncManager = SupabaseSyncManager(supabaseAuthManager, supabaseNotesRepository)
        googleDriveManager = GoogleDriveManager(secureStorage)
        googleAccountManager = com.graphite.app.core.auth.GoogleAccountManager(secureStorage, supabaseAuthManager)
        fileImportManager = com.graphite.app.core.importer.FileImportManager(
            supabaseAuthManager = supabaseAuthManager,
            supabaseSyncManager = supabaseSyncManager
        )
        voiceRecorderManager = com.graphite.app.core.voice.VoiceRecorderManager(this)
        appUpdateManager = com.graphite.app.core.updater.AppUpdateManager()

        com.graphite.app.data.repository.GraphiteRepository.initStorage(this)
    }


    companion object {
        lateinit var instance: GraphiteApplication
            private set
    }
}
