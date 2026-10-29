package org.openchat.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NostrEmbeddedTest {
    private val me = "1122334455667788".hexToBytes()!!
    private val them = "99aabbccddeeff00".hexToBytes()!!

    @Test
    fun privateMessageRoundTrips() {
        val s = NostrEmbedded.privateMessage("ID-1", "明月几时有", me, them, 1_800_000_000_000L)!!
        assertEquals(true, s.startsWith("bitchat1:"))
        assertEquals(false, s.contains("=") || s.contains("+") || s.contains("/"))
        val d = NostrEmbedded.decode(s)!!
        assertArrayEquals(me, d.senderId); assertArrayEquals(them, d.recipientId)
        assertEquals(NoisePayloadType.PRIVATE_MESSAGE, d.payload.type)
        assertEquals(PrivateMessage("ID-1", "明月几时有"), PrivateMessage.decode(d.payload.body))
    }

    @Test
    fun receiptsAndNotices() {
        val d = NostrEmbedded.decode(NostrEmbedded.receipt(NoisePayloadType.READ_RECEIPT, "ID-2", me, them, 1L)!!)!!
        assertEquals(NoisePayloadType.READ_RECEIPT, d.payload.type)
        assertEquals("ID-2", String(d.payload.body))
        assertEquals(true to "npub1abc", NostrEmbedded.parseFavoriteNotice("[FAVORITED]:npub1abc"))
        assertEquals(false to "npub1abc", NostrEmbedded.parseFavoriteNotice("[UNFAVORITED]:npub1abc"))
        assertNull(NostrEmbedded.parseFavoriteNotice("favorited"))
        assertNull(NostrEmbedded.decode("bitchat1:!!"))
        assertNull(NostrEmbedded.decode("hello"))
    }
}
