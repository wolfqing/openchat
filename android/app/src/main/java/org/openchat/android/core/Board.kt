package org.openchat.android.core

import org.openchat.android.crypto.Crypto

/**
 * Notice board (docs/protocol-spec.md §23): signed posts that outlive chat for up to
 * seven days, and signed deletions. Self-authenticating, so they verify long after
 * the author has left. TLVs: 1-byte type, 2-byte big-endian length.
 */
class BoardPost(
    val postId: ByteArray,
    val geohash: String, // "" = the mesh board
    val content: String,
    val authorKey: ByteArray,
    val nickname: String,
    val createdAt: Long,
    val expiresAt: Long,
    val flags: Int,
    val signature: ByteArray,
) {
    val urgent get() = flags and URGENT != 0
    val idHex get() = postId.toHex()

    fun signingBytes() = Board.postSigningBytes(postId, geohash, content, authorKey, nickname, createdAt, expiresAt, flags)
    fun verify() = Crypto.ed25519Verify(authorKey, signingBytes(), signature)

    companion object { const val URGENT = 0x01 }
}

class BoardTombstone(val postId: ByteArray, val authorKey: ByteArray, val deletedAt: Long, val signature: ByteArray) {
    fun verify() = Crypto.ed25519Verify(authorKey, Board.tombstoneSigningBytes(postId, deletedAt), signature)
}

object Board {
    const val ID_LENGTH = 16
    const val MAX_CONTENT = 512
    const val MAX_NICK = 64
    const val MAX_LIFETIME_MS = 7 * 24 * 3600_000L
    private const val GEOHASH_CHARS = "0123456789bcdefghjkmnpqrstuvwxyz"

    private fun context(w: ByteWriter, s: String) { val b = s.toByteArray(Charsets.US_ASCII); w.u8(b.size).bytes(b) }
    private fun prefixed(w: ByteWriter, b: ByteArray) { w.u16(b.size).bytes(b) }

    fun postSigningBytes(id: ByteArray, geohash: String, content: String, key: ByteArray, nick: String, created: Long, expires: Long, flags: Int): ByteArray {
        val w = ByteWriter(128 + content.length * 3)
        context(w, "bitchat-board-v1")
        w.bytes(id)
        prefixed(w, geohash.toByteArray(Charsets.UTF_8))
        prefixed(w, content.toByteArray(Charsets.UTF_8))
        w.bytes(key)
        prefixed(w, nick.toByteArray(Charsets.UTF_8))
        w.u64(created).u64(expires).u8(flags)
        return w.toByteArray()
    }

    fun tombstoneSigningBytes(id: ByteArray, deletedAt: Long): ByteArray {
        val w = ByteWriter(48)
        context(w, "bitchat-board-del-v1")
        return w.bytes(id).u64(deletedAt).toByteArray()
    }

