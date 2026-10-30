package org.openchat.android.nostr

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.json.JSONArray
import org.json.JSONObject
import org.openchat.android.core.hexToBytes
import org.openchat.android.core.toHex
import org.openchat.android.crypto.Crypto
import java.math.BigInteger
import java.util.Base64

/**
 * bitchat's private envelope over Nostr relays (docs/protocol-spec.md §15.3).
 * Uses NIP-17/59 kind numbers (14 rumor, 13 seal, 1059 wrap) but its own
 * cipher: `v2:` + base64url(nonce24 ‖ ciphertext ‖ tag), XChaCha20-Poly1305
 * under HKDF-SHA256(ECDH, salt empty, info "nip44-v2"). Not NIP-44.
 */
object PrivateEnvelope {
    const val KIND_RUMOR = 14
    const val KIND_SEAL = 13
    const val KIND_WRAP = 1059
    const val MAX_CIPHERTEXT = 64 * 1024
    private val curve = CustomNamedCurves.getByName("secp256k1")
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    class Opened(val content: String, val senderPubkey: String, val createdAt: Long)

    fun create(content: String, recipientHex: String, sender: NostrKey, nowS: Long, random: () -> Long = { Crypto.random.nextInt(1801).toLong() - 900 }): NostrEvent {
        val rumor = JSONObject()
            .put("id", "").put("pubkey", sender.publicKeyHex).put("created_at", nowS)
            .put("kind", KIND_RUMOR).put("tags", JSONArray()).put("content", content)
        val seal = NostrEvent.create(sender, KIND_SEAL, emptyList(), encrypt(rumor.toString(), recipientHex, sender.secret), nowS + random())
        val wrapKey = NostrKey(randomSecret())
        return NostrEvent.create(wrapKey, KIND_WRAP, listOf(listOf("p", recipientHex)), encrypt(seal.toJson(), recipientHex, wrapKey.secret), nowS + random())
    }

    /** Opens a wrap addressed to [me]; null for anything malformed, misaddressed or unauthenticated. */
    fun open(wrap: NostrEvent, me: NostrKey): Opened? = try {
        if (wrap.kind != KIND_WRAP || wrap.content.length > MAX_CIPHERTEXT) null
        else if (wrap.tags != listOf(listOf("p", me.publicKeyHex)) || !wrap.isValid()) null
        else {
            val seal = NostrEvent.fromJson(JSONObject(decrypt(wrap.content, wrap.pubkey, me.secret)))
            if (seal == null || seal.kind != KIND_SEAL || seal.tags.isNotEmpty() || !seal.isValid()) null
            else {
                val r = JSONObject(decrypt(seal.content, seal.pubkey, me.secret))
                val tags = r.getJSONArray("tags")
                val okTags = tags.length() == 0 ||
                    (tags.length() == 1 && tags.getJSONArray(0).let { it.length() == 2 && it.getString(0) == "p" && it.getString(1) == me.publicKeyHex })
                val unsigned = !r.has("sig") || r.isNull("sig")
                if (r.getInt("kind") != KIND_RUMOR || !okTags || !unsigned || r.getString("pubkey") != seal.pubkey) null
                else Opened(r.getString("content"), seal.pubkey, r.getLong("created_at"))
            }
        }
    } catch (e: Exception) { null }

    fun encrypt(plaintext: String, recipientHex: String, senderSecret: ByteArray): String {
        val key = conversationKey(senderSecret, recipientHex.hexToBytes() ?: error("bad key"), odd = false)
        val nonce = Crypto.randomBytes(24)
        val sealed = XChaCha.seal(key, nonce, plaintext.toByteArray(Charsets.UTF_8))
        return "v2:" + b64.encodeToString(nonce + sealed)
    }

    fun decrypt(content: String, senderHex: String, recipientSecret: ByteArray): String {
        require(content.length <= MAX_CIPHERTEXT && content.startsWith("v2:"))
        val data = Base64.getUrlDecoder().decode(content.substring(3).trimEnd('='))
        require(data.size > 24 + 16)
        val nonce = data.copyOfRange(0, 24)
        val body = data.copyOfRange(24, data.size)
        val pub = senderHex.hexToBytes() ?: error("bad key")
        // An x-only key names two points; the sender's could be either.
        val plain = XChaCha.open(conversationKey(recipientSecret, pub, odd = false), nonce, body)
            ?: XChaCha.open(conversationKey(recipientSecret, pub, odd = true), nonce, body)
            ?: error("decrypt failed")
        return String(plain, Charsets.UTF_8)
    }

