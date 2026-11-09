package org.openchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.app.ChatMessage
import org.openchat.android.app.DeliveryState
import java.util.TimeZone

class MeshVisualsTest {
    @Test
    fun emblemsAreMirroredStableAndNeverBlank() {
        val keys = (0 until 200).map { "%016x".format(it * 0x9E3779B97F4A7C15uL.toLong()) }
        for (k in keys) {
            val c = Emblem.cells(k)
            assertEquals(25, c.size)
            for (row in 0 until 5) {
                assertEquals(c[row * 5], c[row * 5 + 4])
                assertEquals(c[row * 5 + 1], c[row * 5 + 3])
            }
            assertTrue(c.any { it })
            assertTrue(Emblem.colorIndex(k) in Emblem.PALETTE.indices)
        }
        // Case of the hex key does not matter; different people mostly differ.
        assertTrue(Emblem.cells("5d02aa11").contentEquals(Emblem.cells("5D02AA11")))
        assertTrue(keys.map { Emblem.cells(it).joinToString("") { b -> if (b) "1" else "0" } }.toSet().size > 150)
    }

    @Test
    fun emblemColoursSpreadOverThePalette() {
        val used = (0 until 400).map { Emblem.colorIndex("peer$it") }.toSet()
        assertEquals(Emblem.PALETTE.indices.toSet(), used)
    }

    @Test
    fun deliveryDotsOnlyMoveForward() {
        assertEquals(0, deliveryDots(DeliveryState.SENDING))
        assertEquals(1, deliveryDots(DeliveryState.SENT))
        assertEquals(1, deliveryDots(DeliveryState.QUEUED))
        assertEquals(2, deliveryDots(DeliveryState.CARRIED))
        assertEquals(3, deliveryDots(DeliveryState.DELIVERED))
        assertEquals(4, deliveryDots(DeliveryState.READ))
        assertEquals(0, deliveryDots(DeliveryState.FAILED))
    }

    @Test
    fun aDayMarkerOpensEachDay() {
        val utc = TimeZone.getTimeZone("UTC")
        val day = 86_400_000L
        fun msg(id: String, ts: Long) = ChatMessage(id, "mesh", "Curie", "e887", id, ts, mine = false)
        val rows = withDayBreaks(listOf(msg("a", 10 * day + 1_000), msg("b", 10 * day + 5_000), msg("c", 11 * day + 10)), utc)
        assertEquals(listOf("day", "a", "b", "day", "c"), rows.map { if (it is TimelineRow.Day) "day" else (it as TimelineRow.Message).m.id })
        val days = rows.filterIsInstance<TimelineRow.Day>()
        assertTrue(days[0].first)
        assertFalse(days[1].first)
        assertNotEquals(days[0].key, days[1].key)
        // Midnight is local: the same instant falls on different days in different zones.
        val late = 10 * day + 23 * 3_600_000L
        assertNotEquals(localDay(late, utc), localDay(late, TimeZone.getTimeZone("Asia/Shanghai")))
        assertTrue(withDayBreaks(emptyList(), utc).isEmpty())
    }
}
