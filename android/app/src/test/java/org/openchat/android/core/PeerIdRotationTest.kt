package org.openchat.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.crypto.Crypto

/** Upstream's draft vectors (docs/PEER-ID-ROTATION.md §7 in the bitchat repository). */
class PeerIdRotationTest {
    @Test
    fun rotatingIdMatchesUpstream() {
        val priv = ByteArray(32) { (it + 1).toByte() }
        val secret = PeerIdRotation.rotationSecret(priv)
        assertEquals("fb82dfec0c0a2a4677beca44e2f72c80e7c5de773dd5fce6ee47af83d3c25f09", secret.toHex())
        assertEquals("f7c08c528506a374", PeerIdRotation.peerId(secret, 100).toHex())
        assertFalse(PeerIdRotation.peerId(secret, 100).contentEquals(PeerIdRotation.peerId(secret, 101)))
    }

    @Test
    fun recognitionTagsMatchUpstreamAndAreDirectional() {
        val key = PeerIdRotation.recognitionKey(ByteArray(32) { 0x42 })
        val a = ByteArray(32) { 0x0A }; val b = ByteArray(32) { 0x0B }; val id = ByteArray(8) { 0xA1.toByte() }
        assertEquals("4568f61d61d6cbfb", PeerIdRotation.tag(key, 100, a, b, id).toHex())
        assertEquals("5313c7731f629959", PeerIdRotation.tag(key, 100, b, a, id).toHex())
    }

    @Test
    fun bothSidesOfARealPairDeriveTheSameTag() {
        val alice = Crypto.x25519Generate(); val bob = Crypto.x25519Generate()
        val id = ByteArray(8) { 3 }
        val ka = PeerIdRotation.recognitionKey(Crypto.x25519(alice.privateKey, bob.publicKey))
        val kb = PeerIdRotation.recognitionKey(Crypto.x25519(bob.privateKey, alice.publicKey))
        val t = PeerIdRotation.tag(ka, 7, alice.publicKey, bob.publicKey, id)
        assertArrayEquals(t, PeerIdRotation.tag(kb, 7, alice.publicKey, bob.publicKey, id))
        val block = PeerIdRotation.tagBlock(listOf(t))
        assertEquals(64, block.size)
        assertTrue(PeerIdRotation.blockContains(block, t))
        assertEquals(64, PeerIdRotation.tagBlock(emptyList()).size)
    }

    @Test
    fun announceV2AndBindingAreFixedShape() {
        val block = PeerIdRotation.tagBlock(emptyList())
        val bytes = AnnounceV2(100, block, 1L shl 14).encode()!!
        val back = AnnounceV2.decode(bytes)!!
        assertEquals(100L, back.epoch); assertArrayEquals(block, back.tags); assertEquals(1L shl 14, back.capabilities)
        assertEquals(25 + 4 + 8 + 32, PeerIdRotation.bindingMessage(1, ByteArray(8), ByteArray(32)).size)
    }
}
