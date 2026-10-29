package org.openchat.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilePacketTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100) { it.toByte() }
    private val m4a = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70) + ByteArray(40)

    @Test
    fun encodesTheSharedTlv() {
        val bytes = FilePacket("img_a.jpg", "image/jpeg", jpeg).encode()!!
        val expectedHead = "010009" + "696d675f612e6a7067" + "020004" + "00000068" + "03000a" + "696d6167652f6a706567" + "04" + "00000068"
        assertEquals(expectedHead, bytes.copyOf(expectedHead.length / 2).toHex())
        val back = FilePacket.decode(bytes)!!
        assertEquals("img_a.jpg", back.fileName); assertEquals("image/jpeg", back.mimeType)
        assertArrayEquals(jpeg, back.content)
        assertEquals(FilePacket.Kind.IMAGE, back.kind)
    }

    @Test
    fun acceptsLegacyTwoByteContentLength() {
        val legacy = ByteWriter().u8(3).u16(9).bytes("audio/mp4".toByteArray()).u8(4).u16(m4a.size).bytes(m4a).toByteArray()
        val f = FilePacket.decode(legacy)!!
        assertArrayEquals(m4a, f.content)
        assertEquals(FilePacket.Kind.AUDIO, f.kind)
    }

    @Test
    fun rejectsMismatchedOrDisallowedTypes() {
        assertNull(FilePacket("x.jpg", "image/jpeg", m4a).kind)          // bytes are not a JPEG
        assertNull(FilePacket("x.pdf", "application/pdf", jpeg).kind)    // only images and audio
        assertNull(FilePacket.decode(ByteArray(0)))
    }

    @Test
    fun peerStateIsCanonical() {
        val key = ByteArray(32) { 7 }
        val (caps, k) = AuthPeerState.decode(AuthPeerState.encode(AuthPeerState.CAP_PRIVATE_MEDIA, key))!!
        assertEquals(AuthPeerState.CAP_PRIVATE_MEDIA, caps); assertArrayEquals(key, k)
        assertEquals("01" + "01020001" + "0220" + "07".repeat(32), AuthPeerState.encode(AuthPeerState.CAP_PRIVATE_MEDIA, key).toHex())
        assertNull(AuthPeerState.decode("0101030001000220".hexToBytes()!! + key)) // non-minimal capabilities
        assertNull(AuthPeerState.decode("02010101".hexToBytes()!!))              // unknown version
    }
}
