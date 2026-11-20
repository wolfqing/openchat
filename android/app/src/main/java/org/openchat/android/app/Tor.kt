package org.openchat.android.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.openchat.android.nostr.NetRoute
import org.torproject.jni.TorService
import org.torproject.jni.TorStatus
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class TorState { OFF, STARTING, ON }

internal val BOOTSTRAP_PROGRESS_REGEX = Regex("""PROGRESS=(\d{1,3})""")
private const val AUTO_PROXY_HEADER = "# openchat-auto-proxy"
private const val STALL_THRESHOLD_MS = 20_000L

internal fun parseBootstrapProgress(phaseInfo: String?): Int {
    if (phaseInfo.isNullOrBlank()) return 0
    val m = BOOTSTRAP_PROGRESS_REGEX.find(phaseInfo) ?: return 0
    return (m.groupValues[1].toIntOrNull() ?: 0).coerceIn(0, 100)
}

/**
 * Computes `(readySocksPort, bootstrapProgressPct)`.
 * Queries `status/circuit-established` and `status/bootstrap-phase` alongside [TorStatus.isOn]
 * because `tor-android` 0.4.8's `FileObserver` race can miss an early `CIRC BUILT` event and leave
 * `TorService.currentStatus` stuck at `"STARTING"` even after circuits are 100% established.
 */
internal fun computeTorReadiness(
    statusOn: Boolean,
    circuitEstablished: String?,
    bootstrapPhase: String?,
    rawSocksPort: Int,
): Pair<Int, Int> {
    val circuitUp = circuitEstablished?.trim() == "1"
    val parsedPct = parseBootstrapProgress(bootstrapPhase)
    val isLive = statusOn || circuitUp || parsedPct >= 100
    val readyPort = if (isLive && rawSocksPort > 0) rawSocksPort else 0
    val progress = if (readyPort > 0) 100 else parsedPct.coerceAtMost(99)
    return readyPort to progress
}

internal fun formatAutoProxyTorrc(
    httpHost: String?,
    httpPort: Int?,
    socksHost: String?,
    socksPort: Int?,
): String? {
    fun validEndpoint(h: String?, p: Int?): Pair<String, Int>? {
        val host = h?.trim()?.takeIf { it.isNotEmpty() && it.length <= 253 } ?: return null
        val port = p?.takeIf { it in 1..65_535 } ?: return null
        if (host.any { it.code <= 0x20 || it.code >= 0x7F }) return null
        // Never point Tor back into its own default local SOCKS/HTTP tunnel ports.
        val isLoopback = host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) || host == "::1"
        if (isLoopback && (port == 9050 || port == 9150 || port == 8118)) return null
        return host to port
    }
    validEndpoint(socksHost, socksPort)?.let { (h, p) ->
        return "$AUTO_PROXY_HEADER\nSocks5Proxy $h:$p\n"
    }
    validEndpoint(httpHost, httpPort)?.let { (h, p) ->
        return "$AUTO_PROXY_HEADER\nHTTPSProxy $h:$p\n"
    }
    return null
}

/**
 * Runs Tor in-process (Guardian Project's tor-android) while the user wants
 * relay traffic routed through it, which is the default (§19).
 */
