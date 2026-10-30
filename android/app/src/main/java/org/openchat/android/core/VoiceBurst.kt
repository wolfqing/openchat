package org.openchat.android.core

/**
 * Live push-to-talk (docs/protocol-spec.md §29): one packet of a voice burst, carried as a
 * Noise payload in private chats and as a signed broadcast in Nearby.
 * `burst ID (8) ‖ seq (2, BE) ‖ flags (1) ‖ payload`; seq 0 is START, data from 1.
 */
class VoiceBurst(val burstId: ByteArray, val seq: Int, val kind: Kind) {
    sealed class Kind {
        data class Start(val codec: Int) : Kind()
        class Frames(val frames: List<ByteArray>) : Kind()
        data class End(val dataPackets: Int, val durationMs: Long) : Kind()
        object Canceled : Kind()
    }

    val burstHex get() = burstId.toHex()

    fun encode(): ByteArray {
        val w = ByteWriter(16).bytes(burstId).u16(seq and 0xFFFF)
        when (kind) {
            is Kind.Start -> w.u8(START).u8(kind.codec)
            is Kind.Frames -> { w.u8(0); kind.frames.forEach { w.u16(it.size).bytes(it) } }
            is Kind.End -> w.u8(END).u16(kind.dataPackets).u32(kind.durationMs)
            Kind.Canceled -> w.u8(CANCELED)
        }
        return w.toByteArray()
    }

    companion object {
        const val ID_LENGTH = 8
        const val MAX_FRAMES = 8
        const val CODEC_AAC_LC_16K_MONO = 0x01
        private const val START = 0x01
        private const val END = 0x02
        private const val CANCELED = 0x04

        fun decode(b: ByteArray): VoiceBurst? {
            if (b.size < ID_LENGTH + 3) return null
            val r = ByteReader(b)
            val id = r.bytes(ID_LENGTH)!!
            val seq = r.u16()!!
            val kind = when (r.u8()!!) {
                START -> Kind.Start(r.u8()?.takeIf { it == CODEC_AAC_LC_16K_MONO } ?: return null)
                END -> Kind.End(r.u16() ?: return null, r.u32() ?: return null)
                CANCELED -> Kind.Canceled
                0 -> {
                    val frames = ArrayList<ByteArray>()
                    while (r.remaining > 0) {
                        val n = r.u16() ?: return null
                        if (n == 0 || frames.size >= MAX_FRAMES) return null
                        frames.add(r.bytes(n) ?: return null)
                    }
                    if (frames.isEmpty()) return null
                    Kind.Frames(frames)
                }
                else -> return null
            }
            return VoiceBurst(id, seq, kind)
        }
    }
}

/** Batches encoded frames into packets that fit one radio write after Noise and padding (210 bytes). */
class VoicePacketizer(val burstId: ByteArray, private val budget: Int = 210) {
    private val pending = ArrayList<ByteArray>()
    private var pendingSize = 0
    var nextSeq = 1
        private set
    var dataPackets = 0
        private set

    fun add(frame: ByteArray): List<ByteArray> {
        val cost = 2 + frame.size
        if (VoiceBurst.ID_LENGTH + 3 + cost > budget) return emptyList()
        val out = ArrayList<ByteArray>()
        if (pending.isNotEmpty() && (VoiceBurst.ID_LENGTH + 3 + pendingSize + cost > budget || pending.size >= VoiceBurst.MAX_FRAMES)) out += flush()
        pending.add(frame); pendingSize += cost
        return out
    }

    fun flush(): List<ByteArray> {
        if (pending.isEmpty()) return emptyList()
        val p = VoiceBurst(burstId, nextSeq, VoiceBurst.Kind.Frames(pending.toList())).encode()
        pending.clear(); pendingSize = 0
        nextSeq = (nextSeq + 1) and 0xFFFF; dataPackets++
        return listOf(p)
    }

    fun control(kind: VoiceBurst.Kind) = VoiceBurst(burstId, nextSeq, kind).encode()
}