    /** Signs and encodes a new post; content is trimmed and must fit 512 bytes. */
    fun newPost(content: String, geohash: String, nickname: String, signing: Crypto.Ed25519KeyPair, nowMs: Long, days: Int, urgent: Boolean): BoardPost? {
        val text = content.trim()
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > MAX_CONTENT) return null
        var nick = nickname
        while (nick.toByteArray(Charsets.UTF_8).size > MAX_NICK) nick = nick.substring(0, nick.offsetByCodePoints(nick.length, -1))
        val id = Crypto.randomBytes(ID_LENGTH)
        val expires = nowMs + minOf(days.coerceAtLeast(1) * 24 * 3600_000L, MAX_LIFETIME_MS)
        val flags = if (urgent) BoardPost.URGENT else 0
        val sig = Crypto.ed25519Sign(signing.privateKey, postSigningBytes(id, geohash, text, signing.publicKey, nick, nowMs, expires, flags))
        return BoardPost(id, geohash, text, signing.publicKey, nick, nowMs, expires, flags, sig)
    }

    fun tombstoneFor(post: BoardPost, signing: Crypto.Ed25519KeyPair, nowMs: Long) =
        BoardTombstone(post.postId, signing.publicKey, nowMs, Crypto.ed25519Sign(signing.privateKey, tombstoneSigningBytes(post.postId, nowMs)))

    fun encode(p: BoardPost): ByteArray = ByteWriter(128 + p.content.length * 3).apply {
        tlv(this, 1, byteArrayOf(1)); tlv(this, 2, p.postId); tlv(this, 3, p.geohash.toByteArray(Charsets.UTF_8))
        tlv(this, 4, p.content.toByteArray(Charsets.UTF_8)); tlv(this, 5, p.authorKey); tlv(this, 6, p.nickname.toByteArray(Charsets.UTF_8))
        tlv(this, 7, ByteWriter(8).u64(p.createdAt).toByteArray()); tlv(this, 8, ByteWriter(8).u64(p.expiresAt).toByteArray())
        tlv(this, 9, byteArrayOf(p.flags.toByte())); tlv(this, 10, p.signature)
    }.toByteArray()

    fun encode(t: BoardTombstone): ByteArray = ByteWriter(128).apply {
        tlv(this, 1, byteArrayOf(2)); tlv(this, 2, t.postId); tlv(this, 5, t.authorKey)
        tlv(this, 11, ByteWriter(8).u64(t.deletedAt).toByteArray()); tlv(this, 10, t.signature)
    }.toByteArray()

    private fun tlv(w: ByteWriter, t: Int, v: ByteArray) { w.u8(t).u16(v.size).bytes(v) }

    /** A post or a tombstone, structurally valid; the signature is checked separately. */
    fun decode(b: ByteArray): Any? {
        var kind: Int? = null; var id: ByteArray? = null; var gh: String? = null; var content: String? = null
        var key: ByteArray? = null; var nick: String? = null; var created: Long? = null; var expires: Long? = null
        var flags: Int? = null; var sig: ByteArray? = null; var deleted: Long? = null
        val r = ByteReader(b)
        while (r.remaining >= 3) {
            val t = r.u8()!!
            val v = r.bytes(r.u16()!!) ?: return null
            when (t) {
                1 -> { if (v.size != 1) return null; kind = v[0].toInt() and 0xFF }
                2 -> { if (v.size != ID_LENGTH) return null; id = v }
                3 -> { if (v.size > 12) return null; gh = String(v, Charsets.UTF_8) }
                4 -> { if (v.size > MAX_CONTENT) return null; content = if (v.isEmpty()) null else String(v, Charsets.UTF_8) }
                5 -> { if (v.size != 32) return null; key = v }
                6 -> { if (v.size > MAX_NICK) return null; nick = String(v, Charsets.UTF_8) }
                7 -> created = if (v.size == 8) ByteReader(v).u64() else null
                8 -> expires = if (v.size == 8) ByteReader(v).u64() else null
                9 -> { if (v.size != 1) return null; flags = v[0].toInt() and 0xFF }
                10 -> { if (v.size != 64) return null; sig = v }
                11 -> deleted = if (v.size == 8) ByteReader(v).u64() else null
            }
        }
        val pid = id ?: return null; val k = key ?: return null; val s = sig ?: return null
        return when (kind) {
            1 -> {
                val g = gh ?: return null; val c = content ?: return null; val n = nick ?: return null
                val cr = created ?: return null; val ex = expires ?: return null; val f = flags ?: return null
                if (!g.all { it in GEOHASH_CHARS } || ex <= cr || ex - cr > MAX_LIFETIME_MS) return null
                BoardPost(pid, g, c, k, n, cr, ex, f, s)
            }
            2 -> BoardTombstone(pid, k, deleted ?: return null, s)
            else -> null
        }
    }
}

/**
 * What a phone keeps of the board (§23.2): live posts and the deletions that must keep
 * outrunning stale copies. Same caps as bitchat. Not thread-safe: the engine thread owns it.
 */
class BoardStore(private val clock: () -> Long) {
    enum class Result { ACCEPTED, DUPLICATE, REJECTED }

