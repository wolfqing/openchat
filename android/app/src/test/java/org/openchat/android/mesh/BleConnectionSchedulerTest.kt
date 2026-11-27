package org.openchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleConnectionSchedulerTest {
    @Test
    fun isolatedNodesRelaxRssiFloorAfterThirtySeconds() {
        val s = BleConnectionScheduler()
        assertEquals(-95, s.updateRssiThreshold(totalLinks = 0, centralLinksOrConnecting = 0, nowMs = 1_000L))
        assertEquals(-95, s.updateRssiThreshold(totalLinks = 0, centralLinksOrConnecting = 0, nowMs = 31_000L))
        assertEquals(-100, s.updateRssiThreshold(totalLinks = 0, centralLinksOrConnecting = 0, nowMs = 32_000L))

        // Connected nodes tighten back to -90, and -85 when central slots are full.
        assertEquals(-90, s.updateRssiThreshold(totalLinks = 1, centralLinksOrConnecting = 1, nowMs = 33_000L))
        assertEquals(-85, s.updateRssiThreshold(totalLinks = 6, centralLinksOrConnecting = 6, nowMs = 34_000L))
    }

    @Test
    fun globalRateLimitQueuesSecondDiscoveryAndDrainsStrongestFirst() {
        val s = BleConnectionScheduler()
        val a = BleConnectionScheduler.Candidate("AA:01", rssi = -70, discoveredAtMs = 1_000L)
        val b = BleConnectionScheduler.Candidate("BB:02", rssi = -82, discoveredAtMs = 1_100L)
        val c = BleConnectionScheduler.Candidate("CC:03", rssi = -60, discoveredAtMs = 1_150L)

        assertEquals(
            BleConnectionScheduler.DiscoveryDecision.ConnectNow,
            s.onDiscovery(a, totalLinks = 0, centralLinksOrConnecting = 0, isConnected = false, isConnecting = false, nowMs = 1_000L),
        )
        s.recordConnectionAttempt(a.address, 1_000L)

        val dB = s.onDiscovery(b, totalLinks = 0, centralLinksOrConnecting = 1, isConnected = false, isConnecting = false, nowMs = 1_100L)
        assertTrue(dB is BleConnectionScheduler.DiscoveryDecision.ScheduleRetry)
        val dC = s.onDiscovery(c, totalLinks = 0, centralLinksOrConnecting = 1, isConnected = false, isConnecting = false, nowMs = 1_150L)
        assertTrue(dC is BleConnectionScheduler.DiscoveryDecision.ScheduleRetry)

        val next = s.nextCandidate(centralLinksOrConnecting = 1, isAlreadyActive = { false }, nowMs = 1_600L)
        assertTrue(next is BleConnectionScheduler.QueueDecision.Connect)
        assertEquals("CC:03", (next as BleConnectionScheduler.QueueDecision.Connect).candidate.address)
    }

    @Test
    fun connectTimeoutBacksOffLongerThanEstablishedDisconnect() {
        val s = BleConnectionScheduler()
        s.recordConnectionTimeout("AA:01", nowMs = 10_000L)
        val timedOut = BleConnectionScheduler.Candidate("AA:01", rssi = -70, discoveredAtMs = 20_000L)
        assertEquals(
            BleConnectionScheduler.DiscoveryDecision.Ignore,
            s.onDiscovery(timedOut, totalLinks = 0, centralLinksOrConnecting = 0, isConnected = false, isConnecting = false, nowMs = 20_000L),
        )
        assertEquals(
            BleConnectionScheduler.DiscoveryDecision.ConnectNow,
            s.onDiscovery(timedOut.copy(discoveredAtMs = 26_000L), totalLinks = 0, centralLinksOrConnecting = 0, isConnected = false, isConnecting = false, nowMs = 26_000L),
        )

        s.recordDisconnect("BB:02", nowMs = 30_000L)
        val dropped = BleConnectionScheduler.Candidate("BB:02", rssi = -70, discoveredAtMs = 32_000L)
        assertEquals(
            BleConnectionScheduler.DiscoveryDecision.Ignore,
            s.onDiscovery(dropped, totalLinks = 0, centralLinksOrConnecting = 0, isConnected = false, isConnecting = false, nowMs = 32_000L),
        )
        assertEquals(
            BleConnectionScheduler.DiscoveryDecision.ConnectNow,
            s.onDiscovery(dropped.copy(discoveredAtMs = 34_000L), totalLinks = 0, centralLinksOrConnecting = 0, isConnected = false, isConnecting = false, nowMs = 34_000L),
        )
    }
}
