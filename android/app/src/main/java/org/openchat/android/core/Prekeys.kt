package org.openchat.android.core

import org.openchat.android.crypto.Crypto

/**
 * One-time prekeys (docs/protocol-spec.md §27). A bundle publishes up to eight X25519
 * keys, signed by the owner, so mail for an absent person can be sealed to a key that is
 * deleted after use: forward secrecy for courier mail. TLVs: 1-byte type, 2-byte length.
 */
class PrekeyBundle(val noiseKey: ByteArray, val prekeys: List<Prekey>, val generatedAt: Long, val signature: ByteArray) {
    class Prekey(val id: Long, val publicKey: ByteArray)

    fun signingBytes(): ByteArray {
        val ctx = CONTEXT.toByteArray(Charsets.US_ASCII)
        val w = ByteWriter(64 + prekeys.size * 36).u8(ctx.size).bytes(ctx).bytes(noiseKey).u8(prekeys.size)
        prekeys.forEach { w.u32(it.id).bytes(it.publicKey) }
        return w.u64(generatedAt).toByteArray()
    }

    fun verify(signingKey: ByteArray) = Crypto.ed25519Verify(signingKey, signingBytes(), signature)

    fun encode(): ByteArray? {
        if (noiseKey.size != 32 || signature.size != 64 || prekeys.isEmpty() || prekeys.size > MAX || prekeys.any { it.publicKey.size != 32 }) return null
        val entries = ByteWriter(prekeys.size * 36).apply { prekeys.forEach { u32(it.id).bytes(it.publicKey) } }.toByteArray()
        return ByteWriter(entries.size + 120)
            .u8(1).u16(32).bytes(noiseKey)
            .u8(2).u16(entries.size).bytes(entries)
            .u8(3).u16(8).u64(generatedAt)
            .u8(4).u16(64).bytes(signature)
            .toByteArray()
    }

    companion object {
        const val MAX = 8
        private const val CONTEXT = "bitchat-prekey-bundle-v1"

        /** Noise X prologue for mail sealed to prekey [id], so a ciphertext cannot be moved to another prekey. */
        fun prologue(id: Long): ByteArray = ByteWriter(24).bytes("bitchat-prekey-v1".toByteArray(Charsets.US_ASCII)).u32(id).toByteArray()

        fun signed(noiseKey: ByteArray, prekeys: List<Prekey>, generatedAt: Long, signer: Crypto.Ed25519KeyPair): PrekeyBundle {
            val unsigned = PrekeyBundle(noiseKey, prekeys, generatedAt, ByteArray(64))
            return PrekeyBundle(noiseKey, prekeys, generatedAt, Crypto.ed25519Sign(signer.privateKey, unsigned.signingBytes()))
        }

        fun decode(b: ByteArray): PrekeyBundle? {
            var key: ByteArray? = null; var keys: List<Prekey>? = null; var at: Long? = null; var sig: ByteArray? = null
            val r = ByteReader(b)
            while (r.remaining > 0) {
                val t = r.u8() ?: return null
                val v = r.bytes(r.u16() ?: return null) ?: return null
                when (t) {
                    1 -> { if (v.size != 32) return null; key = v }
                    2 -> {
                        if (v.isEmpty() || v.size % 36 != 0 || v.size / 36 > MAX) return null
                        val e = ByteReader(v)
                        keys = List(v.size / 36) { Prekey(e.u32()!!, e.bytes(32)!!) }
                    }
                    3 -> { if (v.size != 8) return null; at = ByteReader(v).u64() }
                    4 -> { if (v.size != 64) return null; sig = v }
                }
            }
            val list = keys ?: return null
            // Duplicate IDs would let one spent ID shadow another.
            if (list.map { it.id }.toSet().size != list.size) return null
            return PrekeyBundle(key ?: return null, list, at ?: return null, sig ?: return null)
        }
    }
}

/**
 * Our own one-time prekeys. Eight unspent keys back the published bundle; below three it
 * tops up. A spent key's private part survives 48 h, because spray-and-wait can deliver
 * the same mail again days apart; unspent keys retire after 30 days. Every change gives
 * the bundle a strictly newer time, so peers replace their copy.
 */
class LocalPrekeys(private val clock: () -> Long) {
    class Record(val id: Long, val privateKey: ByteArray, val publicKey: ByteArray, val createdAt: Long, var consumedAt: Long? = null)

    private val records = ArrayList<Record>()
    private var nextId = 1L
    var generatedAt = 0L
        private set

    /** The unspent keys to publish, minting the first batch on first use. */
    fun bundleKeys(): List<PrekeyBundle.Prekey> {
        if (records.isEmpty()) replenish()
        return records.filter { it.consumedAt == null }.sortedBy { it.id }.map { PrekeyBundle.Prekey(it.id, it.publicKey) }
    }

    fun keyPair(id: Long): Crypto.X25519KeyPair? = records.firstOrNull { it.id == id }?.let { Crypto.X25519KeyPair(it.privateKey, it.publicKey) }

