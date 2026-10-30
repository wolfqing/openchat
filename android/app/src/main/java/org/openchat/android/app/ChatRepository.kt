package org.openchat.android.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.openchat.android.mesh.MeshPeer

/** Conversation keys: the nearby mesh room, a private chat, or a geohash location channel. */
sealed class Conversation(val key: String) {
    object Mesh : Conversation("mesh")
    data class Private(val peerHex: String) : Conversation("dm:$peerHex")
    data class Geo(val geohash: String) : Conversation("geo:$geohash")
    /** Private chat with a location-channel persona, under my persona for that cell (§15.5). */
    data class GeoPrivate(val geohash: String, val pubkey: String) : Conversation("gdm:$geohash:$pubkey") {
        companion object {
            fun parse(key: String): GeoPrivate? = key.removePrefix("gdm:").split(':').takeIf { key.startsWith("gdm:") && it.size == 2 }?.let { GeoPrivate(it[0], it[1]) }
        }
    }
}

/** Order matters: a state only ever moves forward. QUEUED and CARRIED come from the outbox (§13). */
enum class DeliveryState { SENDING, SENT, QUEUED, CARRIED, DELIVERED, READ, FAILED }

data class ChatMessage(
    val id: String,
    val conversation: String,
    val senderName: String,
    val senderKey: String,
    val text: String,
    val timestamp: Long,
    val mine: Boolean,
    val state: DeliveryState = DeliveryState.SENT,
    val teleported: Boolean = false, // geohash sender says it is not in the cell
    val mentionsMe: Boolean = false,
    val system: Boolean = false, // a local line (command output), never sent
    val attachment: String? = null, // path of an image or voice note in the media cache (§18)
    val audio: Boolean = false,
)

/**
 * A nickname plus a #xxxx suffix. The UI shows both wherever a name appears, so duplicate
 * nicknames stay distinct (docs/design/ui-direction.md). Mesh peers use the first four hex
 * digits of the peer ID; Nostr senders use the last four of the public key, as anon#xxxx did.
 */
data class PeerLabel(val name: String, val suffix: String) {
    override fun toString() = name + suffix

    companion object {
        // A nickname typed as "name#e887" must not pass for someone else's suffix.
        private val FAKE_SUFFIX = Regex("(#[0-9A-Fa-f]{4})+$")

        private fun clean(nickname: String?) =
            nickname?.trim()?.replace(FAKE_SUFFIX, "")?.trim()?.takeIf { it.isNotEmpty() } ?: "anon"

        private fun suffix(four: String) = if (four.length == 4) "#" + four.lowercase() else ""

        fun mesh(nickname: String?, peerHex: String) = PeerLabel(clean(nickname), suffix(peerHex.take(4)))
        fun nostr(nickname: String?, pubkeyHex: String) = PeerLabel(clean(nickname), suffix(pubkeyHex.takeLast(4)))
        fun of(m: ChatMessage) =
            if (m.conversation.startsWith("geo:") || m.conversation.startsWith("gdm:")) nostr(m.senderName, m.senderKey) else mesh(m.senderName, m.senderKey)
    }
}

data class MeshStatus(
    val bluetoothOn: Boolean = false,
    val running: Boolean = false,
    val links: Int = 0,
    val myNickname: String = "",
    val myPeerHex: String = "",
    val fingerprint: String = "",
    val carriedCount: Int = 0, // sealed envelopes held for others (§13.4)
    val storageLocked: Boolean = false, // the saved identity exists but could not be opened; nothing runs until it can
)

/** A note left at a place (§17), visible to anyone nearby for 24 hours. */
data class LocationNote(val id: String, val pubkey: String, val nickname: String, val text: String, val createdAt: Long, val geohash: String, val mine: Boolean)

data class GeoStatus(
    val locationGeohash: String? = null, // full-precision cell of the device, if known
    val relaysConnected: Int = 0,
    val joined: Set<String> = emptySet(),
    val participants: Map<String, Int> = emptyMap(),
    val notesCell: String? = null,          // building-level cell whose notes are listed
    val notes: List<LocationNote> = emptyList(),
    val tor: TorState = TorState.STARTING,
)

