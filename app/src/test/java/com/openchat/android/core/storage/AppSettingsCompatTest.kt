package com.openchat.android.core.storage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AppSettings JSON round trip + backward compatibility (older stored files). */
class AppSettingsCompatTest {

    @Test
    fun `round trip keeps new fields`() {
        val s = AppSettings(localGpu = false, updateAutoCheck = false)
        val back = AppSettings.fromJson(JSONObject(s.toJson().toString()))
        assertFalse(back.localGpu)
        assertFalse(back.updateAutoCheck)
    }

    @Test
    fun `defaults are on`() {
        assertTrue(AppSettings().localGpu)
        assertTrue(AppSettings().updateAutoCheck)
    }

    @Test
    fun `older json without new fields parses with defaults`() {
        // Exactly the shape stored by v0.1.3 (pre GPU/update-checker).
        val old = JSONObject()
            .put("appearance", "light")
            .put("dynamicColors", true)
            .put("chatBackend", "OPENCODE")
            .put("opencodeAutoSyncConfig", true)
            .put("terminal", JSONObject().put("fontSizeSp", 14.0))
            .put("keepScreenOnInTerminal", true)
        val s = AppSettings.fromJson(old)
        assertEquals("light", s.appearance)
        assertEquals("OPENCODE", s.chatBackend)
        assertTrue(s.localGpu)       // opt-in default for the new GPU backend
        assertTrue(s.updateAutoCheck) // opt-in default for update checks
    }
}