    /** True when this open retired the key; false for a redelivery of mail already opened. */
    fun markConsumed(id: Long): Boolean {
        val r = records.firstOrNull { it.id == id && it.consumedAt == null } ?: return false
        r.consumedAt = clock()
        bump()
        return true
    }

    /** Drops dead keys and tops up a low batch; true when the published bundle changed. */
    fun replenish(): Boolean {
        val now = clock()
        var changed = records.removeAll { r ->
            val dead = r.consumedAt?.let { now - it > CONSUMED_GRACE_MS } ?: (now - r.createdAt > UNSPENT_RETENTION_MS)
            if (dead) r.privateKey.fill(0)
            dead
        }
        val unspent = records.count { it.consumedAt == null }
        if (unspent < REPLENISH_BELOW) {
            repeat(PrekeyBundle.MAX - unspent) {
                val kp = Crypto.x25519Generate()
                records.add(Record(nextId++ and 0xFFFFFFFFL, kp.privateKey, kp.publicKey, now))
            }
            changed = true
        }
        if (changed) bump()
        return changed
    }

    private fun bump() { generatedAt = maxOf(clock(), generatedAt + 1) }

    fun clear() { records.forEach { it.privateKey.fill(0) }; records.clear(); nextId = 1; generatedAt = 0 }

    fun serialize(): ByteArray {
        val w = ByteWriter(16 + records.size * 90).u32(nextId).u64(generatedAt).u8(records.size)
        records.forEach { w.u32(it.id).bytes(it.privateKey).bytes(it.publicKey).u64(it.createdAt).u64(it.consumedAt ?: 0) }
        return w.toByteArray()
    }

    fun restore(b: ByteArray) {
        val r = ByteReader(b)
        val next = r.u32() ?: return; val at = r.u64() ?: return
        val list = ArrayList<Record>()
        repeat(r.u8() ?: return) {
            val id = r.u32() ?: return; val priv = r.bytes(32) ?: return; val pub = r.bytes(32) ?: return
            val created = r.u64() ?: return; val consumed = r.u64() ?: return
            list.add(Record(id, priv, pub, created, consumed.takeIf { it > 0 }))
        }
        clear(); records.addAll(list); nextId = next; generatedAt = at
    }

    companion object {
        const val REPLENISH_BELOW = 3
        const val CONSUMED_GRACE_MS = 48 * 3600_000L
        const val UNSPENT_RETENTION_MS = 30 * 24 * 3600_000L
    }
}

/**
 * Other people's verified bundles, newest per owner, at most 200 owners. A bundle older
 * than seven days is not sealed to. Each message gets one prekey, kept for re-deposits,
 * so one message spends exactly one key however many couriers carry it.
 */
class PrekeyCache(private val clock: () -> Long) {
    class Entry(val bundle: PrekeyBundle, val packet: Packet, val used: MutableSet<Long> = mutableSetOf(), val assigned: MutableMap<String, Long> = mutableMapOf(), var updatedAt: Long = 0)

    private val entries = LinkedHashMap<String, Entry>() // owner Noise key hex

    fun all(): List<Entry> = entries.values.toList()

    /** [bundle] is signature-checked; false when an equal or newer one is held. */
    fun ingest(bundle: PrekeyBundle, packet: Packet, restoredUsed: Set<Long> = emptySet(), restoredAssigned: Map<String, Long> = emptyMap()): Boolean {
        val owner = bundle.noiseKey.toHex()
        val old = entries[owner]
        if (old != null && old.bundle.generatedAt >= bundle.generatedAt) return false
        val ids = bundle.prekeys.map { it.id }.toSet()
        // A top-up keeps the owner's unspent keys: what we spent of those stays spent.
        val used = ((old?.used ?: emptySet()) + restoredUsed).filterTo(mutableSetOf()) { it in ids }
        val assigned = ((old?.assigned ?: emptyMap()) + restoredAssigned).filterValues { it in ids }.toMutableMap()
        entries.remove(owner)
        entries[owner] = Entry(bundle, packet, used, assigned, clock())
        while (entries.size > MAX_OWNERS) entries.remove(entries.values.minByOrNull { it.updatedAt }!!.bundle.noiseKey.toHex())
        return true
    }

    fun assign(messageId: String, recipientNoiseKey: ByteArray): PrekeyBundle.Prekey? {
        val e = entries[recipientNoiseKey.toHex()] ?: return null
        if (clock() - e.bundle.generatedAt > MAX_SEAL_AGE_MS) return null
        e.assigned[messageId]?.let { id -> return e.bundle.prekeys.firstOrNull { it.id == id } }
        val k = e.bundle.prekeys.filter { it.id !in e.used }.minByOrNull { it.id } ?: return null
        e.used.add(k.id); e.assigned[messageId] = k.id; e.updatedAt = clock()
        while (e.assigned.size > 64) e.assigned.remove(e.assigned.keys.first())
        return k
    }

    fun clear() = entries.clear()

    companion object {
        const val MAX_OWNERS = 200
        const val MAX_SEAL_AGE_MS = 7 * 24 * 3600_000L
    }
}
