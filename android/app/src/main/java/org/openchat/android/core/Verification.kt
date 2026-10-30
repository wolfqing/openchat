package org.openchat.android.core

import org.openchat.android.crypto.Crypto
import java.util.Base64

/**
 * Verification QR (docs/protocol-spec.md §16): a signed `bitchat://verify` link
 * carrying the identity keys, valid for five minutes either side of now.
 */
data class VerifyQr(
    val v: Int,
    val noiseKeyHex: String,
    val signKeyHex: String,
    val npub: String?,
    val nickname: String,
    val ts: Long,
    val nonceB64: String,
    val sigHex: String = "",
) {
    fun canonicalBytes(): ByteArray {
        val w = ByteWriter(256)
        fun field(s: String) { val b = s.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }; w.u8(b.size).bytes(b) }
        field(CONTEXT); field(v.toString()); field(noiseKeyHex.lowercase()); field(signKeyHex.lowercase())
        field(npub ?: ""); field(nickname); field(ts.toString()); field(nonceB64)
        return w.toByteArray()
    }

    fun toUrl(): String = buildString {
        append("bitchat://verify?v=").append(v)
        append("&noise=").append(enc(noiseKeyHex)).append("&sign=").append(enc(signKeyHex))
        append("&nick=").append(enc(nickname)).append("&ts=").append(ts)
        append("&nonce=").append(enc(nonceB64)).append("&sig=").append(enc(sigHex))
        npub?.let { append("&npub=").append(enc(it)) }
    }

    companion object {
        const val CONTEXT = "bitchat-verify-v1"
        const val MAX_AGE_S = 5 * 60L
        private const val RESPONSE_CONTEXT = "bitchat-verify-resp-v1"

        fun build(noiseKey: ByteArray, signing: Crypto.Ed25519KeyPair, nickname: String, npub: String?, nowS: Long): VerifyQr {
            val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(Crypto.randomBytes(16))
            val unsigned = VerifyQr(1, noiseKey.toHex(), signing.publicKey.toHex(), npub, nickname, nowS, nonce)
            return unsigned.copy(sigHex = Crypto.ed25519Sign(signing.privateKey, unsigned.canonicalBytes()).toHex())
        }

        fun parse(url: String): VerifyQr? {
            if (!url.startsWith("bitchat://verify?")) return null
            val q = url.substringAfter('?').split('&').mapNotNull { kv ->
                val i = kv.indexOf('='); if (i <= 0) null else kv.substring(0, i) to (dec(kv.substring(i + 1)) ?: return null)
            }.toMap()
            return VerifyQr(
                q["v"]?.toIntOrNull() ?: return null, q["noise"] ?: return null, q["sign"] ?: return null, q["npub"],
                q["nick"] ?: return null, q["ts"]?.toLongOrNull() ?: return null, q["nonce"] ?: return null, q["sig"] ?: return null,
            )
        }

        /** The parsed QR if it is fresh and its signature verifies with the key it carries. */
        fun verify(url: String, nowS: Long): VerifyQr? {
            val qr = parse(url) ?: return null
            if (kotlin.math.abs(nowS - qr.ts) > MAX_AGE_S) return null
            val sig = qr.sigHex.hexToBytes() ?: return null
            val key = qr.signKeyHex.hexToBytes()?.takeIf { it.size == 32 } ?: return null
            if (qr.noiseKeyHex.hexToBytes()?.size != 32) return null
            return if (Crypto.ed25519Verify(key, qr.canonicalBytes(), sig)) qr else null
        }

        // Challenge and response over an established Noise session (§16.2): 1-byte type, 1-byte length TLVs.
        fun challenge(noiseKeyHex: String, nonce: ByteArray): ByteArray {
            val k = noiseKeyHex.toByteArray(Charsets.UTF_8)
            return ByteWriter().u8(1).u8(k.size).bytes(k).u8(2).u8(nonce.size).bytes(nonce).toByteArray()
        }

        fun parseChallenge(b: ByteArray): Pair<String, ByteArray>? {
            val r = ByteReader(b)
            if (r.u8() != 1) return null
            val k = r.bytes(r.u8() ?: return null) ?: return null
            if (r.u8() != 2) return null
            val n = r.bytes(r.u8() ?: return null) ?: return null
            return String(k, Charsets.UTF_8) to n
        }

        private fun responseMessage(noiseKeyHex: String, nonce: ByteArray): ByteArray {
            val k = noiseKeyHex.toByteArray(Charsets.UTF_8)
            return ByteWriter().bytes(RESPONSE_CONTEXT.toByteArray(Charsets.US_ASCII)).u8(k.size).bytes(k).bytes(nonce).toByteArray()
        }

        fun response(noiseKeyHex: String, nonce: ByteArray, signing: Crypto.Ed25519KeyPair): ByteArray {
            val k = noiseKeyHex.toByteArray(Charsets.UTF_8)
            val sig = Crypto.ed25519Sign(signing.privateKey, responseMessage(noiseKeyHex, nonce))
            return ByteWriter().u8(1).u8(k.size).bytes(k).u8(2).u8(nonce.size).bytes(nonce).u8(3).u8(sig.size).bytes(sig).toByteArray()
        }

        /** True if [b] is a valid response to our challenge [nonce] for [noiseKeyHex], signed by [signingKey]. */
        fun checkResponse(b: ByteArray, noiseKeyHex: String, nonce: ByteArray, signingKey: ByteArray): Boolean {
            val r = ByteReader(b)
            if (r.u8() != 1) return false
            val k = String(r.bytes(r.u8() ?: return false) ?: return false, Charsets.UTF_8)
            if (r.u8() != 2) return false
            val n = r.bytes(r.u8() ?: return false) ?: return false
            if (r.u8() != 3) return false
            val sig = r.bytes(r.u8() ?: return false) ?: return false
            if (!k.equals(noiseKeyHex, ignoreCase = true) || !n.contentEquals(nonce)) return false
            return Crypto.ed25519Verify(signingKey, responseMessage(k, n), sig)
        }

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        // Percent-decoding only: a literal '+' stays '+', as URLComponents leaves it.
        private fun dec(s: String): String? = try {
            java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) { null }
    }
}
