package org.openchat.android.core

/**
 * File TLV shared with bitchat (docs/protocol-spec.md §18.1): 1-byte type,
 * 2-byte big-endian length, except content whose length is 4 bytes.
 */
class FilePacket(val fileName: String?, val mimeType: String?, val content: ByteArray) {
    fun encode(): ByteArray? {
        if (content.isEmpty() || content.size > MAX_BYTES) return null
        val w = ByteWriter(content.size + 64)
        fileName?.toByteArray(Charsets.UTF_8)?.takeIf { it.size <= 0xFFFF }?.let { w.u8(T_NAME).u16(it.size).bytes(it) }
        w.u8(T_SIZE).u16(4).u32(content.size.toLong())
        mimeType?.toByteArray(Charsets.UTF_8)?.takeIf { it.size <= 0xFFFF }?.let { w.u8(T_MIME).u16(it.size).bytes(it) }
        w.u8(T_CONTENT).u32(content.size.toLong()).bytes(content)
        return w.toByteArray()
    }

    /** The kind of media this is, if its MIME type is allowed and its bytes look like it. */
    val kind: Kind? get() = kindOf(mimeType, content)

    enum class Kind { IMAGE, AUDIO }

    companion object {
        const val MAX_BYTES = 1024 * 1024
        private const val T_NAME = 0x01
        private const val T_SIZE = 0x02
        private const val T_MIME = 0x03
        private const val T_CONTENT = 0x04

        fun decode(data: ByteArray): FilePacket? {
            val r = ByteReader(data)
            var name: String? = null; var mime: String? = null
            val content = java.io.ByteArrayOutputStream()
            while (r.remaining > 0) {
                val t = r.u8() ?: return null
                val len: Int = if (t == T_CONTENT) {
                    // Canonical 4-byte length; older senders used 2.
                    val start = r.offset
                    val four = r.u32()
                    if (four != null && four <= r.remaining) four.toInt() else { r.offset = start; r.u16() ?: return null }
                } else r.u16() ?: return null
                val v = r.bytes(len) ?: return null
                when (t) {
                    T_NAME -> name = String(v, Charsets.UTF_8)
                    T_MIME -> mime = String(v, Charsets.UTF_8)
                    T_SIZE -> if (len == 4 || len == 8) { if (ByteReader(v).let { if (len == 4) it.u32() else it.u64() }!! > MAX_BYTES) return null }
                    T_CONTENT -> { if (content.size() + v.size > MAX_BYTES) return null; content.write(v) }
                }
            }
            if (content.size() == 0) return null
            return FilePacket(name, mime, content.toByteArray())
        }

        /** Images and audio only, checked by magic bytes as well as the declared type. */
        fun kindOf(mime: String?, b: ByteArray): Kind? {
            fun at(off: Int, vararg sig: Int) = b.size >= off + sig.size && sig.indices.all { (b[off + it].toInt() and 0xFF) == sig[it] }
            return when (mime?.lowercase()) {
                "image/jpeg", "image/jpg" -> if (at(0, 0xFF, 0xD8, 0xFF)) Kind.IMAGE else null
                "image/png" -> if (at(0, 0x89, 0x50, 0x4E, 0x47)) Kind.IMAGE else null
                "image/gif" -> if (at(0, 0x47, 0x49, 0x46, 0x38)) Kind.IMAGE else null
                "image/webp" -> if (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) Kind.IMAGE else null
                "audio/mp4", "audio/m4a", "audio/x-m4a" -> if (at(4, 0x66, 0x74, 0x79, 0x70)) Kind.AUDIO else null
                "audio/aac" -> if (at(0, 0xFF) && b.size > 1 && (b[1].toInt() and 0xF0) == 0xF0 || at(4, 0x66, 0x74, 0x79, 0x70)) Kind.AUDIO else null
                "audio/mpeg", "audio/mp3" -> if (at(0, 0x49, 0x44, 0x33) || (at(0, 0xFF) && b.size > 1 && (b[1].toInt() and 0xE0) == 0xE0)) Kind.AUDIO else null
                "audio/wav", "audio/x-wav" -> if (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x41, 0x56, 0x45)) Kind.AUDIO else null
                "audio/ogg" -> if (at(0, 0x4F, 0x67, 0x67, 0x53)) Kind.AUDIO else null
                else -> null
            }
        }

        fun extension(mime: String?) = when (mime?.lowercase()) {
            "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a"
            "audio/mpeg", "audio/mp3" -> "mp3"; "audio/wav", "audio/x-wav" -> "wav"; "audio/ogg" -> "ogg"
            else -> "jpg"
        }
    }
}

/** Authenticated peer state, Noise payload `0x21` (§8.3): what the other side can receive. */
object AuthPeerState {
    const val CAP_PRIVATE_MEDIA = 1L shl 8

    fun encode(capabilities: Long, signingKey: ByteArray): ByteArray {
        val caps = Announcement.encodeCapabilities(capabilities)
        return ByteWriter().u8(1).u8(1).u8(caps.size).bytes(caps).u8(2).u8(signingKey.size).bytes(signingKey).toByteArray()
    }

    /** (capabilities, signing key) or null for anything non-canonical. */
    fun decode(b: ByteArray): Pair<Long, ByteArray>? {
        val r = ByteReader(b)
        if (r.u8() != 1) return null
        var caps: Long? = null; var key: ByteArray? = null
        while (r.remaining > 0) {
            val t = r.u8() ?: return null
            val v = r.bytes(r.u8() ?: return null) ?: return null
            when (t) {
                1 -> { if (caps != null || v.isEmpty() || v.size > 8 || (v.size > 1 && v.last() == 0.toByte())) return null; caps = Announcement.decodeCapabilities(v) }
                2 -> { if (key != null || v.size != 32) return null; key = v }
            }
        }
        return (caps ?: return null) to (key ?: return null)
    }
}
