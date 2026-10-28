package org.openchat.android.core

import java.security.MessageDigest

/** Lowercase hex, matching the peer ID and fingerprint format in docs/protocol-spec.md §4.1. */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private val HEX = "0123456789abcdef".toCharArray()

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

/** Append-only big-endian writer. */
class ByteWriter(initial: Int = 64) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun u8(v: Int): ByteWriter { ensure(1); buf[size++] = v.toByte(); return this }
    fun u16(v: Int): ByteWriter { u8(v ushr 8); return u8(v) }
    fun u32(v: Long): ByteWriter { u8((v ushr 24).toInt()); u8((v ushr 16).toInt()); u8((v ushr 8).toInt()); return u8(v.toInt()) }
    fun u64(v: Long): ByteWriter { for (s in 56 downTo 0 step 8) u8((v ushr s).toInt()); return this }
    fun bytes(b: ByteArray): ByteWriter { ensure(b.size); b.copyInto(buf, size); size += b.size; return this }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Bounds-checked big-endian reader. Every read returns null instead of throwing on short input. */
class ByteReader(private val data: ByteArray, var offset: Int = 0) {
    val remaining get() = data.size - offset

    fun u8(): Int? = if (remaining >= 1) data[offset++].toInt() and 0xFF else null
    fun u16(): Int? {
        if (remaining < 2) return null
        val v = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        offset += 2
        return v
    }
    fun u32(): Long? {
        if (remaining < 4) return null
        var v = 0L
        repeat(4) { v = (v shl 8) or (data[offset++].toLong() and 0xFF) }
        return v
    }
    fun u64(): Long? {
        if (remaining < 8) return null
        var v = 0L
        repeat(8) { v = (v shl 8) or (data[offset++].toLong() and 0xFF) }
        return v
    }
    fun bytes(n: Int): ByteArray? {
        if (n < 0 || remaining < n) return null
        val out = data.copyOfRange(offset, offset + n)
        offset += n
        return out
    }
}
