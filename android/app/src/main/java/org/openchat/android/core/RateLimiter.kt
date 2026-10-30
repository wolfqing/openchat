package org.openchat.android.core

/**
 * Token buckets for incoming public messages (docs/protocol-spec.md §21.1):
 * one per sender, one per normalised text, so neither one person nor many
 * copies of one text can flood a channel. Same numbers as bitchat.
 */
class RateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val senderCapacity: Double = 5.0,
    private val senderRefillPerSec: Double = 1.0,
    private val contentCapacity: Double = 3.0,
    private val contentRefillPerSec: Double = 0.5,
    private val maxBuckets: Int = 2000,
    private val idleMs: Long = 10 * 60_000L,
) {
    private class Bucket(var tokens: Double, var last: Long)

    private val senders = LinkedHashMap<String, Bucket>()
    private val contents = LinkedHashMap<String, Bucket>()

    /**
     * True if the message may be shown. Enough proof of work ([powBits]) pays for the
     * sender's share; the per-text bucket always applies.
     */
    @Synchronized
    fun allow(senderKey: String, content: String, powBits: Int = 0): Boolean {
        val now = clock()
        if (powBits < POW_BYPASS_BITS && !take(senders, senderKey, senderCapacity, senderRefillPerSec, now)) return false
        // A rejected sender never reaches this line, so it cannot fill the text table.
        return take(contents, normalize(content), contentCapacity, contentRefillPerSec, now)
    }

    @Synchronized
    fun reset() { senders.clear(); contents.clear() }

    private fun take(map: LinkedHashMap<String, Bucket>, key: String, capacity: Double, refill: Double, now: Long): Boolean {
        val b = map[key] ?: run {
            if (map.size >= maxBuckets) {
                map.entries.removeAll { now - it.value.last >= idleMs }
                while (map.size >= maxBuckets) map.remove(map.keys.first())
            }
            Bucket(capacity, now).also { map[key] = it }
        }
        if (now > b.last) { b.tokens = minOf(capacity, b.tokens + (now - b.last) / 1000.0 * refill); b.last = now }
        if (b.tokens < 1.0) return false
        b.tokens -= 1.0
        return true
    }

    companion object {
        const val POW_BYPASS_BITS = 8
        private val URL_TAIL = Regex("""(https?://[^\s?#]+)[?#]\S*""", RegexOption.IGNORE_CASE)
        private val SPACE = Regex("""\s+""")

        /** Case, URL query strings and spacing do not make a text "different". */
        fun normalize(text: String): String =
            SPACE.replace(URL_TAIL.replace(text.lowercase(), "$1"), " ").trim().take(256)
    }
}
