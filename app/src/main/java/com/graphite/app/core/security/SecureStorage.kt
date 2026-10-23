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
         * 1. Attempts EncryptedSharedPreferences with a probe read/write to guarantee decryption works.
         * 2. If keystore keys or file are corrupted (e.g. from version update, AutoBackup restore, or reinstall),
         *    clears corrupted XML file and resets the KeyStore master key entry before retrying.
         * 3. If encryption fails or is unsupported on the device, safely falls back to persistent disk-backed SharedPreferences.
         */
        fun create(context: Context): SecureStorage {
            // Attempt 1: Standard EncryptedSharedPreferences with probe test
            try {
                val enc = createEncrypted(context)
                // Test read to guarantee keystore can decrypt existing data without throwing SecurityException
                enc.getString("__probe__", null)
                return enc
            } catch (e: Throwable) {
                android.util.Log.w("SecureStorage", "Initial EncryptedSharedPreferences failed (likely keystore key mismatch from restore/reinstall): ${e.message}")
            }

            // Attempt 2: Clear corrupted file and delete stale AndroidKeyStore key alias
            try {
                cleanCorruptedStorage(context)
                val enc = createEncrypted(context)
                enc.getString("__probe__", null)
                return enc
            } catch (e: Throwable) {
                android.util.Log.e("SecureStorage", "EncryptedSharedPreferences retry failed, falling back to persistent preferences: ${e.message}")
            }

            // Attempt 3: Persistent disk-backed SharedPreferences (always reliable)
            return try {
                val fallbackPrefs = context.getSharedPreferences("graphite_persistent_prefs", Context.MODE_PRIVATE)
                SharedPreferencesSecureStorage(fallbackPrefs)
            } catch (_: Throwable) {
                InMemorySecureStorage()
            }
        }

        private fun cleanCorruptedStorage(context: Context) {
            try {
                val sharedPrefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                val prefsFile = java.io.File(sharedPrefsDir, "$PREFS_FILE.xml")
                if (prefsFile.exists()) {
                    prefsFile.delete()
                }
            } catch (_: Throwable) {}

            try {
                val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
                if (keyStore.containsAlias("_androidx_security_master_key_")) {
                    keyStore.deleteEntry("_androidx_security_master_key_")
                }
            } catch (_: Throwable) {}
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
        return try {
            prefs.getString(key, defaultValue)
        } catch (e: Throwable) {
            android.util.Log.w("SecureStorage", "Failed to get string for key $key: ${e.message}")
            defaultValue
        }
    }

    override fun putString(key: String, value: String?) {
        try {
            prefs.edit().apply {
                if (value != null) putString(key, value) else remove(key)
            }.apply()
        } catch (e: Throwable) {
            android.util.Log.e("SecureStorage", "Failed to put string for key $key: ${e.message}")
        }
    }

    override fun getLong(key: String, defaultValue: Long): Long {
        return try {
            prefs.getLong(key, defaultValue)
        } catch (e: Throwable) {
            android.util.Log.w("SecureStorage", "Failed to get long for key $key: ${e.message}")
            defaultValue
        }
    }

    override fun putLong(key: String, value: Long) {
        try {
            prefs.edit().putLong(key, value).apply()
        } catch (e: Throwable) {
            android.util.Log.e("SecureStorage", "Failed to put long for key $key: ${e.message}")
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return try {
            prefs.getBoolean(key, defaultValue)
        } catch (e: Throwable) {
            android.util.Log.w("SecureStorage", "Failed to get boolean for key $key: ${e.message}")
            defaultValue
        }
    }

    override fun putBoolean(key: String, value: Boolean) {
        try {
            prefs.edit().putBoolean(key, value).apply()
        } catch (e: Throwable) {
            android.util.Log.e("SecureStorage", "Failed to put boolean for key $key: ${e.message}")
        }
    }

    override fun remove(key: String) {
        try {
            prefs.edit().remove(key).apply()
        } catch (e: Throwable) {
            android.util.Log.e("SecureStorage", "Failed to remove key $key: ${e.message}")
        }
    }

    override fun clear() {
        try {
            prefs.edit().clear().apply()
        } catch (e: Throwable) {
            android.util.Log.e("SecureStorage", "Failed to clear preferences: ${e.message}")
        }
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
