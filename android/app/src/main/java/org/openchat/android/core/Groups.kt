package org.openchat.android.core

import org.openchat.android.crypto.Crypto

/** One member as pinned in the creator-signed roster (docs/protocol-spec.md §22.1). */
data class GroupMember(val fingerprint: String, val signingKey: ByteArray, val nickname: String) {
    override fun equals(other: Any?) = other is GroupMember && fingerprint == other.fingerprint &&
        signingKey.contentEquals(other.signingKey) && nickname == other.nickname
    override fun hashCode() = fingerprint.hashCode()
}

/** A creator-managed private group. The key is held next to it, not inside. */
data class Group(
    val groupId: ByteArray,
    val name: String,
    val epoch: Long,
    val members: List<GroupMember>,
    val creatorFingerprint: String,
) {
    val idHex get() = groupId.toHex()
    val creator get() = members.firstOrNull { it.fingerprint == creatorFingerprint }
    fun isMember(fingerprint: String) = members.any { it.fingerprint == fingerprint }
    fun memberWithKey(signingKey: ByteArray) = members.firstOrNull { it.signingKey.contentEquals(signingKey) }

    /** Same creator, fingerprint and signing key: a group never changes hands. */
    fun sameCreator(other: Group): Boolean {
        val a = creator ?: return false
        val b = other.creator ?: return false
        return a.fingerprint == b.fingerprint && a.signingKey.contentEquals(b.signingKey)
    }

    override fun equals(other: Any?) = other is Group && groupId.contentEquals(other.groupId) && name == other.name &&
        epoch == other.epoch && members == other.members && creatorFingerprint == other.creatorFingerprint
    override fun hashCode() = groupId.contentHashCode()

    companion object {
        const val MAX_MEMBERS = 16
        const val MAX_NAME = 40
        const val ID_LENGTH = 16
        const val KEY_LENGTH = 32
    }
}

/** Group TLVs: 1-byte type, 2-byte big-endian length. */
internal object GroupTlv {
    fun put(w: ByteWriter, type: Int, v: ByteArray) { require(v.size <= 0xFFFF); w.u8(type).u16(v.size).bytes(v) }

    fun parse(b: ByteArray): List<Pair<Int, ByteArray>>? {
        val r = ByteReader(b)
        val out = ArrayList<Pair<Int, ByteArray>>()
        while (r.remaining > 0) {
            if (r.remaining < 3) return null
            val t = r.u8()!!
            val v = r.bytes(r.u16()!!) ?: return null
            out.add(t to v)
        }
        return out
    }

    fun be32(v: Long) = ByteWriter(4).u32(v).toByteArray()
}

object Roster {
    private const val MAX_NICK = 64

    fun encode(members: List<GroupMember>): ByteArray? {
        if (members.size > Group.MAX_MEMBERS) return null
        val w = ByteWriter(1 + members.size * 80).u8(members.size)
        for (m in members) {
            val fp = m.fingerprint.hexToBytes()?.takeIf { it.size == 32 } ?: return null
            if (m.signingKey.size != 32) return null
            val nick = truncate(m.nickname)
            w.bytes(fp).bytes(m.signingKey).u8(nick.size).bytes(nick)
        }
        return w.toByteArray()
    }

    fun decode(b: ByteArray): List<GroupMember>? {
        val r = ByteReader(b)
        val n = r.u8() ?: return null
        if (n > Group.MAX_MEMBERS) return null
        val out = ArrayList<GroupMember>()
        repeat(n) {
            val fp = r.bytes(32) ?: return null
            val key = r.bytes(32) ?: return null
            val nick = r.bytes(r.u8() ?: return null) ?: return null
            out.add(GroupMember(fp.toHex(), key, String(nick, Charsets.UTF_8)))
        }
        return if (r.remaining == 0) out else null
    }

    /** At most 64 UTF-8 bytes, dropping whole code points so the result stays valid. */
    private fun truncate(nick: String): ByteArray {
        var s = nick
        while (s.toByteArray(Charsets.UTF_8).size > MAX_NICK) s = s.substring(0, s.offsetByCodePoints(s.length, -1))
        return s.toByteArray(Charsets.UTF_8)
    }
}

/** Creator-signed group state, sent as groupInvite or groupKeyUpdate (§22.1, §22.2). */
class GroupState(val group: Group, val key: ByteArray, val signature: ByteArray) {
    fun encode(): ByteArray? {
        val roster = Roster.encode(group.members) ?: return null
        val creator = group.creatorFingerprint.hexToBytes()?.takeIf { it.size == 32 } ?: return null
        val w = ByteWriter(256)
        GroupTlv.put(w, 1, group.groupId)
        GroupTlv.put(w, 2, group.name.toByteArray(Charsets.UTF_8))
        GroupTlv.put(w, 3, key)
        GroupTlv.put(w, 4, GroupTlv.be32(group.epoch))
        GroupTlv.put(w, 5, roster)
        GroupTlv.put(w, 6, creator)
        GroupTlv.put(w, 7, signature)
        return w.toByteArray()
    }

    /** The signature checks out against the creator's key in the roster. */
    fun verify(): Boolean {
        val creator = group.creator ?: return false
        val roster = Roster.encode(group.members) ?: return false
        return Crypto.ed25519Verify(creator.signingKey, signingContent(group, key, roster), signature)
    }

