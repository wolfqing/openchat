package org.openchat.android.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.openchat.android.core.MeshMessageId
import org.openchat.android.core.NostrCarrier
import org.openchat.android.core.RateLimiter
import org.openchat.android.crypto.Crypto
import org.openchat.android.mesh.MeshEngine
import org.openchat.android.nostr.Geohash
import org.openchat.android.nostr.NostrEvent
import org.openchat.android.nostr.NostrKey
import org.openchat.android.nostr.RelayDirectory
import org.openchat.android.nostr.RelayPool
import org.openchat.android.nostr.Schnorr
import java.util.Base64

/** What the Me tab shows of the bridge. */
data class BridgeStatus(val enabled: Boolean = false, val cell: String? = null, val online: Boolean = false, val across: Int = 0)

/**
 * The mesh bridge (docs/protocol-spec.md §24), as bitchat runs it. Islands of the
 * Bluetooth mesh that share a neighbourhood cell meet on Nostr relays: each public
 * message is also signed by an unlinkable per-cell key and published there, and what
 * other islands publish comes back into Nearby. A bridging phone with internet also
 * serves its island: it publishes deposits from mesh-only phones and carries remote
 * messages onto the radio. Sealed mail for absent people is parked on relays as drops.
 * Runs on the mesh thread; relay callbacks are posted to it.
 */
