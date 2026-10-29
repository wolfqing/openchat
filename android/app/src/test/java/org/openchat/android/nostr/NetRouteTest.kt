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
}
