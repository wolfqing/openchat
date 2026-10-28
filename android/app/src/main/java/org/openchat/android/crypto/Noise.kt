package org.openchat.android.crypto

import org.openchat.android.core.sha256

/**
 * Noise_XX_25519_ChaChaPoly_SHA256 with bitchat's transport framing
 * (docs/protocol-spec.md §8): empty prologue, empty handshake payloads, and
 * transport ciphertexts prefixed with the 4-byte big-endian nonce.
 */
const val NOISE_PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"

class NoiseException(message: String) : Exception(message)

class CipherState(private var key: ByteArray? = null, private val extractedNonce: Boolean = false) {
    var nonce: Long = 0
        private set

    // Sliding replay window over received nonces, used only with extracted nonces.
    private var highest: Long = -1
    private val window = java.util.BitSet(WINDOW)

    val hasKey get() = key != null

    fun initializeKey(k: ByteArray) { key = k; nonce = 0 }

    private fun nonce12(n: Long): ByteArray {
        val out = ByteArray(12)
        for (i in 0 until 8) out[4 + i] = (n ushr (8 * i)).toByte() // little-endian counter
        return out
    }

    fun encrypt(plaintext: ByteArray, ad: ByteArray = ByteArray(0)): ByteArray {
        val k = key ?: return plaintext
        if (nonce >= 0xFFFFFFFFL - 1) throw NoiseException("nonce exhausted")
        val n = nonce
        val sealed = Crypto.chachaSeal(k, nonce12(n), plaintext, ad)
        nonce++
        if (!extractedNonce) return sealed
        val prefix = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
        return prefix + sealed
    }

    fun decrypt(ciphertext: ByteArray, ad: ByteArray = ByteArray(0)): ByteArray {
        val k = key ?: return ciphertext
        val n: Long
        val body: ByteArray
        if (extractedNonce) {
            if (ciphertext.size < 4 + 16) throw NoiseException("short ciphertext")
            n = ((ciphertext[0].toLong() and 0xFF) shl 24) or ((ciphertext[1].toLong() and 0xFF) shl 16) or
                ((ciphertext[2].toLong() and 0xFF) shl 8) or (ciphertext[3].toLong() and 0xFF)
            if (!isFresh(n)) throw NoiseException("replayed nonce $n")
            body = ciphertext.copyOfRange(4, ciphertext.size)
        } else {
            if (ciphertext.size < 16) throw NoiseException("short ciphertext")
            n = nonce
            body = ciphertext
        }
        val plain = Crypto.chachaOpen(k, nonce12(n), body, ad) ?: throw NoiseException("decrypt failed")
        if (extractedNonce) markSeen(n)
        nonce++
        return plain
    }

    private fun isFresh(n: Long): Boolean {
        if (n > highest) return true
        if (highest - n >= WINDOW) return false
        return !window.get((n % WINDOW).toInt())
    }

    private fun markSeen(n: Long) {
        if (n > highest) {
            val shift = n - highest
            if (shift >= WINDOW) window.clear() else {
                // Clear slots that now fall out of the window.
                var i = highest + 1
                while (i <= n) { window.clear((i % WINDOW).toInt()); i++ }
            }
            highest = n
        }
        window.set((n % WINDOW).toInt())
    }

    fun clear() { key?.fill(0); key = null }

    companion object { const val WINDOW = 1024 }
}

class SymmetricState(protocolName: String) {
    private val cipher = CipherState()
    var chainingKey: ByteArray
        private set
    var hash: ByteArray
        private set

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        hash = if (name.size <= 32) name.copyOf(32) else sha256(name)
        chainingKey = hash.copyOf()
    }

    fun mixKey(ikm: ByteArray) {
        val (ck, k) = Crypto.noiseHkdf(chainingKey, ikm, 2)
        chainingKey = ck
        cipher.initializeKey(k)
    }

    fun mixHash(data: ByteArray) { hash = sha256(hash, data) }

    fun encryptAndHash(plain: ByteArray): ByteArray {
        val c = cipher.encrypt(plain, hash)
        mixHash(c)
        return c
    }

    fun decryptAndHash(c: ByteArray): ByteArray {
        val p = cipher.decrypt(c, hash)
        mixHash(c)
        return p
    }

    fun split(extractedNonce: Boolean): Pair<CipherState, CipherState> {
        val (k1, k2) = Crypto.noiseHkdf(chainingKey, ByteArray(0), 2)
        chainingKey.fill(0)
        return CipherState(k1, extractedNonce) to CipherState(k2, extractedNonce)
    }
}

