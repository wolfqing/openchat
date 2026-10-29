package org.openchat.android.core

import kotlin.math.ceil
import kotlin.math.ln

/** Packet ID for history sync (docs/protocol-spec.md §14.1). */
object PacketId {
    fun compute(p: Packet): ByteArray =
        sha256(byteArrayOf(p.type.toByte()), p.senderId, ByteWriter(8).u64(p.timestamp).toByteArray(), p.payload).copyOf(16)
}

/** Golomb-coded set over packet IDs (§14.2). Buckets are in [1, M). */
object GcsFilter {
    const val MAX_P = 32

    class Params(val p: Int, val m: Long, val data: ByteArray, val includedCount: Int)

    fun deriveP(targetFpr: Double): Int {
        val f = targetFpr.coerceIn(0.000001, 0.25)
        return maxOf(1, ceil(ln(1.0 / f) / ln(2.0)).toInt())
    }

    /** Roughly P + 2 bits per element. */
    fun estimateMaxElements(sizeBytes: Int, p: Int): Int = maxOf(1, maxOf(8, sizeBytes * 8) / maxOf(3, p + 2))

    /**
     * Encodes a newest-first prefix of [ids] that fits in [maxBytes]; M is fixed from the
     * initial candidate count so trimming the tail never changes the mapping.
     */
    fun build(ids: List<ByteArray>, maxBytes: Int, targetFpr: Double): Params {
        val p = deriveP(targetFpr)
        if (ids.isEmpty()) return Params(p, 1, ByteArray(0), 0)
        val cap = estimateMaxElements(maxBytes, p)
        val m = hashRange(minOf(ids.size, cap), p)
        fun encodeFirst(n: Int): ByteArray {
            val mapped = ids.take(n).map { bucket(it, m) }.sorted()
            val unique = ArrayList<Long>(mapped.size)
            var last = 0L
            for (v in mapped) { val c = minOf(v, m - 1); if (c > last) { unique.add(c); last = c } }
            return if (unique.isEmpty()) ByteArray(0) else encode(unique, p)
        }
        var count = minOf(ids.size, cap)
        var encoded = encodeFirst(count)
        while (encoded.size > maxBytes && count > 1) { count = maxOf(1, count * 9 / 10); encoded = encodeFirst(count) }
        if (encoded.size > maxBytes) return Params(p, m, ByteArray(0), 0)
        return Params(p, m, encoded, if (encoded.isEmpty()) 0 else count)
    }

    /** Sorted buckets, or empty for parameters out of range (treated as "peer has nothing"). */
    fun decode(p: Int, m: Long, data: ByteArray): LongArray {
        if (p < 1 || p > MAX_P || m <= 1) return LongArray(0)
        val out = ArrayList<Long>()
        val r = BitReader(data)
        var acc = 0L
        while (true) {
            val q = r.readUnary() ?: break
            val rem = r.readBits(p) ?: break
            acc += (q.toLong() shl p) + rem + 1
            if (acc >= m) break
            out.add(acc)
        }
        return out.toLongArray()
    }

    fun contains(sorted: LongArray, candidate: Long): Boolean = sorted.binarySearch(candidate) >= 0

    fun bucket(id: ByteArray, m: Long): Long {
        if (m <= 1) return 0
        val d = sha256(id)
        var h = 0L
        for (i in 0 until 8) h = (h shl 8) or (d[i].toLong() and 0xFF)
        h = h and 0x7FFF_FFFF_FFFF_FFFFL
        val v = h % m
        return if (v == 0L) 1 else v
    }

    private fun hashRange(count: Int, p: Int): Long {
        if (count <= 0) return 1
        val product = count.toLong() shl p
        return if (p >= 32 || product > 0xFFFF_FFFFL || product <= 0) 0xFFFF_FFFFL else product
    }

    private fun encode(sorted: List<Long>, p: Int): ByteArray {
        val w = BitWriter()
        var prev = 0L
        val mask = (1L shl p) - 1
        for (v in sorted) {
            val x = v - prev; prev = v
            val q = (x - 1) ushr p
            repeat(q.toInt()) { w.bit(1) }
            w.bit(0)
            w.bits((x - 1) and mask, p)
        }
        return w.toByteArray()
    }

