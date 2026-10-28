package org.openchat.android.app

import org.json.JSONArray
import org.json.JSONObject
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
) {
    private class Joined(val relays: List<String>, val teleported: Boolean, val key: NostrKey)

    private val joined = ConcurrentHashMap<String, Joined>()
    private val presence = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>() // geohash -> pubkey -> last seen (s)
    private val pool = RelayPool(::onEvent)

    fun join(geohash: String, teleported: Boolean) {
        if (joined.containsKey(geohash)) return
        val relays = directory.closest(geohash, 5)
        if (relays.isEmpty()) return
        joined[geohash] = Joined(relays, teleported, NostrKey.forGeohash(seed, geohash))
        val filter = JSONObject()
            .put("kinds", JSONArray().put(NostrEvent.KIND_GEOHASH_MESSAGE).put(NostrEvent.KIND_GEOHASH_PRESENCE))
            .put("#g", JSONArray().put(geohash))
            .put("since", System.currentTimeMillis() / 1000 - 3600)
            .put("limit", 200)
        pool.subscribe(subId(geohash), relays, filter)
        if (!teleported) heartbeat(geohash)
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
        val ev = NostrEvent.geohashMessage(j.key, geohash, nickname(), text, j.teleported)
        pool.publish(j.relays, ev)
        ChatRepository.add(
            ChatMessage(ev.id, Conversation.Geo(geohash).key, nickname(), ev.pubkey, text, ev.createdAt * 1000, mine = true),
        )
    }

    /** Presence heartbeat, only from people physically in the cell. Called periodically by the service. */
    fun heartbeatAll() = joined.forEach { (g, j) -> if (!j.teleported) heartbeat(g) }

    private fun heartbeat(geohash: String) {
        val j = joined[geohash] ?: return
        pool.publish(j.relays, NostrEvent.geohashPresence(j.key, geohash))
    }

    /** Panic wipe: forget every channel and switch to the new seed. */
    fun reset(newSeed: ByteArray) {
        joined.keys.toList().forEach { pool.unsubscribe(subId(it)) }
        joined.clear(); presence.clear()
        pool.closeAll()
        seed = newSeed
        publishStatus()
    }

    fun close() = pool.closeAll()

    private fun subId(geohash: String) = "geo-$geohash"

    private fun onEvent(relay: String, ev: NostrEvent) {
        val g = ev.tag("g") ?: return
        val j = joined[g] ?: return
        val nowS = System.currentTimeMillis() / 1000
        presence.getOrPut(g) { ConcurrentHashMap() }[ev.pubkey] = ev.createdAt
        if (ev.kind == NostrEvent.KIND_GEOHASH_MESSAGE && ev.content.isNotBlank()) {
            val name = ev.tag("n")?.take(32) ?: ("anon#" + ev.pubkey.takeLast(4))
            val teleported = ev.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" }
            ChatRepository.add(
                ChatMessage(
                    id = ev.id, conversation = Conversation.Geo(g).key,
                    senderName = if (teleported) "$name ✈" else name, senderKey = ev.pubkey,
                    text = ev.content.take(2000), timestamp = minOf(ev.createdAt, nowS) * 1000,
                    mine = ev.pubkey == j.key.publicKeyHex,
                ),
            )
        }
        publishStatus()
    }

    private fun publishStatus() {
        val cutoff = System.currentTimeMillis() / 1000 - 300
        ChatRepository.updateGeo { s ->
            s.copy(
                relaysConnected = pool.connectedCount,
                joined = joined.keys.toSet(),
                participants = presence.mapValues { (_, m) -> m.values.count { it >= cutoff } },
            )
        }
    }
}
