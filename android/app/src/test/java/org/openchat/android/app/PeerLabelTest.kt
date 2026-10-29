package org.openchat.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PeerLabelTest {
    @Test fun meshUsesFirstFourOfPeerId() {
        assertEquals("苏轼#e887", PeerLabel.mesh("苏轼", "E88713F0B45C0011").toString())
    }

    @Test fun nostrUsesLastFourOfPubkey() {
        assertEquals("Curie#9a0b", PeerLabel.nostr("Curie", "00".repeat(30) + "9a0b").toString())
    }

    @Test fun duplicateNicknamesStayDistinct() {
        assertNotEquals(PeerLabel.mesh("苏轼", "e88713f0b45c0011").toString(), PeerLabel.mesh("苏轼", "0c3b9e21d7f80022").toString())
    }

    @Test fun blankNicknameBecomesAnon() {
        assertEquals("anon#a1f4", PeerLabel.mesh("  ", "a1f4c09e77b20000").toString())
        assertEquals("anon#a1f4", PeerLabel.mesh(null, "a1f4c09e77b20000").toString())
    }

    @Test fun typedSuffixCannotImpersonate() {
        assertEquals(PeerLabel("苏轼", "#0c3b"), PeerLabel.mesh("苏轼#e887", "0c3b9e21d7f80022"))
        assertEquals(PeerLabel("苏轼", "#0c3b"), PeerLabel.mesh("苏轼#E887#1234", "0c3b9e21d7f80022"))
        assertEquals("anon#0c3b", PeerLabel.mesh("#e887", "0c3b9e21d7f80022").toString())
    }

    @Test fun geoMessagesUseNostrRule() {
        val m = ChatMessage("1", Conversation.Geo("wtw3s").key, "", "ff".repeat(31) + "beef", "hi", 0, mine = false)
        assertEquals("anon#beef", PeerLabel.of(m).toString())
    }

    @Test fun missingKeyGivesNoSuffix() {
        assertEquals("李白", PeerLabel.mesh("李白", "").toString())
    }
}
