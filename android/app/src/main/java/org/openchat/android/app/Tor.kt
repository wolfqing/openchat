package org.openchat.android.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import net.freehaven.tor.control.RawEventListener
import net.freehaven.tor.control.TorControlConnection
import org.openchat.android.nostr.NetRoute
import org.torproject.jni.TorService
import org.torproject.jni.TorStatus
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class TorState { OFF, STANDBY, STARTING, ON }

internal val BOOTSTRAP_PROGRESS_REGEX = Regex("""PROGRESS=(\d{1,3})""")
private val BOOTSTRAP_TAG_REGEX = Regex("""TAG=([A-Za-z0-9_]+)""")
private val BOOTSTRAP_REASON_REGEX = Regex("""REASON=([A-Za-z0-9_]+)""")
private val BOOTSTRAP_WARNING_REGEX = Regex("""WARNING="([^"]+)"""")
private const val AUTO_PROXY_HEADER = "# openchat-auto-proxy"
private const val STALL_THRESHOLD_MS = 45_000L

internal fun parseBootstrapProgress(phaseInfo: String?): Int {
    if (phaseInfo.isNullOrBlank()) return 0
    val m = BOOTSTRAP_PROGRESS_REGEX.find(phaseInfo) ?: return 0
    return (m.groupValues[1].toIntOrNull() ?: 0).coerceIn(0, 100)
}

/** Extracts a short human-readable diagnostic reason from Tor's `status/bootstrap-phase` line. */
internal fun parseBootstrapDetail(phaseInfo: String?, activeProxy: Pair<String, String>?): String {
    val parts = ArrayList<String>(3)
    val pct = parseBootstrapProgress(phaseInfo)
    if (pct in 1..99) parts += "$pct%"
    val tag = phaseInfo?.let { BOOTSTRAP_TAG_REGEX.find(it)?.groupValues?.get(1) }
    val reason = phaseInfo?.let { BOOTSTRAP_REASON_REGEX.find(it)?.groupValues?.get(1) }
    val warning = phaseInfo?.let { BOOTSTRAP_WARNING_REGEX.find(it)?.groupValues?.get(1) }
    when {
        !reason.isNullOrBlank() && reason != "DONE" -> parts += reason
        !warning.isNullOrBlank() -> parts += warning
        !tag.isNullOrBlank() -> parts += tag
    }
    parts += if (activeProxy != null) "${activeProxy.first} ${activeProxy.second}" else "direct"
    return parts.joinToString(" · ")
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

private fun validateProxyEndpoint(h: String?, p: Int?): Pair<String, Int>? {
    val host = h?.trim()?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() && it.length <= 253 } ?: return null
    val port = p?.takeIf { it in 1..65_535 } ?: return null
    if (host.any { it.code <= 0x20 || it.code >= 0x7F }) return null
    // Never point Tor back into its own default local SOCKS/HTTP tunnel ports.
    val isLoopback = host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) || host == "::1"
    if (isLoopback && (port == 9050 || port == 9150 || port == 8118)) return null
    val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return formattedHost to port
}

/**
 * Parses an optional user-supplied upstream proxy spec into `(torrcDirective, "host:port")`:
 * - `http://127.0.0.1:7890` / `https://...` -> `"HTTPSProxy" to "127.0.0.1:7890"`
 * - `socks5://127.0.0.1:10808` / `socks://...` / bare `127.0.0.1:7890` -> `"Socks5Proxy" to "127.0.0.1:7890"`
 */
internal fun parseManualProxySpec(raw: String?): Pair<String, String>? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lower = trimmed.lowercase()
    val (directive, remainder) = when {
        lower.startsWith("http://") -> "HTTPSProxy" to trimmed.substring(7)
        lower.startsWith("https://") -> "HTTPSProxy" to trimmed.substring(8)
        lower.startsWith("socks5://") -> "Socks5Proxy" to trimmed.substring(9)
        lower.startsWith("socks5h://") -> "Socks5Proxy" to trimmed.substring(10)
        lower.startsWith("socks://") -> "Socks5Proxy" to trimmed.substring(8)
        trimmed.contains("://") -> return null
        else -> "Socks5Proxy" to trimmed
    }
    val hostPort = remainder.substringBefore('/').trim()
    val colon = hostPort.lastIndexOf(':')
    if (colon <= 0 || colon >= hostPort.lastIndex) return null
    val host = hostPort.substring(0, colon)
    val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
    val (cleanHost, cleanPort) = validateProxyEndpoint(host, port) ?: return null
    return directive to "$cleanHost:$cleanPort"
}

