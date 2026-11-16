package org.openchat.android.app

import org.openchat.android.core.BurstAssembly
import org.openchat.android.core.VoiceBurst

/** Immediate outcome when the user presses and holds the Walkie-Talkie PTT disc. */
enum class PttStartResult {
    STARTED,
    BUSY,
    NO_PEERS,
    PEER_UNREACHABLE,
    HANDSHAKING,
    MUTED,
    MIC_FAILED,
}

/** Per-conversation half-duplex walkie-talkie floor state. */
sealed class PttFloorState {
    object Idle : PttFloorState()
    data class Transmitting(val startedAt: Long) : PttFloorState()
    data class Receiving(
        val senderKey: String,
        val senderName: String,
        val burstHex: String,
        val hops: Int?,
        val startedAt: Long,
    ) : PttFloorState()
}

sealed class PttRemoteOutcome {
    object Ignored : PttRemoteOutcome()
    data class Active(
        val conversation: String,
        val senderKey: String,
        val senderName: String,
        val burstHex: String,
        val hops: Int?,
        val frames: List<ByteArray>,
        val startedNew: Boolean,
        val preemptedLocalTx: Boolean = false,
        val finishedCleanly: Boolean = false,
        val canceled: Boolean = false,
    ) : PttRemoteOutcome()
}

/**
 * Pure-Kotlin half-duplex floor arbiter for Walkie-Talkie PTT (`docs/specs/2026-11-15-walkie-talkie-ptt-design.md`):
 * - Enforces single-speaker occupancy per conversation (`mesh` or `dm:<peerHex>`).
 * - Resolves simultaneous BLE TX collisions deterministically by lowercase `peerIdHex` ordering.
 * - Tracks live-heard bursts so trailing stock-bitchat `voice_<burst>.m4a` files do not duplicate in chat.
 */
class PttFloorCoordinator(private val clock: () -> Long = System::currentTimeMillis) {
    private class ActiveRx(
        val conversation: String,
        val senderKey: String,
        var senderName: String,
        val burstHex: String,
        var hops: Int?,
        val startedAt: Long,
        val assembly: BurstAssembly,
        var playedLive: Boolean,
        var absorbedNote: Boolean = false,
    ) {
        fun toPublicState() = PttFloorState.Receiving(senderKey, senderName, burstHex, hops, startedAt)
    }

    private val txStates = HashMap<String, PttFloorState.Transmitting>()
    private val rxStates = LinkedHashMap<String, ActiveRx>()
    private val settledBursts = LinkedHashMap<String, Long>() // "$conv|$sender|$burst" -> settledAt
    private val heardLiveBursts = LinkedHashMap<String, Long>() // "$conv|$sender|$burst" -> heardAt

    @Synchronized
    fun stateOf(conversation: String): PttFloorState =
        txStates[conversation] ?: rxStates[conversation]?.toPublicState() ?: PttFloorState.Idle

    @Synchronized
    fun snapshotStates(): Map<String, PttFloorState> {
        val out = HashMap<String, PttFloorState>(txStates.size + rxStates.size)
        rxStates.forEach { (c, r) -> out[c] = r.toPublicState() }
        txStates.forEach { (c, t) -> out[c] = t }
        return out
    }

    @Synchronized
    fun tryAcquireTx(conversation: String): PttStartResult {
        if (rxStates.containsKey(conversation)) return PttStartResult.BUSY
        if (!txStates.containsKey(conversation)) {
            txStates[conversation] = PttFloorState.Transmitting(clock())
        }
        return PttStartResult.STARTED
    }

    /** Releases local TX and returns true when held at least [MIN_HOLD_MS] (valid burst), false when a brief tap. */
    @Synchronized
    fun releaseTx(conversation: String): Boolean {
        val tx = txStates.remove(conversation) ?: return false
        return clock() - tx.startedAt >= MIN_HOLD_MS
    }

    @Synchronized
    fun cancelTx(conversation: String): Boolean = txStates.remove(conversation) != null

