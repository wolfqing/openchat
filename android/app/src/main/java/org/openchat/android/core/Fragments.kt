package org.openchat.android.core

import java.security.SecureRandom

/** Fragmentation and reassembly (docs/protocol-spec.md §6). */
object Fragmenter {
    const val DEFAULT_CHUNK = 469
    const val MIN_CHUNK = 64
    private val random = SecureRandom()

    /** Splits an already-built packet into fragment packets. Returns null when encoding fails. */
    fun split(original: Packet, chunkSize: Int = DEFAULT_CHUNK, fragmentId: ByteArray = randomId()): List<Packet>? {
        val full = PacketCodec.encode(original) ?: return null
        val size = maxOf(MIN_CHUNK, chunkSize)
        val chunks = (full.indices step size).map { full.copyOfRange(it, minOf(it + size, full.size)) }
        if (chunks.size > 0xFFFF) return null
        val version = if (!original.route.isNullOrEmpty()) 2 else 1
        return chunks.mapIndexed { i, chunk ->
            val payload = ByteWriter(chunk.size + 13)
                .bytes(fragmentId).u16(i).u16(chunks.size).u8(original.type).bytes(chunk).toByteArray()
            Packet(
                version = version, type = MessageType.FRAGMENT, senderId = original.senderId,
                recipientId = original.recipientId, timestamp = original.timestamp, payload = payload,
                signature = null, ttl = original.ttl, route = original.route, isRsr = original.isRsr,
            )
        }
    }

    fun randomId(): ByteArray = ByteArray(8).also { random.nextBytes(it) }
}

class FragmentAssembler(
    private val maxAssemblies: Int = 128,
    private val lifetimeMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Assembly(val total: Int, val type: Int, val createdAt: Long) {
        val chunks = HashMap<Int, ByteArray>()
        var bytes = 0
    }

    private val assemblies = LinkedHashMap<String, Assembly>()

    /** Feeds one fragment packet. Returns the reassembled original packet when the last chunk arrives. */
    @Synchronized
    fun accept(fragment: Packet): Packet? {
        val r = ByteReader(fragment.payload)
        val fid = r.bytes(8) ?: return null
        val index = r.u16() ?: return null
        val total = r.u16() ?: return null
        val type = r.u8() ?: return null
        val chunk = r.bytes(r.remaining) ?: return null
        if (total == 0 || index >= total) return null

        expire()
        val key = fragment.senderId.toHex() + ":" + fid.toHex()
        val asm = assemblies[key] ?: run {
            if (assemblies.size >= maxAssemblies) assemblies.remove(assemblies.keys.first())
            Assembly(total, type, clock()).also { assemblies[key] = it }
        }
        if (asm.total != total || asm.type != type) return null
        if (asm.chunks.containsKey(index)) return null
        if (asm.bytes + chunk.size > MessageType.maxFrameBytes(type)) { assemblies.remove(key); return null }
        asm.chunks[index] = chunk
        asm.bytes += chunk.size
        if (asm.chunks.size < total) return null

        assemblies.remove(key)
        val w = ByteWriter(asm.bytes)
        for (i in 0 until total) w.bytes(asm.chunks[i]!!)
        return PacketCodec.decode(w.toByteArray())
    }

    private fun expire() {
        val now = clock()
        assemblies.entries.removeAll { now - it.value.createdAt > lifetimeMs }
    }
}
