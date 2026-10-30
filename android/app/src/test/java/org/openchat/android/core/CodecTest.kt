package org.openchat.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.crypto.Crypto

class CodecTest {
    private val sender = "0102030405060708".hexToBytes()!!

    @Test
    fun v1HeaderLayout() {
        val p = Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 0x0102030405060708L, payload = "hi".toByteArray(), ttl = 7)
        val b = PacketCodec.encode(p)!!
        assertEquals(14 + 8 + 2, b.size)
        assertEquals("01" + "02" + "07" + "0102030405060708" + "00" + "0002", b.copyOf(14).toHex())
        assertEquals(p, PacketCodec.decode(b))
    }

    @Test
    fun flagsRecipientSignatureRoute() {
        val p = Packet(
            version = 2, type = MessageType.NOISE_ENCRYPTED, senderId = sender, recipientId = ByteArray(8) { 0x0A },
            timestamp = 5, payload = ByteArray(40) { 9 }, signature = ByteArray(64) { 3 }, ttl = 4,
            route = listOf(ByteArray(8) { 1 }, ByteArray(8) { 2 }),
        )
        val b = PacketCodec.encode(p, pad = false)!!
        assertEquals(Flags.HAS_RECIPIENT or Flags.HAS_SIGNATURE or Flags.HAS_ROUTE, b[11].toInt())
        assertEquals("00000028", b.copyOfRange(12, 16).toHex()) // v2: 4-byte length, route excluded
        assertEquals(16 + 8 + 8 + 1 + 16 + 40 + 64, b.size)
        assertEquals(b.size, PacketCodec.frameLength(b))
        assertEquals(p, PacketCodec.decode(b))
    }

    @Test
    fun routeFlagIgnoredOnV1() {
        val p = Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 1, payload = byteArrayOf(1), route = listOf(ByteArray(8)))
        val b = PacketCodec.encode(p)!!
        assertEquals(0, b[11].toInt() and Flags.HAS_ROUTE)
    }

    @Test
    fun compressesRepetitivePayloadAsRawDeflate() {
        val text = "mesh relay test ".repeat(20).toByteArray()
        val p = Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 1, payload = text)
        val b = PacketCodec.encode(p)!!
        assertTrue(b[11].toInt() and Flags.IS_COMPRESSED != 0)
        assertTrue(b.size < text.size)
        // Original size follows the IDs; the stream has no zlib header (0x78).
        assertEquals(text.size, ((b[22].toInt() and 0xFF) shl 8) or (b[23].toInt() and 0xFF))
        assertTrue(b[24] != 0x78.toByte())
        assertArrayEquals(text, PacketCodec.decode(b)!!.payload)
    }

    @Test
    fun highEntropyPayloadIsNotCompressed() {
        val p = Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 1, payload = Crypto.randomBytes(300))
        assertEquals(0, PacketCodec.encode(p)!![11].toInt() and Flags.IS_COMPRESSED)
    }

    @Test
    fun noiseFramesArePaddedOthersAreNot() {
        val hs = Packet(type = MessageType.NOISE_HANDSHAKE, senderId = sender, recipientId = ByteArray(8), timestamp = 1, payload = ByteArray(32))
        val msg = Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 1, payload = ByteArray(32))
        val hsBytes = PacketCodec.encode(hs)!!
        assertEquals(256, hsBytes.size)
        assertEquals(256 - (14 + 16 + 32), hsBytes.last().toInt() and 0xFF)
        assertEquals(14 + 8 + 32, PacketCodec.encode(msg)!!.size)
        assertEquals(hs, PacketCodec.decode(hsBytes))
    }

    @Test
    fun signingBytesZeroTtlAndPadEveryType() {
        val p = Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 9, payload = "x".toByteArray(), ttl = 6, signature = ByteArray(64))
        val s = PacketCodec.signingBytes(p)!!
        assertEquals(256, s.size)
        assertEquals(0, s[2].toInt())
        assertEquals(0, s[11].toInt() and Flags.HAS_SIGNATURE)
        // Relays change only TTL, so signing bytes survive a relay.
        assertArrayEquals(s, PacketCodec.signingBytes(p.copy(ttl = 3)))
    }

    @Test
    fun signatureRoundTrip() {
        val keys = Crypto.ed25519Generate()
        val p = Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 9, payload = "mesh relay test ".repeat(10).toByteArray())
        val signed = p.copy(signature = Crypto.ed25519Sign(keys.privateKey, PacketCodec.signingBytes(p)!!))
        val decoded = PacketCodec.decode(PacketCodec.encode(signed.copy(ttl = 2))!!)!!
        assertTrue(Crypto.ed25519Verify(keys.publicKey, PacketCodec.signingBytes(decoded)!!, decoded.signature!!))
    }

    @Test
    fun rejectsTruncatedAndOversized() {
        val b = PacketCodec.encode(Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 1, payload = ByteArray(10)))!!
        assertNull(PacketCodec.decode(b.copyOf(b.size - 1)))
        val big = Packet(type = MessageType.ANNOUNCE, senderId = sender, timestamp = 1, payload = Crypto.randomBytes(5000))
        assertNull(PacketCodec.decode(PacketCodec.encode(big)!!))
    }

    @Test
    fun announceTlvRoundTrip() {
        val a = Announcement("alice", ByteArray(32) { 1 }, ByteArray(32) { 2 }, listOf(ByteArray(8) { 3 }), capabilities = 0x105L)
        val enc = a.encode()!!
        assertEquals("0105616c696365", enc.copyOf(7).toHex())
        val d = Announcement.decode(enc)!!
        assertEquals("alice", d.nickname)
        assertEquals(0x105L, d.capabilities)
        assertEquals("0501", Announcement.encodeCapabilities(0x105L).toHex())
        assertEquals(1, d.directNeighbors.size)
        // Unknown TLVs are skipped; missing required TLVs fail.
        assertNotNull(Announcement.decode(enc + byteArrayOf(0x7F, 1, 0)))
        assertNull(Announcement.decode(enc.copyOf(7)))
    }

    @Test
    fun privateMessageTlv() {
        val pm = PrivateMessage("ID-1", "hello")
        assertEquals("000449442d31" + "010568656c6c6f", pm.encode()!!.toHex())
        assertEquals(pm, PrivateMessage.decode(pm.encode()!!))
        val parts = splitUtf8("好".repeat(200), 255)
        assertTrue(parts.all { it.toByteArray().size <= 255 })
        assertEquals("好".repeat(200), parts.joinToString(""))
    }

    @Test
    fun streamFramerSplitsAndJoins() {
        val a = PacketCodec.encode(Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 1, payload = "first".toByteArray()))!!
        val hs = PacketCodec.encode(Packet(type = MessageType.NOISE_HANDSHAKE, senderId = sender, recipientId = ByteArray(8), timestamp = 2, payload = ByteArray(32)))!!
        val c = PacketCodec.encode(Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 3, payload = "third".toByteArray()))!!
        val stream = a + hs + c
        val framer = StreamFramer()
        val frames = ArrayList<ByteArray>()
        stream.toList().chunked(37).forEach { frames += framer.append(it.toByteArray()) }
        assertEquals(3, frames.size)
        assertEquals("first", String(PacketCodec.decode(frames[0])!!.payload))
        assertEquals(MessageType.NOISE_HANDSHAKE, PacketCodec.decode(frames[1])!!.type)
        assertEquals("third", String(PacketCodec.decode(frames[2])!!.payload))
    }

    @Test
    fun fragmentsReassemble() {
        val big = Packet(type = MessageType.NOISE_ENCRYPTED, senderId = sender, recipientId = ByteArray(8) { 5 }, timestamp = 7, payload = Crypto.randomBytes(1500))
        val frags = Fragmenter.split(big, 200)!!
        assertTrue(frags.size > 5)
        frags.forEach { assertTrue(PacketCodec.encode(it)!!.size <= 200 + 14 + 16 + 13) }
        val asm = FragmentAssembler()
        var out: Packet? = null
        frags.shuffled().forEach { f -> asm.accept(PacketCodec.decode(PacketCodec.encode(f)!!)!!)?.let { out = it } }
        assertEquals(big, out)
        val payload = frags[0].payload
        assertEquals(MessageType.NOISE_ENCRYPTED, payload[12].toInt())
        assertEquals(frags.size, ((payload[10].toInt() and 0xFF) shl 8) or (payload[11].toInt() and 0xFF))
    }

    @Test
    fun dedupAndRelayPolicy() {
        var now = 0L
        val d = Deduplicator(maxEntries = 3, maxAgeMs = 1000, clock = { now })
        assertTrue(d.markIfNew("a"))
        assertTrue(!d.markIfNew("a"))
        now = 2000
        assertTrue(d.markIfNew("a"))

        val bcast = Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 1, payload = byteArrayOf(1), ttl = 7)
        assertEquals(4, RelayPolicy.decide(bcast, false, false, degree = 7).newTtl)
        assertEquals(6, RelayPolicy.decide(bcast, false, false, degree = 1).newTtl)
        assertTrue(!RelayPolicy.decide(bcast.copy(ttl = 1), false, false, 1).relay)
        assertTrue(!RelayPolicy.decide(bcast, true, false, 1).relay)
    }

    @Test
    fun framerDropsAStalledPartialFrame() {
        var now = 0L
        val framer = StreamFramer(clock = { now })
        val a = PacketCodec.encode(Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 1, payload = "first".toByteArray()))!!
        val c = PacketCodec.encode(Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 3, payload = "third".toByteArray()))!!
        // Pieces that keep coming are joined.
        assertEquals(0, framer.append(a.copyOfRange(0, 10)).size)
        now = 100
        assertEquals("first", String(PacketCodec.decode(framer.append(a.copyOfRange(10, a.size)).single())!!.payload))
        // A head whose tail never came is abandoned, so the next frame is not glued to it.
        assertEquals(0, framer.append(a.copyOfRange(0, 10)).size)
        now = 100 + StreamFramer.STALL_MS + 1
        val frames = framer.append(c)
        assertEquals("third", String(PacketCodec.decode(frames.single())!!.payload))
        assertEquals(0, framer.pendingBytes)
    }

    @Test
    fun framerResyncsAfterGarbage() {
        val a = PacketCodec.encode(Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 1, payload = "first".toByteArray()))!!
        val c = PacketCodec.encode(Packet(type = MessageType.MESSAGE, senderId = sender, timestamp = 3, payload = "third".toByteArray()))!!
        // Lost traffic that happens to start with 0x01: it reads as a fragment header claiming 60 KB
        // (over the fragment cap) followed by bytes that are no header at all.
        val garbage = byteArrayOf(1, MessageType.FRAGMENT.toByte(), 7) + ByteArray(8) + byteArrayOf(0, 0xEA.toByte(), 0x60) + ByteArray(30) { 0x55 }
        val framer = StreamFramer()
        val frames = ArrayList<ByteArray>()
        (garbage + a + c + ByteArray(300) { 0x55 }).toList().chunked(50).forEach { frames += framer.append(it.toByteArray()) }
        assertEquals(listOf("first", "third"), frames.map { String(PacketCodec.decode(it)!!.payload) })
    }

    @Test
    fun frameLengthUsesThePerTypeCap() {
        fun header(type: Int, payloadLength: Int) = byteArrayOf(1, type.toByte(), 7) + ByteArray(8) + byteArrayOf(0, (payloadLength shr 8).toByte(), payloadLength.toByte()) + ByteArray(8)
        assertNull("a fragment cannot carry 60 KB", PacketCodec.frameLength(header(MessageType.FRAGMENT, 60_000)))
        assertEquals(14 + 8 + 60_000, PacketCodec.frameLength(header(MessageType.MESSAGE, 60_000)))
    }
}
