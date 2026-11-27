package org.openchat.android.nostr

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Minimal NIP-01 relay pool: one WebSocket per relay, replayed subscriptions on
 * reconnect, automatic backup-relay failover when primary relays time out or reject
 * Tor exits, jittered exponential backoff with failure cooldown, optional idle teardown
 * grace period, and pause/resume for lazy internet activation (aligned with bitchat).
 */
class RelayPool(
    private val failoverGraceMs: Long = 6_000L,
    private val idleTeardownMs: Long = 0L,
    private val onEvent: (relay: String, event: NostrEvent) -> Unit,
) {
    constructor(onEvent: (relay: String, event: NostrEvent) -> Unit) : this(6_000L, 0L, onEvent)

    @Volatile var onStatusChange: () -> Unit = {}
    @Volatile private var active = true

    private class SubState(
        val active: LinkedHashSet<String>,
        val fallbacks: ArrayDeque<String>,
        val filter: JSONObject,
        val epoch: Int,
    )

    private val subLock = Any()
    private var nextSubEpoch = 1
    private val routeListener: () -> Unit = {
        reconnectAttempts.clear()
        val hadSockets = sockets.isNotEmpty()
        sockets.values.forEach { it.cancel() }
        if (!hadSockets && active) {
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
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val wanted = ConcurrentHashMap.newKeySet<String>()
    private val subs = ConcurrentHashMap<String, SubState>()
    private val idleTeardowns = ConcurrentHashMap<String, ScheduledFuture<*>>()
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

    /**
     * Pauses or resumes all socket connections while preserving subscription intent.
     * Used by lazy internet activation so offline/mesh-only sessions never spin reconnects.
     */
    fun setActive(enabled: Boolean) {
        if (active == enabled) return
        active = enabled
        if (!enabled) {
            idleTeardowns.values.forEach { it.cancel(false) }
            idleTeardowns.clear()
            wanted.clear()
            reconnectAttempts.clear()
            val hadConnected = connected.isNotEmpty()
            sockets.values.forEach { it.close(1000, null) }
            sockets.clear()
            connected.clear()
            if (hadConnected) onStatusChange()
        } else {
            reconnectAttempts.clear()
            val toKick = synchronized(subLock) {
                subs.entries.map { (id, s) -> Triple(id, s.epoch, s.active.toList() to s.fallbacks.isNotEmpty()) }
            }
            for ((subId, epoch, pair) in toKick) {
                val (relays, hasBackups) = pair
                relays.forEach { ensure(it) }
                if (hasBackups) scheduleEscalation(subId, epoch, failoverGraceMs)
            }
        }
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
        primary.forEach { cancelIdleTeardown(it) }
        if (droppedOld.isNotEmpty()) {
            val close = JSONArray().put("CLOSE").put(subId).toString()
            droppedOld.forEach { sockets[it]?.send(close) }
            pruneUnneededRelays(droppedOld)
        }
        if (!active) return
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

    private fun cancelIdleTeardown(relay: String) {
        idleTeardowns.remove(relay)?.cancel(false)
    }

    private fun isRelayStillNeeded(relay: String): Boolean = synchronized(subLock) {
        subs.values.any { relay in it.active }
    } || pending[relay]?.isNotEmpty() == true

    private fun pruneUnneededRelays(candidates: Collection<String>) {
        val stillNeeded = synchronized(subLock) {
            subs.values.flatMapTo(HashSet()) { it.active }
        }
        var changed = false
        candidates.filter { it !in stillNeeded }.forEach { r ->
            if (idleTeardownMs > 0L && active && sockets.containsKey(r)) {
                scheduleIdleTeardown(r)
            } else {
                cancelIdleTeardown(r)
                wanted.remove(r)
                sockets.remove(r)?.close(1000, null)
                if (connected.remove(r)) changed = true
            }
        }
        if (changed) onStatusChange()
    }

    private fun scheduleIdleTeardown(relay: String) {
        if (idleTeardowns.containsKey(relay)) return
        val task = timer.schedule({
            idleTeardowns.remove(relay)
            if (isRelayStillNeeded(relay)) return@schedule
            wanted.remove(relay)
            sockets.remove(relay)?.close(1000, null)
            if (connected.remove(relay)) onStatusChange()
        }, idleTeardownMs, TimeUnit.MILLISECONDS)
        idleTeardowns[relay] = task
    }

    private fun scheduleEscalation(subId: String, epoch: Int, delayMs: Long) {
        timer.schedule({
            if (!active) return@schedule
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
            cancelIdleTeardown(r)
            ensure(r)
            if (connected.contains(r)) sockets[r]?.send(req(subId, filter))
        }
        return moreLeft
    }

    private fun promoteOnRelayFailure(failedRelay: String) {
        if (!active || !NetRoute.ready) return
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
            cancelIdleTeardown(r)
            if (active) ensure(r)
            if (active && connected.contains(r)) sockets[r]?.send(msg)
            else pending.getOrPut(r) { java.util.Collections.synchronizedList(ArrayList()) }.let { q ->
                synchronized(q) {
                    while (q.size >= PENDING_SEND_CAP) q.removeAt(0)
                    q.add(msg)
                }
            }
        }
    }

    fun closeAll() {
        val hadConnected = connected.isNotEmpty()
        idleTeardowns.values.forEach { it.cancel(false) }
        idleTeardowns.clear()
        wanted.clear()
        synchronized(subLock) { subs.clear() }
        pending.clear()
        reconnectAttempts.clear()
        sockets.values.forEach { it.close(1000, null) }
        sockets.clear(); connected.clear()
        if (hadConnected) onStatusChange()
    }

    fun shutdown() {
        active = false
        NetRoute.removeOnChange(routeListener)
        closeAll()
        timer.shutdownNow()
    }

    private fun ensure(relay: String) {
        if (!active) return
        cancelIdleTeardown(relay)
        wanted.add(relay)
        if (sockets.containsKey(relay)) return
        // An onion address only exists through Tor; handing it to the system resolver tells the network which hidden service we wanted.
        if (relay.contains(".onion") && !NetRoute.torWanted) return
        val client = NetRoute.client() ?: run {
            // Tor is wanted but not up yet: wait rather than go clearnet.
            timer.schedule({ if (active && wanted.contains(relay)) ensure(relay) }, 3, TimeUnit.SECONDS)
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
            if (!active || route != NetRoute.generation) { webSocket.cancel(); return }
            val added = connected.add(relay)
            reconnectAttempts.remove(relay)
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
            if (!active || !wanted.contains(relay)) return
            promoteOnRelayFailure(relay)
            val attempt = (reconnectAttempts[relay] ?: 0) + 1
            reconnectAttempts[relay] = attempt
            val delay = computeBackoffDelayMs(attempt)
            timer.schedule({
                if (active && wanted.contains(relay)) {
                    // After completing a cooldown period, give the relay a fresh attempt budget.
                    if (attempt > MAX_RECONNECT_ATTEMPTS) reconnectAttempts.remove(relay)
                    ensure(relay)
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 300_000L
        const val MAX_RECONNECT_ATTEMPTS = 10
        const val FAILURE_COOLDOWN_MS = 600_000L
        const val BACKOFF_JITTER_RATIO = 0.2
        const val DEFAULT_IDLE_TEARDOWN_MS = 30_000L
        private const val PENDING_SEND_CAP = 200

        /**
         * Exponential backoff (`1 s -> 300 s`, ±20% jitter) across 10 reconnect attempts,
         * followed by a 10-minute cooldown before retrying (aligned with bitchat `NostrRelayManager`).
         */
        internal fun computeBackoffDelayMs(attempt: Int, jitterUnit: Double = Math.random()): Long {
            val base = if (attempt > MAX_RECONNECT_ATTEMPTS) {
                FAILURE_COOLDOWN_MS
            } else {
                val shift = (attempt - 1).coerceIn(0, 10)
                minOf(INITIAL_BACKOFF_MS shl shift, MAX_BACKOFF_MS)
            }
            val factor = 1.0 + (jitterUnit.coerceIn(0.0, 1.0) * 2.0 - 1.0) * BACKOFF_JITTER_RATIO
            return (base * factor).roundToLong().coerceAtLeast(INITIAL_BACKOFF_MS / 2)
        }
    }
}