    @Synchronized
    fun hasActiveRx(): Boolean = rxStates.isNotEmpty()

    @Synchronized
    fun onRemotePacket(
        conversation: String,
        myPeerHex: String,
        senderHex: String,
        senderName: String,
        hops: Int?,
        packet: VoiceBurst,
        rawSize: Int,
        playLive: Boolean,
    ): PttRemoteOutcome {
        if (myPeerHex.isNotEmpty() && senderHex.equals(myPeerHex, ignoreCase = true)) return PttRemoteOutcome.Ignored
        val now = clock()
        pruneMaps(now)
        val bKey = burstKey(conversation, senderHex, packet.burstHex)

        // 1. Check simultaneous TX collision window
        val localTx = txStates[conversation]
        if (localTx != null) {
            val isOpening = packet.kind is VoiceBurst.Kind.Start || packet.kind is VoiceBurst.Kind.Frames
            if (!isOpening || bKey in settledBursts) return PttRemoteOutcome.Ignored
            val remoteWins = senderHex.lowercase() < myPeerHex.lowercase()
            if (!remoteWins) return PttRemoteOutcome.Ignored
            txStates.remove(conversation)
            return openAndFeed(conversation, senderHex, senderName, hops, packet, rawSize, playLive, now, preempted = true)
        }

        // 2. Check existing RX holder in this conversation
        val current = rxStates[conversation]
        if (current != null) {
            if (!current.senderKey.equals(senderHex, ignoreCase = true)) {
                // Another speaker already holds the channel -> strict half-duplex rejects concurrent speaker
                return PttRemoteOutcome.Ignored
            }
            if (!current.burstHex.equals(packet.burstHex, ignoreCase = true)) {
                val isOpening = packet.kind is VoiceBurst.Kind.Start || packet.kind is VoiceBurst.Kind.Frames
                if (!isOpening || bKey in settledBursts) return PttRemoteOutcome.Ignored
                // Same speaker started a fresh burst right away: close previous cleanly and open new
                finalizeRx(current, now)
                return openAndFeed(conversation, senderHex, senderName, hops, packet, rawSize, playLive, now, preempted = false)
            }
            if (senderName.isNotEmpty()) current.senderName = senderName
            if (hops != null) current.hops = hops
            if (playLive) current.playedLive = true
            val frames = current.assembly.accept(packet, rawSize)
            return settleIfDone(current, frames, startedNew = false, preempted = false, now = now)
        }

        // 3. Channel is IDLE
        if (packet.kind is VoiceBurst.Kind.End || packet.kind == VoiceBurst.Kind.Canceled || bKey in settledBursts) {
            return PttRemoteOutcome.Ignored
        }
        return openAndFeed(conversation, senderHex, senderName, hops, packet, rawSize, playLive, now, preempted = false)
    }

    @Synchronized
    fun tick(): List<PttRemoteOutcome.Active> {
        val now = clock()
        pruneMaps(now)
        val out = ArrayList<PttRemoteOutcome.Active>()
        for (rx in rxStates.values.toList()) {
            val frames = rx.assembly.tick()
            val done = rx.assembly.state != BurstAssembly.State.OPEN
            if (frames.isNotEmpty() || done) {
                out += settleIfDone(rx, frames, startedNew = false, preempted = false, now = now)
            }
        }
        return out
    }

    /**
     * Returns true when `fileName` (`voice_<16-hex>.m4a`) is the trailing note of a burst we already
     * played live out loud, so `MeshService.onFile` can drop the duplicate chat bubble.
     */
    @Synchronized
    fun shouldAbsorbVoiceNote(conversation: String, senderHex: String, fileName: String?): Boolean {
        val hex = extractBurstHex(fileName) ?: return false
        val now = clock()
        pruneMaps(now)
        val active = rxStates[conversation]
        if (active != null && active.senderKey.equals(senderHex, ignoreCase = true) && active.burstHex.equals(hex, ignoreCase = true)) {
            if (!active.playedLive || active.absorbedNote) return false
            active.absorbedNote = true
            return true
        }
        val k = burstKey(conversation, senderHex, hex)
        return heardLiveBursts.remove(k) != null
    }

