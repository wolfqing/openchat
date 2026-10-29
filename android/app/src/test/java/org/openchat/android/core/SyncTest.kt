package org.openchat.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** History sync wire format (docs/protocol-spec.md §14): packet IDs, the GCS filter and the request TLV. */
class SyncTest {
    private val rnd = Random(14)
    private fun id() = rnd.nextBytes(16)

    @Test
    fun packetIdIgnoresTtlFlagsAndSignature() {
        val p = Packet(type = MessageType.MESSAGE, senderId = "1122334455667788".hexToBytes()!!, timestamp = 1_750_000_000_000L, payload = "hi".toByteArray())
        val a = PacketId.compute(p)
        assertEquals(16, a.size)
        assertArrayEquals(a, PacketId.compute(p.copy(ttl = 0, isRsr = true, signature = ByteArray(64), recipientId = ByteArray(8))))
        assertFalse(a.contentEquals(PacketId.compute(p.copy(timestamp = p.timestamp + 1))))
        // SHA-256(02 ‖ sender ‖ BE64 ts ‖ "hi")[0:16]
        val expected = sha256(byteArrayOf(2), p.senderId, "000001977420dc00".hexToBytes()!!, "hi".toByteArray()).copyOf(16)
        assertArrayEquals(expected, a)
    }

    @Test
    fun singleElementBitstreamMatchesSpec() {
        val one = id()
        val f = GcsFilter.build(listOf(one), 400, 0.01)
        assertEquals(7, f.p)
        assertEquals(1L shl 7, f.m)
        assertEquals(1, f.includedCount)
        val b = GcsFilter.bucket(one, f.m)
        // x = b: q = (b-1) >> 7 = 0 → one zero bit, then 7 bits of (b-1), padded to a byte.
        assertEquals(1, f.data.size)
        assertEquals(((b - 1).toInt() and 0x7F) shl 0, f.data[0].toInt() and 0xFF)
        assertArrayEquals(longArrayOf(b), GcsFilter.decode(f.p, f.m, f.data))
    }

    @Test
    fun filterContainsEveryMemberAndFewOthers() {
        val members = List(200) { id() }
        val f = GcsFilter.build(members, 400, 0.01)
        assertEquals(200, f.includedCount)
        assertTrue(f.data.size <= 400)
        val sorted = GcsFilter.decode(f.p, f.m, f.data)
        for (m in members) assertTrue(GcsFilter.contains(sorted, GcsFilter.bucket(m, f.m)))
        val falsePositives = (1..5000).count { GcsFilter.contains(sorted, GcsFilter.bucket(id(), f.m)) }
        assertTrue("fpr $falsePositives/5000", falsePositives < 200) // ~1% expected
    }

    @Test
    fun oversizedSetIsTrimmedNewestFirst() {
        val ids = List(2000) { id() }
        val f = GcsFilter.build(ids, 400, 0.01)
        assertTrue(f.data.size <= 400)
        assertTrue(f.includedCount in 1 until 2000)
        val sorted = GcsFilter.decode(f.p, f.m, f.data)
        for (m in ids.take(f.includedCount)) assertTrue(GcsFilter.contains(sorted, GcsFilter.bucket(m, f.m)))
        assertEquals(0, GcsFilter.decode(40, f.m, f.data).size)
        assertEquals(0, GcsFilter.decode(7, 1, f.data).size)
        assertEquals(0, GcsFilter.build(emptyList(), 400, 0.01).includedCount)
    }

    @Test
    fun requestEncodesLikeUpstream() {
        val r = SyncRequest(7, 0x80, byteArrayOf(0xAB.toByte()), SyncTypes.PUBLIC, 1_800_000_000_000L)
        val hex = "010001" + "07" + "020004" + "00000080" + "030001" + "ab" + "040001" + "03" + "050008" + "000001a3185c5000"
        assertEquals(hex, r.encode().toHex())
        val d = SyncRequest.decode(hex.hexToBytes()!!)!!
        assertEquals(7, d.p); assertEquals(0x80L, d.m); assertArrayEquals(byteArrayOf(0xAB.toByte()), d.data)
        assertEquals(SyncTypes.PUBLIC, d.types); assertEquals(1_800_000_000_000L, d.since)
        assertTrue(d.wants(SyncTypes.ANNOUNCE) && d.wants(SyncTypes.MESSAGE))
    }

    @Test
    fun requestDecodeTolerance() {
        // No types TLV means announces and messages; unknown and fragment-filter TLVs are skipped.
        val bare = "010001" + "07" + "020004" + "00000080" + "030000"
        val d = SyncRequest.decode(bare.hexToBytes()!!)!!
        assertEquals(SyncTypes.PUBLIC, d.types); assertNull(d.since); assertEquals(0, d.data.size)
        val wide = SyncRequest.decode((bare + "040002" + "0204" + "060003" + "616263" + "7f0001ff").hexToBytes()!!)!!
        assertEquals(SyncTypes.MESSAGE or (1L shl 10), wide.types) // bit 10 is a type openchat does not sync
        assertTrue(wide.wants(SyncTypes.MESSAGE) && !wide.wants(SyncTypes.ANNOUNCE))
        assertNull(SyncRequest.decode(("010001" + "00" + "020004" + "00000080" + "030000").hexToBytes()!!)) // P = 0
        assertNull(SyncRequest.decode(("010001" + "07" + "020004" + "00000000" + "030000").hexToBytes()!!)) // M = 0
        assertNull(SyncRequest.decode(("010001" + "07" + "020004" + "00000080" + "030401" + "00".repeat(1025)).hexToBytes()!!)) // data too big
        assertNull(SyncRequest.decode(("010001" + "07").hexToBytes()!!)) // missing fields
    }

    @Test
    fun typeFlagsAreLittleEndianAndTrimmed() {
        assertArrayEquals(byteArrayOf(0x03), SyncTypes.encode(SyncTypes.PUBLIC))
        assertArrayEquals(byteArrayOf(0x00, 0x04), SyncTypes.encode(1L shl 10))
        assertNull(SyncTypes.encode(0))
        assertEquals(3L, SyncTypes.decode(byteArrayOf(0x03, 0x00)))
        assertEquals(1L shl 10, SyncTypes.decode(byteArrayOf(0x00, 0x04)))
        assertNull(SyncTypes.decode(ByteArray(9)))
        assertNull(SyncTypes.decode(ByteArray(0)))
    }
}
