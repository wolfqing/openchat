package org.openchat.android.core

/** Announce payload TLVs (docs/protocol-spec.md §4.2). */
data class Announcement(
    val nickname: String,
    val noisePublicKey: ByteArray,
    val signingPublicKey: ByteArray,
    val directNeighbors: List<ByteArray> = emptyList(),
    val capabilities: Long? = null,
    val bridgeGeohash: String? = null,
) {
    fun encode(): ByteArray? {
        val nick = nickname.toByteArray(Charsets.UTF_8)
        if (nick.size > 255 || noisePublicKey.size > 255 || signingPublicKey.size > 255) return null
        val w = ByteWriter()
        w.u8(T_NICKNAME).u8(nick.size).bytes(nick)
        w.u8(T_NOISE_KEY).u8(noisePublicKey.size).bytes(noisePublicKey)
        w.u8(T_SIGNING_KEY).u8(signingPublicKey.size).bytes(signingPublicKey)
        val neighbors = directNeighbors.take(10).filter { it.size == 8 }
        if (neighbors.isNotEmpty()) {
            w.u8(T_NEIGHBORS).u8(neighbors.size * 8)
            neighbors.forEach { w.bytes(it) }
        }
        capabilities?.let {
            val caps = encodeCapabilities(it)
            w.u8(T_CAPABILITIES).u8(caps.size).bytes(caps)
        }
        bridgeGeohash?.toByteArray(Charsets.UTF_8)?.takeIf { it.isNotEmpty() && it.size <= 12 }?.let {
            w.u8(T_BRIDGE_GEOHASH).u8(it.size).bytes(it)
        }
        return w.toByteArray()
    }

    companion object {
        private const val T_NICKNAME = 0x01
        private const val T_NOISE_KEY = 0x02
        private const val T_SIGNING_KEY = 0x03
        private const val T_NEIGHBORS = 0x04
        private const val T_CAPABILITIES = 0x05
        private const val T_BRIDGE_GEOHASH = 0x06

        fun decode(data: ByteArray): Announcement? {
            var nickname: String? = null
            var noise: ByteArray? = null
            var signing: ByteArray? = null
            var neighbors = emptyList<ByteArray>()
            var caps: Long? = null
            var bridge: String? = null
            val r = ByteReader(data)
            while (r.remaining >= 2) {
                val t = r.u8()!!
                val len = r.u8()!!
                val v = r.bytes(len) ?: return null
                when (t) {
                    T_NICKNAME -> nickname = String(v, Charsets.UTF_8)
                    T_NOISE_KEY -> noise = v
                    T_SIGNING_KEY -> signing = v
                    T_NEIGHBORS -> if (len > 0 && len % 8 == 0) neighbors = List(len / 8) { v.copyOfRange(it * 8, it * 8 + 8) }
                    T_CAPABILITIES -> caps = decodeCapabilities(v)
                    T_BRIDGE_GEOHASH -> if (len <= 12) bridge = String(v, Charsets.UTF_8)
                }
            }
            return Announcement(nickname ?: return null, noise ?: return null, signing ?: return null, neighbors, caps, bridge)
        }

        /** Little-endian, minimal length, at least one byte. */
        fun encodeCapabilities(value: Long): ByteArray {
            val out = ArrayList<Byte>()
            var v = value
            do { out.add((v and 0xFF).toByte()); v = v ushr 8 } while (v != 0L)
            return out.toByteArray()
        }

        fun decodeCapabilities(bytes: ByteArray): Long {
            var v = 0L
            bytes.take(8).forEachIndexed { i, b -> v = v or ((b.toLong() and 0xFF) shl (8 * i)) }
            return v
        }
    }
}

/** privateMessage body inside a Noise transport message (§8.3). */
data class PrivateMessage(val messageId: String, val content: String) {
    fun encode(): ByteArray? {
        val id = messageId.toByteArray(Charsets.UTF_8)
        val body = content.toByteArray(Charsets.UTF_8)
        if (id.size > 255 || body.size > 255) return null
        return ByteWriter().u8(0x00).u8(id.size).bytes(id).u8(0x01).u8(body.size).bytes(body).toByteArray()
    }

    companion object {
        /** Largest content in UTF-8 bytes that fits one private message. */
        const val MAX_CONTENT_BYTES = 255

        fun decode(data: ByteArray): PrivateMessage? {
            var id: String? = null
            var content: String? = null
            val r = ByteReader(data)
            while (r.remaining >= 2) {
                val t = r.u8()!!
                val len = r.u8()!!
                val v = r.bytes(len) ?: return null
                when (t) {
                    0x00 -> id = String(v, Charsets.UTF_8)
                    0x01 -> content = String(v, Charsets.UTF_8)
                    else -> return null
                }
            }
            return PrivateMessage(id ?: return null, content ?: return null)
        }
    }
}

/** A typed Noise plaintext: one type byte, then the body. */
data class NoisePayload(val type: Int, val body: ByteArray) {
    fun encode(): ByteArray = byteArrayOf(type.toByte()) + body

    companion object {
        fun decode(plain: ByteArray): NoisePayload? =
            if (plain.isEmpty()) null else NoisePayload(plain[0].toInt() and 0xFF, plain.copyOfRange(1, plain.size))
    }
}

/**
 * Splits text into chunks of at most [maxBytes] UTF-8 bytes without cutting a
 * code point, so long private messages fit the 255-byte TLV.
 */
fun splitUtf8(text: String, maxBytes: Int): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val chars = Character.charCount(cp)
        val s = text.substring(i, i + chars)
        val n = s.toByteArray(Charsets.UTF_8).size
        if (bytes + n > maxBytes && sb.isNotEmpty()) { out.add(sb.toString()); sb.clear(); bytes = 0 }
        sb.append(s); bytes += n; i += chars
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}
