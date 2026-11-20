package org.openchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.app.ChatMessage
import org.openchat.android.app.DeliveryState
import org.openchat.android.mesh.MeshPeer
import java.util.TimeZone

class MeshVisualsTest {
    @Test
    fun agoPicksTheCoarsestWholeUnit() {
        val now = 1_762_000_000_000L
        assertEquals(0L to AgoUnit.SECONDS, agoParts(now, now))
        assertEquals(0L to AgoUnit.SECONDS, agoParts(now - 999, now))
        assertEquals(12L to AgoUnit.SECONDS, agoParts(now - 12_300, now))
        assertEquals(59L to AgoUnit.SECONDS, agoParts(now - 59_999, now))
        assertEquals(1L to AgoUnit.MINUTES, agoParts(now - 60_000, now))
        assertEquals(59L to AgoUnit.MINUTES, agoParts(now - 3_599_000, now))
        assertEquals(1L to AgoUnit.HOURS, agoParts(now - 3_600_000, now))
        assertEquals(23L to AgoUnit.HOURS, agoParts(now - 86_399_000, now))
        assertEquals(2L to AgoUnit.DAYS, agoParts(now - 2 * 86_400_000L - 5, now))
        // A peer whose clock runs ahead was heard just now, not in the future.
        assertEquals(0L to AgoUnit.SECONDS, agoParts(now + 90_000, now))
    }

    @Test
    fun theSweepTurnsOnceEveryFourSeconds() {
        assertEquals(0f, sweepAngle(0), 1e-6f)
        assertEquals(90f, sweepAngle(1_000), 1e-4f)
        assertEquals(270f, sweepAngle(7_000), 1e-4f)
        assertEquals(sweepAngle(123), sweepAngle(123 + 4_000 * 1_000_000L), 1e-3f)
        assertTrue(sweepAngle(3_999) < 360f)
    }

    @Test
    fun emblemsAreMirroredStableAndNeverBlank() {
        val keys = (0 until 200).map { "%016x".format(it * 0x9E3779B97F4A7C15uL.toLong()) }
        for (k in keys) {
            val c = Emblem.cells(k)
            assertEquals(25, c.size)
            for (row in 0 until 5) {
                assertEquals(c[row * 5], c[row * 5 + 4])
                assertEquals(c[row * 5 + 1], c[row * 5 + 3])
            }
            assertTrue(c.any { it })
            assertTrue(Emblem.colorIndex(k) in Emblem.PALETTE.indices)
        }
        // Case of the hex key does not matter; different people mostly differ.
        assertTrue(Emblem.cells("5d02aa11").contentEquals(Emblem.cells("5D02AA11")))
        assertTrue(keys.map { Emblem.cells(it).joinToString("") { b -> if (b) "1" else "0" } }.toSet().size > 150)
    }

    @Test
    fun emblemColoursSpreadOverThePalette() {
        val used = (0 until 400).map { Emblem.colorIndex("peer$it") }.toSet()
        assertEquals(Emblem.PALETTE.indices.toSet(), used)
    }

    @Test
    fun deliveryDotsOnlyMoveForward() {
        assertEquals(0, deliveryDots(DeliveryState.SENDING))
        assertEquals(1, deliveryDots(DeliveryState.SENT))
        assertEquals(1, deliveryDots(DeliveryState.QUEUED))
        assertEquals(2, deliveryDots(DeliveryState.CARRIED))
        assertEquals(3, deliveryDots(DeliveryState.DELIVERED))
        assertEquals(4, deliveryDots(DeliveryState.READ))
        assertEquals(0, deliveryDots(DeliveryState.FAILED))
    }

    @Test
    fun aDayMarkerOpensEachDay() {
        val utc = TimeZone.getTimeZone("UTC")
        val day = 86_400_000L
        fun msg(id: String, ts: Long) = ChatMessage(id, "mesh", "Curie", "e887", id, ts, mine = false)
        val rows = withDayBreaks(listOf(msg("a", 10 * day + 1_000), msg("b", 10 * day + 5_000), msg("c", 11 * day + 10)), utc)
        assertEquals(listOf("day", "a", "b", "day", "c"), rows.map { if (it is TimelineRow.Day) "day" else (it as TimelineRow.Message).m.id })
        val days = rows.filterIsInstance<TimelineRow.Day>()
        assertTrue(days[0].first)
        assertFalse(days[1].first)
        assertNotEquals(days[0].key, days[1].key)
        // Midnight is local: the same instant falls on different days in different zones.
        val late = 10 * day + 23 * 3_600_000L
        assertNotEquals(localDay(late, utc), localDay(late, TimeZone.getTimeZone("Asia/Shanghai")))
        assertTrue(withDayBreaks(emptyList(), utc).isEmpty())
    }

