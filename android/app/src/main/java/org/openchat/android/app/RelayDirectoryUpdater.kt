package org.openchat.android.app

import android.content.Context
import okhttp3.Call
import okhttp3.Request
import org.openchat.android.nostr.NetRoute
import org.openchat.android.nostr.RelayDirectory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Keeps [RelayDirectory] fresh from the reviewed online geo-relay CSV while
 * preserving strict fail-closed Tor routing:
 * - Never opens a socket until [NetRoute.client] is ready (no clearnet leak).
 * - Aborts any in-flight transfer if the route changes underneath it.
 * - Rejects malformed, undersized, or low-overlap payloads against [baseline].
 * - Stores its cache inside [Context.getCacheDir] so [Panic.wipeStorage] erases it automatically.
 */
class RelayDirectoryUpdater(
    context: Context,
    private val directory: RelayDirectory,
    private val baseline: List<RelayDirectory.Entry>,
) {
    private val cacheFile = File(context.applicationContext.cacheDir, CACHE_FILE_NAME)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var activeCall: Call? = null
    @Volatile private var fetching = false
    @Volatile private var stopped = false
    @Volatile private var active = false
    private var retryAttempts = 0

    private val routeListener: () -> Unit = {
        activeCall?.cancel()
        if (!stopped && active && NetRoute.ready) {
            executor.execute { refreshIfStale() }
        }
    }

    fun start(active: Boolean = false) {
        this.active = active
        NetRoute.onChange(routeListener)
        if (active && NetRoute.ready) {
            executor.execute { refreshIfStale() }
        }
    }

    fun setActive(enabled: Boolean) {
        if (active == enabled) return
        active = enabled
        if (!enabled) {
            activeCall?.cancel()
        } else if (!stopped && NetRoute.ready) {
            executor.execute { refreshIfStale() }
        }
    }

    fun stop() {
        stopped = true
        active = false
        NetRoute.removeOnChange(routeListener)
        activeCall?.cancel()
        executor.shutdownNow()
    }

    private fun isCacheFresh(): Boolean {
        if (!cacheFile.isFile) return false
        val ageMs = System.currentTimeMillis() - cacheFile.lastModified()
        return ageMs in 0 until REFRESH_INTERVAL_MS
    }

    private fun refreshIfStale(force: Boolean = false) {
        if (stopped || !active || fetching) return
        if (!force && isCacheFresh()) return
        val startGen = NetRoute.generation
        val client = NetRoute.client() ?: return
        fetching = true
        try {
            val req = Request.Builder().url(REMOTE_CSV_URL).get().build()
            val call = client.newCall(req)
            activeCall = call
            val ok = try {
                call.execute().use { resp ->
                    if (stopped || NetRoute.generation != startGen || !resp.isSuccessful) return@use false
                    val body = resp.body
                    if (body.contentLength() > MAX_CSV_BYTES) return@use false
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8 * 1024)
                    body.byteStream().use { ins ->
                        while (true) {
                            if (stopped || NetRoute.generation != startGen) return@use false
                            val n = ins.read(buf)
                            if (n < 0) break
                            if (out.size() + n > MAX_CSV_BYTES) return@use false
                            out.write(buf, 0, n)
                        }
                    }
                    val csvText = out.toString(Charsets.UTF_8.name())
                    val validated = RelayDirectory.parseValidatedEntries(
                        csv = csvText,
                        minimumEntries = MIN_REMOTE_ENTRIES,
                        baseline = baseline,
                        maxBytes = MAX_CSV_BYTES,
                    ) ?: return@use false
                    writeCacheAtomically(out.toByteArray())
                    directory.updateEntries(validated)
                    true
                }
            } catch (_: Exception) {
                false
            } finally {
                activeCall = null
            }

            if (ok) {
                retryAttempts = 0
            } else if (!stopped && NetRoute.ready && retryAttempts < MAX_RETRIES) {
                retryAttempts++
                val delaySec = minOf(15L shl (retryAttempts - 1), 180L)
                executor.schedule({ refreshIfStale(force = true) }, delaySec, TimeUnit.SECONDS)
            }
        } finally {
            fetching = false
        }
    }

    private fun writeCacheAtomically(bytes: ByteArray) {
        try {
            val tmp = File(cacheFile.parentFile, "$CACHE_FILE_NAME.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeBytes(bytes)
                tmp.delete()
            }
        } catch (_: Exception) { }
    }

    companion object {
        private const val CACHE_FILE_NAME = "georelays_cache_v1.csv"
        private const val REMOTE_CSV_URL =
            "https://raw.githubusercontent.com/permissionlesstech/bitchat/refs/heads/main/relays/online_relays_gps.csv"
        private const val REFRESH_INTERVAL_MS = 24L * 3_600_000L
        private const val MAX_CSV_BYTES = 512 * 1024
        private const val MIN_REMOTE_ENTRIES = 50
        private const val MAX_RETRIES = 4

        /**
         * Loads the validated cached directory when present and trustworthy; otherwise
         * returns the bundled baseline directory. Returns `(directory, baselineEntries)`.
         */
        fun loadInitial(context: Context, bundledCsv: String): Pair<RelayDirectory, List<RelayDirectory.Entry>> {
            val bundledDir = RelayDirectory.parse(bundledCsv)
            val baseline = bundledDir.entriesSnapshot()
            val cache = File(context.applicationContext.cacheDir, CACHE_FILE_NAME)
            if (cache.isFile && cache.length() in 1..MAX_CSV_BYTES.toLong()) {
                val cachedEntries = runCatching {
                    val text = cache.readText(Charsets.UTF_8)
                    RelayDirectory.parseValidatedEntries(
                        csv = text,
                        minimumEntries = MIN_REMOTE_ENTRIES,
                        baseline = baseline,
                        maxBytes = MAX_CSV_BYTES,
                    )
                }.getOrNull()
                if (cachedEntries != null) {
                    return RelayDirectory(cachedEntries) to baseline
                } else {
                    cache.delete()
                }
            }
            return bundledDir to baseline
        }
    }
}
