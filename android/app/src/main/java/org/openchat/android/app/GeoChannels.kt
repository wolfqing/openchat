package org.openchat.android.app

import org.json.JSONArray
import org.json.JSONObject
import org.openchat.android.core.Mentions
import org.openchat.android.core.RateLimiter
import org.openchat.android.nostr.Pow
import org.openchat.android.nostr.Geohash
import org.openchat.android.nostr.NostrEvent
import org.openchat.android.nostr.NostrKey
import org.openchat.android.nostr.RelayDirectory
import org.openchat.android.nostr.RelayPool
import java.util.concurrent.ConcurrentHashMap

/**
 * Geohash location channels over Nostr (docs/protocol-spec.md §9). Each cell
 * uses its own derived key, so a user cannot be linked across cells.
 */
class GeoChannels(
    private val directory: RelayDirectory,
    @Volatile private var seed: ByteArray,
    private val nickname: () -> String,
    private val onMention: (from: String, text: String) -> Unit = { _, _ -> },
) {
    private class Joined(val relays: List<String>, val teleported: Boolean, val key: NostrKey)

    private val joined = ConcurrentHashMap<String, Joined>()
    private val presence = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>() // geohash -> pubkey -> last seen (s)
    private val pool = RelayPool(::onEvent).apply { onStatusChange = ::publishStatus }
    private val limiter = RateLimiter()
    // Mining proof of work takes up to two seconds; never on the caller's thread.
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Gateway hooks (§24.5): live channel events a gateway may carry onto the radio, and our own events when offline. */
    @Volatile var onLive: ((NostrEvent, String) -> Unit)? = null
    @Volatile var onOfflineSend: ((NostrEvent, String) -> Unit)? = null

    val online get() = pool.connectedCount > 0
    fun isJoined(geohash: String) = joined.containsKey(geohash)
    fun joinedSnapshot(): Map<String, Boolean> = joined.mapValues { it.value.teleported }

    /** A signed channel event that came over the radio: shown like one from a relay. */
    fun injectCarried(ev: NostrEvent) = onEvent(CARRIED, ev)

    /** Publishes someone else's signed event, deposited with us as their gateway. */
    fun publishCarried(ev: NostrEvent, geohash: String) = pool.publish(directory.closest(geohash, 5), ev)

    fun join(geohash: String, teleported: Boolean) {
        if (joined.containsKey(geohash)) return
        val candidates = directory.closest(geohash, CANDIDATE_RELAYS)
        val relays = candidates.take(PRIMARY_RELAYS)
        if (relays.isEmpty()) return
        joined[geohash] = Joined(relays, teleported, NostrKey.forGeohash(seed, geohash))
        subscribeChannel(geohash, relays, candidates.drop(PRIMARY_RELAYS))
        if (!teleported) heartbeat(geohash)
        publishStatus()
    }

    private fun subscribeChannel(geohash: String, primary: List<String>, fallbacks: List<String>) {
        val filter = JSONObject()
            .put("kinds", JSONArray().put(NostrEvent.KIND_GEOHASH_MESSAGE).put(NostrEvent.KIND_GEOHASH_PRESENCE))
            .put("#g", JSONArray().put(geohash))
            .put("since", System.currentTimeMillis() / 1000 - 3600)
            .put("limit", 200)
        pool.subscribe(subId(geohash), primary, filter, fallbackRelays = fallbacks)
    }

    /** Re-evaluates primary and backup relays for active subscriptions after a directory refresh. */
    fun onDirectoryUpdated() {
        joined.forEach { (g, old) ->
            val candidates = directory.closest(g, CANDIDATE_RELAYS)
            val primary = candidates.take(PRIMARY_RELAYS)
            if (primary.isNotEmpty() && primary != old.relays) {
                joined[g] = Joined(primary, old.teleported, old.key)
                subscribeChannel(g, primary, candidates.drop(PRIMARY_RELAYS))
            }
        }
        val cell = notesCell
        if (cell != null) {
            val candidates = directory.closest(cell, CANDIDATE_RELAYS)
            val primary = candidates.take(PRIMARY_RELAYS)
            if (primary.isNotEmpty()) {
                val cells = listOf(cell) + Geohash.neighbors(cell)
                val filter = JSONObject()
                    .put("kinds", JSONArray().put(NostrEvent.KIND_TEXT_NOTE))
                    .put("#g", JSONArray(cells))
                    .put("limit", 200)
                pool.subscribe(NOTES_SUB, primary, filter, fallbackRelays = candidates.drop(PRIMARY_RELAYS))
            }
        }
        publishStatus()
    }

    fun leave(geohash: String) {
        joined.remove(geohash) ?: return
        presence.remove(geohash)
        pool.unsubscribe(subId(geohash))
        publishStatus()
    }

    fun send(geohash: String, text: String) {
        val j = joined[geohash] ?: return
        val name = nickname()
        val localId = "pending-" + java.util.UUID.randomUUID()
        val now = System.currentTimeMillis()
        ChatRepository.add(ChatMessage(localId, Conversation.Geo(geohash).key, name, j.key.publicKeyHex, text, now, mine = true, state = DeliveryState.SENDING))
        sender.execute {
            val ev = NostrEvent.geohashMessage(j.key, geohash, name, text, j.teleported, mine = true)
            val targetRelays = pool.activeRelays(subId(geohash)).ifEmpty { j.relays }
            pool.publish(targetRelays, ev)
            // No relay reachable: a gateway phone on the mesh can publish it for us.
            if (!online) onOfflineSend?.invoke(ev, geohash)
            ChatRepository.replaceId(Conversation.Geo(geohash).key, localId, ev.id, DeliveryState.SENT)
        }
    }

    // ------------------------------------------------------------------ location notes (§17)

    @Volatile private var notesCell: String? = null
    private val notes = ConcurrentHashMap<String, LocationNote>()

    /** Lists notes at [cell] and its eight neighbours; null stops watching. */
    fun watchNotes(cell: String?) {
        if (cell == notesCell) return
        pool.unsubscribe(NOTES_SUB)
        notes.clear()
        notesCell = cell
        if (cell != null) {
            val candidates = directory.closest(cell, CANDIDATE_RELAYS)
            val primary = candidates.take(PRIMARY_RELAYS)
            if (primary.isNotEmpty()) {
                val cells = listOf(cell) + Geohash.neighbors(cell)
                val filter = JSONObject()
                    .put("kinds", JSONArray().put(NostrEvent.KIND_TEXT_NOTE))
                    .put("#g", JSONArray(cells))
                    .put("limit", 200)
                pool.subscribe(NOTES_SUB, primary, filter, fallbackRelays = candidates.drop(PRIMARY_RELAYS))
            }
        }
        publishNotes()
    }

    fun dropNote(cell: String, text: String) {
        val relays = pool.activeRelays(NOTES_SUB).ifEmpty { directory.closest(cell, PRIMARY_RELAYS) }
        if (relays.isEmpty()) return
        val key = NostrKey.forGeohash(seed, cell)
        val ev = NostrEvent.locationNote(key, cell, nickname(), text, System.currentTimeMillis() / 1000)
        pool.publish(relays, ev)
        if (cell == notesCell || Geohash.neighbors(cell).contains(notesCell)) onNote(ev)
    }

    private fun onNote(ev: NostrEvent) {
        val cell = notesCell ?: return
        val g = ev.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.get(1)?.lowercase() ?: return
        if (g != cell && g !in Geohash.neighbors(cell)) return
        val nowS = System.currentTimeMillis() / 1000
        val expires = ev.tag("expiration")?.toLongOrNull()
        if (expires != null && expires <= nowS) return
        if (ev.pubkey in Social.state.value.geoBlocked || ev.content.isBlank()) return
        notes[ev.id] = LocationNote(ev.id, ev.pubkey, ev.tag("n")?.take(32).orEmpty(), ev.content.take(1000), minOf(ev.createdAt, nowS) * 1000, g, ev.pubkey == NostrKey.forGeohash(seed, g).publicKeyHex)
        while (notes.size > 500) notes.values.minByOrNull { it.createdAt }?.let { notes.remove(it.id) }
        publishNotes()
    }

    private fun publishNotes() {
        val nowS = System.currentTimeMillis() / 1000
        ChatRepository.updateGeo { s -> s.copy(notesCell = notesCell, notes = notes.values.filter { it.createdAt / 1000 > nowS - 86_400 }.sortedByDescending { it.createdAt }) }
    }

    /** Presence heartbeat, only from people physically in the cell. Called periodically by the service. */
    fun heartbeatAll() = joined.forEach { (g, j) -> if (!j.teleported) heartbeat(g) }

    private fun heartbeat(geohash: String) {
        val j = joined[geohash] ?: return
        val targetRelays = pool.activeRelays(subId(geohash)).ifEmpty { j.relays }
        pool.publish(targetRelays, NostrEvent.geohashPresence(j.key, geohash))
    }

    /** Panic wipe: forget every channel and switch to the new seed. */
    fun reset(newSeed: ByteArray) {
        joined.keys.toList().forEach { pool.unsubscribe(subId(it)) }
        joined.clear(); presence.clear()
        pool.unsubscribe(NOTES_SUB); notes.clear(); notesCell = null
        pool.closeAll()
        seed = newSeed
        publishStatus()
    }

    fun close() {
        sender.shutdownNow()
        pool.shutdown()
    }

    private fun subId(geohash: String) = "geo-$geohash"

    private companion object {
        const val NOTES_SUB = "notes"
        const val CARRIED = "mesh"
        const val PRIMARY_RELAYS = 5
        const val CANDIDATE_RELAYS = 15
    }

    private fun onEvent(relay: String, ev: NostrEvent) {
        if (ev.kind == NostrEvent.KIND_TEXT_NOTE) return onNote(ev)
        val g = ev.tag("g") ?: return
        val j = joined[g] ?: return
        val nowS = System.currentTimeMillis() / 1000
        if (relay != CARRIED && ev.kind == NostrEvent.KIND_GEOHASH_MESSAGE) onLive?.invoke(ev, g)
        presence.getOrPut(g) { ConcurrentHashMap() }[ev.pubkey] = ev.createdAt
        if (ev.pubkey in Social.state.value.geoBlocked) return publishStatus()
        if (ev.kind == NostrEvent.KIND_GEOHASH_MESSAGE && ev.content.isNotBlank()) {
            val teleported = ev.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" }
            val mine = ev.pubkey == j.key.publicKeyHex
            val mentioned = !mine && Mentions.mentionsMe(ev.content, nickname(), j.key.publicKeyHex.takeLast(4), ambiguous = false)
            val conv = Conversation.Geo(g).key
            // Live traffic only: what relays replay on joining is history, and fine in a burst.
            if (!mine && ev.createdAt >= nowS - 60 && !limiter.allow(ev.pubkey, ev.content, Pow.validatedBits(ev))) return publishStatus()
            if (mentioned && ev.createdAt >= nowS - 300 && ChatRepository.openConversation != conv && ChatRepository.messages.value[conv].orEmpty().none { it.id == ev.id }) {
                onMention(PeerLabel.nostr(ev.tag("n"), ev.pubkey).toString() + " · #" + g, ev.content.take(200))
            }
            ChatRepository.add(
                ChatMessage(
                    id = ev.id, conversation = Conversation.Geo(g).key,
                    senderName = ev.tag("n")?.take(32).orEmpty(), senderKey = ev.pubkey,
                    text = ev.content.take(2000), timestamp = minOf(ev.createdAt, nowS) * 1000,
                    mine = mine, teleported = teleported, mentionsMe = mentioned,
                ),
            )
        }
        publishStatus()
    }

    private fun publishStatus() {
        val cutoff = System.currentTimeMillis() / 1000 - 300
        val joinedSnapshot = joined.keys.toSet()
        val connectedSubs = joinedSnapshot.filterTo(HashSet()) { pool.isSubConnected(subId(it)) }
        ChatRepository.updateGeo { s ->
            s.copy(
                relaysConnected = pool.connectedCount,
                connectedChannels = connectedSubs,
                joined = joinedSnapshot,
                participants = presence.mapValues { (_, m) -> m.values.count { it >= cutoff } },
            )
        }
    }
}