@Suppress("DEPRECATION")
class Tor(
    private val context: Context,
    private val onState: (state: TorState, progress: Int, stalled: Boolean) -> Unit,
) {
    private var bound = false
    @Volatile private var service: TorService? = null
    @Volatile private var probeToken = 0L
    @Volatile private var lastProgressPct = 0
    @Volatile private var lastProgressMoveMs = System.currentTimeMillis()
    private val fastPollArmed = AtomicBoolean(false)
    private val probeExecutor = Executors.newSingleThreadScheduledExecutor()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
            poll()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            NetRoute.set(true, 0)
            onState(TorState.STARTING, 0, false)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = poll()
    }

    val wanted get() = prefs().getBoolean(KEY, true)

    fun apply() {
        if (wanted) start() else stop()
    }

    fun setWanted(on: Boolean) {
        prefs().edit().putBoolean(KEY, on).apply()
        apply()
    }

    /** Schedules an off-thread readiness & bootstrap-progress check against TorService's control socket. */
    fun poll() {
        if (!wanted) return
        val token = probeToken
        probeExecutor.execute { probeNow(token) }
    }

    private fun probeNow(token: Long) {
        if (!wanted || token != probeToken) return
        val svc = service
        val statusOn = TorStatus.isOn
        val circuitInfo = runCatching { svc?.getInfo("status/circuit-established") }.getOrNull()
        val phaseInfo = runCatching { svc?.getInfo("status/bootstrap-phase") }.getOrNull()
        val rawPort = TorService.socksPort.takeIf { it > 0 }
            ?: runCatching { svc?.socksPort }.getOrNull()?.takeIf { it > 0 }
            ?: 0

        val (readyPort, pct) = computeTorReadiness(statusOn, circuitInfo, phaseInfo, rawPort)
        val now = System.currentTimeMillis()
        if (readyPort > 0) {
            lastProgressPct = 100
            lastProgressMoveMs = now
        } else if (pct > lastProgressPct) {
            lastProgressPct = pct
            lastProgressMoveMs = now
        }
        val stalled = readyPort == 0 && (now - lastProgressMoveMs >= STALL_THRESHOLD_MS)
        if (!wanted || token != probeToken) return

        NetRoute.set(true, readyPort)
        onState(if (readyPort > 0) TorState.ON else TorState.STARTING, pct, stalled)

        if (readyPort == 0 && bound && fastPollArmed.compareAndSet(false, true)) {
            probeExecutor.schedule({
                fastPollArmed.set(false)
                probeNow(token)
            }, 1_500L, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Panic wipe: stop Tor, delete its directory (guards, state, cached descriptors) and start it
     * again after [delayMs]. Relay sockets stay closed in between (fail-closed), so the new identity's
     * first contact with a relay is over circuits that share nothing with the old one.
     */
    fun wipeAndRestart(main: android.os.Handler, delayMs: Long) {
        main.post {
            val wasWanted = wanted
            stop()
            main.postDelayed({
                try { context.getDir("TorService", Context.MODE_PRIVATE).deleteRecursively() } catch (_: Exception) { }
                if (wasWanted && wanted) start()
            }, delayMs)
        }
    }

    private fun start() {
        val now = System.currentTimeMillis()
        if (!bound) {
            probeToken++
            lastProgressPct = 0
            lastProgressMoveMs = now
            clearStaleControlSocket()
            syncSystemProxyTorrc()
            NetRoute.set(true, 0)
            onState(TorState.STARTING, 0, false)
            LocalBroadcastManager.getInstance(context).registerReceiver(statusReceiver, IntentFilter(TorService.ACTION_STATUS))
            bound = context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        poll()
    }

    fun stop() {
        probeToken++
        service = null
        fastPollArmed.set(false)
        if (bound) {
            try { context.unbindService(connection) } catch (_: Exception) { }
            try { LocalBroadcastManager.getInstance(context).unregisterReceiver(statusReceiver) } catch (_: Exception) { }
            bound = false
        }
        lastProgressPct = 0
        NetRoute.set(wanted, 0)
        onState(if (wanted) TorState.STARTING else TorState.OFF, 0, false)
    }

    fun destroy() {
        stop()
        probeExecutor.shutdownNow()
    }

    private fun clearStaleControlSocket() {
        runCatching {
            val sock = File(File(context.getDir("TorService", Context.MODE_PRIVATE), "data"), "ControlSocket")
            if (sock.exists()) sock.delete()
        }
    }

    private fun syncSystemProxyTorrc() {
        runCatching {
            val torrc = TorService.getTorrc(context)
            val existing = if (torrc.isFile) torrc.readText(Charsets.UTF_8) else null
            // Respect a user-authored torrc if one ever exists without our auto-generated header.
            if (existing != null && existing.isNotBlank() && !existing.startsWith(AUTO_PROXY_HEADER)) return

            val sysProxy = runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.defaultProxy
            }.getOrNull()
            val httpHost = sysProxy?.host?.takeIf { it.isNotBlank() } ?: System.getProperty("http.proxyHost")
            val httpPort = sysProxy?.port?.takeIf { it > 0 } ?: System.getProperty("http.proxyPort")?.toIntOrNull()
            val socksHost = System.getProperty("socksProxyHost")
            val socksPort = System.getProperty("socksProxyPort")?.toIntOrNull()

            val generated = formatAutoProxyTorrc(httpHost, httpPort, socksHost, socksPort)
            if (generated != null) {
                torrc.parentFile?.mkdirs()
                torrc.writeText(generated, Charsets.UTF_8)
            } else if (existing != null && existing.startsWith(AUTO_PROXY_HEADER)) {
                torrc.delete()
            }
        }
    }

    private fun prefs() = context.getSharedPreferences("net", Context.MODE_PRIVATE)

    private companion object { const val KEY = "tor" }
}
