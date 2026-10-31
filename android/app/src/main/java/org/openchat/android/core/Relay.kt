package org.openchat.android.core

import kotlin.random.Random

/** LRU seen-set: 1000 keys, 5 minutes (docs/protocol-spec.md §7). */
class Deduplicator(
    private val maxEntries: Int = 1000,
    private val maxAgeMs: Long = 300_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val seen = LinkedHashMap<String, Long>(maxEntries, 0.75f, true)

    /** Returns true the first time a key is seen, false for duplicates. */
    @Synchronized
    fun markIfNew(key: String): Boolean {
        val now = clock()
        val at = seen[key]
        if (at != null && now - at <= maxAgeMs) return false
        seen[key] = now
        while (seen.size > maxEntries) seen.remove(seen.keys.first())
        return true
    }

    @Synchronized
    fun clear() = seen.clear()

    companion object {
        fun key(p: Packet): String =
            p.senderId.toHex() + "-" + p.timestamp + "-" + p.type + "-" + sha256(p.payload).copyOf(4).toHex()
    }
}

data class RelayDecision(val relay: Boolean, val newTtl: Int, val delayMs: Long)

/** Relay TTL and delay rules, §7 table. */
object RelayPolicy {
    const val HIGH_DEGREE = 6

    fun decide(p: Packet, senderIsSelf: Boolean, recipientIsSelf: Boolean, degree: Int, random: Random = Random): RelayDecision {
        val t = minOf(p.ttl, Packet.DEFAULT_TTL)
        val no = RelayDecision(false, t, 0)
        if (p.type == MessageType.REQUEST_SYNC) return no
        if (t <= 1 || senderIsSelf || recipientIsSelf) return no

        val directed = !p.isBroadcast
        if (p.type == MessageType.NOISE_HANDSHAKE) return RelayDecision(true, t - 1, random.nextLong(10, 36))
        if (directed && (p.type == MessageType.FRAGMENT || p.type == MessageType.NOISE_ENCRYPTED)) {
            return RelayDecision(true, t - 1, random.nextLong(20, 61))
        }
        if (p.type == MessageType.FRAGMENT || p.type == MessageType.VOICE_FRAME) {
            val cap = minOf(t, if (degree >= HIGH_DEGREE) 5 else 7)
            if (cap <= 1) return RelayDecision(false, cap, 0)
            return RelayDecision(true, cap - 1, random.nextLong(8, 26))
        }
        val limit = when {
            degree >= HIGH_DEGREE -> maxOf(2, minOf(t, 5))
            degree <= 2 -> t
            else -> maxOf(2, minOf(t, if (p.type == MessageType.ANNOUNCE) 7 else 6))
        }
        val delay = when (degree) {
            in 0..2 -> random.nextLong(10, 41)
            in 3..5 -> random.nextLong(60, 151)
            in 6..9 -> random.nextLong(80, 181)
            else -> random.nextLong(100, 221)
        }
        return RelayDecision(true, limit - 1, delay)
    }

    /** Broadcast fanout subset: all links for announces and fragments, about log2(degree) otherwise. */
    fun fanout(p: Packet, links: List<String>, random: Random = Random): List<String> {
        if (!p.isBroadcast || p.type == MessageType.ANNOUNCE || p.type == MessageType.FRAGMENT || links.size <= 2) return links
        val k = maxOf(2, (Math.log(links.size.toDouble()) / Math.log(2.0)).toInt() + 1)
        val seed = Deduplicator.key(p).hashCode().toLong()
        return links.sorted().shuffled(Random(seed)).take(minOf(k, links.size))
    }
}

/**
 * Cuts a link's byte stream into whole frames (docs/protocol-spec.md §1).
 * Not thread-safe; use one per link direction.
 */
class StreamFramer(
    private val capBytes: Int = 8 * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var buf = ByteArray(0)
    private var lastChunkAt = 0L

    fun reset() { buf = ByteArray(0) }

    /** Bytes held back waiting for the rest of a frame; for diagnostics and tests. */
    val pendingBytes get() = buf.size

    fun append(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        val now = clock()
        // A partial frame whose next piece never came was lost on the radio. Keeping it would
        // glue the next frame's bytes onto a dead one and lose that frame too (§1 rule 4).
        if (buf.isNotEmpty() && now - lastChunkAt > STALL_MS) reset()
        lastChunkAt = now
        buf += chunk
        if (buf.size > capBytes) { reset(); return emptyList() }
        val frames = ArrayList<ByteArray>()
        var start = 0
        while (buf.size - start >= PacketCodec.V1_HEADER + PacketCodec.ID_SIZE) {
            val first = buf[start].toInt() and 0xFF
            if (first != 1 && first != 2) {
                // Padding left over from a Noise frame, or garbage after a loss: skip it and keep looking.
                start += paddingAt(start).takeIf { it > 0 } ?: 1
                continue
            }
            val len = PacketCodec.frameLength(buf, start)
            if (len == null) {
                // Not a header after all (a stray 0x01/0x02 inside lost traffic): resync one byte on.
                start += 1
                continue
            }
            if (buf.size - start < len) break
            // Noise frames are followed by their PKCS#7 padding; its length is fully
            // determined by the frame length, so consume it here instead of guessing later.
            val type = buf[start + 1].toInt() and 0xFF
            val pad = if (MessageType.padsOnWire(type)) Padding.targetSize(len) - len else 0
            val consumed = if (pad in 1..255) {
                if (buf.size - start < len + pad) break
                if ((start + len until start + len + pad).all { (buf[it].toInt() and 0xFF) == pad }) len + pad else len
            } else len
            val frame = buf.copyOfRange(start, start + len)
            // A header that passed the length check can still be a stray byte pair inside lost
            // traffic; only a frame that decodes is one. Otherwise resync one byte on.
            if (PacketCodec.decode(frame) == null) { start += 1; continue }
            frames.add(frame)
            start += consumed
        }
        if (start > 0) buf = buf.copyOfRange(start, buf.size)
        return frames
    }

    /** Length of a PKCS#7 padding run starting at [at], or 0 if the bytes there are not one. */
    private fun paddingAt(at: Int): Int {
        val n = buf[at].toInt() and 0xFF
        if (n == 0 || n > buf.size - at) return 0
        for (i in at until at + n) if (buf[i] != buf[at]) return 0
        return n
    }

    companion object {
        /** A frame whose pieces stop arriving for this long is abandoned (§1). */
        const val STALL_MS = 250L
    }
}
