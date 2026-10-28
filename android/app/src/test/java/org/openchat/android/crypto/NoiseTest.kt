package org.openchat.android.crypto

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openchat.android.core.hexToBytes
import org.openchat.android.core.toHex

class NoiseTest {
    private fun vector(): JSONObject =
        JSONObject(javaClass.classLoader!!.getResource("noise-xx-vector.json")!!.readText())

    @Test
    fun xxMatchesCacophonyVector() {
        val v = vector()
        val prologue = v.getString("init_prologue").hexToBytes()!!
        val ini = HandshakeXX(
            initiator = true,
            localStatic = Crypto.x25519FromPrivate(v.getString("init_static").hexToBytes()!!),
            ephemeralOverride = Crypto.x25519FromPrivate(v.getString("init_ephemeral").hexToBytes()!!),
            prologue = prologue,
        )
        val res = HandshakeXX(
            initiator = false,
            localStatic = Crypto.x25519FromPrivate(v.getString("resp_static").hexToBytes()!!),
            ephemeralOverride = Crypto.x25519FromPrivate(v.getString("resp_ephemeral").hexToBytes()!!),
            prologue = v.getString("resp_prologue").hexToBytes()!!,
        )
        val messages = v.getJSONArray("messages")
        for (i in 0 until 3) {
            val m = messages.getJSONObject(i)
            val (writer, reader) = if (i % 2 == 0) ini to res else res to ini
            val ct = writer.writeMessage(m.getString("payload").hexToBytes()!!)
            assertEquals("handshake message $i", m.getString("ciphertext"), ct.toHex())
            assertArrayEquals(m.getString("payload").hexToBytes(), reader.readMessage(ct))
        }
        assertTrue(ini.isComplete && res.isComplete)
        assertEquals(v.getString("handshake_hash"), ini.handshakeHash.toHex())

        // The vector's transport messages are plain Noise (no nonce prefix).
        val (iSend, iRecv) = ini.split(extractedNonce = false)
        val (rSend, rRecv) = res.split(extractedNonce = false)
        for (i in 3 until messages.length()) {
            val m = messages.getJSONObject(i)
            val fromInitiator = i % 2 == 0
            val ct = (if (fromInitiator) iSend else rSend).encrypt(m.getString("payload").hexToBytes()!!)
            assertEquals("transport message $i", m.getString("ciphertext"), ct.toHex())
            assertArrayEquals(m.getString("payload").hexToBytes(), (if (fromInitiator) rRecv else iRecv).decrypt(ct))
        }
    }

    private fun session(): List<CipherState> {
        val a = HandshakeXX(true, Crypto.x25519Generate())
        val b = HandshakeXX(false, Crypto.x25519Generate())
        b.readMessage(a.writeMessage())
        a.readMessage(b.writeMessage())
        b.readMessage(a.writeMessage())
        val (aSend, aRecv) = a.split()
        val (bSend, bRecv) = b.split()
        return listOf(aSend, aRecv, bSend, bRecv)
    }

    @Test
    fun bitchatFramingPrefixesBigEndianNonce() {
        val (aSend, _, _, bRecv) = session()
        val c0 = aSend.encrypt("one".toByteArray())
        val c1 = aSend.encrypt("two".toByteArray())
        assertEquals("00000000", c0.copyOf(4).toHex())
        assertEquals("00000001", c1.copyOf(4).toHex())
        assertEquals(4 + 3 + 16, c0.size)
        // Out-of-order delivery is fine; replays are not.
        assertEquals("two", String(bRecv.decrypt(c1)))
        assertEquals("one", String(bRecv.decrypt(c0)))
        try {
            bRecv.decrypt(c1)
            fail("replay accepted")
        } catch (e: NoiseException) { }
    }

    @Test
    fun bothDirectionsWork() {
        val (aSend, aRecv, bSend, bRecv) = session()
        repeat(50) { assertEquals("a$it", String(bRecv.decrypt(aSend.encrypt("a$it".toByteArray())))) }
        repeat(50) { assertEquals("b$it", String(aRecv.decrypt(bSend.encrypt("b$it".toByteArray())))) }
    }

    @Test
    fun replayWindowRejectsVeryOldNonces() {
        val (aSend, _, _, bRecv) = session()
        val first = aSend.encrypt("old".toByteArray())
        val later = (1..1100).map { aSend.encrypt("x".toByteArray()) }
        bRecv.decrypt(later.last())
        try {
            bRecv.decrypt(first)
            fail("nonce outside window accepted")
        } catch (e: NoiseException) { }
        assertEquals("x", String(bRecv.decrypt(later[later.size - 2])))
    }

    @Test
    fun remoteStaticIsLearned() {
        val sa = Crypto.x25519Generate()
        val sb = Crypto.x25519Generate()
        val a = HandshakeXX(true, sa)
        val b = HandshakeXX(false, sb)
        val m1 = a.writeMessage()
        assertEquals(32, m1.size)
        b.readMessage(m1)
        val m2 = b.writeMessage()
        assertEquals(32 + 48 + 16, m2.size)
        a.readMessage(m2)
        val m3 = a.writeMessage()
        assertEquals(48 + 16, m3.size)
        b.readMessage(m3)
        assertArrayEquals(sb.publicKey, a.remoteStatic)
        assertArrayEquals(sa.publicKey, b.remoteStatic)
    }
}
