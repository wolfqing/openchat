package org.openchat.android.nostr

import org.openchat.android.core.hexToBytes
import org.openchat.android.crypto.Crypto

/**
 * NIP-13 proof of work on location-channel messages (§21.2): a
 * `["nonce", value, target]` tag makes the event ID start with that many zero
 * bits. Received work is scored, never required.
 */
object Pow {
    const val TARGET_BITS = 8
    private const val TIME_CAP_MS = 2_000L
    private const val FALLBACK_CAP_MS = 150L

    fun leadingZeroBits(bytes: ByteArray): Int {
        var n = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0) { n += 8; continue }
            return n + Integer.numberOfLeadingZeros(v) - 24
        }
        return n
    }

    /** The committed difficulty if the ID really has it, else 0. */
    fun validatedBits(ev: NostrEvent): Int {
        val tag = ev.tags.lastOrNull { it.firstOrNull() == "nonce" } ?: return 0
        val committed = tag.getOrNull(2)?.toIntOrNull() ?: return 0
        if (committed !in 1..256) return 0
        val id = ev.id.hexToBytes() ?: return 0
        return if (leadingZeroBits(id) >= committed) committed else 0
    }

    /**
     * Finds a nonce tag for an event with these fields. Gives up on the target after two
     * seconds and retries at half the difficulty with a short budget, as bitchat does.
     */
    fun mine(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String, target: Int = TARGET_BITS,
             now: () -> Long = System::currentTimeMillis): List<String>? {
        var bits = target.coerceIn(0, 256)
        var budget = TIME_CAP_MS
        while (true) {
            val deadline = now() + budget
            var nonce = Crypto.random.nextLong()
            var tries = 0
            while (true) {
                val tag = listOf("nonce", "%016x".format(nonce), bits.toString())
                val id = NostrEvent.computeId(pubkey, createdAt, kind, tags + listOf(tag), content)
                if (leadingZeroBits(id.hexToBytes()!!) >= bits) return tag
                nonce++
                if (++tries % 256 == 0 && now() >= deadline) break
            }
            if (bits == 0) return null
            bits /= 2
            budget = FALLBACK_CAP_MS
        }
    }
}
