package org.openchat.android.nostr

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Minimal NIP-01 relay pool for geohash channels: one WebSocket per relay,
 * replayed subscriptions on reconnect, automatic backup-relay failover when
 * primary relays time out or reject Tor exits, exponential backoff, event dedup.
 */
class RelayPool(
    private val failoverGraceMs: Long = 6_000L,
    private val onEvent: (relay: String, event: NostrEvent) -> Unit,
) {
    constructor(onEvent: (relay: String, event: NostrEvent) -> Unit) : this(6_000L, onEvent)

    @Volatile var onStatusChange: () -> Unit = {}

    private class SubState(
        val active: LinkedHashSet<String>,
        val fallbacks: ArrayDeque<String>,
        val filter: JSONObject,
        val epoch: Int,
    )

    private val subLock = Any()
    private var nextSubEpoch = 1
    private val routeListener: () -> Unit = {
        backoff.clear()
        val hadSockets = sockets.isNotEmpty()
        sockets.values.forEach { it.cancel() }
        if (!hadSockets) {
            timer.execute { wanted.forEach { ensure(it) } }
        }
        onStatusChange()
    }

    init {
        // A route change (Tor on, off or ready) resets backoff, drops stale sockets, and immediately kicks wanted relays.
        NetRoute.onChange(routeListener)
    }

    private val pending = ConcurrentHashMap<String, MutableList<String>>() // relay -> events waiting for the socket
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val backoff = ConcurrentHashMap<String, Long>()
    private val wanted = ConcurrentHashMap.newKeySet<String>()
    private val subs = ConcurrentHashMap<String, SubState>()
    private val seen = object : LinkedHashMap<String, Boolean>(4096, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 4096
    }

    val connectedCount get() = connected.size

    fun activeRelays(subId: String): Set<String> = synchronized(subLock) {
        subs[subId]?.active?.toSet().orEmpty()
    }

    fun isSubConnected(subId: String): Boolean = synchronized(subLock) {
        subs[subId]?.active?.any { connected.contains(it) } == true
    }

    fun subscribe(
        subId: String,
        relays: Collection<String>,
        filter: JSONObject,
        fallbackRelays: Collection<String> = emptyList(),
    ) {
        val primary = LinkedHashSet(relays)
        val backups = ArrayDeque(fallbackRelays.filter { it !in primary }.distinct())
        val epoch: Int
        val droppedOld: List<String>
        synchronized(subLock) {
            epoch = nextSubEpoch++
            val prev = subs.put(subId, SubState(primary, backups, filter, epoch))
            droppedOld = prev?.active?.filter { it !in primary }.orEmpty()
        }
        if (droppedOld.isNotEmpty()) {
            val close = JSONArray().put("CLOSE").put(subId).toString()
            droppedOld.forEach { sockets[it]?.send(close) }
            pruneUnneededRelays(droppedOld)
        }
        primary.forEach { ensure(it) }
        primary.forEach { r -> if (connected.contains(r)) sockets[r]?.send(req(subId, filter)) }
        if (backups.isNotEmpty()) {
            scheduleEscalation(subId, epoch, failoverGraceMs)
        }
    }

    fun unsubscribe(subId: String) {
        val removed = synchronized(subLock) { subs.remove(subId)?.active?.toList() } ?: return
        val close = JSONArray().put("CLOSE").put(subId).toString()
        removed.forEach { sockets[it]?.send(close) }
        pruneUnneededRelays(removed)
    }

    private fun pruneUnneededRelays(candidates: Collection<String>) {
        val stillNeeded = synchronized(subLock) {
            subs.values.flatMapTo(HashSet()) { it.active }
        }
        var changed = false
        candidates.filter { it !in stillNeeded }.forEach { r ->
            wanted.remove(r)
            sockets.remove(r)?.close(1000, null)
            if (connected.remove(r)) changed = true
        }
        if (changed) onStatusChange()
    }

    private fun scheduleEscalation(subId: String, epoch: Int, delayMs: Long) {
        timer.schedule({
            val valid = synchronized(subLock) { subs[subId]?.epoch == epoch }
            if (!valid) return@schedule
            if (!NetRoute.ready) {
                // Wait for Tor bootstrap before spending fallback slots.
                scheduleEscalation(subId, epoch, minOf(delayMs, 4_000L))
                return@schedule
            }
            if (isSubConnected(subId)) return@schedule
            val moreLeft = promoteBackups(subId, maxCount = 3)
            if (moreLeft) {
                scheduleEscalation(subId, epoch, delayMs)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Promotes up to [maxCount] fallback relays into [subId]'s active set if it currently has
     * zero connected relays. Returns `true` when additional fallbacks remain queued.
     */
    private fun promoteBackups(subId: String, maxCount: Int): Boolean {
        val promoted = ArrayList<String>(maxCount)
        val filter: JSONObject
        val moreLeft: Boolean
        synchronized(subLock) {
            val state = subs[subId] ?: return false
            if (state.active.any { connected.contains(it) } || state.fallbacks.isEmpty()) return false
            filter = state.filter
            while (promoted.size < maxCount && state.fallbacks.isNotEmpty()) {
                val next = state.fallbacks.removeFirst()
                if (state.active.add(next)) promoted.add(next)
            }
            moreLeft = state.fallbacks.isNotEmpty()
        }
        promoted.forEach { r ->
            ensure(r)
            if (connected.contains(r)) sockets[r]?.send(req(subId, filter))
        }
        return moreLeft
    }

    private fun promoteOnRelayFailure(failedRelay: String) {
        if (!NetRoute.ready) return
        val starvedSubs = synchronized(subLock) {
            subs.entries
                .filter { (_, s) -> failedRelay in s.active && s.fallbacks.isNotEmpty() && s.active.none { connected.contains(it) } }
                .map { it.key }
        }
        starvedSubs.forEach { subId -> promoteBackups(subId, maxCount = 1) }
    }

    fun publish(relays: Collection<String>, event: NostrEvent) {
        synchronized(seen) { seen[event.id] = true }
        val msg = "[\"EVENT\"," + event.toJson() + "]"
        relays.forEach { r ->
            ensure(r)
            if (connected.contains(r)) sockets[r]?.send(msg)
            else pending.getOrPut(r) { java.util.Collections.synchronizedList(ArrayList()) }.let { q -> if (q.size < 50) q.add(msg) }
        }
    }

    fun closeAll() {
        val hadConnected = connected.isNotEmpty()
        wanted.clear()
        synchronized(subLock) { subs.clear() }
        pending.clear()
        sockets.values.forEach { it.close(1000, null) }
        sockets.clear(); connected.clear()
        if (hadConnected) onStatusChange()
    }

    fun shutdown() {
        NetRoute.removeOnChange(routeListener)
        closeAll()
        timer.shutdownNow()
    }

    private fun ensure(relay: String) {
        wanted.add(relay)
        if (sockets.containsKey(relay)) return
        // An onion address only exists through Tor; handing it to the system resolver tells the network which hidden service we wanted.
        if (relay.contains(".onion") && !NetRoute.torWanted) return
        val client = NetRoute.client() ?: run {
            // Tor is wanted but not up yet: wait rather than go clearnet.
            timer.schedule({ if (wanted.contains(relay)) ensure(relay) }, 3, TimeUnit.SECONDS)
            return
        }
        val ws = client.newWebSocket(Request.Builder().url(relay).build(), Listener(relay, NetRoute.generation))
        sockets[relay] = ws
    }

    private fun req(subId: String, filter: JSONObject) = JSONArray().put("REQ").put(subId).put(filter).toString()

    private inner class Listener(private val relay: String, private val route: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Opened under a route that changed meanwhile (Tor came on between picking the client and
            // connecting): this socket may be clearnet. Drop it; the reconnect uses the current route.
            if (route != NetRoute.generation) { webSocket.cancel(); return }
            val added = connected.add(relay)
            backoff.remove(relay)
            val toReplay = synchronized(subLock) {
                subs.entries.mapNotNull { (id, s) -> if (relay in s.active) id to s.filter else null }
            }
            toReplay.forEach { (id, filter) -> webSocket.send(req(id, filter)) }
            pending.remove(relay)?.let { q -> synchronized(q) { q.forEach { webSocket.send(it) } } }
            if (added) onStatusChange()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > 512 * 1024) return
            val arr = try { JSONArray(text) } catch (e: Exception) { return }
            if (arr.optString(0) != "EVENT" || arr.length() < 3) return
            val event = NostrEvent.fromJson(arr.optJSONObject(2) ?: return) ?: return
            val fresh = synchronized(seen) { seen.put(event.id, true) == null }
            if (fresh && event.isValid()) onEvent(relay, event)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)

        private fun dropped(webSocket: WebSocket) {
            // A socket replaced after a route change must not unseat its successor.
            if (!sockets.remove(relay, webSocket)) return
            val wasConnected = connected.remove(relay)
            if (wasConnected) onStatusChange()
            if (!wanted.contains(relay)) return
            promoteOnRelayFailure(relay)
            val delay = minOf((backoff[relay] ?: 1_000L) * 2, 300_000L)
            backoff[relay] = delay
            timer.schedule({ if (wanted.contains(relay)) ensure(relay) }, delay, TimeUnit.MILLISECONDS)
        }
    }
}
