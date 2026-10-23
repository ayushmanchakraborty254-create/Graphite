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

    val isGoogleAccountManagerInitialized: Boolean
        get() = ::googleAccountManager.isInitialized

    lateinit var fileImportManager: com.graphite.app.core.importer.FileImportManager
        private set

    lateinit var voiceRecorderManager: com.graphite.app.core.voice.VoiceRecorderManager
        private set

    lateinit var appUpdateManager: com.graphite.app.core.updater.AppUpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        setupGlobalCrashHandler()

        secureStorage = SecureStorage.create(this)
        supabaseAuthManager = SupabaseAuthManager(secureStorage)
        supabaseNotesRepository = SupabaseNotesRepository(supabaseAuthManager)
        supabaseSyncManager = SupabaseSyncManager(supabaseAuthManager, supabaseNotesRepository)

        // Initialize googleAccountManager BEFORE googleDriveManager so account references are ready
        googleAccountManager = com.graphite.app.core.auth.GoogleAccountManager(
            secureStorage = secureStorage,
            supabaseAuthManager = supabaseAuthManager,
            context = this
        )
        googleDriveManager = GoogleDriveManager(secureStorage)

        fileImportManager = com.graphite.app.core.importer.FileImportManager(
            supabaseAuthManager = supabaseAuthManager,
            supabaseSyncManager = supabaseSyncManager
        )
        voiceRecorderManager = com.graphite.app.core.voice.VoiceRecorderManager(this)
        appUpdateManager = com.graphite.app.core.updater.AppUpdateManager()

        try {
            com.graphite.app.data.repository.GraphiteRepository.initStorage(this)
        } catch (e: Throwable) {
            android.util.Log.e("GraphiteApplication", "Failed to initialize storage on startup: ${e.message}", e)
        }
    }

    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("GraphiteFatal", "FATAL CRASH on thread ${thread.name}: ${throwable.message}", throwable)
            try {
                val crashFile = java.io.File(filesDir, "graphite_fatal_crash.txt")
                crashFile.writeText(
                    "Time: ${java.util.Date()}\n" +
                    "Thread: ${thread.name}\n" +
                    "Exception: ${throwable.javaClass.name}: ${throwable.message}\n" +
                    "StackTrace:\n${android.util.Log.getStackTraceString(throwable)}\n"
                )
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }


    companion object {
        lateinit var instance: GraphiteApplication
            private set
    }
}
