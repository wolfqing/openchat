package org.openchat.android.core

import org.openchat.android.crypto.Crypto

/**
 * Vouching (docs/protocol-spec.md §26): a signed statement, sent inside a Noise session,
 * that the sender has verified someone. The voucher is not named: the receiver checks the
 * signature against the session peer's announced signing key, so a captured attestation
 * is worthless to anyone else. TLVs: 1-byte type, 1-byte length.
 */
class Vouch(val voucheeFingerprint: ByteArray, val voucheeSigningKey: ByteArray, val timestampMs: Long, val signature: ByteArray) {
    val voucheeHex get() = voucheeFingerprint.toHex()

    fun verify(voucherSigningKey: ByteArray) =
        Crypto.ed25519Verify(voucherSigningKey, signingBytes(voucheeFingerprint, voucheeSigningKey, timestampMs), signature)

    /** Outside 30 days back or an hour ahead. */
    fun expired(nowMs: Long) = nowMs - timestampMs > MAX_AGE_MS || timestampMs - nowMs > MAX_SKEW_MS

    fun encode(): ByteArray = ByteWriter(140).apply {
        u8(1).u8(32).bytes(voucheeFingerprint)
        u8(2).u8(32).bytes(voucheeSigningKey)
        u8(3).u8(8).u64(timestampMs)
        u8(4).u8(64).bytes(signature)
    }.toByteArray()

    companion object {
        const val MAX_AGE_MS = 30 * 24 * 3600_000L
        const val MAX_SKEW_MS = 3600_000L
        const val MAX_BATCH = 16

        fun signingBytes(fingerprint: ByteArray, signingKey: ByteArray, timestampMs: Long): ByteArray =
            ByteWriter(96).bytes("bitchat-vouch-v1".toByteArray(Charsets.US_ASCII)).bytes(fingerprint).bytes(signingKey).u64(timestampMs).toByteArray()

        fun create(fingerprint: ByteArray, signingKey: ByteArray, nowMs: Long, signer: Crypto.Ed25519KeyPair): Vouch? {
            if (fingerprint.size != 32 || signingKey.size != 32) return null
            return Vouch(fingerprint, signingKey, nowMs, Crypto.ed25519Sign(signer.privateKey, signingBytes(fingerprint, signingKey, nowMs)))
        }

        fun decode(b: ByteArray): Vouch? {
            var fp: ByteArray? = null; var key: ByteArray? = null; var ts: Long? = null; var sig: ByteArray? = null
            val r = ByteReader(b)
            while (r.remaining > 0) {
                val t = r.u8() ?: return null
                val v = r.bytes(r.u8() ?: return null) ?: return null
                when (t) {
                    1 -> { if (v.size != 32) return null; fp = v }
                    2 -> { if (v.size != 32) return null; key = v }
                    3 -> { if (v.size != 8) return null; ts = ByteReader(v).u64() }
                    4 -> { if (v.size != 64) return null; sig = v }
                }
            }
            return Vouch(fp ?: return null, key ?: return null, ts ?: return null, sig ?: return null)
        }

        /** Batch: count byte, then each attestation behind a 2-byte big-endian length. */
        fun encodeList(list: List<Vouch>): ByteArray? {
            if (list.isEmpty() || list.size > MAX_BATCH) return null
            val w = ByteWriter(list.size * 150).u8(list.size)
            list.forEach { val e = it.encode(); w.u16(e.size).bytes(e) }
            return w.toByteArray()
        }

        /** Malformed entries are skipped; the declared count is trusted only up to 16. */
        fun decodeList(b: ByteArray): List<Vouch> {
            if (b.size < 2) return emptyList()
            val r = ByteReader(b)
            val limit = minOf(r.u8()!!, MAX_BATCH)
            val out = ArrayList<Vouch>()
            while (out.size < limit && r.remaining >= 2) {
                val entry = r.bytes(r.u16()!!) ?: break
                decode(entry)?.let { out.add(it) }
            }
            return out
        }
    }
}

/**
 * Vouches received, as bitchat keeps them: per vouchee, the eight most recent vouchers.
 * Validity is decided when read, so un-verifying a voucher retires its vouches and
 * verifying it again brings them back. Fingerprints are lowercase hex.
 */
/** voucher fingerprint -> attestation time */
typealias VouchEntries = Map<String, Long>

object VouchBook {
    const val MAX_VOUCHERS = 8
    const val MAX_VOUCHEES = 500

    /** Returns the new book, or null when the vouch is not stored. */
    fun record(book: Map<String, VouchEntries>, vouchee: String, voucher: String, timestampMs: Long, nowMs: Long, verified: Set<String>): Map<String, VouchEntries>? {
        if (voucher !in verified || vouchee == voucher || vouchee in verified) return null
        if (nowMs - timestampMs > Vouch.MAX_AGE_MS || timestampMs - nowMs > Vouch.MAX_SKEW_MS) return null
        val current = book[vouchee].orEmpty()
        if (current[voucher]?.let { it >= timestampMs } == true) return null
        if (voucher !in current && current.size >= MAX_VOUCHERS && current.values.all { it > timestampMs }) return null
        val entries = (current + (voucher to timestampMs)).entries.sortedByDescending { it.value }.take(MAX_VOUCHERS).associate { it.key to it.value }
        var next = book + (vouchee to entries)
        if (next.size > MAX_VOUCHEES) {
            val oldest = next.minByOrNull { (_, e) -> e.values.maxOrNull() ?: 0L }!!.key
            next = next - oldest
        }
        return next
    }

    /** Vouchers for [vouchee] that still count: verified by us now, and not expired. */
    fun vouchers(book: Map<String, VouchEntries>, vouchee: String, nowMs: Long, verified: Set<String>): List<String> =
        book[vouchee].orEmpty().filter { (v, ts) -> v in verified && nowMs - ts <= Vouch.MAX_AGE_MS }.keys.toList()
}
