package com.graphite.app.core.config

import com.graphite.app.BuildConfig

/**
 * Central configuration for Graphite external services (Supabase & Google OAuth).
 *
 * Security Invariants:
 * 1. Only publishable/anon keys are ever referenced.
 * 2. Service-role or secret keys MUST NEVER be bundled into or accepted by this application.
 * 3. Secrets are strictly validated to prevent inadvertent leaks.
 */
object GraphiteConfig {

    const val DRIVE_SCOPE_FILE = "https://www.googleapis.com/auth/drive.file"

    val supabaseUrl: String by lazy {
        val url = BuildConfig.SUPABASE_URL.trim()
        require(url.isNotBlank() && url.startsWith("https://")) {
            "Invalid Supabase Project URL: must be a valid https URL"
        }
        url
    }

    val supabasePublishableKey: String by lazy {
        val key = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()
        validatePublishableKey(key)
        key
    }

    val googleWebClientId: String by lazy {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
        require(clientId.isNotBlank() && !clientId.contains("secret")) {
            "Invalid Google Web Client ID: client secrets must never be embedded"
        }
        clientId
    }

    val geminiApiKey: String
        get() = BuildConfig.GEMINI_API_KEY.trim()

    val groqApiKey: String
        get() = BuildConfig.GROQ_API_KEY.trim()


    /**
     * Strict validation ensuring that the key is safe for client-side embedding
     * and is never a service-role or secret key.
     */
    fun validatePublishableKey(key: String) {
        require(key.isNotBlank()) { "Supabase key cannot be blank" }
        require(!key.contains("service_role", ignoreCase = true)) {
            "CRITICAL SECURITY VIOLATION: Service-role key must NEVER be embedded in the Android app!"
        }
        require(!key.contains("secret", ignoreCase = true)) {
            "CRITICAL SECURITY VIOLATION: Secret key must NEVER be embedded in the Android app!"
        }
    }
}
