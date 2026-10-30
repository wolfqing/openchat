package org.openchat.android.core

/** Packet type byte, docs/protocol-spec.md §3. */
object MessageType {
    const val ANNOUNCE = 0x01
    const val MESSAGE = 0x02
    const val LEAVE = 0x03
    const val COURIER_ENVELOPE = 0x04
    const val NOISE_HANDSHAKE = 0x10
    const val NOISE_ENCRYPTED = 0x11
    const val FRAGMENT = 0x20
    const val REQUEST_SYNC = 0x21
    const val FILE_TRANSFER = 0x22
    const val BOARD_POST = 0x23
    const val PREKEY_BUNDLE = 0x24
    const val GROUP_MESSAGE = 0x25
    const val PING = 0x26
    const val PONG = 0x27
    const val NOSTR_CARRIER = 0x28
    const val VOICE_FRAME = 0x29
    const val ANNOUNCE_V2 = 0x2C

    private val known = setOf(
        ANNOUNCE, MESSAGE, LEAVE, COURIER_ENVELOPE, NOISE_HANDSHAKE, NOISE_ENCRYPTED, FRAGMENT,
        REQUEST_SYNC, FILE_TRANSFER, BOARD_POST, PREKEY_BUNDLE, GROUP_MESSAGE, PING, PONG,
        NOSTR_CARRIER, VOICE_FRAME, ANNOUNCE_V2,
    )

    fun isKnown(type: Int) = type in known

    /** Only Noise frames are padded on the wire (§2.4). */
    fun padsOnWire(type: Int) = type == NOISE_HANDSHAKE || type == NOISE_ENCRYPTED

    /** Largest decoded payload accepted for a type (§2.7). */
    fun maxPayloadBytes(type: Int): Int = when (type) {
        FRAGMENT -> 1_280
        ANNOUNCE, ANNOUNCE_V2, LEAVE, BOARD_POST, PREKEY_BUNDLE, VOICE_FRAME, NOISE_HANDSHAKE, PING, PONG -> 4 * 1024
        REQUEST_SYNC -> 8 * 1024
        COURIER_ENVELOPE, NOSTR_CARRIER -> 64 * 1024
        FILE_TRANSFER, NOISE_ENCRYPTED -> MAX_FRAMED_FILE_BYTES
        else -> 128 * 1024
    }

    /** 1 MiB file plus TLV metadata and binary framing, mirroring upstream's ceiling. */
    const val MAX_FRAMED_FILE_BYTES = 1024 * 1024 + 18 + 65535 * 2 + 16 + 8 + 8 + 64

    /** Everything a frame can carry besides its payload (header, IDs, widest route, size field, signature, padding). */
    private const val MAX_FRAME_OVERHEAD = 16 + 8 + 8 + 1 + 255 * 8 + 4 + 64 + 255

    fun maxFrameBytes(type: Int) = maxPayloadBytes(type) + MAX_FRAME_OVERHEAD
}

/** Payload type byte inside a Noise transport message (§8.3). */
object NoisePayloadType {
    const val PRIVATE_MESSAGE = 0x01
    const val READ_RECEIPT = 0x02
    const val DELIVERED = 0x03
    const val GROUP_INVITE = 0x06
    const val GROUP_KEY_UPDATE = 0x07
    const val VERIFY_CHALLENGE = 0x10
    const val VERIFY_RESPONSE = 0x11
    const val PRIVATE_FILE = 0x20
    const val PRIVATE_FILE_PRERELEASE = 0x09 // accepted, never sent
    const val AUTHENTICATED_PEER_STATE = 0x21
}
