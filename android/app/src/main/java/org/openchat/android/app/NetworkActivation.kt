package org.openchat.android.app

/**
 * Pure policy for lazy internet activation (Tor, Nostr relays, relay directory refresh),
 * aligned with bitchat's `NetworkActivationService`:
 * - Internet networking activates only when the device has a usable network path AND at least
 *   one internet feature is needed (location permission, mutual favorites, a joined location
 *   channel or watched place notes, Mesh Bridge enabled, or an explicit user internet action).
 * - Purely offline / BLE-only sessions never start Tor or spin relay reconnect timers.
 * - BLE mesh operates completely independently of this gate.
 */
data class NetworkActivationInput(
    val networkReachable: Boolean = true,
    val hasLocationPermission: Boolean = false,
    val hasMutualFavorites: Boolean = false,
    val hasJoinedGeoChannels: Boolean = false,
    val watchingNotes: Boolean = false,
    val bridgeEnabled: Boolean = false,
    val manualInternetDemand: Boolean = false,
) {
    val hasInternetNeed: Boolean
        get() = hasLocationPermission ||
            hasMutualFavorites ||
            hasJoinedGeoChannels ||
            watchingNotes ||
            bridgeEnabled ||
            manualInternetDemand

    val allowed: Boolean
        get() = networkReachable && hasInternetNeed
}

data class NetworkActivationDecision(
    val activationAllowed: Boolean,
    val torDesired: Boolean,
)

object NetworkActivationPolicy {
    fun evaluate(input: NetworkActivationInput, userTorEnabled: Boolean): NetworkActivationDecision {
        val allowed = input.allowed
        return NetworkActivationDecision(
            activationAllowed = allowed,
            torDesired = allowed && userTorEnabled,
        )
    }
}
