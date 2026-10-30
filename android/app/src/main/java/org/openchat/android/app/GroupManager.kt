package org.openchat.android.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.openchat.android.R
import org.openchat.android.core.Group
import org.openchat.android.core.GroupMember
import org.openchat.android.core.GroupMessage
import org.openchat.android.core.GroupState
import org.openchat.android.core.hexToBytes
import org.openchat.android.core.sha256
import org.openchat.android.core.toHex
import org.openchat.android.crypto.Crypto
import org.openchat.android.mesh.MeshEngine
import java.util.UUID

/**
 * Private groups (docs/protocol-spec.md §22): the creator signs roster and key and
 * hands them out over Noise; messages are sealed with the group key and flooded.
 * Runs on the mesh thread. Groups and keys are sealed on disk and die with a panic wipe.
 */
class GroupManager(
    private val context: Context,
    private val store: SealedFile,
    private val engine: () -> MeshEngine,
    private val notify: (title: String, text: String) -> Unit,
) {
    private class Held(val group: Group, val key: ByteArray)

    private val groups = LinkedHashMap<String, Held>() // group ID hex -> group and current key
    private val seen = LinkedHashSet<String>()          // message IDs shown, so replays and sync copies show once

    fun load() {
        store.read()?.let { decode(it) }?.forEach { groups[it.group.idHex] = it }
        publish()
    }

    fun wipe() { groups.clear(); seen.clear(); store.delete(); publish() }

    private val me get() = engine().identity
    private val myFingerprint get() = sha256(me.noise.publicKey).toHex()
    private fun conv(idHex: String) = Conversation.Group(idHex).key
    private fun local(idHex: String, res: Int, vararg args: Any) = ChatRepository.addLocal(conv(idHex), context.getString(res, *args))

    // ------------------------------------------------------------------ commands

    /** Creates a group with only us in it and returns its conversation key, or null. */
    fun create(rawName: String): String? {
        val name = rawName.trim().take(Group.MAX_NAME).ifEmpty { return null }
        val self = GroupMember(myFingerprint, me.signing.publicKey, me.nickname)
        val g = Group(Crypto.randomBytes(Group.ID_LENGTH), name, 1, listOf(self), myFingerprint)
        put(g, Crypto.randomBytes(Group.KEY_LENGTH))
        local(g.idHex, R.string.group_created, name)
        return conv(g.idHex)
    }

    fun invite(idHex: String, peerHex: String) {
        val held = groups[idHex] ?: return
        if (held.group.creatorFingerprint != myFingerprint) return local(idHex, R.string.group_creator_only)
        val peer = engine().peersSnapshot().firstOrNull { it.peerIdHex == peerHex }
        if (peer == null || !engine().isReachable(peerHex)) return local(idHex, R.string.group_not_connected, peerHex.take(8))
        val fp = sha256(peer.noisePublicKey).toHex()
        val label = PeerLabel.mesh(peer.nickname, peerHex).toString()
        if (held.group.isMember(fp)) return local(idHex, R.string.group_already, label)
        if (held.group.members.size >= Group.MAX_MEMBERS) return local(idHex, R.string.group_full, Group.MAX_MEMBERS)
        // Every roster change gets a new key and epoch, so states have a strict order.
        val g = held.group.copy(epoch = held.group.epoch + 1, members = held.group.members + GroupMember(fp, peer.signingPublicKey, peer.nickname))
        val key = Crypto.randomBytes(Group.KEY_LENGTH)
        val state = GroupState.signed(g, key, me.signing)?.encode() ?: return
        put(g, key)
        engine().sendGroupState(peerHex, invite = true, payload = state)
        distribute(g, state, except = setOf(fp))
        local(idHex, R.string.group_invited, label, g.name)
    }

    fun remove(idHex: String, fingerprint: String) {
        val held = groups[idHex] ?: return
        if (held.group.creatorFingerprint != myFingerprint) return local(idHex, R.string.group_creator_only)
        val gone = held.group.members.firstOrNull { it.fingerprint == fingerprint } ?: return
        if (fingerprint == myFingerprint) return
        val g = held.group.copy(epoch = held.group.epoch + 1, members = held.group.members - gone)
        val key = Crypto.randomBytes(Group.KEY_LENGTH)
        val state = GroupState.signed(g, key, me.signing)?.encode() ?: return
        put(g, key)
        distribute(g, state, except = emptySet())
        // The removed member learns it is out from a state with a zero key, never the new one.
        GroupState.signed(g, ByteArray(Group.KEY_LENGTH), me.signing)?.encode()?.let { notice ->
            reachable(fingerprint)?.let { engine().sendGroupState(it, invite = false, payload = notice) }
        }
        local(idHex, R.string.group_removed_member, gone.nickname)
    }

    /** Leaving is local: the group and its key are forgotten. */
    fun leave(idHex: String) {
        val held = groups.remove(idHex) ?: return
        save(); publish()
        ChatRepository.clear(conv(idHex))
        ChatRepository.addLocal(Conversation.Mesh.key, context.getString(R.string.group_left, held.group.name))
    }

    fun send(idHex: String, text: String) {
        val held = groups[idHex] ?: return
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString().uppercase()
        val payload = GroupMessage.seal(text, id, me.nickname, me.signing, now, held.group.groupId, held.group.epoch, held.key)
        seen.add(id)
        ChatRepository.add(ChatMessage(id, conv(idHex), me.nickname, me.peerIdHex, text, now, mine = true))
        engine().broadcastGroupMessage(payload)
    }

    // ------------------------------------------------------------------ incoming

    fun onState(peerHex: String, sessionKey: ByteArray, invite: Boolean, payload: ByteArray) {
        val state = GroupState.decode(payload) ?: return
        val g = state.group
        // The authenticated session peer must be the creator the state names, and must have signed it.
        if (sha256(sessionKey).toHex() != g.creatorFingerprint || !state.verify()) return
        val existing = groups[g.idHex]
        if (existing != null && (!existing.group.sameCreator(g) || g.epoch < existing.group.epoch)) return
        if (!g.isMember(myFingerprint)) {
            if (existing != null) {
                groups.remove(g.idHex); save(); publish()
                ChatRepository.addLocal(Conversation.Mesh.key, context.getString(R.string.group_removed_from, existing.group.name))
            }
            return
        }
        put(g, state.key)
        if (existing == null) {
            val creator = g.creator?.nickname ?: peerHex.take(8)
            val line = context.getString(R.string.group_joined, g.name, creator)
            local(g.idHex, R.string.group_joined, g.name, creator)
            notify(creator, line)
        }
    }

    fun onMessage(payload: ByteArray, timestamp: Long) {
        val e = GroupMessage.envelope(payload) ?: return
        val held = groups[e.groupId.toHex()] ?: return // not ours: relayed, never read
        if (e.epoch != held.group.epoch) return
        val p = GroupMessage.open(e, held.key) ?: return
        val member = held.group.memberWithKey(p.senderSigningKey) ?: return // key alone is not membership
        if (p.senderSigningKey.contentEquals(me.signing.publicKey)) return
        if (member.fingerprint in Social.state.value.blocked) return
        if (!seen.add(p.messageId)) return
        while (seen.size > 2000) seen.remove(seen.first())
        val name = member.nickname.ifEmpty { p.nickname }
        val at = minOf(p.timestampMs, System.currentTimeMillis())
        val c = conv(held.group.idHex)
        ChatRepository.add(ChatMessage(p.messageId, c, name, member.fingerprint.take(16), p.content, at, mine = false))
        if (ChatRepository.openConversation != c && System.currentTimeMillis() - at < 30_000) notify("$name · ${held.group.name}", p.content)
    }

    // ------------------------------------------------------------------ internals

    private fun put(g: Group, key: ByteArray) { groups[g.idHex] = Held(g, key); save(); publish() }

    private fun reachable(fingerprint: String): String? = fingerprint.take(16).takeIf { engine().isReachable(it) }

    private fun distribute(g: Group, state: ByteArray, except: Set<String>) {
        for (m in g.members) {
            if (m.fingerprint == myFingerprint || m.fingerprint in except) continue
            reachable(m.fingerprint)?.let { engine().sendGroupState(it, invite = false, payload = state) }
        }
    }

    private fun publish() {
        val mine = myFingerprint
        ChatRepository.setGroups(groups.values.associate { h ->
            h.group.idHex to GroupInfo(h.group.idHex, h.group.name, h.group.members, h.group.creatorFingerprint, h.group.creatorFingerprint == mine, mine)
        })
    }

    private fun save() = store.write(encode(groups.values).toByteArray(Charsets.UTF_8))

    private fun encode(all: Collection<Held>): String = JSONArray().apply {
        all.forEach { h ->
            val g = h.group
            put(JSONObject().put("id", g.idHex).put("name", g.name).put("epoch", g.epoch).put("creator", g.creatorFingerprint)
                .put("key", h.key.toHex()).put("members", JSONArray().apply {
                    g.members.forEach { m -> put(JSONObject().put("fp", m.fingerprint).put("sk", m.signingKey.toHex()).put("nick", m.nickname)) }
                }))
        }
    }.toString()

    private fun decode(b: ByteArray): List<Held>? = try {
        val arr = JSONArray(String(b, Charsets.UTF_8))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val ms = o.getJSONArray("members")
            val members = (0 until ms.length()).map { j ->
                val m = ms.getJSONObject(j)
                GroupMember(m.getString("fp"), m.getString("sk").hexToBytes()!!, m.getString("nick"))
            }
            val g = Group(o.getString("id").hexToBytes()!!, o.getString("name"), o.getLong("epoch"), members, o.getString("creator"))
            Held(g, o.getString("key").hexToBytes()!!)
        }
    } catch (e: Exception) { null }
}

/** What the UI shows of a group. */
data class GroupInfo(
    val idHex: String,
    val name: String,
    val members: List<GroupMember>,
    val creatorFingerprint: String,
    val iAmCreator: Boolean,
    val myFingerprint: String,
)
