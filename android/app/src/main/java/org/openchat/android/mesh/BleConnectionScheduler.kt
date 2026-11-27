package org.openchat.android.mesh

/**
 * Pure BLE central connection scheduler aligned with bitchat's `BLEConnectionScheduler`:
 * - Dynamic RSSI floor: `-95 dBm` when isolated (0 links), relaxing to `-100 dBm` after 30 s
 *   isolated so fringe links still form; `-90 dBm` when connected, tightening to `-85 dBm` when
 *   central slots or the candidate queue are full.
 * - Ranked candidate queue (up to 100 peers) scored by RSSI, recency, and per-peer failure penalty.
 * - Global connect rate limit (500 ms) and per-address minimum retry interval (2 s).
 * - Distinct recovery windows for connect timeouts (15 s discovery ignore + 30 s weak-link cooldown)
 *   versus established disconnects (3 s settle window before fast reconnect).
 */
class BleConnectionScheduler(
    private val maxCentralLinks: Int = MAX_CENTRAL_LINKS,
    private val connectRateLimitMs: Long = CONNECT_RATE_LIMIT_MS,
    private val candidateCap: Int = CANDIDATE_CAP,
    private val weakLinkCooldownMs: Long = WEAK_LINK_COOLDOWN_MS,
    private val weakLinkRssiCutoff: Int = WEAK_LINK_RSSI_CUTOFF,
) {
    data class Candidate(
        val address: String,
        val rssi: Int,
        val isConnectable: Boolean = true,
        val discoveredAtMs: Long,
    )

    sealed interface DiscoveryDecision {
        data object Ignore : DiscoveryDecision
        data object Queued : DiscoveryDecision
        data class ScheduleRetry(val delayMs: Long) : DiscoveryDecision
        data object ConnectNow : DiscoveryDecision
    }

    sealed interface QueueDecision {
        data object None : QueueDecision
        data class RetryAfter(val delayMs: Long) : QueueDecision
        data class Connect(val candidate: Candidate) : QueueDecision
    }

    private var lastGlobalConnectMs = Long.MIN_VALUE
    private var isolatedSinceMs: Long? = null
    private val candidates = ArrayList<Candidate>()
    private val lastAttemptMs = HashMap<String, Long>()
    private val failureCounts = HashMap<String, Int>()
    private val recentTimeouts = HashMap<String, Long>()
    private val recentDisconnects = HashMap<String, Long>()

    var dynamicRssiThreshold: Int = RSSI_DEFAULT
        private set

    val candidateCount: Int get() = candidates.size

    fun updateRssiThreshold(totalLinks: Int, centralLinksOrConnecting: Int, nowMs: Long): Int {
        if (totalLinks == 0) {
            val since = isolatedSinceMs ?: nowMs.also { isolatedSinceMs = it }
            dynamicRssiThreshold = if (nowMs - since > ISOLATION_RELAX_MS) RSSI_ISOLATED_RELAXED else RSSI_ISOLATED_BASE
            return dynamicRssiThreshold
        }
        isolatedSinceMs = null
        dynamicRssiThreshold = if (centralLinksOrConnecting >= maxCentralLinks || candidates.size >= candidateCap) {
            RSSI_CONNECTED_TIGHT
        } else {
            RSSI_DEFAULT
        }
        return dynamicRssiThreshold
    }

    fun onDiscovery(
        candidate: Candidate,
        totalLinks: Int,
        centralLinksOrConnecting: Int,
        isConnected: Boolean,
        isConnecting: Boolean,
        nowMs: Long,
    ): DiscoveryDecision {
        if (!candidate.isConnectable) return DiscoveryDecision.Ignore
        updateRssiThreshold(totalLinks, centralLinksOrConnecting, nowMs)

        if (isConnected || isConnecting) return DiscoveryDecision.Ignore

        val lastTimeout = recentTimeouts[candidate.address]
        if (lastTimeout != null && nowMs - lastTimeout < TIMEOUT_DISCOVERY_IGNORE_MS) {
            return DiscoveryDecision.Ignore
        }

        val lastDisconnect = recentDisconnects[candidate.address]
        if (lastDisconnect != null && nowMs - lastDisconnect < DISCONNECT_DISCOVERY_IGNORE_MS) {
            return DiscoveryDecision.Ignore
        }

        val prevAttempt = lastAttemptMs[candidate.address]
        if (prevAttempt != null && nowMs - prevAttempt < PER_PEER_MIN_INTERVAL_MS) {
            return DiscoveryDecision.Ignore
        }

        if (candidate.rssi <= dynamicRssiThreshold) {
            enqueue(candidate)
            return DiscoveryDecision.Queued
        }

        if (centralLinksOrConnecting >= maxCentralLinks) {
            enqueue(candidate)
            return DiscoveryDecision.Queued
        }

        val weakDelay = weakLinkRetryDelay(candidate, nowMs)
        if (weakDelay != null) {
            enqueue(candidate)
            return DiscoveryDecision.ScheduleRetry(weakDelay)
        }

        val rateDelay = rateLimitDelay(nowMs)
        if (rateDelay != null) {
            enqueue(candidate)
            return DiscoveryDecision.ScheduleRetry(rateDelay)
        }

        return DiscoveryDecision.ConnectNow
    }

    fun enqueue(candidate: Candidate) {
        val idx = candidates.indexOfFirst { it.address == candidate.address }
        if (idx >= 0) candidates[idx] = candidate else candidates.add(candidate)
        if (candidates.size > 1) {
            candidates.sortWith(compareByDescending<Candidate> { it.rssi }.thenBy { it.discoveredAtMs })
        }
        if (candidates.size > candidateCap) {
            candidates.subList(candidateCap, candidates.size).clear()
        }
    }

    fun nextCandidate(
        centralLinksOrConnecting: Int,
        isAlreadyActive: (String) -> Boolean,
        nowMs: Long,
    ): QueueDecision {
        if (centralLinksOrConnecting >= maxCentralLinks || candidates.isEmpty()) return QueueDecision.None
        rateLimitDelay(nowMs)?.let { return QueueDecision.RetryAfter(it) }

        if (candidates.size > 1) {
            candidates.sortByDescending { score(it, nowMs) }
        }

        var earliestWait: Long? = null
        val iter = candidates.iterator()
        while (iter.hasNext()) {
            val c = iter.next()
            if (!c.isConnectable || isAlreadyActive(c.address)) {
                iter.remove()
                continue
            }
            if (nowMs - c.discoveredAtMs > CANDIDATE_MAX_AGE_MS) {
                iter.remove()
                continue
            }
            val wait = maxOfNullable(
                weakLinkRetryDelay(c, nowMs),
                disconnectSettleDelay(c, nowMs),
                timeoutSettleDelay(c, nowMs),
                perPeerDelay(c.address, nowMs),
            )
            if (wait != null) {
                earliestWait = if (earliestWait == null) wait else minOf(earliestWait, wait)
                continue
            }
            if (c.rssi <= dynamicRssiThreshold) continue
            iter.remove()
            return QueueDecision.Connect(c)
        }
        return earliestWait?.let { QueueDecision.RetryAfter(it) } ?: QueueDecision.None
    }

    fun recordConnectionAttempt(address: String, nowMs: Long) {
        lastGlobalConnectMs = nowMs
        lastAttemptMs[address] = nowMs
    }

    fun recordConnectionSuccess(address: String) {
        failureCounts.remove(address)
        recentTimeouts.remove(address)
        recentDisconnects.remove(address)
    }

    fun recordConnectionFailure(address: String) {
        failureCounts[address] = (failureCounts[address] ?: 0) + 1
    }

    fun recordConnectionTimeout(address: String, nowMs: Long) {
        recentTimeouts[address] = nowMs
        recordConnectionFailure(address)
    }

    fun recordDisconnect(address: String, nowMs: Long) {
        recentDisconnects[address] = nowMs
    }

    fun prune(nowMs: Long) {
        val cutoff = nowMs - BACKOFF_PRUNE_AGE_MS
        recentTimeouts.entries.removeAll { it.value < cutoff }
        recentDisconnects.entries.removeAll { it.value < cutoff }
        lastAttemptMs.entries.removeAll { it.value < cutoff }
        candidates.removeAll { nowMs - it.discoveredAtMs > CANDIDATE_MAX_AGE_MS }
    }

    fun reset() {
        lastGlobalConnectMs = Long.MIN_VALUE
        isolatedSinceMs = null
        candidates.clear()
        lastAttemptMs.clear()
        failureCounts.clear()
        recentTimeouts.clear()
        recentDisconnects.clear()
        dynamicRssiThreshold = RSSI_DEFAULT
    }

    private fun rateLimitDelay(nowMs: Long): Long? {
        if (lastGlobalConnectMs == Long.MIN_VALUE) return null
        val elapsed = nowMs - lastGlobalConnectMs
        return if (elapsed < connectRateLimitMs) connectRateLimitMs - elapsed + 50L else null
    }

    private fun perPeerDelay(address: String, nowMs: Long): Long? {
        val prev = lastAttemptMs[address] ?: return null
        val elapsed = nowMs - prev
        return if (elapsed < PER_PEER_MIN_INTERVAL_MS) PER_PEER_MIN_INTERVAL_MS - elapsed + 50L else null
    }

    private fun weakLinkRetryDelay(candidate: Candidate, nowMs: Long): Long? {
        val lastTimeout = recentTimeouts[candidate.address] ?: return null
        val elapsed = nowMs - lastTimeout
        if (elapsed >= weakLinkCooldownMs || candidate.rssi > weakLinkRssiCutoff) return null
        return (weakLinkCooldownMs - elapsed).coerceIn(2_000L, 15_000L)
    }

    private fun timeoutSettleDelay(candidate: Candidate, nowMs: Long): Long? {
        val lastTimeout = recentTimeouts[candidate.address] ?: return null
        val remaining = TIMEOUT_DISCOVERY_IGNORE_MS - (nowMs - lastTimeout)
        return if (remaining > 0) remaining + 50L else null
    }

    private fun disconnectSettleDelay(candidate: Candidate, nowMs: Long): Long? {
        val lastDisconnect = recentDisconnects[candidate.address] ?: return null
        val remaining = DISCONNECT_DISCOVERY_IGNORE_MS - (nowMs - lastDisconnect)
        return if (remaining > 0) remaining + 50L else null
    }

    private fun score(candidate: Candidate, nowMs: Long): Int {
        val failures = failureCounts[candidate.address] ?: 0
        val penalty = minOf(20, 1 shl minOf(4, failures))
        val timeoutBias = recentTimeouts[candidate.address]?.let { if (nowMs - it < 60_000L) 10 else 0 } ?: 0
        val base = (if (candidate.isConnectable) 1000 else 0) + (candidate.rssi + 100) * 2
        val recency = -((nowMs - candidate.discoveredAtMs) / 100L).toInt().coerceAtLeast(0)
        return base + recency - penalty - timeoutBias
    }

    private fun maxOfNullable(vararg values: Long?): Long? {
        var best: Long? = null
        for (v in values) if (v != null && (best == null || v > best)) best = v
        return best
    }

    companion object {
        const val MAX_CENTRAL_LINKS = 6
        const val CONNECT_RATE_LIMIT_MS = 500L
        const val PER_PEER_MIN_INTERVAL_MS = 2_000L
        const val CANDIDATE_CAP = 100
        const val CANDIDATE_MAX_AGE_MS = 120_000L
        const val ISOLATION_RELAX_MS = 30_000L
        const val RSSI_ISOLATED_BASE = -95
        const val RSSI_ISOLATED_RELAXED = -100
        const val RSSI_DEFAULT = -90
        const val RSSI_CONNECTED_TIGHT = -85
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val WEAK_LINK_COOLDOWN_MS = 30_000L
        const val WEAK_LINK_RSSI_CUTOFF = -90
        const val TIMEOUT_DISCOVERY_IGNORE_MS = 15_000L
        const val DISCONNECT_DISCOVERY_IGNORE_MS = 3_000L
        private const val BACKOFF_PRUNE_AGE_MS = 120_000L
    }
}
