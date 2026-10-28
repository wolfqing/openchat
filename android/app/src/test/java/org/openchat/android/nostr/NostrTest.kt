package org.openchat.android.nostr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.core.hexToBytes
import org.openchat.android.core.toHex

class NostrTest {
    @Test
    fun bip340OfficialVectors() {
        val lines = javaClass.classLoader!!.getResource("bip340-vectors.csv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
        var signed = 0
        for (line in lines) {
            val c = line.split(",")
            val (index, secret, pub, aux, msg, sig, result) = c
            val expected = result == "TRUE"
            val message = msg.hexToBytes()!!
            if (secret.isNotEmpty()) {
                assertEquals("pubkey $index", pub.lowercase(), Schnorr.publicKey(secret.hexToBytes()!!).toHex())
                assertEquals("sig $index", sig.lowercase(), Schnorr.sign(secret.hexToBytes()!!, message, aux.hexToBytes()!!).toHex())
                signed++
            }
            assertEquals("verify $index", expected, Schnorr.verify(pub.hexToBytes()!!, message, sig.hexToBytes()!!))
        }
        assertTrue(signed >= 4)
    }

    private operator fun <T> List<T>.component6() = this[5]
    private operator fun <T> List<T>.component7() = this[6]

    @Test
    fun geohashChannelEventRoundTrip() {
        val key = NostrKey.forGeohash(ByteArray(32) { 7 }, "9q8yy")
        val ev = NostrEvent.geohashMessage(key, "9q8yy", "alice", "hello \"quoted\"\nline", teleported = false)
        assertTrue(ev.isValid())
        assertEquals(20000, ev.kind)
        assertEquals("9q8yy", ev.tag("g"))
        assertEquals("alice", ev.tag("n"))
        val parsed = NostrEvent.fromJson(JSONObject(ev.toJson()))!!
        assertEquals(ev, parsed)
        assertTrue(parsed.isValid())
        assertFalse(parsed.copy(content = "tampered").isValid())
    }

    @Test
    fun eventIdUsesNip01Serialization() {
        // Escapes only the NIP-01 set; non-ASCII passes through unescaped.
        assertEquals("\"a\\\"b\\\\c\\nd é\"", NostrEvent.jsonString("a\"b\\c\nd é"))
        val id = NostrEvent.computeId("aa", 1, 1, listOf(listOf("g", "u4")), "hi")
        assertEquals(org.openchat.android.core.sha256("[0,\"aa\",1,1,[[\"g\",\"u4\"]],\"hi\"]".toByteArray()).toHex(), id)
    }

    @Test
    fun geohashEncodeAndCenter() {
        assertEquals("ezs42", Geohash.encode(42.6, -5.6, 5))
        assertEquals("u4pruydqqvj", Geohash.encode(57.64911, 10.40744, 11))
        val (lat, lon) = Geohash.center("ezs42")!!
        assertEquals(42.6, lat, 0.03)
        assertEquals(-5.6, lon, 0.03)
        assertFalse(Geohash.isValid("abc!"))
    }

    @Test
    fun perGeohashIdentitiesAreUnlinkableButStable() {
        val seed = ByteArray(32) { it.toByte() }
        val a = NostrKey.forGeohash(seed, "wtw3s")
        assertEquals(a.publicKeyHex, NostrKey.forGeohash(seed, "wtw3s").publicKeyHex)
        assertNotEquals(a.publicKeyHex, NostrKey.forGeohash(seed, "wtw3").publicKeyHex)
        assertNotEquals(a.publicKeyHex, NostrKey.forGeohash(ByteArray(32), "wtw3s").publicKeyHex)
    }

    @Test
    fun relayDirectoryPicksNearest() {
        val dir = RelayDirectory.parse("Relay URL,Latitude,Longitude\nny.example,40.7,-74.0\nsf.example,37.77,-122.42\nparis.example,48.85,2.35\n")
        assertEquals(listOf("wss://paris.example"), dir.closest("u09tv", 1)) // Paris
        assertEquals("wss://sf.example", dir.closest("9q8yy", 3).first()) // San Francisco
    }
}