    @Synchronized
    fun wipe() {
        txStates.clear()
        rxStates.clear()
        settledBursts.clear()
        heardLiveBursts.clear()
    }

    private fun openAndFeed(
        conversation: String,
        senderHex: String,
        senderName: String,
        hops: Int?,
        packet: VoiceBurst,
        rawSize: Int,
        playLive: Boolean,
        now: Long,
        preempted: Boolean,
    ): PttRemoteOutcome {
        val rx = ActiveRx(
            conversation = conversation,
            senderKey = senderHex,
            senderName = senderName,
            burstHex = packet.burstHex,
            hops = hops,
            startedAt = now,
            assembly = BurstAssembly(clock),
            playedLive = playLive,
        )
        rxStates[conversation] = rx
        val frames = rx.assembly.accept(packet, rawSize)
        return settleIfDone(rx, frames, startedNew = true, preempted = preempted, now = now)
    }

    private fun settleIfDone(
        rx: ActiveRx,
        frames: List<ByteArray>,
        startedNew: Boolean,
        preempted: Boolean,
        now: Long,
    ): PttRemoteOutcome.Active {
        if (rx.assembly.state == BurstAssembly.State.OPEN) {
            return PttRemoteOutcome.Active(
                conversation = rx.conversation,
                senderKey = rx.senderKey,
                senderName = rx.senderName,
                burstHex = rx.burstHex,
                hops = rx.hops,
                frames = frames,
                startedNew = startedNew,
                preemptedLocalTx = preempted,
            )
        }
        val canceled = rx.assembly.state == BurstAssembly.State.CANCELED
        val clean = !canceled && rx.assembly.delivered > 0
        finalizeRx(rx, now)
        return PttRemoteOutcome.Active(
            conversation = rx.conversation,
            senderKey = rx.senderKey,
            senderName = rx.senderName,
            burstHex = rx.burstHex,
            hops = rx.hops,
            frames = frames,
            startedNew = startedNew,
            preemptedLocalTx = preempted,
            finishedCleanly = clean,
            canceled = canceled,
        )
    }

    private fun finalizeRx(rx: ActiveRx, now: Long) {
        rxStates.remove(rx.conversation)
        val k = burstKey(rx.conversation, rx.senderKey, rx.burstHex)
        settledBursts[k] = now
        if (rx.assembly.state == BurstAssembly.State.FINISHED && rx.playedLive && rx.assembly.delivered > 0 && !rx.absorbedNote) {
            heardLiveBursts[k] = now
        }
    }

    private fun pruneMaps(now: Long) {
        val cutoff = now - HEARD_TTL_MS
        val sIt = settledBursts.entries.iterator()
        while (sIt.hasNext()) {
            if (sIt.next().value < cutoff || settledBursts.size > MAX_REMEMBERED) sIt.remove() else break
        }
        val hIt = heardLiveBursts.entries.iterator()
        while (hIt.hasNext()) {
            if (hIt.next().value < cutoff || heardLiveBursts.size > MAX_REMEMBERED) hIt.remove() else break
        }
    }

    private fun burstKey(conv: String, sender: String, burstHex: String) =
        "$conv|${sender.lowercase()}|${burstHex.lowercase()}"

    private fun extractBurstHex(fileName: String?): String? {
        if (fileName == null || !fileName.startsWith("voice_", ignoreCase = true)) return null
        val hex = fileName.substring(6).take(16).lowercase()
        return hex.takeIf { it.length == 16 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
    }

    companion object {
        const val MIN_HOLD_MS = 300L
        const val MAX_HOLD_MS = 60_000L
        private const val HEARD_TTL_MS = 10 * 60_000L
        private const val MAX_REMEMBERED = 64
    }
}
