package org.openchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class NetRouteTest {
    @Test
    fun torIsFailClosed() {
        NetRoute.set(torWanted = false, socksPort = 0)
        var changes = 0
        NetRoute.onChange { changes++ }
        NetRoute.set(torWanted = true, socksPort = 0)
        assertNull("no clearnet fallback while Tor starts", NetRoute.client())
        NetRoute.set(torWanted = true, socksPort = 9150)
        val c = NetRoute.client()!!
        assertEquals(Proxy.Type.SOCKS, c.proxy!!.type())
        assertEquals(9150, (c.proxy!!.address() as InetSocketAddress).port)
        NetRoute.set(torWanted = false, socksPort = 0)
        assertNotNull(NetRoute.client())
        assertNull(NetRoute.client()!!.proxy)
        assertEquals(3, changes)
        NetRoute.set(torWanted = true, socksPort = 0)
    }

    @Test
    fun torReadinessRecoversWhenControlSocketMissedCircBuiltEvent() {
        // Even if TorService.currentStatus stayed at STARTING due to FileObserver race,
        // status/circuit-established == 1 or PROGRESS=100 unlocks the SOCKS port immediately.
        assertEquals(
            0 to 45,
            org.openchat.android.app.computeTorReadiness(
                statusOn = false,
                circuitEstablished = "0",
                bootstrapPhase = "NOTICE BOOTSTRAP PROGRESS=45 TAG=loading_descriptors SUMMARY=\"Loading relay descriptors\"",
                rawSocksPort = 9050,
            ),
        )
        assertEquals(
            9050 to 100,
            org.openchat.android.app.computeTorReadiness(
                statusOn = false,
                circuitEstablished = "1\n",
                bootstrapPhase = "NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"",
                rawSocksPort = 9050,
            ),
        )
        assertEquals(
            null,
            org.openchat.android.app.formatAutoProxyTorrc("127.0.0.1", 9050, null, null),
        )
        assertEquals(
            "# openchat-auto-proxy\nHTTPSProxy 127.0.0.1:7890\n",
            org.openchat.android.app.formatAutoProxyTorrc("127.0.0.1", 7890, null, null),
        )
    }
}
