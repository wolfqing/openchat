package org.openchat.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TorTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun emptyUpstreamSettingLeavesDefaultTorBootstrapUnrestricted() {
        val torrc = File(temp.root, "torrc")
        assertNull(syncManagedProxyTorrc(torrc, ""))
        assertFalse(torrc.exists())
    }

    @Test
    fun directBootstrapClearsLegacyProxyAndPortRestrictions() {
        val torrc = temp.newFile("torrc")
        torrc.writeText("# openchat-auto-proxy\nHTTPSProxy 192.0.2.1:8080\nFascistFirewall 1\nClientUseIPv6 0\n")

        assertNull(syncManagedProxyTorrc(torrc, ""))
        assertFalse(torrc.exists())
    }

    @Test
    fun parseBootstrapProgressExtractsClampedPercentage() {
        assertEquals(0, parseBootstrapProgress(null))
        assertEquals(5, parseBootstrapProgress("NOTICE BOOTSTRAP PROGRESS=5 TAG=conn SUMMARY=\"Connecting to a relay\""))
        assertEquals(85, parseBootstrapProgress("NOTICE BOOTSTRAP PROGRESS=85 TAG=ap_conn_done"))
        assertEquals(100, parseBootstrapProgress("NOTICE BOOTSTRAP PROGRESS=100 TAG=done"))
    }

    @Test
    fun computeTorReadinessRespectsCircuitAndPort() {
        assertEquals(0 to 5, computeTorReadiness(false, "0", "PROGRESS=5", 9050))
        assertEquals(9050 to 100, computeTorReadiness(false, "1", "PROGRESS=85", 9050))
        assertEquals(0 to 99, computeTorReadiness(true, "1", "PROGRESS=100", 0))
    }

    @Test
    fun parseManualProxySpecHandlesSocksHttpBareAndRejectsSelfPorts() {
        assertEquals("Socks5Proxy" to "127.0.0.1:7890", parseManualProxySpec("127.0.0.1:7890"))
        assertEquals("Socks5Proxy" to "127.0.0.1:10808", parseManualProxySpec("socks5://127.0.0.1:10808"))
        assertEquals("HTTPSProxy" to "127.0.0.1:7890", parseManualProxySpec("http://127.0.0.1:7890"))
        assertEquals("HTTPSProxy" to "proxy.local:8080", parseManualProxySpec("https://proxy.local:8080/"))
        assertNull(parseManualProxySpec("127.0.0.1:9050"))
        assertNull(parseManualProxySpec("localhost:8118"))
        assertNull(parseManualProxySpec("ftp://127.0.0.1:21"))
        assertNull(parseManualProxySpec("not-a-proxy"))
    }

    @Test
    fun parseBootstrapDetailFormatsReasonAndProxyRoute() {
        val raw = """WARN BOOTSTRAP PROGRESS=5 TAG=conn SUMMARY="Connecting to a relay" WARNING="Connection timed out" REASON=TIMEOUT"""
        assertEquals("5% · TIMEOUT · direct", parseBootstrapDetail(raw, null))
        assertEquals(
            "5% · TIMEOUT · Socks5Proxy 127.0.0.1:7890",
            parseBootstrapDetail(raw, "Socks5Proxy" to "127.0.0.1:7890"),
        )
    }

    @Test
    fun writesExplicitUpstreamProxyToTorrc() {
        val torrc = File(temp.root, "torrc")
        assertEquals("Socks5Proxy" to "127.0.0.1:7890", syncManagedProxyTorrc(torrc, "socks5://127.0.0.1:7890"))
        assertEquals("# openchat-auto-proxy\nSocks5Proxy 127.0.0.1:7890\n", torrc.readText())
    }

    @Test
    fun preservesUnmanagedTorrc() {
        val torrc = temp.newFile("torrc")
        val custom = "Log notice stderr\n"
        torrc.writeText(custom)

        assertNull(syncManagedProxyTorrc(torrc, ""))
        assertEquals(custom, torrc.readText())
    }
}
