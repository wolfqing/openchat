package org.openchat.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.crypto.Crypto

/** Verification QR and challenge/response (docs/protocol-spec.md §16). */
class VerificationTest {
    private val noise = Crypto.x25519Generate()
    private val signing = Crypto.ed25519Generate()
    private val now = 1_800_000_000L

    @Test
    fun signedLinkRoundTrips() {
        val qr = VerifyQr.build(noise.publicKey, signing, "Ada Lovelace+1 & co", "npub1test", now)
        val url = qr.toUrl()
        assertTrue(url.startsWith("bitchat://verify?v=1&noise="))
        assertTrue("space must be %20, not +: $url", url.contains("Ada%20Lovelace%2B1%20%26%20co"))
        val back = VerifyQr.verify(url, now + 10)!!
        assertEquals("Ada Lovelace+1 & co", back.nickname)
        assertEquals(noise.publicKey.toHex(), back.noiseKeyHex)
        assertEquals("npub1test", back.npub)
    }

    @Test
    fun canonicalBytesFollowTheFieldOrder() {
        val qr = VerifyQr(1, "AB", "CD", null, "李白", 42, "bm9uY2U")
        val expected = ByteWriter()
        listOf("bitchat-verify-v1", "1", "ab", "cd", "", "李白", "42", "bm9uY2U").forEach {
            val b = it.toByteArray(Charsets.UTF_8); expected.u8(b.size).bytes(b)
        }
        assertEquals(expected.toByteArray().toHex(), qr.canonicalBytes().toHex())
    }

    @Test
    fun rejectsExpiredTamperedAndForeignLinks() {
        val url = VerifyQr.build(noise.publicKey, signing, "Darwin", null, now).toUrl()
        assertNull(VerifyQr.verify(url, now + 301))
        assertNull(VerifyQr.verify(url, now - 301))
        assertNotNull(VerifyQr.verify(url, now + 299))
        assertNull(VerifyQr.verify(url.replace("nick=Darwin", "nick=Curie"), now))
        assertNull(VerifyQr.verify("https://example.org/verify?v=1", now))
        assertNull(VerifyQr.verify("bitchat://verify?v=1&noise=00", now))
    }

    @Test
    fun challengeResponseProvesTheKey() {
        val nonce = Crypto.randomBytes(16)
        val keyHex = noise.publicKey.toHex()
        val (k, n) = VerifyQr.parseChallenge(VerifyQr.challenge(keyHex, nonce))!!
        assertEquals(keyHex, k); assertTrue(n.contentEquals(nonce))
        val resp = VerifyQr.response(k, n, signing)
        assertTrue(VerifyQr.checkResponse(resp, keyHex, nonce, signing.publicKey))
        assertFalse(VerifyQr.checkResponse(resp, keyHex, Crypto.randomBytes(16), signing.publicKey))
        assertFalse(VerifyQr.checkResponse(resp, keyHex, nonce, Crypto.ed25519Generate().publicKey))
    }
}
