package org.openchat.android.core

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** PKCS#7-style padding toward 256/512/1024/2048 buckets (docs/protocol-spec.md §2.4). */
object Padding {
    private val buckets = intArrayOf(256, 512, 1024, 2048)

    fun targetSize(length: Int): Int {
        val withTag = length + 16
        for (b in buckets) if (withTag <= b) return b
        return length
    }

    fun pad(data: ByteArray): ByteArray {
        val target = targetSize(data.size)
        val n = target - data.size
        if (n <= 0 || n > 255) return data
        return data + ByteArray(n) { n.toByte() }
    }

    /** Returns the unpadded bytes, or the input itself when the tail is not valid padding. */
    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val n = data.last().toInt() and 0xFF
        if (n == 0 || n > data.size) return data
        for (i in data.size - n until data.size) if (data[i] != data.last()) return data
        return data.copyOfRange(0, data.size - n)
    }
}

/** Raw DEFLATE without a zlib header, level 5, as Apple's COMPRESSION_ZLIB produces (§2.5). */
object Compression {
    const val THRESHOLD = 100
    const val MAX_RATIO = 1032

    fun shouldCompress(data: ByteArray): Boolean {
        if (data.size < THRESHOLD) return false
        val unique = data.toSet().size
        val sample = minOf(data.size, 256)
        return unique.toDouble() / sample < 0.9
    }

    fun compress(data: ByteArray): ByteArray? {
        if (data.size < THRESHOLD) return null
        val deflater = Deflater(5, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size)
            val buf = ByteArray(4096)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            val result = out.toByteArray()
            return if (result.isNotEmpty() && result.size < data.size) result else null
        } finally {
            deflater.end()
        }
    }

    fun decompress(data: ByteArray, originalSize: Int): ByteArray? {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArray(originalSize)
            var total = 0
            while (total < originalSize && !inflater.finished()) {
                val n = inflater.inflate(out, total, originalSize - total)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                total += n
            }
            return if (total == originalSize) out else null
        } catch (e: Exception) {
            return null
        } finally {
            inflater.end()
        }
    }
}

object Flags {
    const val HAS_RECIPIENT = 0x01
    const val HAS_SIGNATURE = 0x02
    const val IS_COMPRESSED = 0x04
    const val HAS_ROUTE = 0x08
    const val IS_RSR = 0x10
}

/** One mesh packet (§2). IDs are 8 bytes. A null recipient means broadcast. */
data class Packet(
    val version: Int = 1,
    val type: Int,
    val senderId: ByteArray,
    val recipientId: ByteArray? = null,
    val timestamp: Long,
    val payload: ByteArray,
    val signature: ByteArray? = null,
    val ttl: Int = DEFAULT_TTL,
    val route: List<ByteArray>? = null,
    val isRsr: Boolean = false,
) {
    val isBroadcast get() = recipientId == null || recipientId.all { it == 0xFF.toByte() }

    override fun equals(other: Any?): Boolean {
        if (other !is Packet) return false
        fun same(a: ByteArray?, b: ByteArray?) = if (a == null || b == null) a == null && b == null else a.contentEquals(b)
        val ra = route.orEmpty(); val rb = other.route.orEmpty()
        return version == other.version && type == other.type && ttl == other.ttl &&
            timestamp == other.timestamp && isRsr == other.isRsr &&
            senderId.contentEquals(other.senderId) && payload.contentEquals(other.payload) &&
            same(recipientId, other.recipientId) && same(signature, other.signature) &&
            ra.size == rb.size && ra.zip(rb).all { (a, b) -> a.contentEquals(b) }
    }

    override fun hashCode(): Int = 31 * (31 * type + timestamp.hashCode()) + senderId.contentHashCode()

    companion object {
        const val DEFAULT_TTL = 7
    }
}

object PacketCodec {
    const val V1_HEADER = 14
    const val V2_HEADER = 16
    const val ID_SIZE = 8
    const val SIGNATURE_SIZE = 64

    fun headerSize(version: Int) = when (version) { 1 -> V1_HEADER; 2 -> V2_HEADER; else -> -1 }

    /** Wire encoding. Pads only when [pad] is true; callers pass [MessageType.padsOnWire]. */
    fun encode(p: Packet, pad: Boolean = MessageType.padsOnWire(p.type)): ByteArray? {
        if (p.version != 1 && p.version != 2) return null
        val lengthBytes = if (p.version == 2) 4 else 2
        val maxLen = if (p.version == 2) 0xFFFFFFFFL else 0xFFFFL

        var payload = p.payload
        var originalSize = -1
        if (Compression.shouldCompress(payload) && payload.size <= maxLen) {
            Compression.compress(payload)?.let { originalSize = payload.size; payload = it }
        }
        val compressed = originalSize >= 0
        val route = if (p.version >= 2) p.route.orEmpty() else emptyList()
        if (route.size > 255 || route.any { it.isEmpty() }) return null
        val payloadDataSize = payload.size + if (compressed) lengthBytes else 0
        if (payloadDataSize > maxLen) return null

        var flags = 0
        if (p.recipientId != null) flags = flags or Flags.HAS_RECIPIENT
        if (p.signature != null) flags = flags or Flags.HAS_SIGNATURE
        if (compressed) flags = flags or Flags.IS_COMPRESSED
        if (route.isNotEmpty()) flags = flags or Flags.HAS_ROUTE
        if (p.isRsr) flags = flags or Flags.IS_RSR

        val w = ByteWriter(payload.size + 128)
        w.u8(p.version).u8(p.type).u8(p.ttl).u64(p.timestamp).u8(flags)
        if (p.version == 2) w.u32(payloadDataSize.toLong()) else w.u16(payloadDataSize)
        w.bytes(fixId(p.senderId))
        p.recipientId?.let { w.bytes(fixId(it)) }
        if (route.isNotEmpty()) {
            w.u8(route.size)
            route.forEach { w.bytes(fixId(it)) }
        }
        if (compressed) { if (p.version == 2) w.u32(originalSize.toLong()) else w.u16(originalSize) }
        w.bytes(payload)
        p.signature?.let { w.bytes(it.copyOf(SIGNATURE_SIZE)) }
        val raw = w.toByteArray()
        return if (pad) Padding.pad(raw) else raw
    }

