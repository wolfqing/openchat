package org.openchat.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.core.VoiceBurst

class PttFloorCoordinatorTest {
    private var now = 10_000L
    private val burstA = ByteArray(8) { (it + 1).toByte() }
    private val burstB = ByteArray(8) { (it + 0x21).toByte() }
    private val sampleFrame = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    private fun startPacket(id: ByteArray) =
        VoiceBurst(id, 0, VoiceBurst.Kind.Start(VoiceBurst.CODEC_AAC_LC_16K_MONO))

    private fun dataPacket(id: ByteArray, seq: Int) =
        VoiceBurst(id, seq, VoiceBurst.Kind.Frames(listOf(sampleFrame)))

    private fun endPacket(id: ByteArray, seq: Int, count: Int) =
        VoiceBurst(id, seq, VoiceBurst.Kind.End(count, 1200L))

    private fun cancelPacket(id: ByteArray, seq: Int) =
        VoiceBurst(id, seq, VoiceBurst.Kind.Canceled)

    @Test
    fun localHoldAndReleaseFollowsMinDurationThreshold() {
        val coord = PttFloorCoordinator { now }
        assertEquals(PttStartResult.STARTED, coord.tryAcquireTx("mesh"))
        assertTrue(coord.stateOf("mesh") is PttFloorState.Transmitting)

        // Short tap (< 300ms) releases to Idle and signals too-short
        now += 180L
        assertFalse(coord.releaseTx("mesh"))
        assertEquals(PttFloorState.Idle, coord.stateOf("mesh"))

        // Valid hold (>= 300ms) releases to Idle and signals valid burst
        assertEquals(PttStartResult.STARTED, coord.tryAcquireTx("mesh"))
        now += 450L
        assertTrue(coord.releaseTx("mesh"))
        assertEquals(PttFloorState.Idle, coord.stateOf("mesh"))
    }

    @Test
    fun remoteSpeakerLocksFloorAgainstLocalTxAndThirdSpeaker() {
        val coord = PttFloorCoordinator { now }
        val peer1 = "1111222233334444"
        val peer2 = "9999888877776666"
        val me = "5555666677778888"

        val r1 = coord.onRemotePacket("mesh", me, peer1, "张衡", 2, startPacket(burstA), 12, playLive = true)
        assertTrue(r1 is PttRemoteOutcome.Active)
        val rxState = coord.stateOf("mesh") as PttFloorState.Receiving
        assertEquals(peer1, rxState.senderKey)
        assertEquals(2, rxState.hops)

        // Local user tries to press PTT while peer1 holds the floor -> BUSY
        assertEquals(PttStartResult.BUSY, coord.tryAcquireTx("mesh"))

        // Third speaker tries to transmit on the same channel -> ignored, floor stays with peer1
        val rThird = coord.onRemotePacket("mesh", me, peer2, "李白", 1, dataPacket(burstB, 1), 20, playLive = true)
        assertEquals(PttRemoteOutcome.Ignored, rThird)
        assertEquals(peer1, (coord.stateOf("mesh") as PttFloorState.Receiving).senderKey)

        // peer1 sends data and END -> floor returns to Idle cleanly
        val rData = coord.onRemotePacket("mesh", me, peer1, "张衡", 2, dataPacket(burstA, 1), 20, playLive = true)
        assertEquals(1, (rData as PttRemoteOutcome.Active).frames.size)
        assertFalse(rData.finishedCleanly)

        val rEnd = coord.onRemotePacket("mesh", me, peer1, "张衡", 2, endPacket(burstA, 2, 1), 16, playLive = true)
        assertTrue((rEnd as PttRemoteOutcome.Active).finishedCleanly)
        assertEquals(PttFloorState.Idle, coord.stateOf("mesh"))

        // Now local user can acquire TX again
        assertEquals(PttStartResult.STARTED, coord.tryAcquireTx("mesh"))
    }

