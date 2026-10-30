package org.openchat.android.core

import org.openchat.android.crypto.Crypto

/**
 * Courier envelope payload for store-and-forward (docs/protocol-spec.md §13.2).
 * Opaque to the courier: the only routing hint is a rotating recipient tag.
 */
class CourierEnvelope(
    val recipientTag: ByteArray,
    val expiry: Long,
    val ciphertext: ByteArray,
    copies: Int = 1,
    val prekeyId: Long? = null,
) {
    val copies: Int = copies.coerceIn(1, MAX_COPIES)

    fun withCopies(n: Int) = CourierEnvelope(recipientTag, expiry, ciphertext, n, prekeyId)

    fun isExpired(nowMs: Long) = nowMs >= expiry

    fun encode(): ByteArray? {
        if (recipientTag.size != TAG_LENGTH) return null
        if (ciphertext.isEmpty() || ciphertext.size > MAX_CIPHERTEXT) return null
        val w = ByteWriter(32 + ciphertext.size)
        w.u8(T_TAG).u16(TAG_LENGTH).bytes(recipientTag)
        w.u8(T_EXPIRY).u16(8).u64(expiry)
        w.u8(T_CIPHERTEXT).u16(ciphertext.size).bytes(ciphertext)
        if (copies > 1) w.u8(T_COPIES).u16(1).u8(copies)
        prekeyId?.let { w.u8(T_PREKEY_ID).u16(4).u32(it) }
        return w.toByteArray()
    }

    companion object {
        const val TAG_LENGTH = 16
        const val MAX_CIPHERTEXT = 16 * 1024
        const val MAX_COPIES = 8
        const val LIFETIME_MS = 24 * 60 * 60 * 1000L
        const val INITIAL_COPIES = 4

        private const val T_TAG = 0x01
        private const val T_EXPIRY = 0x02
        private const val T_CIPHERTEXT = 0x03
        private const val T_COPIES = 0x04
        private const val T_PREKEY_ID = 0x05

        private val TAG_CONTEXT = "bitchat-courier-tag-v1".toByteArray(Charsets.US_ASCII)

        fun decode(data: ByteArray): CourierEnvelope? {
            var tag: ByteArray? = null
            var expiry: Long? = null
            var ct: ByteArray? = null
            var copies = 1
            var prekey: Long? = null
            val r = ByteReader(data)
            while (r.remaining > 0) {
                val t = r.u8() ?: return null
                val len = r.u16() ?: return null
                val v = r.bytes(len) ?: return null
                when (t) {
                    T_TAG -> { if (len != TAG_LENGTH) return null; tag = v }
                    T_EXPIRY -> { if (len != 8) return null; expiry = ByteReader(v).u64() }
                    T_CIPHERTEXT -> { if (len == 0 || len > MAX_CIPHERTEXT) return null; ct = v }
                    T_COPIES -> { if (len != 1) return null; copies = v[0].toInt() and 0xFF }
                    T_PREKEY_ID -> { if (len != 4) return null; prekey = ByteReader(v).u32() }
                    else -> Unit // unknown: skipped by length
                }
            }
            return CourierEnvelope(tag ?: return null, expiry ?: return null, ct ?: return null, copies, prekey)
        }

        fun epochDay(nowMs: Long): Long = maxOf(0L, nowMs / 1000) / 86_400

        fun recipientTag(noiseStaticKey: ByteArray, day: Long): ByteArray {
            val msg = ByteWriter().bytes(TAG_CONTEXT).u32(day).toByteArray()
            return Crypto.hmacSha256(noiseStaticKey, msg).copyOf(TAG_LENGTH)
        }

        /** Tags for yesterday, today and tomorrow, as hex, so mail sealed near midnight still matches. */
        fun candidateTags(noiseStaticKey: ByteArray, nowMs: Long): Set<String> {
            val day = epochDay(nowMs)
            return listOf(if (day == 0L) 0L else day - 1, day, day + 1).map { recipientTag(noiseStaticKey, it).toHex() }.toSet()
        }
    }
}
