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

    @Test
    fun relayDirectoryStrips443DeduplicatesAndTieBreaksByHost() {
        val csv = """
            Relay URL,Latitude,Longitude
            nostr.2b9t.xyz,34.0549,-118.243
            wss://nostr.2b9t.xyz:443/,34.0549,-118.243
            relay.lanacoin-eternity.com:443,40.8302,-74.1299
            communities.nos.social,40.8302,-74.1299
            nostr.notribe.net:443,40.8302,-74.1299
            custom.relay.org:8443,34.0549,-118.243
        """.trimIndent()
        val dir = RelayDirectory.parse(csv)
        assertEquals(5, dir.size)
        // Equidistant NYC/NJ relays tie-break alphabetically by host.
        assertEquals(
            listOf(
                "wss://communities.nos.social",
                "wss://nostr.notribe.net",
                "wss://relay.lanacoin-eternity.com",
            ),
            dir.closest("dr", 3),
        )
        // LA relays keep custom non-443 port and only one copy of nostr.2b9t.xyz.
        assertEquals(
            listOf("wss://custom.relay.org:8443", "wss://nostr.2b9t.xyz"),
            dir.closest("9q", 2),
        )

        val bundledFile = java.io.File("src/main/assets/relays.csv")
        if (bundledFile.isFile) {
            val bundled = RelayDirectory.parse(bundledFile.readText())
            assertEquals(326, bundled.size)
            assertTrue("herbstmeister.com enters #9q top 5 once :443 duplicates are removed",
                "wss://herbstmeister.com" in bundled.closest("9q", 5))
            val drTop = bundled.closest("dr", 5)
            assertEquals(5, drTop.distinct().size)
            assertTrue("no :443 suffix remains in normalized relay URLs", drTop.none { it.endsWith(":443") })
            assertEquals(
                listOf("wss://bridge.tagomago.me", "wss://nostr.tagomago.me", "wss://relay.fundstr.me"),
                drTop.subList(1, 4),
            )
        }
    }

    @Test
    fun relayDirectoryValidatedParserEnforcesIntegrityAndOverlap() {
        val baseCsv = buildString {
            appendLine("Relay URL,Latitude,Longitude")
            for (i in 1..6) appendLine("r$i.example.org,${10.0 + i},${20.0 + i}")
        }
        val baseline = RelayDirectory.parseValidatedEntries(baseCsv, minimumEntries = 5)!!
        assertEquals(6, baseline.size)

        // Conflicting coordinates for the same host (after :443 normalization) rejects the whole CSV.
        val conflictCsv = baseCsv + "r1.example.org:443,50.0,60.0\n"
        assertEquals(null, RelayDirectory.parseValidatedEntries(conflictCsv, minimumEntries = 5, baseline = baseline))

        // Fewer than 50% retained baseline hosts rejects a hostile retargeting CSV.
        val hijackCsv = buildString {
            appendLine("Relay URL,Latitude,Longitude")
            appendLine("r1.example.org,11.0,21.0")
            appendLine("r2.example.org,12.0,22.0")
            for (i in 1..5) appendLine("evil$i.attacker.net,30.0,40.0")
        }
        assertEquals(null, RelayDirectory.parseValidatedEntries(hijackCsv, minimumEntries = 5, baseline = baseline))

        // Valid refresh (>= 50% overlap) succeeds and fires onUpdated.
        val refreshedCsv = buildString {
            appendLine("Relay URL,Latitude,Longitude")
            for (i in 1..4) appendLine("r$i.example.org,${10.0 + i},${20.0 + i}")
            appendLine("fresh1.example.org,35.0,-118.0")
            appendLine("fresh2.example.org,40.7,-74.0")
        }
        val refreshed = RelayDirectory.parseValidatedEntries(refreshedCsv, minimumEntries = 5, baseline = baseline)!!
        val dir = RelayDirectory(baseline)
        var updates = 0
        dir.onUpdated = { updates++ }
        dir.updateEntries(refreshed)
        assertEquals(1, updates)
        assertEquals(6, dir.size)
    }

    @Test
    fun relayPoolPromotesFallbackRelaysWhenPrimaryFails() {
        val closedServer = java.net.ServerSocket(0)
        val deadPort = closedServer.localPort
        closedServer.close()

        NetRoute.set(torWanted = false, socksPort = 0)
        val pool = RelayPool(failoverGraceMs = 80L) { _, _ -> }
        try {
            pool.subscribe(
                subId = "geo-dr",
                relays = listOf("ws://127.0.0.1:$deadPort"),
                filter = JSONObject().put("limit", 1),
                fallbackRelays = listOf("ws://127.0.0.1:${deadPort + 1}", "ws://127.0.0.1:${deadPort + 2}"),
            )
            val deadline = System.currentTimeMillis() + 2_500L
            while (System.currentTimeMillis() < deadline && pool.activeRelays("geo-dr").size < 2) {
                Thread.sleep(25L)
            }
            assertTrue(
                "backup relay should be promoted automatically when primary refuses connection",
                pool.activeRelays("geo-dr").contains("ws://127.0.0.1:${deadPort + 1}"),
            )
        } finally {
            pool.shutdown()
            NetRoute.set(torWanted = true, socksPort = 0)
        }
    }
}
