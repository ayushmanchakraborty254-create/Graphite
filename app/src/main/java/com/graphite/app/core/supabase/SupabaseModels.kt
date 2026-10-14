package com.graphite.app.core.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class SupabaseUser(
    val id: String,
    val email: String? = null,
    @SerialName("user_metadata")
    val userMetadata: Map<String, JsonElement>? = null
) {
    val avatarUrl: String?
        get() {
            val meta = userMetadata ?: return null
            val avatarElem = meta["avatar_url"] ?: meta["picture"]
            return if (avatarElem is JsonPrimitive && avatarElem.isString) {
                avatarElem.content
            } else null
        }

    val fullName: String?
        get() {
            val meta = userMetadata ?: return null
            val nameElem = meta["full_name"] ?: meta["name"]
            return if (nameElem is JsonPrimitive && nameElem.isString) {
                nameElem.content
            } else null
        }
}

@Serializable
data class SupabaseSession(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("token_type")
    val tokenType: String = "bearer",
    @SerialName("expires_in")
    val expiresIn: Long = 3600,
    val user: SupabaseUser,
    val expiresAtMillis: Long = System.currentTimeMillis() + (expiresIn * 1000L)
) {
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= (expiresAtMillis - 30_000L) // 30s buffer
}

sealed interface SupabaseAuthState {
    data object Unauthenticated : SupabaseAuthState
    data object Loading : SupabaseAuthState
    data class Authenticated(val session: SupabaseSession) : SupabaseAuthState {
        val user: SupabaseUser get() = session.user
    }
    data class Error(val message: String) : SupabaseAuthState
}

@Serializable
data class SupabaseNotebookDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val title: String,
    @SerialName("folder_id")
    val folderId: String = "my_notes",
    @SerialName("paper_style")
    val paperStyle: String = "RULED",
    @SerialName("preview_lines")
    val previewLines: List<String> = emptyList(),
    @SerialName("strokes_json")
    val strokesJson: String = "[]",
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("updated_at_millis")
    val updatedAtMillis: Long = 0L,
    @SerialName("is_deleted")
    val isDeleted: Boolean = false,
    @SerialName("version")
    val version: Long = 1L
)
