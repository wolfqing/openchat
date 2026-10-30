package org.openchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.core.hexToBytes

class PowTest {
    @Test
    fun countsLeadingZeroBits() {
        assertEquals(0, Pow.leadingZeroBits("80".hexToBytes()!!))
        assertEquals(7, Pow.leadingZeroBits("01ff".hexToBytes()!!))
        assertEquals(12, Pow.leadingZeroBits("000f".hexToBytes()!!))
        assertEquals(16, Pow.leadingZeroBits("0000".hexToBytes()!!))
    }

    @Test
    fun minedMessageCarriesVerifiableWork() {
        val key = NostrKey(ByteArray(32).also { it[31] = 9 })
        val ev = NostrEvent.geohashMessage(key, "wtw3s", "张衡", "地动仪有动静", teleported = false, mine = true)
        val tag = ev.tags.last()
        assertEquals("nonce", tag[0]); assertEquals(16, tag[1].length)
        assertTrue(ev.isValid())
        assertTrue(Pow.validatedBits(ev) >= 4)
        // Claiming more work than the ID has scores zero.
        val inflated = ev.copy(tags = ev.tags.dropLast(1) + listOf(listOf("nonce", tag[1], "200")))
        assertEquals(0, Pow.validatedBits(inflated))
        assertEquals(0, Pow.validatedBits(NostrEvent.geohashMessage(key, "wtw3s", null, "x", false)))
    }
}
