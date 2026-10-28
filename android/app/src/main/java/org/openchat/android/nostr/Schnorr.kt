package org.openchat.android.nostr

import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import org.openchat.android.core.sha256
import java.math.BigInteger

/** BIP-340 Schnorr signatures over secp256k1, as Nostr uses them. Keys and signatures are raw bytes. */
object Schnorr {
    private val curve: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
    private val n: BigInteger = curve.n
    private val g: ECPoint = curve.g
    private val p: BigInteger = curve.curve.field.characteristic

    private fun taggedHash(tag: String, vararg data: ByteArray): ByteArray {
        val t = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(t, t, *data)
    }

    private fun bytes32(x: BigInteger): ByteArray {
        val b = x.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun int(b: ByteArray) = BigInteger(1, b)

    /** True when [secret] is a valid private key (1 ≤ d < n). */
    fun isValidSecret(secret: ByteArray): Boolean {
        if (secret.size != 32) return false
        val d = int(secret)
        return d.signum() > 0 && d < n
    }

    /** 32-byte x-only public key. */
    fun publicKey(secret: ByteArray): ByteArray {
        require(isValidSecret(secret)) { "invalid secret key" }
        return bytes32(g.multiply(int(secret)).normalize().affineXCoord.toBigInteger())
    }

    fun sign(secret: ByteArray, message: ByteArray, auxRand: ByteArray = org.openchat.android.crypto.Crypto.randomBytes(32)): ByteArray {
        require(isValidSecret(secret))
        val P = g.multiply(int(secret)).normalize()
        val d = if (P.affineYCoord.toBigInteger().testBit(0)) n - int(secret) else int(secret)
        val px = bytes32(P.affineXCoord.toBigInteger())
        val t = bytes32(d.xor(int(taggedHash("BIP0340/aux", auxRand))))
        val k0 = int(taggedHash("BIP0340/nonce", t, px, message)).mod(n)
        require(k0.signum() != 0)
        val R = g.multiply(k0).normalize()
        val k = if (R.affineYCoord.toBigInteger().testBit(0)) n - k0 else k0
        val rx = bytes32(R.affineXCoord.toBigInteger())
        val e = int(taggedHash("BIP0340/challenge", rx, px, message)).mod(n)
        return rx + bytes32(k.add(e.multiply(d)).mod(n))
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        if (publicKey.size != 32 || signature.size != 64) false else {
            val P = liftX(int(publicKey))
            val r = int(signature.copyOfRange(0, 32))
            val s = int(signature.copyOfRange(32, 64))
            if (P == null || r >= p || s >= n) false else {
                val e = int(taggedHash("BIP0340/challenge", signature.copyOfRange(0, 32), publicKey, message)).mod(n)
                val R = g.multiply(s).add(P.multiply(n - e)).normalize()
                !R.isInfinity && !R.affineYCoord.toBigInteger().testBit(0) && R.affineXCoord.toBigInteger() == r
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun liftX(x: BigInteger): ECPoint? {
        if (x >= p) return null
        val c = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p)
        val y = c.modPow(p.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), p)
        if (y.modPow(BigInteger.valueOf(2), p) != c) return null
        val yy = if (y.testBit(0)) p - y else y
        return curve.curve.createPoint(x, yy)
    }
}
