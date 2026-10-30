package org.torproject.jni

/**
 * Reads TorService's package-private status. The static SOCKS port keeps its last value after
 * Tor stops, so the port alone cannot say whether Tor is up; the status can.
 */
object TorStatus {
    val current: String get() = TorService.currentStatus ?: ""
    val isOn get() = current == TorService.STATUS_ON
}
