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
 * replayed subscriptions on reconnect, exponential backoff, event dedup.
 */
class RelayPool(private val onEvent: (relay: String, event: NostrEvent) -> Unit) {
    init {
        // A route change (Tor on, off or ready) drops every socket; wanted relays reconnect on the new route.
        NetRoute.onChange { sockets.values.forEach { it.cancel() } }
    }
    private val pending = ConcurrentHashMap<String, MutableList<String>>() // relay -> events waiting for the socket
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val backoff = ConcurrentHashMap<String, Long>()
    private val wanted = ConcurrentHashMap.newKeySet<String>()
    private val subs = ConcurrentHashMap<String, Pair<Set<String>, JSONObject>>() // subId -> (relays, filter)
    private val seen = object : LinkedHashMap<String, Boolean>(4096, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 4096
    }

    val connectedCount get() = connected.size

    fun subscribe(subId: String, relays: Collection<String>, filter: JSONObject) {
        subs[subId] = relays.toSet() to filter
        relays.forEach { ensure(it) }
        relays.forEach { r -> if (connected.contains(r)) sockets[r]?.send(req(subId, filter)) }
    }

    fun unsubscribe(subId: String) {
        val (relays, _) = subs.remove(subId) ?: return
        val close = JSONArray().put("CLOSE").put(subId).toString()
        relays.forEach { sockets[it]?.send(close) }
        // Drop relays no subscription needs any more.
        val stillNeeded = subs.values.flatMap { it.first }.toSet()
        relays.filter { it !in stillNeeded }.forEach { r ->
            wanted.remove(r); sockets.remove(r)?.close(1000, null); connected.remove(r)
        }
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
        wanted.clear(); subs.clear(); pending.clear()
        sockets.values.forEach { it.close(1000, null) }
        sockets.clear(); connected.clear()
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
            connected.add(relay)
            backoff.remove(relay)
            subs.forEach { (id, v) -> if (relay in v.first) webSocket.send(req(id, v.second)) }
            pending.remove(relay)?.let { q -> synchronized(q) { q.forEach { webSocket.send(it) } } }
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
            connected.remove(relay)
            if (!wanted.contains(relay)) return
            val delay = minOf((backoff[relay] ?: 1_000L) * 2, 300_000L)
            backoff[relay] = delay
            timer.schedule({ if (wanted.contains(relay)) ensure(relay) }, delay, TimeUnit.MILLISECONDS)
        }
    }
}
