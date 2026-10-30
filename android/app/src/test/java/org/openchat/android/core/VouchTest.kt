package org.openchat.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.crypto.Crypto

class VouchTest {
    private val voucher = Crypto.ed25519Generate()
    private val now = 1_761_700_000_000L
    private fun fp(b: Int) = ByteArray(32) { b.toByte() }

    @Test
    fun attestationRoundTripsAndBindsTheVoucher() {
        val v = Vouch.create(fp(1), ByteArray(32) { 2 }, now, voucher)!!
        val bytes = v.encode()
        assertEquals("0120" + "01".repeat(32), bytes.copyOf(34).toHex())
        assertEquals(2 + 32 + 2 + 32 + 2 + 8 + 2 + 64, bytes.size)
        val back = Vouch.decode(bytes)!!
        assertEquals(now, back.timestampMs)
        assertTrue(back.verify(voucher.publicKey))
        assertFalse("someone else's key does not check", back.verify(Crypto.ed25519Generate().publicKey))
        // The signed message is the context, then the vouchee, then the time.
        assertEquals("bitchat-vouch-v1".length + 72, Vouch.signingBytes(fp(1), fp(2), now).size)
        assertNull(Vouch.decode(bytes.copyOf(bytes.size - 1)))
        assertNotNull(Vouch.decode(bytes + byteArrayOf(0x7F, 1, 0))) // unknown TLVs are skipped
        assertTrue(back.expired(now + Vouch.MAX_AGE_MS + 1))
        assertTrue(back.expired(now - Vouch.MAX_SKEW_MS - 1))
        assertFalse(back.expired(now))
    }

    @Test
    fun batchesSkipJunkAndCapAtSixteen() {
        val list = (1..3).map { Vouch.create(fp(it), fp(9), now, voucher)!! }
        val batch = Vouch.encodeList(list)!!
        assertEquals(3, Vouch.decodeList(batch).size)
        // A junk entry in the middle is dropped, the rest kept.
        val junk = ByteWriter().u8(3).u16(list[0].encode().size).bytes(list[0].encode()).u16(2).bytes(byteArrayOf(1, 2)).u16(list[1].encode().size).bytes(list[1].encode()).toByteArray()
        assertEquals(2, Vouch.decodeList(junk).size)
        assertNull(Vouch.encodeList(List(17) { list[0] }))
        val inflated = ByteWriter().u8(200).apply { repeat(20) { u16(list[0].encode().size).bytes(list[0].encode()) } }.toByteArray()
        assertEquals(16, Vouch.decodeList(inflated).size)
    }

    @Test
    fun bookFollowsBitchatsGates() {
        val a = "aa".repeat(32); val b = "bb".repeat(32); val c = "cc".repeat(32)
        assertNull("only from people we verified", VouchBook.record(emptyMap(), b, a, now, now, emptySet()))
        assertNull("no self-vouch", VouchBook.record(emptyMap(), a, a, now, now, setOf(a)))
        assertNull("not for someone we verified", VouchBook.record(emptyMap(), b, a, now, now, setOf(a, b)))
        assertNull("stale", VouchBook.record(emptyMap(), b, a, now - 31L * 24 * 3600_000, now, setOf(a)))
        assertNull("far future", VouchBook.record(emptyMap(), b, a, now + 2 * 3600_000, now, setOf(a)))
        val book = VouchBook.record(emptyMap(), b, a, now, now, setOf(a))!!
        assertEquals(listOf(a), VouchBook.vouchers(book, b, now, setOf(a)))
        // Un-verifying the voucher retires the vouch without deleting it.
        assertEquals(emptyList<String>(), VouchBook.vouchers(book, b, now, setOf(c)))
        assertEquals(listOf(a), VouchBook.vouchers(book, b, now, setOf(a)))
        assertEquals(emptyList<String>(), VouchBook.vouchers(book, b, now + Vouch.MAX_AGE_MS + 1, setOf(a)))
    }

    @Test
    fun bookKeepsTheEightNewestVouchers() {
        val vouchee = "ee".repeat(32)
        val vouchers = (0 until 9).map { "%02x".format(0x10 + it) + "00".repeat(31) }
        var book: Map<String, Map<String, Long>> = emptyMap()
        vouchers.forEachIndexed { i, v -> book = VouchBook.record(book, vouchee, v, now + i, now + i, vouchers.toSet())!! }
        val kept = VouchBook.vouchers(book, vouchee, now + 9, vouchers.toSet())
        assertEquals(8, kept.size)
        assertFalse(vouchers[0] in kept)
        val older = "0c".repeat(32)
        assertNull(VouchBook.record(book, vouchee, older, now - 1, now, vouchers.toSet() + older))
        assertNotNull("a retained voucher refreshes", VouchBook.record(book, vouchee, vouchers[8], now + 100, now + 100, vouchers.toSet()))
    }
}
