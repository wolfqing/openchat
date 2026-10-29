package org.openchat.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayUrlTest {
    @Test
    fun normalisesAndRejects() {
        assertEquals("wss://relay.example.org", NostrDirect.normalizeRelay(" relay.example.org/ "))
        assertEquals("wss://relay.example.org:7777/nostr", NostrDirect.normalizeRelay("WSS://Relay.Example.org:7777/nostr"))
        assertEquals("ws://abcdefghijklmnop.onion", NostrDirect.normalizeRelay("ws://abcdefghijklmnop.onion"))
        assertNull(NostrDirect.normalizeRelay("ws://relay.example.org"))   // clear text only over Tor's .onion
        assertNull(NostrDirect.normalizeRelay("https://relay.example.org"))
        assertNull(NostrDirect.normalizeRelay("localhost"))
        assertNull(NostrDirect.normalizeRelay("wss://user@relay.example.org"))
        assertNull(NostrDirect.normalizeRelay("not a url"))
    }
}