/**
 * XX handshake:
 *   -> e
 *   <- e, ee, s, es
 *   -> s, se
 */
class HandshakeXX(
    val initiator: Boolean,
    private val localStatic: Crypto.X25519KeyPair,
    private val ephemeralOverride: Crypto.X25519KeyPair? = null,
    prologue: ByteArray = ByteArray(0),
) {
    private val ss = SymmetricState(NOISE_PROTOCOL_NAME)
    private var e: Crypto.X25519KeyPair? = null
    private var re: ByteArray? = null
    var remoteStatic: ByteArray? = null
        private set
    private var step = 0

    init { ss.mixHash(prologue) }

    val isComplete get() = step >= 3
    val handshakeHash get() = ss.hash

    /** True when it is this side's turn to write. */
    val shouldWrite get() = !isComplete && ((step % 2 == 0) == initiator)

    fun writeMessage(payload: ByteArray = ByteArray(0)): ByteArray {
        if (!shouldWrite) throw NoiseException("not our turn")
        val out = java.io.ByteArrayOutputStream()
        when (step) {
            0 -> {
                val eph = ephemeralOverride ?: Crypto.x25519Generate(); e = eph
                ss.mixHash(eph.publicKey); out.write(eph.publicKey)
            }
            1 -> {
                val eph = ephemeralOverride ?: Crypto.x25519Generate(); e = eph
                ss.mixHash(eph.publicKey); out.write(eph.publicKey)
                ss.mixKey(Crypto.x25519(eph.privateKey, re!!))
                out.write(ss.encryptAndHash(localStatic.publicKey))
                ss.mixKey(Crypto.x25519(localStatic.privateKey, re!!))
            }
            2 -> {
                out.write(ss.encryptAndHash(localStatic.publicKey))
                ss.mixKey(Crypto.x25519(localStatic.privateKey, re!!))
            }
        }
        out.write(ss.encryptAndHash(payload))
        step++
        return out.toByteArray()
    }

    fun readMessage(message: ByteArray): ByteArray {
        if (isComplete || shouldWrite) throw NoiseException("not their turn")
        var off = 0
        fun take(n: Int): ByteArray {
            if (message.size - off < n) throw NoiseException("short handshake message")
            return message.copyOfRange(off, off + n).also { off += n }
        }
        val staticLen = 32 + 16
        when (step) {
            0 -> { re = take(32); ss.mixHash(re!!) }
            1 -> {
                re = take(32); ss.mixHash(re!!)
                ss.mixKey(Crypto.x25519(e!!.privateKey, re!!))
                remoteStatic = ss.decryptAndHash(take(staticLen))
                ss.mixKey(Crypto.x25519(e!!.privateKey, remoteStatic!!))
            }
            2 -> {
                remoteStatic = ss.decryptAndHash(take(staticLen))
                ss.mixKey(Crypto.x25519(e!!.privateKey, remoteStatic!!))
            }
        }
        val payload = ss.decryptAndHash(message.copyOfRange(off, message.size))
        step++
        return payload
    }

    /**
     * (send, receive) cipher states. Only valid once [isComplete]. bitchat's
     * transport framing uses [extractedNonce] = true; plain Noise uses false.
     */
    fun split(extractedNonce: Boolean = true): Pair<CipherState, CipherState> {
        if (!isComplete) throw NoiseException("handshake incomplete")
        val (c1, c2) = ss.split(extractedNonce)
        e?.privateKey?.fill(0)
        return if (initiator) c1 to c2 else c2 to c1
    }
}
