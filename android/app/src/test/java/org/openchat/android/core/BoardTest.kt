package org.openchat.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.crypto.Crypto

/** Notice board wire format and store rules (docs/protocol-spec.md §23). */
class BoardTest {
    private var now = 1_800_000_000_000L
    private val keys = Crypto.ed25519Generate()
    private val day = 24 * 3600_000L
    private fun pkt(payload: ByteArray) = Packet(type = MessageType.BOARD_POST, senderId = ByteArray(8), timestamp = now, payload = payload)
    private fun post(text: String = "水在北门", k: Crypto.Ed25519KeyPair = keys, days: Int = 3, urgent: Boolean = false) =
        Board.newPost(text, "", "张衡", k, now, days, urgent)!!

    @Test
    fun postRoundTripsAndVerifies() {
        val p = post(urgent = true)
        val back = Board.decode(Board.encode(p)) as BoardPost
        assertTrue(back.verify())
        assertEquals("水在北门", back.content); assertTrue(back.urgent)
        assertEquals(now + 3 * day, back.expiresAt)
        assertArrayEquals(p.postId, back.postId)
        // Tampering with the text breaks the signature.
        val forged = BoardPost(p.postId, "", "水在南门", p.authorKey, p.nickname, p.createdAt, p.expiresAt, p.flags, p.signature)
        assertFalse(forged.verify())
        assertEquals(7 * day, Board.newPost("x", "", "n", keys, now, 30, false)!!.let { it.expiresAt - it.createdAt })
        assertNull(Board.newPost("x".repeat(513), "", "n", keys, now, 1, false))
    }

    @Test
    fun decodeRejectsBadShapes() {
        val p = post()
        val tooLong = BoardPost(p.postId, "", p.content, p.authorKey, p.nickname, now, now + 8 * day, 0, p.signature)
        assertNull(Board.decode(Board.encode(tooLong)))
        val badCell = BoardPost(p.postId, "WTW!", p.content, p.authorKey, p.nickname, now, now + day, 0, p.signature)
        assertNull(Board.decode(Board.encode(badCell)))
        val t = Board.tombstoneFor(p, keys, now)
        assertTrue((Board.decode(Board.encode(t)) as BoardTombstone).verify())
    }

    @Test
    fun storeKeepsFivePerAuthorAndHonoursDeletes() {
        val store = BoardStore { now }
        val posts = (1..6).map { now += 1000; post("notice $it") }
        posts.forEach { assertEquals(BoardStore.Result.ACCEPTED, store.ingest(it, pkt(Board.encode(it)))) }
        assertEquals(5, store.posts().size)
        assertFalse(store.posts().any { it.content == "notice 1" }) // oldest evicted
        assertEquals(BoardStore.Result.DUPLICATE, store.ingest(posts[5], pkt(Board.encode(posts[5]))))
        // Only the author can take a post down.
        val other = Crypto.ed25519Generate()
        val notMine = BoardTombstone(posts[5].postId, other.publicKey, now, Crypto.ed25519Sign(other.privateKey, Board.tombstoneSigningBytes(posts[5].postId, now)))
        assertEquals(BoardStore.Result.REJECTED, store.ingest(notMine, pkt(Board.encode(notMine))))
        val t = Board.tombstoneFor(posts[5], keys, now)
        assertEquals(BoardStore.Result.ACCEPTED, store.ingest(t, pkt(Board.encode(t))))
        assertEquals(4, store.posts().size)
        // A stale copy of the deleted post arriving later stays out.
        assertEquals(BoardStore.Result.REJECTED, store.ingest(posts[5], pkt(Board.encode(posts[5]))))
    }

    @Test
    fun storeRejectsExpiredAndFutureDatedPosts() {
        val store = BoardStore { now }
        val p = post(days = 1)
        now += 2 * day
        assertEquals(BoardStore.Result.REJECTED, store.ingest(p, pkt(Board.encode(p))))
        val future = Board.newPost("from tomorrow", "", "n", keys, now + 2 * 3600_000L, 1, false)!!
        assertEquals(BoardStore.Result.REJECTED, store.ingest(future, pkt(Board.encode(future))))
        // A tombstone that raced ahead suppresses the post when it comes.
        val q = post("later")
        val t = Board.tombstoneFor(q, keys, now)
        assertEquals(BoardStore.Result.ACCEPTED, store.ingest(t, pkt(Board.encode(t))))
        assertEquals(BoardStore.Result.REJECTED, store.ingest(q, pkt(Board.encode(q))))
    }
}
