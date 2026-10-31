package org.openchat.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.crypto.Crypto

/** Private groups wire format and crypto (docs/protocol-spec.md §22). */
class GroupsTest {
    private val creatorKeys = Crypto.ed25519Generate()
    private val memberKeys = Crypto.ed25519Generate()
    private val creator = GroupMember("aa".repeat(32), creatorKeys.publicKey, "张衡")
    private val member = GroupMember("bb".repeat(32), memberKeys.publicKey, "Lovelace")
    private val group = Group(ByteArray(16) { it.toByte() }, "观星", 2, listOf(creator, member), creator.fingerprint)
    private val key = ByteArray(32) { 9 }

    @Test
    fun rosterIsFixedLayout() {
        val bytes = Roster.encode(listOf(creator))!!
        assertEquals(1 + 32 + 32 + 1 + "张衡".toByteArray().size, bytes.size)
        assertEquals(1, bytes[0].toInt())
        assertEquals(listOf(creator), Roster.decode(bytes))
        assertNull(Roster.decode(bytes + byteArrayOf(0))) // trailing bytes
        // A nickname over 64 bytes is cut on a code-point boundary and still decodes.
        val long = GroupMember("cc".repeat(32), memberKeys.publicKey, "苏".repeat(30))
        assertEquals(21, Roster.decode(Roster.encode(listOf(long))!!)!![0].nickname.length)
    }

    @Test
    fun stateIsSignedByTheCreatorAndRoundTrips() {
        val state = GroupState.signed(group, key, creatorKeys)!!
        val back = GroupState.decode(state.encode()!!)!!
        assertTrue(back.verify())
        assertEquals(group, back.group)
        assertArrayEquals(key, back.key)
        // Any change to name, key or roster breaks the signature.
        assertFalse(GroupState(group.copy(name = "观月"), key, state.signature).verify())
        assertFalse(GroupState(group, ByteArray(32), state.signature).verify())
        assertFalse(GroupState(group.copy(members = listOf(creator)), key, state.signature).verify())
        // Signed by a member instead of the creator: rejected.
        assertFalse(GroupState.signed(group, key, memberKeys)!!.verify())
    }

    @Test
    fun messageOpensOnlyWithTheRightKeyAndEpoch() {
        val sealed = GroupMessage.seal("今夜无云", "M-1", "Lovelace", memberKeys, 1_800_000_000_000L, group.groupId, group.epoch, key)
        val env = GroupMessage.envelope(sealed)!!
        assertArrayEquals(group.groupId, env.groupId)
        assertEquals(2L, env.epoch)
        val p = GroupMessage.open(env, key)!!
        assertEquals("今夜无云", p.content); assertEquals("M-1", p.messageId)
        assertArrayEquals(memberKeys.publicKey, p.senderSigningKey)
        assertEquals(member, group.memberWithKey(p.senderSigningKey))
        assertNull(GroupMessage.open(env, ByteArray(32) { 1 }))
        // The epoch is bound into the AEAD: relabelling it fails.
        assertNull(GroupMessage.open(GroupMessage.Envelope(env.groupId, 3, env.nonce, env.ciphertext), key))
    }

    @Test
    fun creatorPinning() {
        val other = Crypto.ed25519Generate()
        val takeover = group.copy(members = listOf(creator.copy(signingKey = other.publicKey), member))
        assertTrue(group.sameCreator(group.copy(epoch = 9)))
        assertFalse(group.sameCreator(takeover))
    }
}
