package org.openchat.android.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkActivationTest {
    @Test
    fun staysIdleForFreshOfflineOrBleOnlySession() {
        val d = NetworkActivationPolicy.evaluate(NetworkActivationInput(), userTorEnabled = true)
        assertFalse(d.activationAllowed)
        assertFalse(d.torDesired)
    }

    @Test
    fun activatesWhenAnyInternetFeatureIsNeededAndNetworkIsReachable() {
        val triggers = listOf(
            NetworkActivationInput(hasLocationPermission = true),
            NetworkActivationInput(hasMutualFavorites = true),
            NetworkActivationInput(hasJoinedGeoChannels = true),
            NetworkActivationInput(watchingNotes = true),
            NetworkActivationInput(bridgeEnabled = true),
            NetworkActivationInput(manualInternetDemand = true),
        )
        for (input in triggers) {
            val withTor = NetworkActivationPolicy.evaluate(input, userTorEnabled = true)
            assertTrue("expected activation for $input", withTor.activationAllowed)
            assertTrue("expected Tor for $input", withTor.torDesired)

            val direct = NetworkActivationPolicy.evaluate(input, userTorEnabled = false)
            assertTrue("expected activation without Tor for $input", direct.activationAllowed)
            assertFalse("Tor stays off when disabled", direct.torDesired)
        }
    }

    @Test
    fun suppressesActivationWhenDeviceHasNoUsableNetwork() {
        val input = NetworkActivationInput(
            networkReachable = false,
            hasLocationPermission = true,
            hasMutualFavorites = true,
            hasJoinedGeoChannels = true,
            bridgeEnabled = true,
        )
        val d = NetworkActivationPolicy.evaluate(input, userTorEnabled = true)
        assertFalse(d.activationAllowed)
        assertFalse(d.torDesired)
    }
}
