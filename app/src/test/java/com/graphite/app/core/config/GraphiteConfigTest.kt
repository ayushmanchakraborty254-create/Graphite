package com.graphite.app.core.config

import org.junit.Assert.*
import org.junit.Test

class GraphiteConfigTest {

    @Test
    fun testSupabaseConfigValuesAreValid() {
        val url = GraphiteConfig.supabaseUrl
        val key = GraphiteConfig.supabasePublishableKey
        val clientId = GraphiteConfig.googleWebClientId

        assertTrue("Supabase URL must start with https://", url.startsWith("https://"))
        assertTrue("Supabase URL must point to supabase.co", url.contains("supabase.co"))
        assertTrue("Supabase key must start with sb_publishable_", key.startsWith("sb_publishable_"))
        assertTrue("Google Web Client ID must end with apps.googleusercontent.com", clientId.endsWith(".apps.googleusercontent.com"))
    }

    @Test
    fun testValidatePublishableKeyRejectsServiceRoleKey() {
        val dangerousServiceRoleKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.service_role.fake_dangerous_key"
        try {
            GraphiteConfig.validatePublishableKey(dangerousServiceRoleKey)
            fail("Should have rejected service_role key with IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Service-role key must NEVER be embedded") == true)
        }
    }

    @Test
    fun testValidatePublishableKeyRejectsSecretKey() {
        val dangerousSecretKey = "secret_service_api_key_12345"
        try {
            GraphiteConfig.validatePublishableKey(dangerousSecretKey)
            fail("Should have rejected secret key with IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Secret key must NEVER be embedded") == true)
        }
    }

    @Test
    fun testValidatePublishableKeyAcceptsValidPublishableKey() {
        val validKey = "sb_publishable_n0ooLv3l1fmcy-gbNrimJQ_yCQNSbIM"
        // Should not throw
        GraphiteConfig.validatePublishableKey(validKey)
    }

    @Test
    fun testMinimumAppropriateDriveScopeIsDriveFile() {
        assertEquals("https://www.googleapis.com/auth/drive.file", GraphiteConfig.DRIVE_SCOPE_FILE)
    }
}
