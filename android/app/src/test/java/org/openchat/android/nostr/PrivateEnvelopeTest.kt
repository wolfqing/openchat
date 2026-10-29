package org.openchat.android.nostr

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.openchat.android.core.hexToBytes
import org.openchat.android.core.toHex
import org.openchat.android.crypto.Crypto

/**
 * bitchat's Nostr private envelope (docs/protocol-spec.md §15.3), checked against
 * two envelopes recorded from released bitchat builds (fixtures in upstream's
 * bitchatTests/Nostr/Fixtures, public domain).
 */
class PrivateEnvelopeTest {
    private fun fixture(name: String) =
        NostrEvent.fromJson(JSONObject(javaClass.getResource("/nostr/$name.json")!!.readText()))!!

    @Test
    fun opensEnvelopeFromReleasedIosBuild() {
        val me = NostrKey("8355a5c110cdfef2e644f4ad5d51c39f253b2c2c80ebb6856379fb16531dc1fa".hexToBytes()!!)
        val opened = PrivateEnvelope.open(fixture("LegacyPrivateEnvelope733098bb"), me)!!
        assertEquals("legacy fixture from 733098bb", opened.content)
    }

    @Test
    fun opensEnvelopeFromAndroidBuild() {
        val me = NostrKey("0000000000000000000000000000000000000000000000000000000000000002".hexToBytes()!!)
        val opened = PrivateEnvelope.open(fixture("AndroidLegacyPrivateEnvelopeB7f0b33d"), me)!!
        assertEquals("legacy fixture from Android b7f0b33d", opened.content)
        assertEquals("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", opened.senderPubkey)
    }

    @Test
    fun roundTripsAndRejectsOtherRecipients() {
        val alice = NostrKey(Crypto.randomBytes(32).also { it[0] = 1 })
        val bob = NostrKey(Crypto.randomBytes(32).also { it[0] = 2 })
        val carol = NostrKey(Crypto.randomBytes(32).also { it[0] = 3 })
        val wrap = PrivateEnvelope.create("bitchat1:AAEC", bob.publicKeyHex, alice, 1_800_000_000L)
        assertEquals(PrivateEnvelope.KIND_WRAP, wrap.kind)
        assertEquals(listOf(listOf("p", bob.publicKeyHex)), wrap.tags)
        val opened = PrivateEnvelope.open(wrap, bob)!!
        assertEquals("bitchat1:AAEC", opened.content)
        assertEquals(alice.publicKeyHex, opened.senderPubkey)
        assertEquals(1_800_000_000L, opened.createdAt)
        assertNull(PrivateEnvelope.open(wrap, carol))
        assertNull(PrivateEnvelope.open(wrap.copy(content = wrap.content.dropLast(2) + "AA"), bob))
    }

    @Test
    fun xchachaMatchesTheDraftVector() {
        // draft-irtf-cfrg-xchacha-03 §2.2.1 HChaCha20 test vector.
        val key = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f".hexToBytes()!!
        val nonce = "000000090000004a0000000031415927".hexToBytes()!!
        assertEquals("82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc", XChaCha.hchacha(key, nonce).toHex())
    }

    @Test
    fun bech32NpubRoundTrips() {
        val hex = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"
        assertEquals("npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg", Bech32.npub(hex))
        assertEquals(hex, Bech32.npubToHex("npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"))
        assertNull(Bech32.npubToHex("npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptx"))
        assertNotNull(Bech32.decode("A12UEL5L"))
    }
}
