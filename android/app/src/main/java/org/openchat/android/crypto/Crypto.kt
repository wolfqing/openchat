package org.openchat.android.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** Thin wrappers over BouncyCastle lightweight primitives. Keys are raw 32-byte arrays. */
object Crypto {
    val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    // X25519
    class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun x25519Generate(): X25519KeyPair {
        val priv = X25519PrivateKeyParameters(random)
        return X25519KeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    fun x25519FromPrivate(privateKey: ByteArray): X25519KeyPair {
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        return X25519KeyPair(privateKey.copyOf(), priv.generatePublicKey().encoded)
    }

    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val out = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), out, 0)
        if (out.all { it == 0.toByte() }) throw IllegalArgumentException("low-order X25519 point")
        return out
    }

    // Ed25519
    class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun ed25519Generate(): Ed25519KeyPair {
        val priv = Ed25519PrivateKeyParameters(random)
        return Ed25519KeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    fun ed25519FromPrivate(privateKey: ByteArray): Ed25519KeyPair {
        val priv = Ed25519PrivateKeyParameters(privateKey, 0)
        return Ed25519KeyPair(privateKey.copyOf(), priv.generatePublicKey().encoded)
    }

    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        if (publicKey.size != 32 || signature.size != 64) false else {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        }
    } catch (e: Exception) {
        false
    }

    // ChaCha20-Poly1305 (RFC 8439). Output is ciphertext || 16-byte tag.
    fun chachaSeal(key: ByteArray, nonce12: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce12, ad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    /** Returns null when authentication fails. */
    fun chachaOpen(key: ByteArray, nonce12: ByteArray, ciphertext: ByteArray, ad: ByteArray): ByteArray? = try {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce12, ad))
        val out = ByteArray(c.getOutputSize(ciphertext.size))
        val n = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        c.doFinal(out, n)
        out
    } catch (e: Exception) {
        null
    }

    fun hmacSha256(key: ByteArray, vararg data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        for (d in data) mac.update(d, 0, d.size)
        val out = ByteArray(32)
        mac.doFinal(out, 0)
        return out
    }

    /** Noise HKDF (spec §4.3): returns [n] 32-byte outputs, n in 2..3. */
    fun noiseHkdf(chainingKey: ByteArray, ikm: ByteArray, n: Int): List<ByteArray> {
        val temp = hmacSha256(chainingKey, ikm)
        val o1 = hmacSha256(temp, byteArrayOf(1))
        val o2 = hmacSha256(temp, o1, byteArrayOf(2))
        if (n == 2) return listOf(o1, o2)
        val o3 = hmacSha256(temp, o2, byteArrayOf(3))
        return listOf(o1, o2, o3)
    }
}
