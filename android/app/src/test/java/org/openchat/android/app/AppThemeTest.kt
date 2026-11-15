package org.openchat.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    @Test
    fun fromCodeResolvesKnownChoicesAndFallsBackToSystem() {
        assertEquals(AppTheme.Choice.SYSTEM, AppTheme.fromCode(null))
        assertEquals(AppTheme.Choice.SYSTEM, AppTheme.fromCode(""))
        assertEquals(AppTheme.Choice.SYSTEM, AppTheme.fromCode("unknown"))
        assertEquals(AppTheme.Choice.SYSTEM, AppTheme.fromCode("system"))
        assertEquals(AppTheme.Choice.LIGHT, AppTheme.fromCode("light"))
        assertEquals(AppTheme.Choice.DARK, AppTheme.fromCode("dark"))
    }

    @Test
    fun resolveIsDarkHonorsExplicitThemeAndSystemDefault() {
        assertFalse(AppTheme.resolveIsDark(AppTheme.Choice.SYSTEM, systemIsDark = false))
        assertTrue(AppTheme.resolveIsDark(AppTheme.Choice.SYSTEM, systemIsDark = true))
        assertFalse(AppTheme.resolveIsDark(AppTheme.Choice.LIGHT, systemIsDark = false))
        assertFalse(AppTheme.resolveIsDark(AppTheme.Choice.LIGHT, systemIsDark = true))
        assertTrue(AppTheme.resolveIsDark(AppTheme.Choice.DARK, systemIsDark = false))
        assertTrue(AppTheme.resolveIsDark(AppTheme.Choice.DARK, systemIsDark = true))
    }

    @Test
    fun pcmLevelMeasuresSilenceNormalSpeechAndFullScale() {
        assertEquals(0f, pcmLevel(ByteArray(0), 0), 0.0001f)
        assertEquals(0f, pcmLevel(ByteArray(1), 1), 0.0001f)
        assertEquals(0f, pcmLevel(ByteArray(64), 64), 0.0001f)

        // ~10% full-scale 16-bit LE samples (3277 = 0x0CCD) -> ~0.45 normalized level
        val speech = ByteArray(8)
        repeat(4) { i ->
            speech[i * 2] = 0xCD.toByte()
            speech[i * 2 + 1] = 0x0C.toByte()
        }
        val mid = pcmLevel(speech, speech.size)
        assertTrue("expected speech level in 0.4..0.5, got $mid", mid in 0.40f..0.50f)

        // Full-scale samples clamp cleanly to 1.0f
        val loud = byteArrayOf(0xFF.toByte(), 0x7F.toByte(), 0xFF.toByte(), 0x7F.toByte())
        assertEquals(1f, pcmLevel(loud, loud.size), 0.0001f)
    }
}
