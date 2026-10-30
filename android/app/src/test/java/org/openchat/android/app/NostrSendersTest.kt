package org.openchat.android.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.openchat.android.core.toHex
import org.openchat.android.mesh.Identity
import org.openchat.android.nostr.Bech32

/** Over Nostr only a npub we already hold may speak for a peer (docs/protocol-spec.md §15.3). */
class NostrSendersTest {
    private val alice = Identity.generate("alice")
    private val aliceNpubHex = "ab".repeat(32)
    private val malloryNpubHex = "cd".repeat(32)

    private fun favorite(npubHex: String?) =
        Favorite(alice.noise.publicKey.toHex(), "alice", npubHex?.let { Bech32.npub(it) }, theyFavorited = true)

    @Test
    fun boundNpubSpeaksForItsPeer() {
        assertNotNull(NostrSenders.resolve(listOf(favorite(aliceNpubHex)), aliceNpubHex, alice.peerId))
    }

    @Test
    fun unknownNpubCannotClaimAPeer() {
        // Mallory signs an envelope whose embedded sender field says "alice".
        assertNull(NostrSenders.resolve(listOf(favorite(aliceNpubHex)), malloryNpubHex, alice.peerId))
    }

    @Test
    fun aFavoriteWithoutAnNpubCannotBeClaimedOverNostr() {
        assertNull(NostrSenders.resolve(listOf(favorite(null)), malloryNpubHex, alice.peerId))
    }

    @Test
    fun embeddedSenderMustMatchTheBoundKey() {
        val bob = Identity.generate("bob")
        assertNull(NostrSenders.resolve(listOf(favorite(aliceNpubHex)), aliceNpubHex, bob.peerId))
    }
}