    /**
     * Bytes covered by the Ed25519 signature (§2.6): TTL 0, no RSR, no signature,
     * and padded as if the type were a padded type.
     */
    fun signingBytes(p: Packet): ByteArray? =
        encode(p.copy(ttl = 0, isRsr = false, signature = null), pad = true)

    fun decode(data: ByteArray): Packet? = decodeCore(data) ?: run {
        val unpadded = Padding.unpad(data)
        if (unpadded === data) null else decodeCore(unpadded)
    }

    /** Total length of the frame at the start of [data], or null when the header is incomplete or invalid. */
    fun frameLength(data: ByteArray, offset: Int = 0): Int? {
        val r = ByteReader(data, offset)
        val version = r.u8() ?: return null
        val header = headerSize(version)
        if (header < 0) return null
        r.u8() ?: return null
        r.u8() ?: return null
        r.u64() ?: return null
        val flags = r.u8() ?: return null
        val payloadLength = (if (version == 2) r.u32() else r.u16()?.toLong()) ?: return null
        if (payloadLength > MessageType.MAX_FRAMED_FILE_BYTES) return null
        var total = header + ID_SIZE
        if (flags and Flags.HAS_RECIPIENT != 0) total += ID_SIZE
        if (version >= 2 && flags and Flags.HAS_ROUTE != 0) {
            val routeCountOffset = offset + total
            if (routeCountOffset >= data.size) return null
            total += 1 + (data[routeCountOffset].toInt() and 0xFF) * ID_SIZE
        }
        total += payloadLength.toInt()
        if (flags and Flags.HAS_SIGNATURE != 0) total += SIGNATURE_SIZE
        return total
    }

    private fun decodeCore(raw: ByteArray): Packet? {
        if (raw.size < V1_HEADER + ID_SIZE) return null
        val r = ByteReader(raw)
        val version = r.u8() ?: return null
        if (version != 1 && version != 2) return null
        if (raw.size < headerSize(version) + ID_SIZE) return null
        val lengthBytes = if (version == 2) 4 else 2
        val type = r.u8() ?: return null
        val ttl = r.u8() ?: return null
        val timestamp = r.u64() ?: return null
        val flags = r.u8() ?: return null
        val payloadLength = (if (version == 2) r.u32() else r.u16()?.toLong()) ?: return null
        if (payloadLength > MessageType.MAX_FRAMED_FILE_BYTES) return null

        val sender = r.bytes(ID_SIZE) ?: return null
        val recipient = if (flags and Flags.HAS_RECIPIENT != 0) r.bytes(ID_SIZE) ?: return null else null
        var route: List<ByteArray>? = null
        if (version >= 2 && flags and Flags.HAS_ROUTE != 0) {
            val count = r.u8() ?: return null
            if (count > 0) route = List(count) { r.bytes(ID_SIZE) ?: return null }
        }

        val cap = MessageType.maxPayloadBytes(type)
        val payload: ByteArray
        if (flags and Flags.IS_COMPRESSED != 0) {
            if (payloadLength < lengthBytes) return null
            val original = (if (version == 2) r.u32() else r.u16()?.toLong()) ?: return null
            if (original > cap) return null
            val compressedSize = (payloadLength - lengthBytes).toInt()
            if (compressedSize <= 0) return null
            val compressed = r.bytes(compressedSize) ?: return null
            if (original > compressedSize.toLong() * Compression.MAX_RATIO) return null
            payload = Compression.decompress(compressed, original.toInt()) ?: return null
        } else {
            if (payloadLength > cap) return null
            payload = r.bytes(payloadLength.toInt()) ?: return null
        }
        val signature = if (flags and Flags.HAS_SIGNATURE != 0) r.bytes(SIGNATURE_SIZE) ?: return null else null

        return Packet(
            version = version, type = type, senderId = sender, recipientId = recipient,
            timestamp = timestamp, payload = payload, signature = signature, ttl = ttl,
            route = route, isRsr = flags and Flags.IS_RSR != 0,
        )
    }

    private fun fixId(id: ByteArray): ByteArray = if (id.size == ID_SIZE) id else id.copyOf(ID_SIZE)
}
