package org.openchat.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.crypto.Crypto
import org.openchat.android.crypto.NoiseX

/**
 * Courier wire format (docs/protocol-spec.md §13) against upstream's published
 * golden vectors (docs/courier-test-vectors.json in the bitchat iOS repository),
 * plus the one-way Noise X seal.
 */
class CourierTest {
    private val tag = "000102030405060708090a0b0c0d0e0f".hexToBytes()!!
    private val ct = "courier-vector-ciphertext-0001".toByteArray(Charsets.US_ASCII)
    private val encoded = "010010000102030405060708090a0b0c0d0e0f020008000001a3185c500003001e636f75726965722d766563746f722d636970686572746578742d303030310400010405000411223344"

    @Test
    fun encodesLikeUpstream() {
        val env = CourierEnvelope(tag, 1_800_000_000_000L, ct, copies = 4, prekeyId = 287454020L)
        assertEquals(encoded, env.encode()!!.toHex())
        assertEquals(74, env.encode()!!.size)
    }

    @Test
    fun decodesAndSkipsUnknownTlv() {
        val d = CourierEnvelope.decode(encoded.hexToBytes()!!)!!
        assertArrayEquals(tag, d.recipientTag)
        assertEquals(1_800_000_000_000L, d.expiry)
        assertArrayEquals(ct, d.ciphertext)
        assertEquals(4, d.copies)
        assertEquals(287454020L, d.prekeyId)
        // An unknown type is skipped by its length; a wrong tag length is rejected.
        val withUnknown = encoded + "7f000201ff"
        assertNotNull(CourierEnvelope.decode(withUnknown.hexToBytes()!!))
        assertNull(CourierEnvelope.decode(("01000f" + "00".repeat(15)).hexToBytes()!!))
    }

    @Test
    fun carryOnlyEnvelopeOmitsOptionalFields() {
        val env = CourierEnvelope(tag, 1_800_000_000_000L, ct)
        val bytes = env.encode()!!
        assertEquals(3 + 16 + 3 + 8 + 3 + ct.size, bytes.size)
        assertEquals(1, CourierEnvelope.decode(bytes)!!.copies)
    }

    @Test
    fun copiesAreClampedNotRejected() {
        assertEquals(1, CourierEnvelope(tag, 1L, ct, copies = 0).copies)
        assertEquals(4, CourierEnvelope(tag, 1L, ct, copies = 4).copies)
        assertEquals(8, CourierEnvelope(tag, 1L, ct, copies = 200).copies)
    }

    @Test
    fun recipientTagMatchesUpstream() {
        val key = "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf".hexToBytes()!!
        assertEquals("ad8514c90ca1fa6bf44e38c8a6252482", CourierEnvelope.recipientTag(key, 20833).toHex())
        assertEquals(20833L, CourierEnvelope.epochDay(1_800_000_000_000L))
        assertTrue(CourierEnvelope.candidateTags(key, 1_800_000_000_000L).contains("ad8514c90ca1fa6bf44e38c8a6252482"))
    }

    @Test
    fun signingPreimageMatchesUpstream() {
        val p = Packet(
            type = MessageType.COURIER_ENVELOPE,
            senderId = "1122334455667788".hexToBytes()!!,
            recipientId = "99aabbccddeeff00".hexToBytes()!!,
            timestamp = 1_750_000_000_000L,
            payload = encoded.hexToBytes()!!,
            ttl = 7,
        )
        val pre = PacketCodec.signingBytes(p)!!
        assertEquals(256, pre.size)
        assertEquals(0x98.toByte(), pre.last())
        val expected = "010400000001977420dc0001004a112233445566778899aabbccddeeff00" + encoded + "98".repeat(152)
        assertEquals(expected, pre.toHex())
        // Our own signature over that pre-image verifies, and the signed frame is 168 bytes with flags 0x03.
        val kp = Crypto.ed25519Generate()
        val signed = p.copy(signature = Crypto.ed25519Sign(kp.privateKey, pre))
        val wire = PacketCodec.encode(signed)!!
        assertEquals(168, wire.size)
        assertEquals(0x03, wire[11].toInt() and 0xFF)
        assertTrue(Crypto.ed25519Verify(kp.publicKey, pre, signed.signature!!))
    }

    private val prologue = "bitchat-courier-v1".toByteArray(Charsets.US_ASCII)

    @Test
    fun noiseXRoundTripAuthenticatesSender() {
        val alice = Crypto.x25519Generate(); val bob = Crypto.x25519Generate()
        val payload = "meet at the north gate".toByteArray()
        val sealed = NoiseX.seal(alice, bob.publicKey, prologue, payload)
        assertEquals(NoiseX.OVERHEAD + payload.size, sealed.size)
        val opened = NoiseX.open(bob, prologue, sealed)
        assertArrayEquals(payload, opened.payload)
        assertArrayEquals(alice.publicKey, opened.senderStatic)
    }

    @Test
    fun noiseXRejectsWrongRecipientTamperAndSplice() {
        val alice = Crypto.x25519Generate(); val bob = Crypto.x25519Generate()
        val carol = Crypto.x25519Generate(); val mallory = Crypto.x25519Generate()
        val sealed = NoiseX.seal(alice, bob.publicKey, prologue, "secret".toByteArray())
        assertTrue(runCatching { NoiseX.open(carol, prologue, sealed) }.isFailure)
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertTrue(runCatching { NoiseX.open(bob, prologue, tampered) }.isFailure)
        val other = NoiseX.seal(mallory, bob.publicKey, prologue, "secret".toByteArray())
        val spliced = other.copyOfRange(0, 32) + sealed.copyOfRange(32, sealed.size)
        assertTrue(runCatching { NoiseX.open(bob, prologue, spliced) }.isFailure)
        assertTrue(runCatching { NoiseX.open(bob, "other-prologue".toByteArray(), sealed) }.isFailure)
        assertTrue(runCatching { NoiseX.seal(alice, ByteArray(32), prologue, ByteArray(1)) }.isFailure)
    }

    @Test
    fun noiseXIsUnlinkableAcrossSeals() {
        val alice = Crypto.x25519Generate(); val bob = Crypto.x25519Generate()
        val a = NoiseX.seal(alice, bob.publicKey, prologue, "same".toByteArray())
        val b = NoiseX.seal(alice, bob.publicKey, prologue, "same".toByteArray())
        assertTrue(!a.contentEquals(b))
        assertTrue(!a.copyOfRange(0, 32).contentEquals(b.copyOfRange(0, 32)))
    }
}
