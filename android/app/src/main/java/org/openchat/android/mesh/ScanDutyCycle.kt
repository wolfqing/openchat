package org.openchat.android.mesh

/**
 * BLE scan duty cycle (docs/protocol-spec.md §11): the scanner runs for 5 s and rests for
 * 10 s; with six or more links, 3 s and 15 s. No scan is started while another central
 * link cannot be taken anyway, and a start that fails is retried with growing pauses.
 */
object ScanDutyCycle {
    const val DENSE_LINKS = 6

    fun onMs(links: Int): Long = if (links >= DENSE_LINKS) 3_000 else 5_000

    fun offMs(links: Int): Long = if (links >= DENSE_LINKS) 15_000 else 10_000

    /** False while the central table is full: a result could not be acted on. */
    fun wanted(centralLinks: Int, connecting: Int, cap: Int): Boolean = centralLinks + connecting < cap

    /** Pause before the next attempt after [failures] consecutive failed starts: 10, 20, 40, then 60 s. */
    fun retryMs(failures: Int): Long = minOf(60_000L, 10_000L shl (failures - 1).coerceIn(0, 3))
}
