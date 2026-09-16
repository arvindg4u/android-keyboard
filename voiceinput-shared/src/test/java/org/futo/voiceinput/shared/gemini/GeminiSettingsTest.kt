package org.futo.voiceinput.shared.gemini

import org.junit.Assert.*
import org.junit.Test

class GeminiSettingsTest {

    @Test
    fun `blank key means no key`() {
        assertFalse(GeminiSettings.hasKey("   "))
        assertTrue(GeminiSettings.hasKey("AIza-secret"))
    }

    @Test
    fun `key normalization trims whitespace and newlines`() {
        assertEquals("AIza-secret", GeminiSettings.normalizeKey("  AIza-secret\n"))
    }
}
