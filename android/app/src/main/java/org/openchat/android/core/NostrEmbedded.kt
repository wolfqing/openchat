package org.openchat.android.core

import java.util.Base64

/**
 * A mesh packet carried inside a Nostr private envelope (docs/protocol-spec.md §15.2):
 * `bitchat1:` + base64url(unpadded) of a noiseEncrypted packet whose payload is a
 * plaintext Noise payload (the envelope already encrypts it).
 */
object NostrEmbedded {
    const val PREFIX = "bitchat1:"

    class Decoded(val senderId: ByteArray, val recipientId: ByteArray?, val payload: NoisePayload)

    fun privateMessage(messageId: String, content: String, senderId: ByteArray, recipientId: ByteArray, nowMs: Long): String? {
        val body = PrivateMessage(messageId, content).encode() ?: return null
        return wrap(NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, body), senderId, recipientId, nowMs)
    }

    fun receipt(type: Int, messageId: String, senderId: ByteArray, recipientId: ByteArray, nowMs: Long): String? =
        wrap(NoisePayload(type, messageId.toByteArray(Charsets.UTF_8)), senderId, recipientId, nowMs)

    private fun wrap(payload: NoisePayload, senderId: ByteArray, recipientId: ByteArray, nowMs: Long): String? {
        val p = Packet(type = MessageType.NOISE_ENCRYPTED, senderId = senderId, recipientId = recipientId, timestamp = nowMs, payload = payload.encode())
        val bytes = PacketCodec.encode(p) ?: return null
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(content: String): Decoded? = try {
        if (!content.startsWith(PREFIX)) null else {
            val bytes = Base64.getUrlDecoder().decode(content.substring(PREFIX.length).trimEnd('='))
            val p = PacketCodec.decode(bytes)
            if (p == null || p.type != MessageType.NOISE_ENCRYPTED) null
            else NoisePayload.decode(p.payload)?.let { Decoded(p.senderId, p.recipientId, it) }
        }
    } catch (e: Exception) { null }

    /** Favorite notice sent as private-message content (§15.1). */
    fun favoriteNotice(on: Boolean, npub: String) = (if (on) "[FAVORITED]:" else "[UNFAVORITED]:") + npub

    /** (favorited?, npub) if [content] is a favorite notice. */
    fun parseFavoriteNotice(content: String): Pair<Boolean, String>? = when {
        content.startsWith("[FAVORITED]:") -> true to content.removePrefix("[FAVORITED]:").trim()
        content.startsWith("[UNFAVORITED]:") -> false to content.removePrefix("[UNFAVORITED]:").trim()
        else -> null
    }
}
