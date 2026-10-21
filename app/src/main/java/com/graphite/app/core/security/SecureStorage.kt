package com.graphite.app.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Interface for secure key-value storage.
 */
interface SecureStorage {
    fun getString(key: String, defaultValue: String? = null): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String, defaultValue: Long = 0L): Long
    fun putLong(key: String, value: Long)
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    fun clear()

    companion object {
        private const val PREFS_FILE = "graphite_encrypted_prefs"

        /**
         * Robust storage creator:
         * 1. Attempts EncryptedSharedPreferences.
         * 2. If keystore keys or file are corrupted (e.g. from version update or signature change), clears corrupted file and retries.
         * 3. If encryption is unsupported on the device, safely falls back to persistent disk-backed SharedPreferences (NEVER in-memory!).
         */
        fun create(context: Context): SecureStorage {
            return try {
                createEncrypted(context)
            } catch (e: Exception) {
                try {
                    val sharedPrefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                    val prefsFile = java.io.File(sharedPrefsDir, "$PREFS_FILE.xml")
                    if (prefsFile.exists()) {
                        prefsFile.delete()
                    }
                    createEncrypted(context)
                } catch (_: Exception) {
                    val fallbackPrefs = context.getSharedPreferences("graphite_persistent_prefs", Context.MODE_PRIVATE)
                    SharedPreferencesSecureStorage(fallbackPrefs)
                }
            }
        }

        /**
         * Creates an EncryptedSharedPreferences backed storage using Android Keystore AES256-GCM.
         */
        fun createEncrypted(context: Context): SecureStorage {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            return SharedPreferencesSecureStorage(prefs)
        }

        /**
         * In-memory implementation primarily for unit tests where Android Keystore is mocked or unavailable.
         */
        fun createInMemory(): SecureStorage = InMemorySecureStorage()
    }
}

private class SharedPreferencesSecureStorage(
    private val prefs: SharedPreferences
) : SecureStorage {

    override fun getString(key: String, defaultValue: String?): String? {
        return prefs.getString(key, defaultValue)
    }

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value != null) putString(key, value) else remove(key)
        }.apply()
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return prefs.getLong(key, defaultValue)
    }

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}

class InMemorySecureStorage : SecureStorage {
    private val store = mutableMapOf<String, Any>()

    override fun getString(key: String, defaultValue: String?): String? {
        return (store[key] as? String) ?: defaultValue
    }

    override fun putString(key: String, value: String?) {
        if (value != null) store[key] = value else store.remove(key)
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return (store[key] as? Long) ?: defaultValue
    }

    override fun putLong(key: String, value: Long) {
        store[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return (store[key] as? Boolean) ?: defaultValue
    }

    override fun putBoolean(key: String, value: Boolean) {
        store[key] = value
    }

    override fun remove(key: String) {
        store.remove(key)
    }

    override fun clear() {
        store.clear()
    }
}