    private class BitWriter {
        private val out = ByteWriter()
        private var cur = 0
        private var n = 0
        fun bit(b: Int) { cur = (cur shl 1) or (b and 1); if (++n == 8) { out.u8(cur); cur = 0; n = 0 } }
        fun bits(v: Long, count: Int) { for (i in count - 1 downTo 0) bit(((v ushr i) and 1).toInt()) }
        fun toByteArray(): ByteArray { if (n > 0) { out.u8(cur shl (8 - n)); cur = 0; n = 0 }; return out.toByteArray() }
    }

    private class BitReader(private val data: ByteArray) {
        private var idx = 0
        private var left = 0
        fun readBit(): Int? {
            if (idx >= data.size) return null
            if (left == 0) left = 8
            left--
            val b = (data[idx].toInt() ushr left) and 1
            if (left == 0) idx++
            return b
        }
        fun readUnary(): Int? { var q = 0; while (true) { val b = readBit() ?: return null; if (b == 1) q++ else return q } }
        fun readBits(count: Int): Long? { var v = 0L; repeat(count) { v = (v shl 1) or (readBit() ?: return null).toLong() }; return v }
    }
}

/** Type flags of a sync request (§14.3): little-endian bit set, 1–8 bytes on the wire. */
object SyncTypes {
    const val ANNOUNCE = 1L
    const val MESSAGE = 2L
    const val PUBLIC = ANNOUNCE or MESSAGE

    fun encode(flags: Long): ByteArray? {
        if (flags == 0L) return null
        val w = ByteWriter(8)
        var v = flags
        while (v != 0L) { w.u8((v and 0xFF).toInt()); v = v ushr 8 }
        return w.toByteArray()
    }

    fun decode(data: ByteArray): Long? {
        if (data.isEmpty() || data.size > 8) return null
        var v = 0L
        data.forEachIndexed { i, b -> v = v or ((b.toLong() and 0xFF) shl (8 * i)) }
        return v
    }
}

/** `requestSync` payload (§14.3). */
class SyncRequest(
    val p: Int,
    val m: Long,
    val data: ByteArray,
    val types: Long = SyncTypes.PUBLIC,
    val since: Long? = null,
) {
    fun wants(flag: Long) = types and flag != 0L

    fun encode(): ByteArray {
        val w = ByteWriter(16 + data.size)
        w.u8(T_P).u16(1).u8(p)
        w.u8(T_M).u16(4).u32(m)
        w.u8(T_DATA).u16(data.size).bytes(data)
        SyncTypes.encode(types)?.let { w.u8(T_TYPES).u16(it.size).bytes(it) }
        since?.let { w.u8(T_SINCE).u16(8).u64(it) }
        return w.toByteArray()
    }

    companion object {
        const val MAX_DATA = 1024
        private const val T_P = 0x01
        private const val T_M = 0x02
        private const val T_DATA = 0x03
        private const val T_TYPES = 0x04
        private const val T_SINCE = 0x05

        fun decode(bytes: ByteArray): SyncRequest? {
            var p: Int? = null; var m: Long? = null; var data: ByteArray? = null
            var types: Long? = null; var since: Long? = null
            val r = ByteReader(bytes)
            while (r.remaining >= 3) {
                val t = r.u8() ?: return null
                val len = r.u16() ?: return null
                val v = r.bytes(len) ?: return null
                when (t) {
                    T_P -> if (len == 1) p = v[0].toInt() and 0xFF
                    T_M -> if (len == 4) m = ByteReader(v).u32()
                    T_DATA -> { if (len > MAX_DATA) return null; data = v }
                    T_TYPES -> SyncTypes.decode(v)?.let { types = it }
                    T_SINCE -> if (len == 8) since = ByteReader(v).u64()
                    else -> Unit // unknown, including 0x06 fragment filters
                }
            }
            val pp = p ?: return null; val mm = m ?: return null; val dd = data ?: return null
            if (pp < 1 || pp > GcsFilter.MAX_P || mm <= 0) return null
            return SyncRequest(pp, mm, dd, types ?: SyncTypes.PUBLIC, since)
        }
    }
}