internal fun resolveAutoProxyDirective(
    manualSpec: String?,
    httpHost: String?,
    httpPort: Int?,
    socksHost: String?,
    socksPort: Int?,
): Pair<String, String>? {
    parseManualProxySpec(manualSpec)?.let { return it }
    validateProxyEndpoint(socksHost, socksPort)?.let { (h, p) -> return "Socks5Proxy" to "$h:$p" }
    validateProxyEndpoint(httpHost, httpPort)?.let { (h, p) -> return "HTTPSProxy" to "$h:$p" }
    return null
}

internal fun formatAutoProxyTorrc(
    httpHost: String?,
    httpPort: Int?,
    socksHost: String?,
    socksPort: Int?,
    manualSpec: String? = null,
): String? {
    val (directive, endpoint) = resolveAutoProxyDirective(manualSpec, httpHost, httpPort, socksHost, socksPort)
        ?: return null
    return "$AUTO_PROXY_HEADER\n$directive $endpoint\n"
}

/**
 * Synchronizes OpenChat's managed `torrc` file for an optional upstream proxy while leaving
 * direct bootstraps completely unrestricted (stock Tor IPv4/IPv6 + all OR/Dir ports).
 * Preserves any user-authored `torrc` lacking [AUTO_PROXY_HEADER], and removes legacy
 * auto-generated `torrc` files (including prior `FascistFirewall` / `ClientUseIPv6` directives).
 */
internal fun syncManagedProxyTorrc(
    torrc: File,
    manualSpec: String?,
    httpHost: String? = null,
    httpPort: Int? = null,
    socksHost: String? = null,
    socksPort: Int? = null,
): Pair<String, String>? {
    val desired = resolveAutoProxyDirective(manualSpec, httpHost, httpPort, socksHost, socksPort)
    val existing = if (torrc.isFile) torrc.readText(Charsets.UTF_8) else null
    if (existing != null && existing.isNotBlank() && !existing.startsWith(AUTO_PROXY_HEADER)) {
        return desired
    }
    if (desired != null) {
        torrc.parentFile?.mkdirs()
        torrc.writeText("$AUTO_PROXY_HEADER\n${desired.first} ${desired.second}\n", Charsets.UTF_8)
    } else if (existing != null && existing.startsWith(AUTO_PROXY_HEADER)) {
        torrc.delete()
    }
    return desired
}

/**
 * Runs Tor in-process (Guardian Project's tor-android) while the user wants
 * relay traffic routed through it, which is the default (§19).
 */
