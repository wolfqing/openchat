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
) {
    @Volatile var key: NostrKey = keyFor(seed)
        private set
    val npub get() = Bech32.npub(key.publicKeyHex)
    private val pool = RelayPool(::onEvent)
    // Receipts go out one at a time: relays rate-limit bursts.
    private val pacer = Executors.newSingleThreadScheduledExecutor()
    private var nextSlot = 0L

    val connected get() = pool.connectedCount > 0

    fun start() {
        val filter = JSONObject()
            .put("kinds", JSONArray().put(PrivateEnvelope.KIND_WRAP))
            .put("#p", JSONArray().put(key.publicKeyHex))
            .put("since", System.currentTimeMillis() / 1000 - 2 * 86_400) // wraps carry a time ±15 min off
        pool.subscribe(SUB, RELAYS, filter)
    }

    fun send(recipientHex: String, content: String, paced: Boolean = false) {
        val ev = PrivateEnvelope.create(content, recipientHex, key, System.currentTimeMillis() / 1000)
        if (!paced) { pool.publish(RELAYS, ev); return }
        val now = System.currentTimeMillis()
        val at = synchronized(this) { maxOf(now, nextSlot).also { nextSlot = it + ACK_SPACING_MS } }
        pacer.schedule({ pool.publish(RELAYS, ev) }, at - now, TimeUnit.MILLISECONDS)
    }

    fun reset(newSeed: ByteArray) {
        pool.unsubscribe(SUB)
        seed = newSeed
        key = keyFor(newSeed)
        start()
    }

    fun close() = pool.closeAll()

    private fun onEvent(relay: String, ev: NostrEvent) {
        val opened = PrivateEnvelope.open(ev, key) ?: return
        onOpened(opened.senderPubkey, opened.content, opened.createdAt, ev.id)
    }

    companion object {
        private const val SUB = "dm"
        private const val ACK_SPACING_MS = 1_200L
        // The relays bitchat uses for private envelopes.
        val RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://offchain.pub")

        fun keyFor(seed: ByteArray): NostrKey =
            NostrKey.forGeohash(Crypto.hmacSha256(seed, "openchat-nostr-account".toByteArray(Charsets.US_ASCII)), "account")

        fun npubToHex(npub: String) = Bech32.npubToHex(npub)
    }
}