class Bridge(
    private val context: Context,
    private val directory: RelayDirectory,
    private val engine: () -> MeshEngine,
    private val geo: () -> GeoChannels,
    private val post: (() -> Unit) -> Unit,
    private val publish: (BridgeStatus) -> Unit,
) {
    private class Ids(private val cap: Int = 512) {
        private val set = LinkedHashSet<String>()
        fun add(id: String): Boolean { val fresh = set.add(id); while (set.size > cap) set.remove(set.first()); return fresh }
        operator fun contains(id: String) = id in set
        fun clear() = set.clear()
    }

    private class Inbound(val eventId: String, val radioHint: String?, val nick: String?, val pubkey: String, val content: String, val at: Long)

    private val pool = RelayPool { _, ev -> post { onRelayEvent(ev) } }
    private val prefs get() = context.getSharedPreferences("net", Context.MODE_PRIVATE)
    var enabled = prefs.getBoolean(KEY, false)
        private set

    private var cell: String? = null
    private var subscribed: Set<String> = emptySet()
    private val published = Ids()     // loop rule 2: what this phone sent up
    private val meshCarried = Ids()   // loop rule 1: what reached us over the radio
    private val rebroadcast = Ids()   // loop rule 3: carried down once at most
    private val received = Ids()
    private val radioSeen = Ids(1000) // stable IDs of messages heard on the radio
    private val aliases = LinkedHashMap<String, String>() // injected event ID -> radio stable ID
    private val pendingDown = ArrayDeque<Pair<NostrEvent, String>>()
    private val downTimes = ArrayDeque<Long>()
    private var drainArmed = false
    private val queuedUp = ArrayList<Triple<String, String, NostrEvent>>() // depositor, cell, event
    private val upTimes = HashMap<String, ArrayDeque<Long>>()
    private val participants = LinkedHashMap<String, Long>()
    private val limiter = RateLimiter(senderCapacity = 20.0, senderRefillPerSec = 2.0)
    private var lastPresence = 0L
    private var dropTags: Set<String> = emptySet()
    private var lastDropRefresh = 0L
    private val dropsSeen = Ids()
    private val pendingDrops = ArrayDeque<NostrEvent>()
    // Location-channel gateway (§24.5).
    private val pendingGw = ArrayDeque<Pair<NostrEvent, String>>()
    private val gwTimes = ArrayDeque<Long>()
    private var gwDrainArmed = false
    private val queuedGw = ArrayList<Triple<String, String, NostrEvent>>() // depositor, channel, event

    private val online get() = pool.connectedCount > 0
    private val internet get() = online || geo().online

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        enabled = on
        if (!on) shutDown()
        tick()
    }

    /** Called every few seconds on the mesh thread: cell, subscriptions, presence, queues. */
    fun tick() {
        if (!enabled) { publishStatus(); return }
        val next = currentCell()
        if (next != cell) {
            pool.unsubscribe(SUB)
            cell = next
            subscribed = if (next == null) emptySet() else (listOf(next) + Geohash.neighbors(next)).toSet()
            if (next != null) {
                // Both sides subscribe to their own cell and its ring, so islands across a cell edge still meet.
                pool.subscribe(SUB, directory.closest(next, 5), JSONObject()
                    .put("kinds", JSONArray().put(NostrEvent.KIND_GEOHASH_MESSAGE).put(NostrEvent.KIND_GEOHASH_PRESENCE))
                    .put("#r", JSONArray(subscribed.toList()))
                    .put("since", System.currentTimeMillis() / 1000 - MAX_AGE_S))
            }
        }
        // Advertise only while able to serve: a mesh-only phone must not attract deposits.
        engine().setBridgeCell(if (online) cell else null)
        engine().setGateway(internet)
        if (geo().online && queuedGw.isNotEmpty()) {
            queuedGw.forEach { (_, g, ev) -> if (published.add(ev.id)) geo().publishCarried(ev, g) }
            queuedGw.clear()
        }
        val now = System.currentTimeMillis()
        if (online && cell != null && now - lastPresence > PRESENCE_MS) {
            lastPresence = now
            val c = cell!!
            NostrEvent.create(cellKey(c), NostrEvent.KIND_GEOHASH_PRESENCE, listOf(listOf("r", c)), "").also { published.add(it.id); pool.publish(directory.closest(c, 5), it) }
        }
        if (online && queuedUp.isNotEmpty()) {
            queuedUp.toList().forEach { (_, c, ev) -> if (!(ev.id in published)) { published.add(ev.id); pool.publish(directory.closest(c, 5), ev) } }
            queuedUp.clear()
        }
        refreshDrops(now)
        if (online) while (pendingDrops.isNotEmpty()) pool.publish(NostrDirect.RELAYS, pendingDrops.removeFirst())
        participants.values.removeAll { now - it > PARTICIPANT_MS }
        publishStatus()
    }

    /** Our own location's neighbourhood cell, else the one a bridging neighbour advertises. */
    private fun currentCell(): String? =
        ChatRepository.geo.value.locationGeohash?.take(CELL)?.takeIf { it.length == CELL }
            ?: engine().bridgePeers().firstNotNullOfOrNull { it.second }?.take(CELL)?.takeIf { it.length == CELL && Geohash.isValid(it) }

    private fun cellKey(c: String) = NostrKey.forGeohash(engine().identity.nostrSeed, c)

    // ------------------------------------------------------------------ sender

    /** A public mesh message of ours was just sent at [timestampMs]; ship its bridged copy. */
    fun outgoing(text: String, timestampMs: Long) {
        val me = engine().identity.peerIdHex
        radioSeen.add(MeshMessageId.stable(me, timestampMs, text))
        if (!enabled || text.toByteArray(Charsets.UTF_8).size > MAX_CONTENT) return
        val c = cell ?: currentCell() ?: return
        val tags = mutableListOf(listOf("r", c))
        engine().identity.nickname.trim().takeIf { it.isNotEmpty() }?.let { tags.add(listOf("n", it)) }
        tags.add(listOf("m", MeshMessageId.stable(me, timestampMs, text), me, timestampMs.toString()))
        val ev = NostrEvent.create(cellKey(c), NostrEvent.KIND_GEOHASH_MESSAGE, tags, text)
        published.add(ev.id)
        if (online) { pool.publish(directory.closest(c, 5), ev); return }
        // Mesh-only: hand the finished, signed event to a bridging neighbour to publish.
        val gateway = engine().bridgePeers().firstOrNull()?.first ?: return
        NostrCarrier(NostrCarrier.TO_BRIDGE, c, ev.toJson().toByteArray(Charsets.UTF_8)).encode()?.let { engine().sendCarrier(it, gateway) }
    }

    /** A verified radio copy arrived: it wins over any bridged row that claimed the same message. */
    fun onRadioMessage(senderHex: String, timestampMs: Long, text: String) {
        val id = MeshMessageId.stable(senderHex, timestampMs, text)
        radioSeen.add(id)
        aliases.entries.filter { it.value == id }.map { it.key }.forEach { ChatRepository.remove(Conversation.Mesh.key, it); aliases.remove(it) }
        pendingDown.removeAll { (ev, c) -> classify(ev, c)?.radioHint == id }
    }

    // ------------------------------------------------------------------ relays in

    private fun onRelayEvent(ev: NostrEvent) {
        if (ev.kind == KIND_DROP) return onDrop(ev)
        if (!enabled) return
        val c = ev.tags.firstOrNull { it.size >= 2 && it[0] == "r" }?.get(1) ?: return
        if (c !in subscribed || ev.id in published) return
        if (ev.pubkey == cellKey(c).publicKeyHex) { published.add(ev.id); return } // our own, after a restart
        if (!received.add(ev.id) || !limiter.allow(ev.pubkey, ev.content.ifEmpty { ev.id })) return
        if (ev.kind == NostrEvent.KIND_GEOHASH_PRESENCE) { participants[ev.pubkey] = System.currentTimeMillis(); return }
        val m = classify(ev, c) ?: return
        val local = m.radioHint?.let { it in radioSeen } == true
        if (!local) inject(m)
        // Serving: carry other islands' messages onto the radio, after a short holdoff in which
        // another bridging phone's broadcast can make ours unnecessary.
        if (!local && ev.id !in meshCarried && ev.id !in rebroadcast && pendingDown.none { it.first.id == ev.id }) {
            pendingDown.addLast(ev to c)
            while (pendingDown.size > MAX_PENDING_DOWN) pendingDown.removeFirst()
            armDrain(200L + Crypto.random.nextInt(1300))
        }
    }

    private fun armDrain(delayMs: Long) {
        if (drainArmed || pendingDown.isEmpty()) return
        drainArmed = true
        android.os.Handler(android.os.Looper.myLooper() ?: return).postDelayed({ drainArmed = false; drain() }, delayMs)
    }

    private fun drain() {
        val now = System.currentTimeMillis()
        while (downTimes.isNotEmpty() && now - downTimes.first() > 60_000) downTimes.removeFirst()
        while (pendingDown.isNotEmpty() && downTimes.size < DOWN_PER_MINUTE) {
            val (ev, c) = pendingDown.removeFirst()
            val m = classify(ev, c) ?: continue
            if (ev.id in meshCarried || ev.id in rebroadcast || m.radioHint?.let { it in radioSeen } == true) continue
            val payload = NostrCarrier(NostrCarrier.FROM_BRIDGE, c, ev.toJson().toByteArray(Charsets.UTF_8)).encode() ?: continue
            engine().sendCarrier(payload, null)
            rebroadcast.add(ev.id)
            downTimes.addLast(now)
        }
        if (pendingDown.isNotEmpty()) armDrain(maxOf(50L, 60_000 - (now - (downTimes.firstOrNull() ?: now))))
    }

    // ------------------------------------------------------------------ radio in

    fun onCarrier(senderHex: String, payload: ByteArray, directed: Boolean) {
        val carrier = NostrCarrier.decode(payload) ?: return
        val ev = event(carrier) ?: return
        if (carrier.direction == NostrCarrier.TO_GATEWAY || carrier.direction == NostrCarrier.FROM_GATEWAY) return onGatewayCarrier(senderHex, carrier, ev, directed)
        val m = classify(ev, carrier.geohash) ?: return
        when {
            carrier.direction == NostrCarrier.TO_BRIDGE && directed -> uplink(senderHex, carrier.geohash, ev)
            carrier.direction == NostrCarrier.FROM_BRIDGE && !directed -> {
                // Receiving over the radio needs no toggle: it is passive and reveals nothing.
                if (ev.id in published || ev.pubkey == cellKey(carrier.geohash).publicKeyHex || !ev.isValid()) return
                if (!meshCarried.add(ev.id) || !received.add(ev.id)) return
                if (!limiter.allow(ev.pubkey, ev.content)) return
                inject(m)
            }
        }
    }

    private fun uplink(depositor: String, c: String, ev: NostrEvent) {
        if (!enabled || ev.id in meshCarried || ev.id in published || queuedUp.any { it.third.id == ev.id }) return
        val now = System.currentTimeMillis()
        val times = upTimes.getOrPut(depositor) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
        if (times.size >= UP_PER_MINUTE || !ev.isValid()) return
        times.addLast(now)
        if (online) { published.add(ev.id); pool.publish(directory.closest(c, 5), ev); return }
        if (queuedUp.count { it.first == depositor } >= 5) return
        queuedUp.add(Triple(depositor, c, ev))
        while (queuedUp.size > 20) queuedUp.removeAt(0)
    }

    // ------------------------------------------------------------------ location-channel gateway (§24.5)

    /** Carried location-channel events: the cell's own kind, its `g` tag, fresh. Signature checked by the caller. */
    private fun channelEvent(ev: NostrEvent, g: String) =
        ev.kind == NostrEvent.KIND_GEOHASH_MESSAGE && Geohash.isValid(g) && ev.tag("g") == g &&
            Math.abs(System.currentTimeMillis() / 1000 - ev.createdAt) <= MAX_AGE_S

    private fun onGatewayCarrier(senderHex: String, c: NostrCarrier, ev: NostrEvent, directed: Boolean) {
        if (!channelEvent(ev, c.geohash)) return
        if (c.direction == NostrCarrier.TO_GATEWAY && directed) {
            // Deposit: publish it (cheap checks and the depositor's budget before the signature).
            if (!enabled || ev.id in meshCarried || ev.id in published || queuedGw.any { it.third.id == ev.id }) return
            val now = System.currentTimeMillis()
            val times = upTimes.getOrPut(senderHex) { ArrayDeque() }
            while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
            if (times.size >= UP_PER_MINUTE || !ev.isValid()) return
            times.addLast(now)
            if (geo().online) { published.add(ev.id); geo().publishCarried(ev, c.geohash) }
            else if (queuedGw.count { it.first == senderHex } < 5) {
                queuedGw.add(Triple(senderHex, c.geohash, ev))
                while (queuedGw.size > 20) queuedGw.removeAt(0)
            } else return
            if (geo().isJoined(c.geohash)) geo().injectCarried(ev)
        } else if (c.direction == NostrCarrier.FROM_GATEWAY && !directed) {
            // Receiving needs no toggle. Marked after the signature check, so a forgery cannot poison the set.
            if (ev.id in published || !ev.isValid() || !meshCarried.add(ev.id)) return
            if (geo().isJoined(c.geohash)) geo().injectCarried(ev)
        }
    }

    /** A live event on a channel we are in: as a gateway, carry it onto the radio for phones without internet. */
    fun onChannelEvent(ev: NostrEvent, g: String) {
        if (!enabled || !channelEvent(ev, g)) return
        if (ev.id in meshCarried || ev.id in published || ev.id in rebroadcast || pendingGw.any { it.first.id == ev.id }) return
        if (!ev.isValid()) return
        pendingGw.addLast(ev to g)
        while (pendingGw.size > MAX_PENDING_DOWN) pendingGw.removeFirst()
        drainGateway()
    }

    private fun drainGateway() {
        val now = System.currentTimeMillis()
        while (gwTimes.isNotEmpty() && now - gwTimes.first() > 60_000) gwTimes.removeFirst()
        while (pendingGw.isNotEmpty() && gwTimes.size < GATEWAY_PER_MINUTE) {
            val (ev, g) = pendingGw.removeFirst()
            if (!channelEvent(ev, g)) continue
            val payload = NostrCarrier(NostrCarrier.FROM_GATEWAY, g, ev.toJson().toByteArray(Charsets.UTF_8)).encode() ?: continue
            engine().sendCarrier(payload, null)
            rebroadcast.add(ev.id) // only once actually sent, so an overflowed event can come again
            gwTimes.addLast(now)
        }
        if (pendingGw.isEmpty() || gwDrainArmed) return
        gwDrainArmed = true
        val wait = maxOf(50L, 60_000 - (now - (gwTimes.firstOrNull() ?: now)))
        android.os.Handler(android.os.Looper.myLooper() ?: return).postDelayed({ gwDrainArmed = false; drainGateway() }, wait)
    }

    /** Our own channel event found no relay: hand it to a gateway on the mesh. Needs no toggle. */
    fun uplinkChannelEvent(ev: NostrEvent, g: String) {
        if (ev.id in meshCarried) return
        published.add(ev.id)
        val gateway = engine().gatewayPeers().firstOrNull() ?: return
        NostrCarrier(NostrCarrier.TO_GATEWAY, g, ev.toJson().toByteArray(Charsets.UTF_8)).encode()?.let { engine().sendCarrier(it, gateway) }
    }

    // ------------------------------------------------------------------ shared

    private fun event(c: NostrCarrier): NostrEvent? = try {
        if (!Geohash.isValid(c.geohash)) null else NostrEvent.fromJson(JSONObject(String(c.eventJson, Charsets.UTF_8)))
    } catch (e: Exception) { null }

    /** Fresh, inside its own `r` cell, the right kind and size. Signature is checked by the caller. */
    private fun classify(ev: NostrEvent, c: String): Inbound? {
        if (Math.abs(System.currentTimeMillis() / 1000 - ev.createdAt) > MAX_AGE_S) return null
        if (ev.tags.none { it.size >= 2 && it[0] == "r" && it[1] == c } || !Geohash.isValid(c)) return null
        if (ev.kind != NostrEvent.KIND_GEOHASH_MESSAGE || ev.content.isBlank() || ev.content.toByteArray(Charsets.UTF_8).size > MAX_CONTENT) return null
        val m = ev.tags.firstOrNull { it.size >= 4 && it[0] == "m" }
        // The m tag's mesh coordinates are public, so they can only spot a radio duplicate, never own dedup.
        val hint = if (m != null && m[2].length == 16 && m[2].all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            m[3].toLongOrNull()?.let { MeshMessageId.stable(m[2], it, ev.content) }
        } else null
        val nick = ev.tag("n")?.trim()?.take(32)?.ifEmpty { null }
        return Inbound(ev.id, hint, nick, ev.pubkey, ev.content.take(2000), minOf(ev.createdAt * 1000, System.currentTimeMillis()))
    }

    private fun inject(m: Inbound) {
        if (Social.state.value.geoBlocked.containsKey(m.pubkey)) return
        ChatRepository.add(ChatMessage(m.eventId, Conversation.Mesh.key, m.nick.orEmpty(), m.pubkey, m.content, m.at, mine = false, bridged = true))
        participants[m.pubkey] = System.currentTimeMillis()
        m.radioHint?.let { aliases[m.eventId] = it; while (aliases.size > 500) aliases.remove(aliases.keys.first()) }
    }

    // ------------------------------------------------------------------ courier drops (§24.4)

    /** Parks sealed mail for an absent peer on the private-message relays, under a throwaway key. */
    fun deposit(envelope: ByteArray, tagHex: String, expiresAt: Long) {
        if (!enabled || envelope.size > MAX_DROP_BYTES) return
        val key = NostrKey(generateSequence { Crypto.randomBytes(32) }.first { Schnorr.isValidSecret(it) })
        val ev = NostrEvent.create(key, KIND_DROP, listOf(listOf("x", tagHex), listOf("expiration", (expiresAt / 1000).toString())),
            Base64.getEncoder().encodeToString(envelope))
        dropsSeen.add(ev.id)
        if (online) pool.publish(NostrDirect.RELAYS, ev) else { pendingDrops.addLast(ev); while (pendingDrops.size > 20) pendingDrops.removeFirst() }
    }

    private fun refreshDrops(now: Long) {
        if (!enabled || now - lastDropRefresh < 60_000) return
        lastDropRefresh = now
        val tags = engine().dropTags()
        if (tags == dropTags) return
        dropTags = tags
        pool.subscribe(DROP_SUB, NostrDirect.RELAYS, JSONObject()
            .put("kinds", JSONArray().put(KIND_DROP))
            .put("#x", JSONArray(tags.toList()))
            .put("since", now / 1000 - 24 * 3600))
    }

    private fun onDrop(ev: NostrEvent) {
        if (!enabled || !dropsSeen.add(ev.id) || ev.content.length > MAX_DROP_BYTES * 2) return
        val bytes = try { Base64.getDecoder().decode(ev.content) } catch (e: Exception) { return }
        if (bytes.size <= MAX_DROP_BYTES) engine().receiveDrop(bytes)
    }

    // ------------------------------------------------------------------ lifecycle

    private fun publishStatus() {
        val now = System.currentTimeMillis()
        publish(BridgeStatus(enabled, cell, online, participants.count { now - it.value <= PARTICIPANT_MS }))
    }

    private fun shutDown() {
        pool.unsubscribe(SUB); pool.unsubscribe(DROP_SUB)
        cell = null; subscribed = emptySet(); dropTags = emptySet(); lastDropRefresh = 0
        pendingDown.clear(); queuedUp.clear(); pendingDrops.clear(); participants.clear()
        pendingGw.clear(); queuedGw.clear()
        engine().setBridgeCell(null); engine().setGateway(false)
    }

    /** Panic wipe: off, forgotten, back to the default. */
    fun wipe() {
        shutDown()
        prefs.edit().remove(KEY).apply()
        enabled = false
        listOf(published, meshCarried, rebroadcast, received, radioSeen, dropsSeen).forEach { it.clear() }
        aliases.clear(); limiter.reset()
        publishStatus()
    }

    fun close() = pool.closeAll()

    companion object {
        private const val KEY = "bridge"
        private const val SUB = "bridge"
        private const val DROP_SUB = "drops"
        const val CELL = 6
        const val KIND_DROP = 1401
        private const val MAX_AGE_S = 15 * 60L
        private const val MAX_CONTENT = 16_000
        private const val MAX_DROP_BYTES = 20 * 1024
        private const val MAX_PENDING_DOWN = 30
        private const val DOWN_PER_MINUTE = 20
        private const val GATEWAY_PER_MINUTE = 30
        private const val UP_PER_MINUTE = 10
        private const val PRESENCE_MS = 4 * 60_000L
        private const val PARTICIPANT_MS = 10 * 60_000L
    }
}