    class Entry(val packet: Packet, val retainUntil: Long) // retainUntil only matters for tombstones

    private val posts = LinkedHashMap<String, Pair<BoardPost, Packet>>()
    private class Tomb(val t: BoardTombstone, val packet: Packet, val retainUntil: Long, val orphan: Boolean)
    private val tombs = LinkedHashMap<String, Tomb>()

    fun posts(): List<BoardPost> { prune(); return posts.values.map { it.first } }

    /** Every packet worth offering in sync: live posts and live tombstones. */
    fun packets(): List<Packet> { prune(); return posts.values.map { it.second } + tombs.values.map { it.packet } }

    fun entries(): List<Entry> { prune(); return posts.values.map { Entry(it.second, 0) } + tombs.values.map { Entry(it.packet, it.retainUntil) } }

    fun clear() { posts.clear(); tombs.clear() }

    /** [item] is a decoded, signature-checked post or tombstone carried by [packet]. */
    fun ingest(item: Any, packet: Packet, restoredRetainUntil: Long = 0): Result {
        val now = clock()
        prune()
        return when (item) {
            is BoardPost -> {
                if (item.expiresAt <= now) return Result.REJECTED
                // Forged future times would sort ahead of honest posts and never expire.
                if (item.createdAt > now + SKEW_MS || item.expiresAt > now + Board.MAX_LIFETIME_MS + SKEW_MS) return Result.REJECTED
                tombs[item.idHex]?.let { if (it.t.authorKey.contentEquals(item.authorKey)) return Result.REJECTED }
                if (posts.containsKey(item.idHex)) return Result.DUPLICATE
                posts[item.idHex] = item to packet
                evict(posts.values.filter { it.first.authorKey.contentEquals(item.authorKey) }, PER_AUTHOR)
                evict(posts.values.toList(), MAX_POSTS)
                Result.ACCEPTED // even if evicted here, it stays valid for others
            }
            is BoardTombstone -> {
                val id = item.postId.toHex()
                if (tombs.containsKey(id)) return Result.DUPLICATE
                val cap = minOf(item.deletedAt + Board.MAX_LIFETIME_MS, now + Board.MAX_LIFETIME_MS + SKEW_MS)
                val target = posts[id]?.first
                val (until, orphan) = when {
                    target != null -> {
                        if (!target.authorKey.contentEquals(item.authorKey)) return Result.REJECTED // only the author deletes
                        posts.remove(id)
                        target.expiresAt to false
                    }
                    restoredRetainUntil > 0 -> minOf(restoredRetainUntil, cap) to false
                    else -> cap to true // the post may still arrive; keep it suppressed
                }
                if (until <= now) return Result.REJECTED
                tombs[id] = Tomb(item, packet, until, orphan)
                if (orphan) {
                    val mine = tombs.values.filter { it.orphan && it.t.authorKey.contentEquals(item.authorKey) }
                    mine.take(maxOf(0, mine.size - PER_AUTHOR)).forEach { tombs.remove(it.t.postId.toHex()) }
                    val all = tombs.values.filter { it.orphan }
                    all.take(maxOf(0, all.size - MAX_ORPHANS)).forEach { tombs.remove(it.t.postId.toHex()) }
                }
                Result.ACCEPTED
            }
            else -> Result.REJECTED
        }
    }

    private fun evict(candidates: List<Pair<BoardPost, Packet>>, keep: Int) {
        candidates.sortedBy { it.first.createdAt }.take(maxOf(0, candidates.size - keep)).forEach { posts.remove(it.first.idHex) }
    }

    private fun prune() {
        val now = clock()
        posts.values.removeAll { it.first.expiresAt <= now }
        tombs.values.removeAll { it.retainUntil <= now }
    }

    companion object {
        const val MAX_POSTS = 200
        const val PER_AUTHOR = 5
        const val MAX_ORPHANS = 100
        const val SKEW_MS = 3600_000L
    }
}
