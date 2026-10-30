package org.openchat.android.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import org.openchat.android.core.hexToBytes
import org.openchat.android.mesh.Identity
import org.openchat.android.nostr.Bech32

/**
 * A mesh peer marked as favorite. [theirNpub] arrives in their favorite notice
 * (docs/protocol-spec.md §15); [theyFavorited] is true once they favorited us
 * back, which is what lets private messages travel over Nostr.
 */
data class Favorite(
    val noiseKeyHex: String,
    val nickname: String,
    val theirNpub: String? = null,
    val theyFavorited: Boolean = false,
    val mine: Boolean = true,
)

/**
 * Local relationships with other people, keyed by identity fingerprint
 * (SHA-256 of the Noise static key, hex). Never sent anywhere except the
 * favorite notice; sealed on disk and wiped by the panic tap.
 */
data class SocialState(
    val blocked: Map<String, String> = emptyMap(),    // fingerprint -> name when blocked
    val geoBlocked: Map<String, String> = emptyMap(), // Nostr pubkey hex -> name, location channels only
    val favorites: Map<String, Favorite> = emptyMap(),
    val verified: Set<String> = emptySet(),
    val relays: List<String> = emptyList(), // extra Nostr relays for private messages (§15.4)
) {
    fun isBlockedPeer(peerHex: String) = blocked.keys.any { it.startsWith(peerHex) }
    fun fingerprintOf(peerHex: String): String? =
        (blocked.keys + favorites.keys + verified).firstOrNull { it.startsWith(peerHex) }
    fun isVerifiedPeer(peerHex: String) = verified.any { it.startsWith(peerHex) }
    fun favoriteOf(peerHex: String) = favorites.entries.firstOrNull { it.key.startsWith(peerHex) }?.value
}

/** Who an inbound Nostr private envelope may speak for (docs/protocol-spec.md §15.3). */
object NostrSenders {
    /**
     * The favorite whose recorded npub signed this envelope, provided the unsigned sender ID inside
     * names that favorite's Noise key. Anything else is ignored, including a notice that would bind
     * a new npub to someone's Noise key: that first binding happens over a Noise session or a QR
     * (§16), where the other side proves it holds the key.
     */
    fun resolve(favorites: Collection<Favorite>, senderPubkeyHex: String, embeddedSenderId: ByteArray): Favorite? {
        val fav = favorites.firstOrNull { f -> f.theirNpub?.let { Bech32.npubToHex(it) } == senderPubkeyHex } ?: return null
        val key = fav.noiseKeyHex.hexToBytes() ?: return null
        return fav.takeIf { Identity.peerIdFor(key).contentEquals(embeddedSenderId) }
    }
}

object Social {
    private val _state = MutableStateFlow(SocialState())
    val state: StateFlow<SocialState> = _state.asStateFlow()

    /** Set by the service; called after every change. */
    @Volatile var onChanged: ((SocialState) -> Unit)? = null

    private fun change(f: (SocialState) -> SocialState) {
        _state.update(f)
        onChanged?.invoke(_state.value)
    }

    fun load(s: SocialState) { _state.value = s }

    fun block(fingerprint: String, name: String) = change { it.copy(blocked = it.blocked + (fingerprint to name), favorites = it.favorites - fingerprint) }
    fun unblock(fingerprint: String) = change { it.copy(blocked = it.blocked - fingerprint) }
    fun blockGeo(pubkey: String, name: String) = change { it.copy(geoBlocked = it.geoBlocked + (pubkey to name)) }
    fun unblockGeo(pubkey: String) = change { it.copy(geoBlocked = it.geoBlocked - pubkey) }
    fun setVerified(fingerprint: String, on: Boolean) = change { it.copy(verified = if (on) it.verified + fingerprint else it.verified - fingerprint) }
    fun putFavorite(fingerprint: String, f: Favorite?) = change {
        it.copy(favorites = if (f == null) it.favorites - fingerprint else it.favorites + (fingerprint to f))
    }

    fun addRelay(url: String) = change { if (url in it.relays || it.relays.size >= NostrDirect.MAX_EXTRA_RELAYS) it else it.copy(relays = it.relays + url) }
    fun removeRelay(url: String) = change { it.copy(relays = it.relays - url) }

    fun wipe() { _state.value = SocialState() }

    fun serialize(s: SocialState): ByteArray {
        val o = JSONObject()
        o.put("v", 1)
        o.put("blocked", JSONObject(s.blocked))
        o.put("geoBlocked", JSONObject(s.geoBlocked))
        o.put("verified", JSONArray(s.verified.toList()))
        val favs = JSONObject()
        s.favorites.forEach { (fp, f) ->
            favs.put(fp, JSONObject().put("noise", f.noiseKeyHex).put("nick", f.nickname).put("npub", f.theirNpub ?: "")
                .put("theirs", f.theyFavorited).put("mine", f.mine))
        }
        o.put("favorites", favs)
        o.put("relays", JSONArray(s.relays))
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    fun deserialize(bytes: ByteArray): SocialState? = try {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        fun map(name: String): Map<String, String> {
            val m = o.optJSONObject(name) ?: return emptyMap()
            return m.keys().asSequence().associateWith { m.getString(it) }
        }
        val favs = o.optJSONObject("favorites")
        val favorites = favs?.keys()?.asSequence()?.associateWith { k ->
            val f = favs.getJSONObject(k)
            Favorite(f.getString("noise"), f.getString("nick"), f.optString("npub").ifEmpty { null }, f.optBoolean("theirs"), f.optBoolean("mine", true))
        }.orEmpty()
        val ver = o.optJSONArray("verified")
        val rel = o.optJSONArray("relays")
        SocialState(
            map("blocked"), map("geoBlocked"), favorites, (0 until (ver?.length() ?: 0)).map { ver!!.getString(it) }.toSet(),
            (0 until (rel?.length() ?: 0)).map { rel!!.getString(it) },
        )
    } catch (e: Exception) { null }
}
