package org.openchat.android.app

import org.json.JSONArray
import org.json.JSONObject
import org.openchat.android.crypto.Crypto
import org.openchat.android.nostr.Bech32
import org.openchat.android.nostr.NostrEvent
import org.openchat.android.nostr.NostrKey
import org.openchat.android.nostr.PrivateEnvelope
import org.openchat.android.nostr.RelayPool
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Private messages between mutual favorites over Nostr relays
 * (docs/protocol-spec.md §15). One account key per device, derived from the
 * identity seed, so a panic wipe replaces it.
 */
class NostrDirect(
    @Volatile private var seed: ByteArray,
    private val onOpened: (senderPubkey: String, content: String, createdAt: Long, wrapId: String) -> Unit,
    /** A wrap opened by a location-channel persona rather than the account key (§15.5). */
    private val onOpenedInbox: (geohash: String, senderPubkey: String, content: String, createdAt: Long) -> Unit = { _, _, _, _ -> },
) {
    private val inboxes = java.util.concurrent.ConcurrentHashMap<String, NostrKey>() // geohash -> per-cell key
    @Volatile var key: NostrKey = keyFor(seed)
        private set
    val npub get() = Bech32.npub(key.publicKeyHex)
    private val pool = RelayPool(::onEvent)
    // Receipts go out one at a time: relays rate-limit bursts.
    private var pacer = Executors.newSingleThreadScheduledExecutor()
    private var nextSlot = 0L

    val connected get() = pool.connectedCount > 0

    /** Built-in relays plus the user's own (§15.4), so blocking four host names does not end private messages. */
    @Volatile private var relays: List<String> = RELAYS

    private fun wraps(pubkey: String) = JSONObject()
        .put("kinds", JSONArray().put(PrivateEnvelope.KIND_WRAP))
        .put("#p", JSONArray().put(pubkey))
        .put("since", System.currentTimeMillis() / 1000 - 2 * 86_400) // wraps carry a time ±15 min off

    fun start() = pool.subscribe(SUB, relays, wraps(key.publicKeyHex))

    fun setExtraRelays(extra: List<String>) {
        val next = (RELAYS + extra).distinct()
        if (next == relays) return
        relays = next
        // Re-subscribe from scratch so a removed relay is also closed.
        pool.unsubscribe(SUB); inboxes.keys.forEach { pool.unsubscribe(inboxSub(it)) }
        pool.subscribe(SUB, relays, wraps(key.publicKeyHex))
        inboxes.forEach { (g, k) -> pool.subscribe(inboxSub(g), relays, wraps(k.publicKeyHex)) }
    }

    /** Also receive wraps addressed to this location-channel persona. */
    fun addInbox(geohash: String, cellKey: NostrKey) {
        if (inboxes.put(geohash, cellKey)?.publicKeyHex == cellKey.publicKeyHex) return
        pool.subscribe(inboxSub(geohash), relays, wraps(cellKey.publicKeyHex))
    }

    fun removeInbox(geohash: String) { if (inboxes.remove(geohash) != null) pool.unsubscribe(inboxSub(geohash)) }

    private fun inboxSub(geohash: String) = "dm-$geohash"

    fun send(recipientHex: String, content: String, paced: Boolean = false, from: NostrKey = key) {
        val ev = PrivateEnvelope.create(content, recipientHex, from, System.currentTimeMillis() / 1000)
        if (!paced) { pool.publish(relays, ev); return }
        val now = System.currentTimeMillis()
        val at = synchronized(this) { maxOf(now, nextSlot).also { nextSlot = it + ACK_SPACING_MS } }
        synchronized(this) { pacer }.schedule({ pool.publish(relays, ev) }, at - now, TimeUnit.MILLISECONDS)
    }

    fun reset(newSeed: ByteArray) {
        pool.unsubscribe(SUB)
        inboxes.keys.toList().forEach { removeInbox(it) }
        // Receipts still waiting their turn were sealed by the old key: they must not go out as it.
        synchronized(this) { pacer.shutdownNow(); pacer = Executors.newSingleThreadScheduledExecutor(); nextSlot = 0L }
        seed = newSeed
        key = keyFor(newSeed)
        start()
    }

    fun close() { synchronized(this) { pacer.shutdownNow() }; pool.closeAll() }

    private fun onEvent(relay: String, ev: NostrEvent) {
        val p = ev.tag("p") ?: return
        if (p == key.publicKeyHex) {
            val opened = PrivateEnvelope.open(ev, key) ?: return
            onOpened(opened.senderPubkey, opened.content, opened.createdAt, ev.id)
            return
        }
        val (geohash, cellKey) = inboxes.entries.firstOrNull { it.value.publicKeyHex == p }?.toPair() ?: return
        val opened = PrivateEnvelope.open(ev, cellKey) ?: return
        onOpenedInbox(geohash, opened.senderPubkey, opened.content, opened.createdAt)
    }

    companion object {
        private const val SUB = "dm"
        private const val ACK_SPACING_MS = 1_200L
        // The relays bitchat uses for private envelopes.
        val RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://offchain.pub")

        fun keyFor(seed: ByteArray): NostrKey =
            NostrKey.forGeohash(Crypto.hmacSha256(seed, "openchat-nostr-account".toByteArray(Charsets.US_ASCII)), "account")

        fun npubToHex(npub: String) = Bech32.npubToHex(npub)

        const val MAX_EXTRA_RELAYS = 8

        /** A usable relay address, normalised, or null. Plain ws:// is allowed only for .onion. */
        fun normalizeRelay(input: String): String? {
            val s = input.trim().trimEnd('/').let { if ("://" in it) it else "wss://$it" }
            val uri = try { java.net.URI(s) } catch (e: Exception) { return null }
            val host = uri.host?.lowercase() ?: return null
            if (!host.contains('.') || s.length > 200 || uri.userInfo != null) return null
            return when (uri.scheme?.lowercase()) {
                "wss" -> "wss://" + host + (if (uri.port > 0) ":${uri.port}" else "") + (uri.rawPath ?: "")
                "ws" -> if (host.endsWith(".onion")) "ws://" + host + (if (uri.port > 0) ":${uri.port}" else "") + (uri.rawPath ?: "") else null
                else -> null
            }
        }
    }
}
