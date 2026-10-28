package org.openchat.android.mesh

import org.openchat.android.core.ByteReader
import org.openchat.android.core.ByteWriter
import org.openchat.android.core.sha256
import org.openchat.android.core.toHex
import org.openchat.android.crypto.Crypto

/**
 * Long-term identity (docs/protocol-spec.md §4.1): an X25519 Noise key, an
 * independent Ed25519 signing key, the nickname and the Nostr device seed.
 * All of it is replaced by a panic wipe.
 */
class Identity(
    val noise: Crypto.X25519KeyPair,
    val signing: Crypto.Ed25519KeyPair,
    val nickname: String,
    val nostrSeed: ByteArray,
) {
    /** 8-byte peer ID = SHA-256(Noise static public key)[0..8]. */
    val peerId: ByteArray = peerIdFor(noise.publicKey)
    val peerIdHex: String = peerId.toHex()
    val fingerprint: String = sha256(noise.publicKey).toHex()

    fun withNickname(name: String) = Identity(noise, signing, name, nostrSeed)

    fun serialize(): ByteArray {
        val nick = nickname.toByteArray(Charsets.UTF_8)
        return ByteWriter()
            .u8(VERSION)
            .bytes(noise.privateKey)
            .bytes(signing.privateKey)
            .bytes(nostrSeed)
            .u16(nick.size).bytes(nick)
            .toByteArray()
    }

    companion object {
        private const val VERSION = 1

        fun peerIdFor(noisePublicKey: ByteArray): ByteArray = sha256(noisePublicKey).copyOf(8)

        fun generate(nickname: String = defaultNickname()): Identity =
            Identity(Crypto.x25519Generate(), Crypto.ed25519Generate(), nickname, Crypto.randomBytes(32))

        fun deserialize(data: ByteArray): Identity? {
            val r = ByteReader(data)
            if (r.u8() != VERSION) return null
            val noise = r.bytes(32) ?: return null
            val signing = r.bytes(32) ?: return null
            val seed = r.bytes(32) ?: return null
            val nickLen = r.u16() ?: return null
            val nick = r.bytes(nickLen) ?: return null
            return Identity(Crypto.x25519FromPrivate(noise), Crypto.ed25519FromPrivate(signing), String(nick, Charsets.UTF_8), seed)
        }

        fun defaultNickname(): String = "anon" + (1000 + Crypto.random.nextInt(9000))
    }
}
