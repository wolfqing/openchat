package org.openchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeohashTest {
    @Test
    fun neighborsAreTheEightAdjacentCells() {
        for (cell in listOf("u4prs", "wtw3sjq6", "9q8yy")) {
            val (lat, lon) = Geohash.center(cell)!!
            val bits = cell.length * 5
            val dLon = 360.0 / (1L shl ((bits + 1) / 2)); val dLat = 180.0 / (1L shl (bits / 2))
            val offsets = Geohash.neighbors(cell).map { n ->
                val (a, b) = Geohash.center(n)!!
                Math.round((a - lat) / dLat).toInt() to Math.round((b - lon) / dLon).toInt()
            }.toSet()
            val expected = (-1..1).flatMap { y -> (-1..1).map { x -> y to x } }.filter { it != 0 to 0 }.toSet()
            assertEquals(cell, expected, offsets)
        }
    }

    @Test
    fun noteCarriesGeohashNameAndExpiry() {
        val key = NostrKey(ByteArray(32).also { it[31] = 7 })
        val ev = NostrEvent.locationNote(key, "wtw3sjq6", "苏轼", "但愿人长久", nowS = 1_800_000_000L)
        assertEquals(1, ev.kind)
        assertEquals(listOf(listOf("g", "wtw3sjq6"), listOf("n", "苏轼"), listOf("expiration", "1800086400")), ev.tags)
        assertTrue(ev.isValid())
    }
}
