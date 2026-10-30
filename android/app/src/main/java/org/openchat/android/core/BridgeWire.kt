package org.openchat.android.core

/**
 * Mesh bridge wire pieces (docs/protocol-spec.md §24): the carrier that moves a signed
 * Nostr event over the radio, and the content-stable ID that matches a radio message
 * with its bridged copy.
 */
class NostrCarrier(val direction: Int, val geohash: String, val eventJson: ByteArray) {
    fun encode(): ByteArray? {
        val gh = geohash.toByteArray(Charsets.UTF_8)
        if (gh.isEmpty() || gh.size > 12 || eventJson.isEmpty() || eventJson.size > MAX_EVENT_BYTES) return null
        return ByteWriter(eventJson.size + 24)
            .u8(1).u16(1).u8(direction)
            .u8(2).u16(gh.size).bytes(gh)
            .u8(3).u16(eventJson.size).bytes(eventJson)
            .toByteArray()
    }

    companion object {
        const val TO_BRIDGE = 0x03   // a mesh-only sender asks a bridge phone to publish its event
        const val FROM_BRIDGE = 0x04 // a bridge phone carries a remote event onto the radio
        const val MAX_EVENT_BYTES = 16 * 1024

        fun decode(b: ByteArray): NostrCarrier? {
            var dir: Int? = null; var gh: String? = null; var json: ByteArray? = null
            val r = ByteReader(b)
            while (r.remaining >= 3) {
                val t = r.u8()!!
                val v = r.bytes(r.u16()!!) ?: return null
                when (t) {
                    1 -> { if (v.size != 1) return null; dir = v[0].toInt() and 0xFF }
                    2 -> gh = String(v, Charsets.UTF_8)
                    3 -> json = v
                }
            }
            if (r.remaining != 0) return null
            val g = gh?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 12 } ?: return null
            val j = json?.takeIf { it.isNotEmpty() && it.size <= MAX_EVENT_BYTES } ?: return null
            return NostrCarrier(dir ?: return null, g, j)
        }
    }
}

object MeshMessageId {
    /** First 32 hex digits of SHA-256(lowercase sender ID ‖ "|" ‖ timestamp ms ‖ "|" ‖ trimmed text). */
    fun stable(senderHex: String, timestampMs: Long, content: String): String =
        sha256((senderHex.lowercase() + "|" + timestampMs + "|" + content.trim()).toByteArray(Charsets.UTF_8)).toHex().take(32)
}