    @Test
    fun simultaneousTxCollisionUsesDeterministicPeerIdTieBreaker() {
        val coord = PttFloorCoordinator { now }
        val me = "8000000000000000"
        val higherPriorityPeer = "2000000000000000" // lexicographically smaller -> wins
        val lowerPriorityPeer = "f000000000000000"  // lexicographically larger -> loses

        // Case 1: I am transmitting, lower-priority peer collides -> I keep transmitting
        assertEquals(PttStartResult.STARTED, coord.tryAcquireTx("mesh"))
        val loseOutcome = coord.onRemotePacket("mesh", me, lowerPriorityPeer, "苏轼", 1, startPacket(burstA), 12, playLive = true)
        assertEquals(PttRemoteOutcome.Ignored, loseOutcome)
        assertTrue(coord.stateOf("mesh") is PttFloorState.Transmitting)

        // Case 2: I am transmitting, higher-priority peer collides -> I am preempted and switch to Receiving
        val winOutcome = coord.onRemotePacket("mesh", me, higherPriorityPeer, "张衡", 3, dataPacket(burstB, 1), 20, playLive = true)
        assertTrue(winOutcome is PttRemoteOutcome.Active)
        val active = winOutcome as PttRemoteOutcome.Active
        assertTrue(active.preemptedLocalTx)
        assertEquals(1, active.frames.size)
        val state = coord.stateOf("mesh") as PttFloorState.Receiving
        assertEquals(higherPriorityPeer, state.senderKey)
        assertEquals(3, state.hops)
    }

    @Test
    fun idleTimeoutReleasesStuckReceivingFloorAfterThreeSeconds() {
        val coord = PttFloorCoordinator { now }
        val me = "aaaa000000000000"
        val peer = "bbbb000000000000"

        coord.onRemotePacket("mesh", me, peer, "张衡", 1, dataPacket(burstA, 1), 20, playLive = true)
        assertTrue(coord.stateOf("mesh") is PttFloorState.Receiving)

        now += 2_900L
        assertTrue(coord.tick().isEmpty())
        assertTrue(coord.stateOf("mesh") is PttFloorState.Receiving)

        now += 200L // 3.1s elapsed without packets
        val settled = coord.tick()
        assertEquals(1, settled.size)
        assertTrue(settled[0].finishedCleanly)
        assertEquals(PttFloorState.Idle, coord.stateOf("mesh"))
    }

    @Test
    fun absorbsTrailingStockBitchatVoiceNoteOnlyWhenHeardLive() {
        val coord = PttFloorCoordinator { now }
        val me = "aaaa000000000000"
        val peer = "bbbb000000000000"
        val hexA = "0102030405060708"
        val hexB = "2122232425262728"

        // Burst A played live -> trailing voice_<hexA>.m4a is absorbed (no duplicate chat bubble)
        coord.onRemotePacket("mesh", me, peer, "张衡", 1, dataPacket(burstA, 1), 20, playLive = true)
        coord.onRemotePacket("mesh", me, peer, "张衡", 1, endPacket(burstA, 2, 1), 16, playLive = true)
        assertTrue(coord.shouldAbsorbVoiceNote("mesh", peer, "voice_${hexA}.m4a"))
        // Second check for same file does not over-absorb unrelated notes
        assertFalse(coord.shouldAbsorbVoiceNote("mesh", peer, "voice_${hexA}.m4a"))

        // Burst B arrived while muted / in background (playLive = false) -> NOT absorbed so user gets missed note bubble
        coord.onRemotePacket("mesh", me, peer, "张衡", 1, dataPacket(burstB, 1), 20, playLive = false)
        coord.onRemotePacket("mesh", me, peer, "张衡", 1, endPacket(burstB, 2, 1), 16, playLive = false)
        assertFalse(coord.shouldAbsorbVoiceNote("mesh", peer, "voice_${hexB}.m4a"))
    }
}
