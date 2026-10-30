package org.openchat.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Mesh bridge wire pieces (docs/protocol-spec.md §24). */
class BridgeWireTest {
    @Test
    fun stableIdMatchesUpstreamVector() {
        // Upstream's pinned vector (bitchatTests/Protocols/BridgeWireFormatTests.swift).
        assertEquals("b83f94d81dcdd1b0c0048f6645995dd4", MeshMessageId.stable("0011223344556677", 1_750_000_000_123L, "hello mesh"))
        assertEquals(MeshMessageId.stable("aabbccdd00112233", 1, "x"), MeshMessageId.stable("AABBCCDD00112233", 1, "x"))
        assertEquals(MeshMessageId.stable("aabbccdd00112233", 1, "hello mesh"), MeshMessageId.stable("aabbccdd00112233", 1, "  hello mesh \n"))
    }

    @Test
    fun carrierRoundTripsAndRejectsBadShapes() {
        val json = """{"id":"x"}""".toByteArray()
        val c = NostrCarrier(NostrCarrier.FROM_BRIDGE, "u4pruy", json)
        val bytes = c.encode()!!
        assertEquals("010001" + "04" + "020006" + "753470727579" + "03000a", bytes.copyOf(16).toHex())
        val back = NostrCarrier.decode(bytes)!!
        assertEquals(NostrCarrier.FROM_BRIDGE, back.direction); assertEquals("u4pruy", back.geohash); assertArrayEquals(json, back.eventJson)
        assertNull(NostrCarrier.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(NostrCarrier.decode(ByteArray(0)))
        assertEquals(NostrCarrier.TO_GATEWAY, NostrCarrier.decode(NostrCarrier(NostrCarrier.TO_GATEWAY, "u4pruy", json).encode()!!)!!.direction)
        assertNull(NostrCarrier.decode(NostrCarrier(0x77, "u4pruy", json).encode()!!))
        assertNull(NostrCarrier(NostrCarrier.TO_BRIDGE, "", json).encode())
        assertNull(NostrCarrier(NostrCarrier.TO_BRIDGE, "u".repeat(13), json).encode())
        assertNotNull(NostrCarrier(NostrCarrier.TO_BRIDGE, "u".repeat(12), json).encode())
        assertNull(NostrCarrier(NostrCarrier.TO_BRIDGE, "u4pruy", ByteArray(16 * 1024 + 1)).encode())
    }

    @Test
    fun announceAdvertisesTheBridgeCell() {
        val a = Announcement("苏轼", ByteArray(32) { 1 }, ByteArray(32) { 2 }, emptyList(), (1L shl 7) or (1L shl 8), "u4pruy")
        val back = Announcement.decode(a.encode()!!)!!
        assertEquals("u4pruy", back.bridgeGeohash)
        assertEquals(1L shl 7, back.capabilities!! and (1L shl 7))
    }
}