    /** HKDF-SHA256 over the compressed ECDH point (33 bytes), info "nip44-v2". */
    private fun conversationKey(secret: ByteArray, xOnly: ByteArray, odd: Boolean): ByteArray {
        require(xOnly.size == 32)
        val point = curve.curve.decodePoint(byteArrayOf(if (odd) 3 else 2) + xOnly)
        val shared = point.multiply(BigInteger(1, secret)).normalize().getEncoded(true)
        val prk = Crypto.hmacSha256(ByteArray(32), shared)
        return Crypto.hmacSha256(prk, "nip44-v2".toByteArray(Charsets.US_ASCII), byteArrayOf(1))
    }

    private fun randomSecret(): ByteArray {
        while (true) { val k = Crypto.randomBytes(32); if (Schnorr.isValidSecret(k)) return k }
    }
}

/** XChaCha20-Poly1305: HChaCha20 subkey from the first 16 nonce bytes, then ChaCha20-Poly1305 with 0⁴ ‖ last 8. */
object XChaCha {
    fun seal(key: ByteArray, nonce24: ByteArray, plain: ByteArray): ByteArray =
        Crypto.chachaSeal(hchacha(key, nonce24.copyOfRange(0, 16)), ByteArray(4) + nonce24.copyOfRange(16, 24), plain, ByteArray(0))

    fun open(key: ByteArray, nonce24: ByteArray, sealed: ByteArray): ByteArray? =
        Crypto.chachaOpen(hchacha(key, nonce24.copyOfRange(0, 16)), ByteArray(4) + nonce24.copyOfRange(16, 24), sealed, ByteArray(0))

    fun hchacha(key: ByteArray, nonce16: ByteArray): ByteArray {
        fun le(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le(key, i * 4)
        for (i in 0 until 4) s[12 + i] = le(nonce16, i * 4)
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 16)
            s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 12)
            s[a] += s[b]; s[d] = Integer.rotateLeft(s[d] xor s[a], 8)
            s[c] += s[d]; s[b] = Integer.rotateLeft(s[b] xor s[c], 7)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        }
        val out = ByteArray(32)
        intArrayOf(s[0], s[1], s[2], s[3], s[12], s[13], s[14], s[15]).forEachIndexed { i, v ->
            for (j in 0 until 4) out[i * 4 + j] = (v ushr (8 * j)).toByte()
        }
        return out
    }
}

/** Bech32 (BIP-173) for `npub` keys. */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((b ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun expand(hrp: String) = IntArray(hrp.length * 2 + 1).also { r ->
        hrp.forEachIndexed { i, c -> r[i] = c.code ushr 5; r[i + hrp.length + 1] = c.code and 31 }
    }

    private fun convert(data: ByteArray, from: Int, to: Int, pad: Boolean): IntArray? {
        var acc = 0; var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl to) - 1
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v ushr from != 0) return null
            acc = (acc shl from) or v; bits += from
            while (bits >= to) { bits -= to; out.add((acc ushr bits) and maxv) }
        }
        if (pad) { if (bits > 0) out.add((acc shl (to - bits)) and maxv) }
        else if (bits >= from || ((acc shl (to - bits)) and maxv) != 0) return null
        return out.toIntArray()
    }

    fun encode(hrp: String, data: ByteArray): String {
        val d = convert(data, 8, 5, true)!!
        val chk = polymod(expand(hrp) + d + IntArray(6)) xor 1
        val sb = StringBuilder(hrp).append('1')
        d.forEach { sb.append(CHARSET[it]) }
        for (i in 0 until 6) sb.append(CHARSET[(chk ushr (5 * (5 - i))) and 31])
        return sb.toString()
    }

    fun decode(s: String): Pair<String, ByteArray>? {
        val str = s.lowercase()
        val pos = str.lastIndexOf('1')
        if (pos < 1 || pos + 7 > str.length || str.length > 120) return null
        val hrp = str.substring(0, pos)
        val values = str.substring(pos + 1).map { CHARSET.indexOf(it).takeIf { i -> i >= 0 } ?: return null }.toIntArray()
        if (polymod(expand(hrp) + values) != 1) return null
        val five = values.copyOfRange(0, values.size - 6)
        val bytes = convert(ByteArray(five.size) { five[it].toByte() }, 5, 8, false) ?: return null
        return hrp to ByteArray(bytes.size) { bytes[it].toByte() }
    }

    fun npub(pubkeyHex: String) = encode("npub", pubkeyHex.hexToBytes()!!)
    fun npubToHex(npub: String): String? = decode(npub)?.takeIf { it.first == "npub" && it.second.size == 32 }?.second?.toHex()
}
