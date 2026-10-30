package org.openchat.android.mesh

import org.openchat.android.core.ByteReader
import org.openchat.android.core.ByteWriter
import org.openchat.android.core.CourierEnvelope
import org.openchat.android.core.Packet
import org.openchat.android.core.PacketCodec
import org.openchat.android.core.hexToBytes
import org.openchat.android.core.toHex

/** A peer whose keys we keep after it leaves range, so mail can still be sealed for it (§13.1). */
class KnownPeer(val noiseKey: ByteArray, var signingKey: ByteArray?, var nickname: String, var lastSeen: Long)

/** One private message waiting for a delivered or read receipt (§13.1). [plaintext] is the §8.3 payload. */
class QueuedMessage(
    val messageId: String,
    val peerHex: String,
    val plaintext: ByteArray,
    val createdAt: Long,
    val couriers: MutableSet<String> = LinkedHashSet(), // Noise keys (hex) of couriers holding a copy
)

/** An envelope this device carries for someone else (§13.4). */
class CarriedEnvelope(
    val envelope: CourierEnvelope,
    val depositorKeyHex: String,
    val storedAt: Long,
    var copies: Int,
    val sprayedTo: MutableSet<String> = LinkedHashSet(),
    var lastRemoteHandover: Long = 0,
)

/** Everything the engine keeps across restarts besides the identity (§13.7, §14.6). */
class MeshState(
    val known: Map<String, KnownPeer>,
    val outbox: List<QueuedMessage>,
    val carried: List<CarriedEnvelope>,
    val history: List<Packet> = emptyList(),
) {
    fun serialize(): ByteArray {
        val w = ByteWriter(1024)
        w.u8(VERSION)
        w.u16(known.size)
        for ((hex, k) in known) {
            str(w, hex); w.bytes(k.noiseKey)
            val sk = k.signingKey ?: ByteArray(0); w.u8(sk.size).bytes(sk)
            str(w, k.nickname); w.u64(k.lastSeen)
        }
        w.u16(outbox.size)
        for (q in outbox) {
            str(w, q.messageId); str(w, q.peerHex)
            w.u16(q.plaintext.size).bytes(q.plaintext); w.u64(q.createdAt)
            w.u8(q.couriers.size); q.couriers.forEach { str(w, it) }
        }
        w.u16(carried.size)
        for (c in carried) {
            val env = c.envelope.withCopies(c.copies).encode() ?: ByteArray(0)
            w.u16(env.size).bytes(env)
            str(w, c.depositorKeyHex); w.u64(c.storedAt)
            w.u8(c.sprayedTo.size); c.sprayedTo.forEach { str(w, it) }
            w.u64(c.lastRemoteHandover)
        }
        val frames = history.mapNotNull { PacketCodec.encode(it) }
        w.u16(frames.size)
        for (f in frames) { w.u16(f.size).bytes(f) }
        return w.toByteArray()
    }

    companion object {
        private const val VERSION = 2

        private fun str(w: ByteWriter, s: String) { val b = s.toByteArray(Charsets.UTF_8); w.u16(b.size).bytes(b) }
        private fun str(r: ByteReader): String? { val n = r.u16() ?: return null; return String(r.bytes(n) ?: return null, Charsets.UTF_8) }

        fun deserialize(data: ByteArray): MeshState? {
            val r = ByteReader(data)
            val version = r.u8() ?: return null
            if (version < 1 || version > VERSION) return null
            val known = LinkedHashMap<String, KnownPeer>()
            repeat(r.u16() ?: return null) {
                val hex = str(r) ?: return null
                val noise = r.bytes(32) ?: return null
                val skLen = r.u8() ?: return null
                val sk = r.bytes(skLen) ?: return null
                val nick = str(r) ?: return null
                val seen = r.u64() ?: return null
                known[hex] = KnownPeer(noise, sk.takeIf { it.size == 32 }, nick, seen)
            }
            val outbox = ArrayList<QueuedMessage>()
            repeat(r.u16() ?: return null) {
                val id = str(r) ?: return null
                val peer = str(r) ?: return null
                val plain = r.bytes(r.u16() ?: return null) ?: return null
                val created = r.u64() ?: return null
                val couriers = LinkedHashSet<String>()
                repeat(r.u8() ?: return null) { couriers.add(str(r) ?: return null) }
                outbox.add(QueuedMessage(id, peer, plain, created, couriers))
            }
            val carried = ArrayList<CarriedEnvelope>()
            repeat(r.u16() ?: return null) {
                val env = CourierEnvelope.decode(r.bytes(r.u16() ?: return null) ?: return null)
                val dep = str(r) ?: return null
                val stored = r.u64() ?: return null
                val sprayed = LinkedHashSet<String>()
                repeat(r.u8() ?: return null) { sprayed.add(str(r) ?: return null) }
                val remote = r.u64() ?: return null
                if (env != null) carried.add(CarriedEnvelope(env, dep, stored, env.copies, sprayed, remote))
            }
            val history = ArrayList<Packet>()
            if (version >= 2) {
                repeat(r.u16() ?: return null) {
                    val frame = r.bytes(r.u16() ?: return null) ?: return null
                    PacketCodec.decode(frame)?.let { history.add(it) }
                }
            }
            return MeshState(known, outbox, carried, history)
        }
    }
}

internal fun String.keyBytes(): ByteArray? = hexToBytes()?.takeIf { it.size == 32 }
internal fun ByteArray.keyHex(): String = toHex()
