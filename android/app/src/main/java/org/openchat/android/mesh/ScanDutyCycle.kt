package org.openchat.android.mesh

/**
 * BLE scan duty cycle aligned with bitchat's `BLEScanDutyPolicy`:
 * - Normally scans continuously while sparse (0–2 links) or when mesh traffic was seen recently
 *   (last 10 s), so isolated phones discover each other without waiting through 10 s sleep gaps.
 * - Duty-cycles 5 s on / 10 s off at 3–5 links and 3 s on / 15 s off at ≥ 6 links.
 * - Under system power-save mode (when no recent traffic), duty-cycles aggressively even when
 *   sparse: 3 s on / 15 s off (< 6 links) or 2 s on / 20 s off (≥ 6 links).
 */
object ScanDutyCycle {
    const val SPARSE_CONTINUOUS_MAX_LINKS = 2
    const val DENSE_LINKS = 6
    const val RECENT_TRAFFIC_WINDOW_MS = 10_000L
    /** Re-evaluates whether continuous scanning should step down to duty cycling. */
    const val CONTINUOUS_RECHECK_MS = 10_000L
    /** Refreshes a continuous scan before Android's 30-minute opportunistic downgrade. */
    const val CONTINUOUS_REFRESH_MS = 20 * 60_000L
    /** Minimum spacing between Android BLE scan starts to stay well under the 5 starts / 30 s cap. */
    const val MIN_SCAN_START_SPACING_MS = 6_500L

    sealed interface ScanPlan {
        data object Continuous : ScanPlan
        data class DutyCycle(val onMs: Long, val offMs: Long) : ScanPlan
    }

    fun plan(
        links: Int,
        hasRecentTraffic: Boolean = false,
        lowPowerMode: Boolean = false,
    ): ScanPlan {
        if (lowPowerMode && !hasRecentTraffic) {
            return if (links >= DENSE_LINKS) ScanPlan.DutyCycle(onMs = 2_000L, offMs = 20_000L)
            else ScanPlan.DutyCycle(onMs = 3_000L, offMs = 15_000L)
        }
        if (links <= SPARSE_CONTINUOUS_MAX_LINKS || hasRecentTraffic) {
            return ScanPlan.Continuous
        }
        return ScanPlan.DutyCycle(onMs = onMs(links), offMs = offMs(links))
    }

    fun onMs(links: Int): Long = if (links >= DENSE_LINKS) 3_000L else 5_000L

    fun offMs(links: Int): Long = if (links >= DENSE_LINKS) 15_000L else 10_000L

    /** False while the central table is full: a result could not be acted on. */
    fun wanted(centralLinks: Int, connecting: Int, cap: Int): Boolean = centralLinks + connecting < cap

    /** Pause before the next attempt after [failures] consecutive failed starts: 10, 20, 40, then 60 s. */
    fun retryMs(failures: Int): Long = minOf(60_000L, 10_000L shl (failures - 1).coerceIn(0, 3))
}
