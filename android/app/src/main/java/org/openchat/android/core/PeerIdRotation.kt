package org.openchat.android.core

import org.openchat.android.crypto.Crypto

/**
 * Peer ID rotation, upstream's draft (docs/protocol-spec.md §20). Implemented
 * and checked against upstream's vectors, but not wired into the mesh:
 * neither bitchat client sends or accepts it yet, so using it would cut
 * openchat off from every bitchat phone.
 */
object PeerIdRotation {
    const val PERIOD_S = 3600L
    const val TAG_SLOTS = 8
    const val TAG_LENGTH = 8
    private val ROTATION_INFO = "bitchat-peer-rotation-v1".toByteArray(Charsets.US_ASCII)
    private val ID_CONTEXT = "bitchat-peer-id-v2".toByteArray(Charsets.US_ASCII)
    private val RECOGNITION_INFO = "bitchat-recognition-v1".toByteArray(Charsets.US_ASCII)
    private val BINDING_CONTEXT = "bitchat-peerid-binding-v1".toByteArray(Charsets.US_ASCII)

    fun epoch(nowS: Long): Long = nowS / PERIOD_S

    /** HKDF-SHA256 with an empty salt, one 32-byte block. */
    fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
        val prk = Crypto.hmacSha256(ByteArray(32), ikm)
        return Crypto.hmacSha256(prk, info, byteArrayOf(1))
    }

    /** From the Noise static private key, so nobody else can compute or predict the IDs. */
    fun rotationSecret(noiseStaticPrivate: ByteArray) = hkdf(noiseStaticPrivate, ROTATION_INFO)

    fun peerId(rotationSecret: ByteArray, epoch: Long): ByteArray =
        Crypto.hmacSha256(rotationSecret, ID_CONTEXT, ByteWriter(4).u32(epoch).toByteArray()).copyOf(8)

    fun recognitionKey(sharedSecret: ByteArray) = hkdf(sharedSecret, RECOGNITION_INFO)

    /** Directional: A→B differs from B→A, so equal bytes in two announces reveal nothing. */
    fun tag(recognitionKey: ByteArray, epoch: Long, senderStatic: ByteArray, recipientStatic: ByteArray, announcedId: ByteArray): ByteArray =
        Crypto.hmacSha256(recognitionKey, ByteWriter(76).u32(epoch).bytes(senderStatic).bytes(recipientStatic).bytes(announcedId).toByteArray()).copyOf(TAG_LENGTH)

    /** Always 64 bytes: real tags in shuffled slots, the rest random. */
    fun tagBlock(tags: List<ByteArray>, random: kotlin.random.Random = Crypto.random.let { r -> kotlin.random.Random(r.nextLong()) }): ByteArray {
        val slots = tags.take(TAG_SLOTS).toMutableList()
        while (slots.size < TAG_SLOTS) slots.add(random.nextBytes(TAG_LENGTH))
        slots.shuffle(random)
        return slots.fold(ByteArray(0)) { acc, t -> acc + t }
    }

    fun blockContains(block: ByteArray, tag: ByteArray) =
        block.size == TAG_SLOTS * TAG_LENGTH && (0 until TAG_SLOTS).any { block.copyOfRange(it * TAG_LENGTH, (it + 1) * TAG_LENGTH).contentEquals(tag) }

    /** Fixed-width message signed inside a session to bind a rotating ID to the static key. */
    fun bindingMessage(epoch: Long, peerId: ByteArray, noiseStatic: ByteArray): ByteArray {
        require(peerId.size == 8 && noiseStatic.size == 32)
        return ByteWriter(69).bytes(BINDING_CONTEXT).u32(epoch).bytes(peerId).bytes(noiseStatic).toByteArray()
    }
}

/** `announceV2` (`0x2C`) payload: no names, no keys, no neighbours (§20). */
class AnnounceV2(val epoch: Long, val tags: ByteArray, val capabilities: Long? = null, val bridgeGeohash: String? = null) {
    fun encode(): ByteArray? {
        if (tags.size != PeerIdRotation.TAG_SLOTS * PeerIdRotation.TAG_LENGTH) return null
        val w = ByteWriter(96)
        w.u8(1).u8(4).u32(epoch)
        w.u8(2).u8(tags.size).bytes(tags)
        capabilities?.let { val c = Announcement.encodeCapabilities(it); w.u8(3).u8(c.size).bytes(c) }
        bridgeGeohash?.toByteArray(Charsets.UTF_8)?.takeIf { it.isNotEmpty() && it.size <= 12 }?.let { w.u8(4).u8(it.size).bytes(it) }
        return w.toByteArray()
    }

    companion object {
        fun decode(b: ByteArray): AnnounceV2? {
            val r = ByteReader(b)
            var epoch: Long? = null; var tags: ByteArray? = null; var caps: Long? = null; var bridge: String? = null
            while (r.remaining >= 2) {
                val t = r.u8()!!; val v = r.bytes(r.u8()!!) ?: return null
                when (t) {
                    1 -> if (v.size == 4) epoch = ByteReader(v).u32()
                    2 -> if (v.size == 64) tags = v
                    3 -> if (v.size in 1..8) caps = Announcement.decodeCapabilities(v)
                    4 -> if (v.size <= 12) bridge = String(v, Charsets.UTF_8)
                }
            }
            return AnnounceV2(epoch ?: return null, tags ?: return null, caps, bridge)
        }
    }
}