/** UI-facing actions; the running MeshService installs itself here. */
interface ChatController {
    fun sendPublic(text: String)
    fun sendPrivate(peerHex: String, text: String)
    fun markRead(peerHex: String, messageIds: List<String>)
    /** Noise static key of a mesh peer we have heard from, if known. */
    fun noiseKey(peerHex: String): ByteArray?
    /** A fresh signed verification link for my QR code (§16). */
    fun myVerifyUrl(): String
    /** Checks a scanned QR; on success marks that identity verified and returns its nickname. */
    fun verifyScanned(url: String): String?
    fun setFavorite(peerHex: String, nickname: String, on: Boolean)
    fun myNpub(): String
    fun setNickname(name: String)
    fun joinGeo(geohash: String, teleported: Boolean)
    fun leaveGeo(geohash: String)
    fun sendGeo(geohash: String, text: String)
    fun sendGeoPrivate(geohash: String, pubkey: String, text: String)
    /** Leaves a note at the device's building-level cell; false when the place is unknown. */
    fun dropNote(text: String): Boolean
    fun watchNotes(cell: String?)
    /** Sends a photo or voice note into a Nearby or private chat; returns a reason string resource on immediate refusal, or 0. A refusal decided later shows as a local line in that chat. */
    fun sendMedia(conversation: String, bytes: ByteArray, mime: String): Int
    fun setTor(on: Boolean)
    fun refreshLocation()
    fun panic()
}

/** Process-wide in-memory state. Timelines live in memory only and die with the process or a panic wipe. */
object ChatRepository {
    private const val TIMELINE_CAP = 500

    private val _messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChatMessage>>> = _messages.asStateFlow()

    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()

    private val _mesh = MutableStateFlow(MeshStatus())
    val mesh: StateFlow<MeshStatus> = _mesh.asStateFlow()

    private val _geo = MutableStateFlow(GeoStatus())
    val geo: StateFlow<GeoStatus> = _geo.asStateFlow()

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    /** Nicknames of private-chat partners, so a chat keeps its title after the peer leaves range. */
    private val _names = MutableStateFlow<Map<String, String>>(emptyMap())
    val names: StateFlow<Map<String, String>> = _names.asStateFlow()

    @Volatile var controller: ChatController? = null
    @Volatile var openConversation: String? = null

    fun add(m: ChatMessage) {
        _messages.update { all ->
            val list = all[m.conversation].orEmpty()
            if (list.any { it.id == m.id && it.senderKey == m.senderKey }) return@update all
            all + (m.conversation to (list + m).sortedBy { it.timestamp }.takeLast(TIMELINE_CAP))
        }
        if (!m.mine && openConversation != m.conversation) {
            _unread.update { it + (m.conversation to (it[m.conversation] ?: 0) + 1) }
        }
    }

    fun setState(conversation: String, messageId: String, state: DeliveryState) {
        _messages.update { all ->
            val list = all[conversation] ?: return@update all
            all + (conversation to list.map { if (it.id == messageId && it.mine && it.state.ordinal < state.ordinal) it.copy(state = state) else it })
        }
    }

    fun clearUnread(conversation: String) = _unread.update { it - conversation }

    /** A message sent before its final ID existed (location channels mine proof of work first). */
    fun replaceId(conversation: String, oldId: String, newId: String, state: DeliveryState) = _messages.update { all ->
        val list = all[conversation] ?: return@update all
        all + (conversation to list.map { if (it.id == oldId) it.copy(id = newId, state = state) else it })
    }

    fun addLocal(conversation: String, text: String) {
        val now = System.currentTimeMillis()
        add(ChatMessage("local-$now-${text.hashCode()}", conversation, "", "", text, now, mine = true, system = true))
    }

    fun clear(conversation: String) = _messages.update { it - conversation }

    /** A chat the UI should open next (from /msg); consumed by the main screen. */
    private val _openRequest = MutableStateFlow<String?>(null)
    val openRequest: StateFlow<String?> = _openRequest.asStateFlow()
    fun requestOpen(conversation: String?) { _openRequest.value = conversation }

    fun rememberName(peerHex: String, name: String) = _names.update { it + (peerHex to name) }

    fun setPeers(p: List<MeshPeer>) {
        _peers.value = p
        p.forEach { if (_names.value[it.peerIdHex] != it.nickname) rememberName(it.peerIdHex, it.nickname) }
    }

    fun updateMesh(f: (MeshStatus) -> MeshStatus) = _mesh.update(f)
    fun updateGeo(f: (GeoStatus) -> GeoStatus) = _geo.update(f)

    /** Panic wipe of everything held in memory. */
    fun wipe() {
        _messages.value = emptyMap()
        _peers.value = emptyList()
        _unread.value = emptyMap()
        _names.value = emptyMap()
        _geo.update { GeoStatus(locationGeohash = it.locationGeohash) }
    }
}
