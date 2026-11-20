package org.openchat.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.nostr.Geohash
import java.util.Locale

class LocationPresetsTest {
    @Test
    fun allPresetsHaveValidTwoCharGeohashesAndUniqueCodes() {
        assertEquals(12, LocationPresets.ALL.size)
        assertEquals(8, LocationPresets.FEATURED.size)
        assertEquals(4, LocationPresets.EXTRA.size)
        val hashes = LocationPresets.ALL.map { it.geohash }
        assertEquals(hashes.size, hashes.toSet().size)
        hashes.forEach { h ->
            assertEquals(2, h.length)
            assertTrue("invalid geohash $h", Geohash.isValid(h))
        }
    }

    @Test
    fun defaultPresetFollowsTimeZoneFirstThenLocaleCountryThenDrFallback() {
        assertEquals("dr", LocationPresets.defaultPreset(Locale.US, "America/New_York").geohash)
        assertEquals("9q", LocationPresets.defaultPreset(Locale.US, "America/Los_Angeles").geohash)
        assertEquals("wt", LocationPresets.defaultPreset(Locale.CHINA, "Asia/Shanghai").geohash)
        assertEquals("ws", LocationPresets.defaultPreset(Locale.US, "Asia/Hong_Kong").geohash)
        assertEquals("xn", LocationPresets.defaultPreset(Locale.US, "Asia/Tokyo").geohash)
        assertEquals("gc", LocationPresets.defaultPreset(Locale.US, "Europe/London").geohash)
        assertEquals("u0", LocationPresets.defaultPreset(Locale.US, "Europe/Paris").geohash)
        assertEquals("u3", LocationPresets.defaultPreset(Locale.US, "Europe/Berlin").geohash)
        // Unknown time zone falls back to locale country, then ultimately #dr.
        assertEquals("wt", LocationPresets.defaultPreset(Locale.CHINA, "UTC").geohash)
        assertEquals("xn", LocationPresets.defaultPreset(Locale.JAPAN, "UTC").geohash)
        assertEquals("dr", LocationPresets.defaultPreset(Locale.ROOT, "UTC").geohash)
    }

    @Test
    fun presetLookupAndManualInputSanitization() {
        assertEquals("dr", LocationPresets.presetFor("DR")?.geohash)
        assertNull(LocationPresets.presetFor("wtw3s"))
        assertEquals("dr", LocationPresets.sanitizeInput(" #DR "))
        assertEquals("wtw3s", LocationPresets.sanitizeInput("#WtW3s!"))
        assertEquals("12345678", LocationPresets.sanitizeInput("1234567890"))
    }

    @Test
    fun joinedChannelPersistenceRoundTripsAndFiltersInvalidEntries() {
        val raw = LocationPresets.serializeJoined(
            linkedMapOf(
                "dr" to true,
                "wtw3s" to false,
                "a" to true,       // too short
                "invalid!" to true, // invalid base32
            ),
        )
        val parsed = LocationPresets.parseJoined(raw)
        assertEquals(mapOf("dr" to true, "wtw3s" to false), parsed)

        val corrupted = "DR,1\nwt,0\nbad!!,1\nx,0\n9q\n".toByteArray(Charsets.UTF_8)
        assertEquals(mapOf("dr" to true, "wt" to false, "9q" to true), LocationPresets.parseJoined(corrupted))
    }

    @Test
    fun bookmarksPersistenceRoundTripsAndDeduplicates() {
        val raw = LocationPresets.serializeBookmarks(linkedSetOf("DR", "9q8yy", "dr", "x", "bad!"))
        assertEquals(linkedSetOf("dr", "9q8yy"), LocationPresets.parseBookmarks(raw))
    }

    @Test
    fun dailyEncounterCounterDeduplicatesExcludesSelfAndResetsAtUtcMidnight() {
        val counter = DailyEncounterCounter()
        val day0 = 100L * 86_400_000L + 12_000L
        assertEquals(2, counter.record("self0000", listOf("self0000", "aaaa1111", "bbbb2222", "aaaa1111"), day0))
        assertEquals(3, counter.record("self0000", listOf("cccc3333"), day0 + 3_600_000L))
        assertEquals(3, counter.currentCount(day0 + 7_200_000L))

        // Next UTC day rolls over cleanly
        val day1 = 101L * 86_400_000L + 500L
        assertEquals(0, counter.currentCount(day1))
        assertEquals(1, counter.record("self0000", listOf("dddd4444"), day1))
        counter.clear()
        assertEquals(0, counter.currentCount(day1))
    }
}