    @Test
    fun consecutiveMessagesFromSameSenderWithinTwoMinutesGroupTogether() {
        val utc = TimeZone.getTimeZone("UTC")
        val t0 = 10 * 86_400_000L + 60_000L
        fun msg(id: String, sender: String, ts: Long, mine: Boolean = false, sys: Boolean = false) =
            ChatMessage(id, "dm:e887", sender, sender, id, ts, mine = mine, system = sys)
        val rows = withDayBreaks(
            listOf(
                msg("1", "e887", t0),
                msg("2", "e887", t0 + 30_000),
                msg("3", "e887", t0 + 200_000), // > 2 min gap starts a new burst
                msg("4", "me", t0 + 210_000, mine = true),
                msg("sys", "sys", t0 + 215_000, sys = true),
                msg("5", "me", t0 + 220_000, mine = true), // system line breaks the burst
            ),
            utc,
        ).filterIsInstance<TimelineRow.Message>()
        assertEquals(listOf(true, false, true, true, true, true), rows.map { it.groupStart })
        assertEquals(listOf(false, true, true, true, true, true), rows.map { it.groupEnd })
    }

    @Test
    fun sortNearbyPeersExcludesSelfAndOrdersDirectThenHopsThenRecency() {
        val key = ByteArray(32)
        fun peer(hex: String, name: String, direct: Boolean, hops: Int?, lastSeen: Long) =
            MeshPeer(hex, name, key, key, lastSeen, direct, hops)

        val sorted = sortNearbyPeers(
            peers = listOf(
                peer("0000", "Self", direct = true, hops = 1, lastSeen = 9_000),
                peer("aaaa", "Curie", direct = false, hops = 3, lastSeen = 5_000),
                peer("bbbb", "Lovelace", direct = false, hops = null, lastSeen = 8_000),
                peer("cccc", "Darwin", direct = false, hops = 2, lastSeen = 4_000),
                peer("dddd", "ZhangHeng", direct = true, hops = 1, lastSeen = 3_000),
                peer("eeee", "LiBai", direct = true, hops = 1, lastSeen = 6_000),
                peer("ffff", "SuShi", direct = false, hops = 2, lastSeen = 7_000),
            ),
            myPeerHex = "0000",
        )

        assertEquals(listOf("eeee", "dddd", "ffff", "cccc", "aaaa", "bbbb"), sorted.map { it.peerIdHex })
    }

    @Test
    fun topologyGraphPlacesRingsBuildsHonestEdgesAndFindsShortestPath() {
        val key = ByteArray(32)
        fun peer(
            hex: String,
            name: String,
            direct: Boolean,
            hops: Int?,
            neighbors: Set<String> = emptySet(),
            heardVia: String? = null,
        ) = MeshPeer(hex, name, key, key, 10_000L, direct, hops, neighbors, heardVia)

        val graph = buildTopologyGraph(
            myPeerHex = "self0000",
            myNickname = "Me",
            peers = listOf(
                peer("self0000", "Me", direct = true, hops = 1),
                peer("dir1", "ZhangHeng", direct = true, hops = 1, neighbors = setOf("hop2a")),
                peer("dir2", "LiBai", direct = true, hops = 1),
                peer("hop2a", "SuShi", direct = false, hops = 2, neighbors = setOf("dir1", "hop3")),
                peer("hop2b", "Curie", direct = false, hops = 2, heardVia = "dir2"),
                peer("hop3", "Lovelace", direct = false, hops = 3, heardVia = "dir1"),
                peer("orphan", "Darwin", direct = false, hops = 3),
            ),
        )

        val byHex = graph.nodes.associateBy { it.peerHex }
        assertEquals(7, graph.nodes.size)
        assertEquals(0, byHex["self0000"]!!.ring)
        assertEquals(1, byHex["dir1"]!!.ring)
        assertEquals(1, byHex["dir2"]!!.ring)
        assertEquals(2, byHex["hop2a"]!!.ring)
        assertEquals(2, byHex["hop2b"]!!.ring)
        assertEquals(3, byHex["hop3"]!!.ring)
        assertEquals(3, graph.maxRing)

        // Direct edges for dir1 & dir2, announced edges dir1-hop2a & hop2a-hop3,
        // and inferred edge dir2-hop2b (while hop3 already has announced upstream hop2a so no duplicate inferred edge).
        val directEdges = graph.edges.filter { it.kind == TopologyEdgeKind.DIRECT }
        val announcedEdges = graph.edges.filter { it.kind == TopologyEdgeKind.ANNOUNCED }
        val inferredEdges = graph.edges.filter { it.kind == TopologyEdgeKind.INFERRED }
        assertEquals(2, directEdges.size)
        assertEquals(2, announcedEdges.size)
        assertEquals(1, inferredEdges.size)
        assertEquals(setOf("dir2", "hop2b"), setOf(inferredEdges[0].fromHex, inferredEdges[0].toHex))

        assertEquals(listOf("self0000", "dir1", "hop2a", "hop3"), shortestTopologyPath(graph, "hop3"))
        assertEquals(listOf("self0000", "dir2", "hop2b"), shortestTopologyPath(graph, "hop2b"))
        assertEquals(null, shortestTopologyPath(graph, "orphan"))
    }
}
