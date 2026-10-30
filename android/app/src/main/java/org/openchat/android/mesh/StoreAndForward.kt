package org.openchat.android.mesh

import org.openchat.android.core.ByteReader
import org.openchat.android.core.ByteWriter
import org.openchat.android.core.BoardStore
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
    val board: List<BoardStore.Entry> = emptyList(), // signed posts and tombstones (§23)
    val localPrekeys: ByteArray? = null, // our one-time prekey privates (§27)
    val bundles: List<StoredBundle> = emptyList(), // others' verified prekey bundles
) {
    /** A cached bundle with what we already spent of it. */
    class StoredBundle(val packet: Packet, val used: Set<Long>, val assigned: Map<String, Long>)

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
        val boards = board.mapNotNull { e -> PacketCodec.encode(e.packet)?.let { it to e.retainUntil } }
        w.u16(boards.size)
        for ((f, until) in boards) { w.u16(f.size).bytes(f); w.u64(until) }
        val local = localPrekeys ?: ByteArray(0)
        w.u16(local.size).bytes(local)
        val stored = bundles.mapNotNull { b -> PacketCodec.encode(b.packet)?.let { it to b } }
        w.u16(stored.size)
        for ((f, b) in stored) {
            w.u16(f.size).bytes(f)
            w.u8(b.used.size); b.used.forEach { w.u32(it) }
            w.u8(b.assigned.size); b.assigned.forEach { (id, k) -> str(w, id); w.u32(k) }
        }
        return w.toByteArray()
    }

    companion object {
        private const val VERSION = 4

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
            val board = ArrayList<BoardStore.Entry>()
            if (version >= 3) {
                repeat(r.u16() ?: return null) {
                    val frame = r.bytes(r.u16() ?: return null) ?: return null
                    val until = r.u64() ?: return null
                    PacketCodec.decode(frame)?.let { board.add(BoardStore.Entry(it, until)) }
                }
            }
            var local: ByteArray? = null
            val bundles = ArrayList<StoredBundle>()
            if (version >= 4) {
                local = r.bytes(r.u16() ?: return null)?.takeIf { it.isNotEmpty() }
                repeat(r.u16() ?: return null) {
                    val frame = r.bytes(r.u16() ?: return null) ?: return null
                    val used = HashSet<Long>()
                    repeat(r.u8() ?: return null) { used.add(r.u32() ?: return null) }
                    val assigned = LinkedHashMap<String, Long>()
                    repeat(r.u8() ?: return null) { val id = str(r) ?: return null; assigned[id] = r.u32() ?: return null }
                    PacketCodec.decode(frame)?.let { bundles.add(StoredBundle(it, used, assigned)) }
                }
            }
            return MeshState(known, outbox, carried, history, board, local, bundles)
        }
    }
}

internal fun String.keyBytes(): ByteArray? = hexToBytes()?.takeIf { it.size == 32 }
internal fun ByteArray.keyHex(): String = toHex()