@Suppress("DEPRECATION")
class Tor(
    private val context: Context,
    private val onState: (state: TorState, progress: Int, stalled: Boolean, detail: String) -> Unit,
) {
    private val lifecycleLock = Any()
    private var bound = false
    private var networkCallbackRegistered = false
    @Volatile private var autoStartAllowed = false
    @Volatile private var wiping = false
    @Volatile private var service: TorService? = null
    @Volatile private var probeToken = 0L
    @Volatile private var lastProgressPct = 0
    @Volatile private var lastProgressMoveMs = System.currentTimeMillis()
    @Volatile private var lastAppliedProxy: Pair<String, String>? = null
    @Volatile private var activeLinkHttpProxy: Pair<String, Int>? = null
    @Volatile private var attachedEventConn: TorControlConnection? = null
    @Volatile private var lastLoggedPhase: String? = null
    private val fastPollArmed = AtomicBoolean(false)
    private val probeExecutor = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val rawEventListener = RawEventListener { keyword, data ->
        if (keyword == "NOTICE" || keyword == "WARN" || keyword == "ERR" || keyword == "STATUS_CLIENT") {
            DiagLog.record("Tor/$keyword", data.orEmpty())
        } else if (keyword == "ORCONN" && lastProgressPct < 100) {
            DiagLog.record("Tor/ORCONN", data.orEmpty())
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
            DiagLog.record("Tor", "onServiceConnected svc=${service != null} status=${TorStatus.current}")
            poll()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            attachedEventConn = null
            DiagLog.record("Tor", "onServiceDisconnected")
            NetRoute.set(true, 0)
            onState(TorState.STARTING, 0, false, "")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == TorService.ACTION_ERROR) {
                DiagLog.record("Tor/ERR", "ACTION_ERROR: ${intent.getStringExtra(Intent.EXTRA_TEXT)}")
            } else {
                DiagLog.record("Tor", "ACTION_STATUS: ${intent.getStringExtra(TorService.EXTRA_STATUS)} static=${TorStatus.current}")
            }
            poll()
        }
    }

    @Volatile private var seenFirstNetwork = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!wanted) return
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            activeLinkHttpProxy = runCatching { cm?.getLinkProperties(network)?.httpProxy }
                .getOrNull()
                ?.let { p -> p.host?.takeIf { it.isNotBlank() && p.port > 0 }?.let { it to p.port } }
            val isSwitch = seenFirstNetwork
            seenFirstNetwork = true
            // Only flush Tor when the user actually switched networks/VPNs, never on initial registration.
            if (isSwitch) {
                DiagLog.record("Tor", "Network changed proxy=$activeLinkHttpProxy")
                probeExecutor.execute { onNetworkOrProxyChanged(forceNudge = lastProgressPct < 100) }
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (!wanted) return
            activeLinkHttpProxy = linkProperties.httpProxy
                ?.let { p -> p.host?.takeIf { it.isNotBlank() && p.port > 0 }?.let { it to p.port } }
            probeExecutor.execute { onNetworkOrProxyChanged(forceNudge = false) }
        }
    }

    val wanted get() = prefs().getBoolean(KEY, true)

    val upstreamProxy: String get() = prefs().getString(KEY_PROXY, "").orEmpty()

    /** Gates Tor startup on lazy internet activation (aligned with bitchat `TorManager.setAutoStartAllowed`). */
    fun setAutoStartAllowed(allowed: Boolean) {
        autoStartAllowed = allowed
        if (!wiping) apply()
    }

    fun apply() {
        synchronized(lifecycleLock) {
            if (wanted && autoStartAllowed && !wiping) start() else stop()
        }
    }

    fun setWanted(on: Boolean) {
        prefs().edit().putBoolean(KEY, on).apply()
        if (!wiping) apply()
    }

    /** Saves an optional upstream proxy (`socks5://127.0.0.1:7890`, `http://...`, or empty to clear) and applies live. */
    fun setUpstreamProxy(raw: String): String {
        val cleaned = raw.trim()
        val valid = cleaned.isEmpty() || parseManualProxySpec(cleaned) != null
        val saved = if (valid) cleaned else upstreamProxy
        if (valid) {
            prefs().edit().putString(KEY_PROXY, saved).apply()
            if (wanted && autoStartAllowed && !wiping) retry()
        }
        return saved
    }

    /**
     * Re-reads Android's active proxy + user upstream proxy, updates `torrc`, applies it live over
     * Tor's control socket, and resets Tor's bootstrap backoff timer so stalled attempts retry
     * immediately without restricting ORPorts or IPv6.
     */
    fun retry() {
        if (!wanted || wiping) return
        if (!autoStartAllowed) {
            autoStartAllowed = true
            synchronized(lifecycleLock) { start() }
            return
        }
        lastProgressMoveMs = System.currentTimeMillis()
        onState(TorState.STARTING, lastProgressPct, false, "")
        probeExecutor.execute {
            val applied = applyLiveProxyAndFlushBackoff()
            if (!applied) {
                mainHandler.post {
                    synchronized(lifecycleLock) {
                        if (wanted && autoStartAllowed && !wiping) {
                            stop()
                            start()
                        }
                    }
                }
            } else {
                probeNow(probeToken)
            }
        }
    }

    /** Schedules an off-thread readiness & bootstrap-progress check against TorService's control socket. */
    fun poll() {
        if (!wanted || !autoStartAllowed || wiping) return
        val token = probeToken
        probeExecutor.execute { probeNow(token) }
    }

    private fun onNetworkOrProxyChanged(forceNudge: Boolean) {
        if (!wanted) return
        val desired = currentResolvedProxy()
        if (desired != lastAppliedProxy || forceNudge) {
            applyLiveProxyAndFlushBackoff()
            probeNow(probeToken)
        }
    }

    private fun applyLiveProxyAndFlushBackoff(): Boolean {
        val desired = syncSystemProxyTorrc()
        val conn = runCatching { service?.torControlConnection }.getOrNull() ?: return false
        return runCatching {
            conn.resetConf(
                listOf(
                    "Socks5Proxy",
                    "HTTPSProxy",
                    "FascistFirewall",
                    "ReachableAddresses",
                    "ReachableORAddresses",
                    "ReachableDirAddresses",
                    "ClientUseIPv6",
                ),
            )
            if (desired != null) {
                conn.setConf(desired.first, desired.second)
            }
            // Flush Tor's exponential retry backoff so guard/directory attempts restart immediately.
            conn.setConf("DisableNetwork", "1")
            conn.setConf("DisableNetwork", "0")
            lastAppliedProxy = desired
            lastProgressMoveMs = System.currentTimeMillis()
            DiagLog.record("Tor", "applyLiveProxy desired=$desired")
            true
        }.onFailure { e ->
            DiagLog.record("Tor", "applyLiveProxy failed: ${e.javaClass.simpleName}: ${e.message}")
        }.getOrDefault(false)
    }

    private fun attachLogListenerIfNeeded(svc: TorService?) {
        val conn = runCatching { svc?.torControlConnection }.getOrNull() ?: return
        if (attachedEventConn === conn) return
        runCatching {
            conn.addRawEventListener(rawEventListener)
            // Keep CIRC subscribed so TorService's own startedEventListener still receives CIRC BUILT.
            conn.setEvents(listOf("CIRC", "ORCONN", "NOTICE", "WARN", "ERR", "STATUS_CLIENT"))
            attachedEventConn = conn
            DiagLog.record("Tor", "Attached TorControlConnection event logger")
        }.onFailure { e ->
            DiagLog.record("Tor", "Attach event logger deferred: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun probeNow(token: Long) {
        if (!wanted || token != probeToken) return
        val svc = service
        attachLogListenerIfNeeded(svc)
        val resolvedProxy = currentResolvedProxy()
        if (svc != null && resolvedProxy != lastAppliedProxy) {
            applyLiveProxyAndFlushBackoff()
        }
        val statusOn = TorStatus.isOn
        val circuitInfo = runCatching { svc?.getInfo("status/circuit-established") }.getOrNull()
        val phaseInfo = runCatching { svc?.getInfo("status/bootstrap-phase") }.getOrNull()
        val rawPort = TorService.socksPort.takeIf { it > 0 }
            ?: runCatching { svc?.socksPort }.getOrNull()?.takeIf { it > 0 }
            ?: 0

        if (phaseInfo != lastLoggedPhase) {
            lastLoggedPhase = phaseInfo
            DiagLog.record("Tor", "probe statusOn=$statusOn circ=$circuitInfo port=$rawPort phase=$phaseInfo")
        }

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
        val detail = if (readyPort > 0) "" else parseBootstrapDetail(phaseInfo, lastAppliedProxy ?: resolvedProxy)
        if (!wanted || token != probeToken) return

        NetRoute.set(true, readyPort)
        onState(if (readyPort > 0) TorState.ON else TorState.STARTING, pct, stalled, detail)

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
    fun wipeAndRestart(main: Handler, delayMs: Long) {
        wiping = true
        main.post {
            val wasWanted = wanted
            clearProxy(context)
            synchronized(lifecycleLock) { stop() }
            main.postDelayed({
                try { context.getDir("TorService", Context.MODE_PRIVATE).deleteRecursively() } catch (_: Exception) { }
                synchronized(lifecycleLock) {
                    wiping = false
                    if (wasWanted && wanted && autoStartAllowed) start() else stop()
                }
            }, delayMs)
        }
    }

    private fun start() {
        synchronized(lifecycleLock) {
            if (wiping) return
            val now = System.currentTimeMillis()
            if (!bound) {
                probeToken++
                lastProgressPct = 0
                lastProgressMoveMs = now
                clearStaleControlSocket()
                lastAppliedProxy = syncSystemProxyTorrc()
                registerNetworkWatcher()
                NetRoute.set(true, 0)
                onState(TorState.STARTING, 0, false, "")
                val filter = IntentFilter(TorService.ACTION_STATUS).apply { addAction(TorService.ACTION_ERROR) }
                LocalBroadcastManager.getInstance(context).registerReceiver(statusReceiver, filter)
                bound = context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
                DiagLog.record("Tor", "start() bound=$bound proxy=$lastAppliedProxy")
            }
            poll()
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            probeToken++
            attachedEventConn = null
            lastLoggedPhase = null
            service = null
            fastPollArmed.set(false)
            unregisterNetworkWatcher()
            if (bound) {
                try { context.unbindService(connection) } catch (_: Exception) { }
                try { LocalBroadcastManager.getInstance(context).unregisterReceiver(statusReceiver) } catch (_: Exception) { }
                bound = false
            }
            lastProgressPct = 0
            DiagLog.record("Tor", "stop() wanted=$wanted autoStart=$autoStartAllowed")
            NetRoute.set(wanted, 0)
            val nextState = when {
                !wanted -> TorState.OFF
                !autoStartAllowed -> TorState.STANDBY
                else -> TorState.STARTING
            }
            onState(nextState, 0, false, "")
        }
    }

    fun destroy() {
        stop()
        probeExecutor.shutdownNow()
    }

    private fun registerNetworkWatcher() {
        if (networkCallbackRegistered) return
        seenFirstNetwork = false
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkWatcher() {
        if (!networkCallbackRegistered) return
        networkCallbackRegistered = false
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.unregisterNetworkCallback(networkCallback)
        }
    }

    private fun clearStaleControlSocket() {
        runCatching {
            val sock = File(File(context.getDir("TorService", Context.MODE_PRIVATE), "data"), "ControlSocket")
            if (sock.exists()) sock.delete()
        }
    }

    private data class DetectedProxyEnv(
        val httpHost: String?,
        val httpPort: Int?,
        val socksHost: String?,
        val socksPort: Int?,
    )

    private fun detectProxyEnv(): DetectedProxyEnv {
        val cm = runCatching { context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }.getOrNull()
        val sysProxy = runCatching { cm?.defaultProxy }.getOrNull()
        val linkProxy = activeLinkHttpProxy ?: runCatching {
            cm?.activeNetwork?.let { cm.getLinkProperties(it)?.httpProxy }
        }.getOrNull()?.let { p -> p.host?.takeIf { it.isNotBlank() && p.port > 0 }?.let { it to p.port } }

        return DetectedProxyEnv(
            httpHost = linkProxy?.first
                ?: sysProxy?.host?.takeIf { it.isNotBlank() }
                ?: System.getProperty("http.proxyHost"),
            httpPort = linkProxy?.second
                ?: sysProxy?.port?.takeIf { it > 0 }
                ?: System.getProperty("http.proxyPort")?.toIntOrNull(),
            socksHost = System.getProperty("socksProxyHost"),
            socksPort = System.getProperty("socksProxyPort")?.toIntOrNull(),
        )
    }

    private fun currentResolvedProxy(): Pair<String, String>? {
        val env = detectProxyEnv()
        return resolveAutoProxyDirective(upstreamProxy, env.httpHost, env.httpPort, env.socksHost, env.socksPort)
    }

    private fun syncSystemProxyTorrc(): Pair<String, String>? {
        val env = detectProxyEnv()
        return runCatching {
            syncManagedProxyTorrc(
                torrc = TorService.getTorrc(context),
                manualSpec = upstreamProxy,
                httpHost = env.httpHost,
                httpPort = env.httpPort,
                socksHost = env.socksHost,
                socksPort = env.socksPort,
            )
        }.getOrElse {
            resolveAutoProxyDirective(upstreamProxy, env.httpHost, env.httpPort, env.socksHost, env.socksPort)
        }
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "net"
        private const val KEY = "tor"
        private const val KEY_PROXY = "tor_proxy"

        fun clearProxy(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_PROXY).apply()
        }
    }
}