/** ADTS headers make a raw AAC-LC 16 kHz mono stream playable at any prefix, so a partial burst is still a note. */
object Adts {
    fun frame(aac: ByteArray): ByteArray {
        val len = aac.size + 7
        val h = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((1 shl 6) or (8 shl 2)).toByte(), // AAC-LC, 16 kHz, channel config high bit 0
            ((1 shl 6) or ((len shr 11) and 0x3)).toByte(), // mono
            ((len shr 3) and 0xFF).toByte(),
            (((len and 0x7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
        return h + aac
    }

    /** AudioSpecificConfig for the same stream, for a decoder. */
    val CONFIG = byteArrayOf(0x14, 0x08)
}

/**
 * Puts one inbound burst back in order, as bitchat does: packets wait behind a missing one
 * for half a second, then the hole is skipped; a burst ends when every data packet up to
 * END is in, after 3 s of silence, or when it floods (over 6 KB/s or 384 KiB).
 */
class BurstAssembly(private val clock: () -> Long) {
    enum class State { OPEN, FINISHED, CANCELED }

    private val buffered = HashMap<Int, List<ByteArray>>()
    private var nextSeq = 1
    private var gapSince = 0L
    private var bytes = 0
    private val startedAt = clock()
    private var end: VoiceBurst.Kind.End? = null
    var lastPacketAt = startedAt
        private set
    var delivered = 0
        private set
    var state = State.OPEN
        private set

    /** Takes a packet; returns frames now playable in order. */
    fun accept(p: VoiceBurst, size: Int): List<ByteArray> {
        if (state != State.OPEN) return emptyList()
        val now = clock()
        bytes += size
        lastPacketAt = now
        if (bytes > MAX_BYTES || bytes > MAX_BYTES_PER_S * ((now - startedAt) / 1000 + 2)) return finish()
        when (val k = p.kind) {
            is VoiceBurst.Kind.Start -> Unit
            is VoiceBurst.Kind.Frames -> if (seqAhead(p.seq) && p.seq !in buffered) buffered[p.seq] = k.frames
            is VoiceBurst.Kind.End -> end = k
            VoiceBurst.Kind.Canceled -> { state = State.CANCELED; buffered.clear(); return emptyList() }
        }
        val out = drain(now)
        val e = end
        if (e != null && buffered.isEmpty() && (nextSeq - 1 and 0xFFFF) >= e.dataPackets) return out + finish()
        return out
    }

    /** Called on a timer: skips a stale hole, or ends a burst that went quiet. */
    fun tick(): List<ByteArray> {
        if (state != State.OPEN) return emptyList()
        val now = clock()
        if (now - lastPacketAt >= IDLE_MS) return finish()
        val out = drain(now)
        val e = end
        if (e != null && buffered.isEmpty() && (nextSeq - 1 and 0xFFFF) >= e.dataPackets) return out + finish()
        return out
    }

    /** Ends the burst, releasing whatever is buffered past any holes. */
    fun finish(): List<ByteArray> {
        if (state != State.OPEN) return emptyList()
        val out = ArrayList<ByteArray>()
        buffered.keys.sorted().forEach { out += buffered[it]!! }
        buffered.clear()
        delivered += out.size
        state = State.FINISHED
        return out
    }

    private fun seqAhead(seq: Int) = ((seq - nextSeq) and 0xFFFF) < 0x8000

    private fun drain(now: Long): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            val f = buffered.remove(nextSeq)
            if (f != null) { out += f; nextSeq = (nextSeq + 1) and 0xFFFF; gapSince = 0; continue }
            if (buffered.isEmpty()) { gapSince = 0; break }
            if (gapSince == 0L) { gapSince = now; break }
            if (now - gapSince < GAP_SKIP_MS) break
            nextSeq = buffered.keys.minByOrNull { (it - nextSeq) and 0xFFFF }!!
            gapSince = 0
        }
        delivered += out.size
        return out
    }

    companion object {
        const val GAP_SKIP_MS = 500L
        const val IDLE_MS = 3_000L
        const val MAX_BYTES = 384 * 1024
        const val MAX_BYTES_PER_S = 6_000
        const val MAX_CONCURRENT = 8
    }
}
