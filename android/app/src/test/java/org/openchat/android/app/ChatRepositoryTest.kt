package org.openchat.android.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRepositoryTest {
    private val conv = Conversation.Private("0011223344556677").key

    @After
    fun tearDown() = ChatRepository.wipe()

    private fun mine(id: String, state: DeliveryState) =
        ChatRepository.add(ChatMessage(id, conv, "me", "aabbccddeeff0011", "hello", 1_000, mine = true, state = state))

    private fun stateOf(id: String) = ChatRepository.messages.value[conv]!!.first { it.id == id }.state

    @Test
    fun aWipeLeavesNothingInMemoryEvenWithNoServiceToRepublish() {
        mine("m0", DeliveryState.SENT)
        ChatRepository.setGroups(mapOf("ab" to GroupInfo("ab", "friends", emptyList(), "fp", iAmCreator = true, myFingerprint = "fp")))
        ChatRepository.setBridge(BridgeStatus(enabled = true, cell = "wtw3sj", online = true, across = 4))
        ChatRepository.requestOpen(conv)
        ChatRepository.rememberName("0011223344556677", "alice")

        ChatRepository.wipe()

        assertTrue(ChatRepository.messages.value.isEmpty())
        assertTrue(ChatRepository.groups.value.isEmpty())
        assertEquals(BridgeStatus(), ChatRepository.bridge.value)
        assertEquals(null, ChatRepository.openRequest.value)
        assertTrue(ChatRepository.names.value.isEmpty())
    }

    @Test
    fun aReceiptThatComesAfterGivingUpStillCounts() {
        // The outbox gave up after a day; the courier handed the message over anyway and the receipt came back later.
        mine("m1", DeliveryState.CARRIED)
        ChatRepository.setState(conv, "m1", DeliveryState.FAILED)
        assertEquals(DeliveryState.FAILED, stateOf("m1"))
        ChatRepository.setState(conv, "m1", DeliveryState.DELIVERED)
        assertEquals(DeliveryState.DELIVERED, stateOf("m1"))
        ChatRepository.setState(conv, "m1", DeliveryState.READ)
        assertEquals(DeliveryState.READ, stateOf("m1"))
    }

    @Test
    fun givingUpNeverHidesAReceipt() {
        mine("m2", DeliveryState.SENT)
        ChatRepository.setState(conv, "m2", DeliveryState.DELIVERED)
        ChatRepository.setState(conv, "m2", DeliveryState.FAILED)
        assertEquals(DeliveryState.DELIVERED, stateOf("m2"))
        ChatRepository.setState(conv, "m2", DeliveryState.READ)
        ChatRepository.setState(conv, "m2", DeliveryState.FAILED)
        assertEquals(DeliveryState.READ, stateOf("m2"))
    }

    @Test
    fun statesOtherwiseOnlyMoveForward() {
        mine("m3", DeliveryState.CARRIED)
        ChatRepository.setState(conv, "m3", DeliveryState.QUEUED)
        assertEquals(DeliveryState.CARRIED, stateOf("m3"))
        ChatRepository.setState(conv, "m3", DeliveryState.READ)
        ChatRepository.setState(conv, "m3", DeliveryState.DELIVERED)
        assertEquals(DeliveryState.READ, stateOf("m3"))
        // A failed message is not revived by anything short of a receipt.
        assertFalse(DeliveryState.FAILED.canBecome(DeliveryState.SENT))
        assertFalse(DeliveryState.FAILED.canBecome(DeliveryState.CARRIED))
        assertTrue(DeliveryState.QUEUED.canBecome(DeliveryState.FAILED))
        assertTrue(DeliveryState.CARRIED.canBecome(DeliveryState.FAILED))
    }
}