    companion object {
        private val DOMAIN = "bitchat-group-v1".toByteArray(Charsets.US_ASCII)

        fun signingContent(g: Group, key: ByteArray, roster: ByteArray): ByteArray =
            ByteWriter(128).bytes(DOMAIN).bytes(g.groupId).u32(g.epoch).bytes(sha256(key)).bytes(sha256(roster))
                .bytes(sha256(g.name.toByteArray(Charsets.UTF_8))).toByteArray()

        fun signed(g: Group, key: ByteArray, signing: Crypto.Ed25519KeyPair): GroupState? {
            val roster = Roster.encode(g.members) ?: return null
            return GroupState(g, key, Crypto.ed25519Sign(signing.privateKey, signingContent(g, key, roster)))
        }

        fun decode(b: ByteArray): GroupState? {
            var id: ByteArray? = null; var name: String? = null; var key: ByteArray? = null; var epoch: Long? = null
            var members: List<GroupMember>? = null; var creator: String? = null; var sig: ByteArray? = null
            for ((t, v) in GroupTlv.parse(b) ?: return null) when (t) {
                1 -> if (v.size == Group.ID_LENGTH) id = v
                2 -> name = String(v, Charsets.UTF_8)
                3 -> if (v.size == Group.KEY_LENGTH) key = v
                4 -> if (v.size == 4) epoch = ByteReader(v).u32()
                5 -> members = Roster.decode(v)
                6 -> if (v.size == 32) creator = v.toHex()
                7 -> if (v.size == 64) sig = v
            }
            val m = members?.takeIf { it.isNotEmpty() } ?: return null
            return GroupState(Group(id ?: return null, name ?: return null, epoch ?: return null, m, creator ?: return null), key ?: return null, sig ?: return null)
        }
    }
}

/** What a member reads from a group message once opened and verified. */
class GroupPlaintext(val messageId: String, val senderSigningKey: ByteArray, val nickname: String, val timestampMs: Long, val content: String)

/** Group message payload (`0x25`, §22.3): clear group ID, epoch and nonce; everything else sealed. */
object GroupMessage {
    private val DOMAIN = "bitchat-group-msg-v1".toByteArray(Charsets.US_ASCII)

    class Envelope(val groupId: ByteArray, val epoch: Long, val nonce: ByteArray, val ciphertext: ByteArray)

    private fun signingContent(groupId: ByteArray, epoch: Long, id: String, ts: Long, content: String) =
        ByteWriter(64 + content.length * 3).bytes(DOMAIN).bytes(groupId).u32(epoch).bytes(id.toByteArray(Charsets.UTF_8))
            .u64(ts).bytes(content.toByteArray(Charsets.UTF_8)).toByteArray()

    private fun aad(groupId: ByteArray, epoch: Long) = groupId + GroupTlv.be32(epoch)

    fun seal(content: String, messageId: String, nickname: String, signing: Crypto.Ed25519KeyPair, timestampMs: Long,
             groupId: ByteArray, epoch: Long, key: ByteArray): ByteArray {
        val sig = Crypto.ed25519Sign(signing.privateKey, signingContent(groupId, epoch, messageId, timestampMs, content))
        val inner = ByteWriter(128 + content.length * 3)
        GroupTlv.put(inner, 1, messageId.toByteArray(Charsets.UTF_8))
        GroupTlv.put(inner, 2, signing.publicKey)
        GroupTlv.put(inner, 3, nickname.toByteArray(Charsets.UTF_8))
        GroupTlv.put(inner, 4, ByteWriter(8).u64(timestampMs).toByteArray())
        GroupTlv.put(inner, 5, content.toByteArray(Charsets.UTF_8))
        GroupTlv.put(inner, 6, sig)
        val nonce = Crypto.randomBytes(12)
        val ct = Crypto.chachaSeal(key, nonce, inner.toByteArray(), aad(groupId, epoch))
        val w = ByteWriter(ct.size + 48)
        GroupTlv.put(w, 1, groupId); GroupTlv.put(w, 2, GroupTlv.be32(epoch)); GroupTlv.put(w, 3, nonce); GroupTlv.put(w, 4, ct)
        return w.toByteArray()
    }

    fun envelope(b: ByteArray): Envelope? {
        var id: ByteArray? = null; var epoch: Long? = null; var nonce: ByteArray? = null; var ct: ByteArray? = null
        for ((t, v) in GroupTlv.parse(b) ?: return null) when (t) {
            1 -> if (v.size == Group.ID_LENGTH) id = v
            2 -> if (v.size == 4) epoch = ByteReader(v).u32()
            3 -> if (v.size == 12) nonce = v
            4 -> if (v.isNotEmpty()) ct = v
        }
        return Envelope(id ?: return null, epoch ?: return null, nonce ?: return null, ct ?: return null)
    }

    /** Decrypts and checks the author's signature; whether the author is a member is the caller's check. */
    fun open(e: Envelope, key: ByteArray): GroupPlaintext? {
        if (e.ciphertext.size <= 16) return null
        val inner = Crypto.chachaOpen(key, e.nonce, e.ciphertext, aad(e.groupId, e.epoch)) ?: return null
        var id: String? = null; var signer: ByteArray? = null; var nick: String? = null
        var ts: Long? = null; var content: String? = null; var sig: ByteArray? = null
        for ((t, v) in GroupTlv.parse(inner) ?: return null) when (t) {
            1 -> id = String(v, Charsets.UTF_8)
            2 -> if (v.size == 32) signer = v
            3 -> nick = String(v, Charsets.UTF_8)
            4 -> if (v.size == 8) ts = ByteReader(v).u64()
            5 -> content = String(v, Charsets.UTF_8)
            6 -> if (v.size == 64) sig = v
        }
        val p = GroupPlaintext(id?.takeIf { it.isNotEmpty() } ?: return null, signer ?: return null, nick ?: return null, ts ?: return null, content ?: return null)
        val ok = Crypto.ed25519Verify(p.senderSigningKey, signingContent(e.groupId, e.epoch, p.messageId, p.timestampMs, p.content), sig ?: return null)
        return if (ok) p else null
    }
}
