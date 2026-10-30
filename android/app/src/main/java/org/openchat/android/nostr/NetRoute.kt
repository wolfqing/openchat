package org.openchat.android.nostr

import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * How relay sockets reach the internet (docs/protocol-spec.md §19). With Tor
 * wanted, it is fail-closed: until Tor's SOCKS port is up there is no client
 * at all, so nothing leaks the device's address over clearnet.
 */
object NetRoute {
    @Volatile var torWanted = true
        private set
    @Volatile var socksPort = 0
        private set
    /** Bumped on every route change, so a socket can tell whether it was opened under the current one. */
    @Volatile var generation = 0L
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val direct = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
    @Volatile private var viaTor: Pair<Int, OkHttpClient>? = null

    val ready get() = !torWanted || socksPort > 0

    fun client(): OkHttpClient? {
        if (!torWanted) return direct
        val port = socksPort.takeIf { it > 0 } ?: return null
        viaTor?.takeIf { it.first == port }?.let { return it.second }
        // OkHttp hands SOCKS proxies an unresolved host name, so Tor resolves DNS too.
        val c = direct.newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(60, TimeUnit.SECONDS).pingInterval(60, TimeUnit.SECONDS)
            .build()
        viaTor = port to c
        return c
    }

    fun set(torWanted: Boolean, socksPort: Int) {
        if (this.torWanted == torWanted && this.socksPort == socksPort) return
        this.torWanted = torWanted
        this.socksPort = socksPort
        generation++
        listeners.forEach { it() }
    }

    fun onChange(f: () -> Unit) { listeners.add(f) }
}
