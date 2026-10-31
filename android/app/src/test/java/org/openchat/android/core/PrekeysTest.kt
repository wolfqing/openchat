package org.openchat.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.crypto.Crypto
import org.openchat.android.crypto.NoiseX

class PrekeysTest {
    private var now = 1_761_700_000_000L
    private val owner = Crypto.ed25519Generate()
    private val noise = Crypto.x25519Generate()

    @Test
    fun bundleRoundTripsAndSignsCanonicalBytes() {
        val keys = listOf(PrekeyBundle.Prekey(1, ByteArray(32) { 1 }), PrekeyBundle.Prekey(2, ByteArray(32) { 2 }))
        val b = PrekeyBundle.signed(noise.publicKey, keys, now, owner)
        val bytes = b.encode()!!
        assertEquals("010020", bytes.copyOf(3).toHex())
        assertEquals("020048" + "00000001", bytes.copyOfRange(35, 42).toHex())
        val back = PrekeyBundle.decode(bytes)!!
        assertTrue(back.verify(owner.publicKey))
        assertEquals(listOf(1L, 2L), back.prekeys.map { it.id })
        // Signed bytes: length-prefixed context, key, count, entries, time.
        assertEquals(1 + 24 + 32 + 1 + 2 * 36 + 8, b.signingBytes().size)
        assertEquals("bitchat-prekey-v1".length + 4, PrekeyBundle.prologue(287454020).size)
        assertEquals("11223344", PrekeyBundle.prologue(287454020).copyOfRange(17, 21).toHex())
        // Duplicate IDs and oversize batches are refused.
        val dup = PrekeyBundle.signed(noise.publicKey, listOf(keys[0], keys[0]), now, owner).encode()!!
        assertNull(PrekeyBundle.decode(dup))
        assertNull(PrekeyBundle.signed(noise.publicKey, List(9) { PrekeyBundle.Prekey(it.toLong(), ByteArray(32)) }, now, owner).encode())
    }

    @Test
    fun localKeysTopUpAndKeepSpentOnesForTheGrace() {
        val local = LocalPrekeys { now }
        val first = local.bundleKeys()
        assertEquals(8, first.size)
        val at = local.generatedAt
        val id = first[0].id
        assertTrue(local.markConsumed(id))
        assertFalse("a redelivery does not spend it twice", local.markConsumed(id))
        assertTrue(local.generatedAt > at)
        assertEquals(7, local.bundleKeys().size)
        assertTrue("still opens redeliveries", local.keyPair(id) != null)
        repeat(5) { local.markConsumed(local.bundleKeys()[0].id) }
        assertTrue(local.replenish())
        assertEquals(8, local.bundleKeys().size)
        now += LocalPrekeys.CONSUMED_GRACE_MS + 1
        local.replenish()
        assertNull("gone after 48 h", local.keyPair(id))
        val copy = LocalPrekeys { now }.apply { restore(local.serialize()) }
        assertEquals(local.bundleKeys().map { it.id }, copy.bundleKeys().map { it.id })
    }

    @Test
    fun cacheAssignsOneKeyPerMessageAndSealsOpen() {
        val local = LocalPrekeys { now }
        val bundle = PrekeyBundle.signed(noise.publicKey, local.bundleKeys(), local.generatedAt, owner)
        val cache = PrekeyCache { now }
        val pkt = Packet(type = MessageType.PREKEY_BUNDLE, senderId = ByteArray(8), timestamp = now, payload = bundle.encode()!!)
        assertTrue(cache.ingest(bundle, pkt))
        assertFalse(cache.ingest(bundle, pkt))
        val k1 = cache.assign("m1", noise.publicKey)!!
        assertEquals(k1.id, cache.assign("m1", noise.publicKey)!!.id)
        assertNotEquals(k1.id, cache.assign("m2", noise.publicKey)!!.id)
        // Sealed to the prekey, opened with its private half; the sender is still authenticated.
        val sender = Crypto.x25519Generate()
        val ct = NoiseX.seal(sender, k1.publicKey, PrekeyBundle.prologue(k1.id), "hello".toByteArray())
        val opened = NoiseX.open(local.keyPair(k1.id)!!, PrekeyBundle.prologue(k1.id), ct)
        assertArrayEquals("hello".toByteArray(), opened.payload)
        assertArrayEquals(sender.publicKey, opened.senderStatic)
        // A stale bundle is not sealed to.
        now += PrekeyCache.MAX_SEAL_AGE_MS + 1
        assertNull(cache.assign("m3", noise.publicKey))
    }
}
