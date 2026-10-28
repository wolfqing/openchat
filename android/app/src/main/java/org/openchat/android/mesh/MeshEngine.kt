package org.openchat.android.mesh

import org.openchat.android.core.Announcement
import org.openchat.android.core.Deduplicator
import org.openchat.android.core.FragmentAssembler
import org.openchat.android.core.Fragmenter
import org.openchat.android.core.MessageType
import org.openchat.android.core.NoisePayload
import org.openchat.android.core.NoisePayloadType
import org.openchat.android.core.Packet
import org.openchat.android.core.PacketCodec
import org.openchat.android.core.PrivateMessage
import org.openchat.android.core.RelayPolicy
import org.openchat.android.core.StreamFramer
import org.openchat.android.core.hexToBytes
import org.openchat.android.core.splitUtf8
import org.openchat.android.core.toHex
import org.openchat.android.crypto.CipherState
import org.openchat.android.crypto.Crypto
import org.openchat.android.crypto.HandshakeXX
import java.util.UUID
import kotlin.random.Random

/** Runs tasks on one thread. Every MeshEngine method must be called through it. */
interface Scheduler {
    fun post(task: () -> Unit)
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
    fun now(): Long
}

fun interface Cancellable { fun cancel() }

/** Platform side of a link: writes raw bytes. One link = one connected remote device. */
fun interface LinkWriter { fun write(linkId: String, bytes: ByteArray) }

data class MeshPeer(
    val peerIdHex: String,
    val nickname: String,
    val noisePublicKey: ByteArray,
    val signingPublicKey: ByteArray,
    val lastSeen: Long,
    val direct: Boolean,
) {
    val fingerprint: String get() = org.openchat.android.core.sha256(noisePublicKey).toHex()
}

interface MeshListener {
    fun onPeersChanged(peers: List<MeshPeer>) {}
    fun onPublicMessage(senderHex: String, nickname: String, text: String, timestamp: Long) {}
    fun onPrivateMessage(senderHex: String, nickname: String, messageId: String, text: String, timestamp: Long) {}
    fun onDelivered(peerHex: String, messageId: String) {}
    fun onRead(peerHex: String, messageId: String) {}
    fun onSessionEstablished(peerHex: String) {}
}

/**
 * Transport-agnostic mesh node: framing, dedup, relaying, presence and Noise
 * sessions as described in docs/protocol-spec.md. The BLE layer feeds it link
 * events and bytes; it never touches Android APIs.
 */
class MeshEngine(
    @Volatile var identity: Identity,
    private val scheduler: Scheduler,
    private val writer: LinkWriter,
    private val listener: MeshListener,
    private val random: Random = Random.Default,
) {
    private class Link(val id: String, var maxWrite: Int) {
        val framer = StreamFramer()
        var peerHex: String? = null
    }

    private class PeerState(
        var nickname: String,
        val noiseKey: ByteArray,
        val signingKey: ByteArray,
        var lastSeen: Long,
        var directLinks: MutableSet<String> = mutableSetOf(),
    )

    private class Session(val handshake: HandshakeXX?) {
        var send: CipherState? = null
        var recv: CipherState? = null
        var establishedAt = 0L
        var messagesSent = 0
        val established get() = send != null
    }

    private val links = LinkedHashMap<String, Link>()
    private val peers = HashMap<String, PeerState>()
    private val sessions = HashMap<String, Session>()
    private val pendingNoise = HashMap<String, ArrayDeque<ByteArray>>()
    // Ciphertexts that overtook the last handshake message on a relay path.
    private val earlyCiphertexts = HashMap<String, ArrayDeque<ByteArray>>()
    private val lastHandshakeStart = HashMap<String, Long>()
    private val dedup = Deduplicator(clock = scheduler::now)
    private var assembler = FragmentAssembler(clock = scheduler::now)
    private val pendingRelays = HashMap<String, Cancellable>()
    private var lastAnnounce = 0L
    private var nextAnnounceDue = 0L
    private var running = false

    val myPeerHex get() = identity.peerIdHex

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        running = true
        sendAnnounce(force = true)
        tick()
    }

    fun stop() {
        if (running) sendLeave()
        running = false
    }

    /** Clears every piece of state tied to the old identity (panic wipe, §10). */
    fun resetIdentity(newIdentity: Identity) {
        identity = newIdentity
        peers.clear(); sessions.clear(); pendingNoise.clear(); lastHandshakeStart.clear(); earlyCiphertexts.clear()
        pendingRelays.values.forEach { it.cancel() }; pendingRelays.clear()
        dedup.clear()
        assembler = FragmentAssembler(clock = scheduler::now)
        links.values.forEach { it.peerHex = null; it.framer.reset() }
        publishPeers()
        sendAnnounce(force = true)
    }

    fun updateNickname(name: String) {
        identity = identity.withNickname(name)
        lastAnnounce = 0
        sendAnnounce(force = true)
    }

    private fun tick() {
        if (!running) return
        val now = scheduler.now()
        if (now >= nextAnnounceDue) sendAnnounce(force = false)
        expirePeers(now)
        rekeyOldSessions(now)
        scheduler.schedule(1000) { tick() }
    }

    // ---------------------------------------------------------------- links

    fun onLinkUp(linkId: String, maxWrite: Int) {
        links[linkId] = Link(linkId, maxWrite)
        sendAnnounce(force = true)
    }

    fun onLinkMtu(linkId: String, maxWrite: Int) { links[linkId]?.maxWrite = maxWrite }

    fun onLinkDown(linkId: String) {
        val link = links.remove(linkId) ?: return
        link.peerHex?.let { hex -> peers[hex]?.directLinks?.remove(linkId) }
        publishPeers()
    }

    val linkCount get() = links.size

    fun onBytes(linkId: String, bytes: ByteArray) {
        val link = links[linkId] ?: return
        for (frame in link.framer.append(bytes)) {
            val packet = PacketCodec.decode(frame) ?: continue
            receive(packet, linkId)
        }
    }

    // ---------------------------------------------------------------- sending API

    fun sendPublic(text: String) {
        val p = sign(Packet(type = MessageType.MESSAGE, senderId = identity.peerId, timestamp = scheduler.now(), payload = text.toByteArray(Charsets.UTF_8)))
            ?: return
        dedup.markIfNew(Deduplicator.key(p))
        broadcast(p, exclude = null)
    }

    /** Sends a private message; returns the message IDs used (one per 255-byte chunk). */
    fun sendPrivate(peerHex: String, text: String): List<String> {
        val ids = ArrayList<String>()
        for (chunk in splitUtf8(text, PrivateMessage.MAX_CONTENT_BYTES)) {
            val id = UUID.randomUUID().toString().uppercase()
            val body = PrivateMessage(id, chunk).encode() ?: continue
            sendNoise(peerHex, NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, body).encode())
            ids.add(id)
        }
        return ids
    }

    fun sendReadReceipt(peerHex: String, messageId: String) =
        sendNoise(peerHex, NoisePayload(NoisePayloadType.READ_RECEIPT, messageId.toByteArray(Charsets.UTF_8)).encode())

    fun peersSnapshot(): List<MeshPeer> = peers.map { (hex, s) ->
        MeshPeer(hex, s.nickname, s.noiseKey, s.signingKey, s.lastSeen, s.directLinks.isNotEmpty())
    }.sortedBy { it.nickname.lowercase() }

    fun hasSession(peerHex: String) = sessions[peerHex]?.established == true

    // ---------------------------------------------------------------- presence

    private fun sendAnnounce(force: Boolean) {
        val now = scheduler.now()
        if (!force && now < nextAnnounceDue) return
        if (now - lastAnnounce < 1000) return
        val neighbors = links.values.mapNotNull { it.peerHex?.hexToBytes() }.distinctBy { it.toHex() }
        val payload = Announcement(identity.nickname, identity.noise.publicKey, identity.signing.publicKey, neighbors).encode() ?: return
        val p = sign(Packet(type = MessageType.ANNOUNCE, senderId = identity.peerId, timestamp = now, payload = payload)) ?: return
        dedup.markIfNew(Deduplicator.key(p))
        lastAnnounce = now
        nextAnnounceDue = now + when {
            links.isEmpty() -> 4_000L
            links.size >= RelayPolicy.HIGH_DEGREE -> 30_000L + random.nextLong(-8_000, 8_001)
            else -> 15_000L + random.nextLong(-4_000, 4_001)
        }
        broadcast(p, exclude = null)
    }

    private fun sendLeave() {
        val p = sign(Packet(type = MessageType.LEAVE, senderId = identity.peerId, timestamp = scheduler.now(), payload = ByteArray(0))) ?: return
        broadcast(p, exclude = null)
    }

    private fun expirePeers(now: Long) {
        val removed = peers.entries.removeAll { (_, s) -> s.directLinks.isEmpty() && now - s.lastSeen > 60_000 }
        if (removed) publishPeers()
    }

    private fun publishPeers() = listener.onPeersChanged(peersSnapshot())

    // ---------------------------------------------------------------- receive pipeline

    private fun receive(p: Packet, linkId: String?) {
        if (p.type == MessageType.FRAGMENT) {
            // Relay fragments independently, and try to reassemble locally.
            if (!dedup.markIfNew(Deduplicator.key(p))) { pendingRelays.remove(Deduplicator.key(p))?.cancel(); return }
            val addressedToMe = p.recipientId?.toHex() == myPeerHex
            if (p.isBroadcast || addressedToMe) assembler.accept(p)?.let { receive(it, null) }
            maybeRelay(p, linkId)
            return
        }
        val key = Deduplicator.key(p)
        if (!dedup.markIfNew(key)) { pendingRelays.remove(key)?.cancel(); return }

        val senderHex = p.senderId.toHex()
        if (senderHex == myPeerHex) return
        val forMe = !p.isBroadcast && p.recipientId!!.toHex() == myPeerHex

        when (p.type) {
            MessageType.ANNOUNCE -> if (!handleAnnounce(p, linkId)) return
            MessageType.LEAVE -> if (!handleLeave(p)) return
            MessageType.MESSAGE -> if (!handlePublic(p)) return
            MessageType.NOISE_HANDSHAKE -> if (forMe) { handleHandshake(senderHex, p.payload); return }
            MessageType.NOISE_ENCRYPTED -> if (forMe) { handleEncrypted(senderHex, p.payload); return }
        }
        // Reassembled packets (linkId == null) were already relayed as fragments.
        if (linkId != null) maybeRelay(p, linkId)
    }

    private fun maybeRelay(p: Packet, ingress: String?) {
        val senderIsSelf = p.senderId.toHex() == myPeerHex
        val forMe = !p.isBroadcast && p.recipientId!!.toHex() == myPeerHex
        val d = RelayPolicy.decide(p, senderIsSelf, forMe, links.size, random)
        if (!d.relay) return
        val relayed = p.copy(ttl = d.newTtl)
        val key = Deduplicator.key(p)
        pendingRelays[key] = scheduler.schedule(d.delayMs) {
            pendingRelays.remove(key)
            // Directed packets go straight to the recipient when it is a neighbour.
            val target = p.recipientId?.toHex()?.let { hex -> peers[hex]?.directLinks?.firstOrNull { it != ingress && links.containsKey(it) } }
            if (!p.isBroadcast && target != null) sendOn(listOf(target), relayed) else broadcast(relayed, exclude = ingress)
        }
    }

    private fun handleAnnounce(p: Packet, linkId: String?): Boolean {
        val a = Announcement.decode(p.payload) ?: return false
        if (a.noisePublicKey.size != 32 || a.signingPublicKey.size != 32) return false
        if (!Identity.peerIdFor(a.noisePublicKey).contentEquals(p.senderId)) return false
        if (!verify(p, a.signingPublicKey)) return false
        val hex = p.senderId.toHex()
        val existing = peers[hex]
        if (existing != null && !existing.signingKey.contentEquals(a.signingPublicKey)) return false
        val now = scheduler.now()
        val state = existing ?: PeerState(a.nickname, a.noisePublicKey, a.signingPublicKey, now).also { peers[hex] = it }
        val changed = existing == null || existing.nickname != a.nickname
        state.nickname = a.nickname
        state.lastSeen = now
        // An announce at full TTL straight off a link came from that link's device.
        if (linkId != null && p.ttl >= Packet.DEFAULT_TTL) {
            val link = links[linkId]
            if (link != null && link.peerHex != hex) {
                link.peerHex = hex
                state.directLinks.add(linkId)
                publishPeers()
            }
        }
        if (changed) publishPeers()
        flushIfPossible(hex)
        return true
    }

    private fun handleLeave(p: Packet): Boolean {
        val hex = p.senderId.toHex()
        val peer = peers[hex] ?: return false
        if (!verify(p, peer.signingKey)) return false
        peers.remove(hex)
        sessions.remove(hex)
        publishPeers()
        return true
    }

    private fun handlePublic(p: Packet): Boolean {
        val hex = p.senderId.toHex()
        val peer = peers[hex] ?: return false
        if (!verify(p, peer.signingKey)) return false
        if (scheduler.now() - p.timestamp > 900_000) return false
        val text = try { String(p.payload, Charsets.UTF_8) } catch (e: Exception) { return false }
        peer.lastSeen = scheduler.now()
        listener.onPublicMessage(hex, peer.nickname, text, p.timestamp)
        return true
    }

    // ---------------------------------------------------------------- Noise

    private fun sendNoise(peerHex: String, plaintext: ByteArray) {
        val s = sessions[peerHex]
        if (s != null && s.established) {
            encryptAndSend(peerHex, s, plaintext)
            return
        }
        val queue = pendingNoise.getOrPut(peerHex) { ArrayDeque() }
        if (queue.size >= 100) queue.removeFirst()
        queue.addLast(plaintext)
        startHandshake(peerHex)
    }

    private fun startHandshake(peerHex: String) {
        val existing = sessions[peerHex]
        if (existing?.handshake != null && !existing.established) return
        val now = scheduler.now()
        if (now - (lastHandshakeStart[peerHex] ?: 0L) < 2_000) return
        lastHandshakeStart[peerHex] = now
        val hs = HandshakeXX(initiator = true, localStatic = identity.noise)
        sessions[peerHex] = Session(hs)
        sendHandshake(peerHex, hs.writeMessage())
    }

    private fun sendHandshake(peerHex: String, message: ByteArray) {
        val p = Packet(type = MessageType.NOISE_HANDSHAKE, senderId = identity.peerId, recipientId = peerHex.hexToBytes(), timestamp = scheduler.now(), payload = message)
        sendDirected(peerHex, p)
    }

    private fun handleHandshake(peerHex: String, message: ByteArray) {
        val current = sessions[peerHex]
        val pendingInitiator = current?.handshake?.takeIf { it.initiator && !current.established }

        // Message 1 is a bare 32-byte ephemeral key.
        if (message.size == 32) {
            if (pendingInitiator != null && myPeerHex < peerHex) return // we keep the initiator role (§8.1)
            val hs = HandshakeXX(initiator = false, localStatic = identity.noise)
            try { hs.readMessage(message) } catch (e: Exception) { return }
            sessions[peerHex] = Session(hs)
            sendHandshake(peerHex, hs.writeMessage())
            return
        }
        val hs = current?.handshake ?: return
        if (current.established) return
        try {
            hs.readMessage(message)
            if (hs.shouldWrite) sendHandshake(peerHex, hs.writeMessage())
        } catch (e: Exception) {
            sessions.remove(peerHex)
            return
        }
        if (hs.isComplete) completeHandshake(peerHex, hs)
    }

    private fun completeHandshake(peerHex: String, hs: HandshakeXX) {
        val remote = hs.remoteStatic
        if (remote == null || Identity.peerIdFor(remote).toHex() != peerHex) {
            sessions.remove(peerHex)
            return
        }
        val (send, recv) = hs.split()
        val s = Session(null).also { it.send = send; it.recv = recv; it.establishedAt = scheduler.now() }
        sessions[peerHex] = s
        listener.onSessionEstablished(peerHex)
        earlyCiphertexts.remove(peerHex)?.forEach { handleEncrypted(peerHex, it) }
        flushIfPossible(peerHex)
    }

    private fun flushIfPossible(peerHex: String) {
        val s = sessions[peerHex] ?: return
        if (!s.established) return
        val queue = pendingNoise.remove(peerHex) ?: return
        while (queue.isNotEmpty()) encryptAndSend(peerHex, s, queue.removeFirst())
    }

    private fun encryptAndSend(peerHex: String, s: Session, plaintext: ByteArray) {
        val ct = try { s.send!!.encrypt(plaintext) } catch (e: Exception) {
            sessions.remove(peerHex)
            pendingNoise.getOrPut(peerHex) { ArrayDeque() }.addFirst(plaintext)
            startHandshake(peerHex)
            return
        }
        s.messagesSent++
        val p = Packet(type = MessageType.NOISE_ENCRYPTED, senderId = identity.peerId, recipientId = peerHex.hexToBytes(), timestamp = scheduler.now(), payload = ct)
        sendDirected(peerHex, p)
    }

    private fun handleEncrypted(peerHex: String, ciphertext: ByteArray) {
        val s = sessions[peerHex]
        val plain = try { s?.recv?.decrypt(ciphertext) } catch (e: Exception) { null }
        if (plain == null) {
            if (s != null && !s.established) {
                // Handshake still running: keep it until the session exists.
                val q = earlyCiphertexts.getOrPut(peerHex) { ArrayDeque() }
                if (q.size < 32) q.addLast(ciphertext)
                return
            }
            // The peer holds a session we lost (restart or wipe); start over.
            sessions.remove(peerHex); startHandshake(peerHex)
            return
        }
        val payload = NoisePayload.decode(plain) ?: return
        val nickname = peers[peerHex]?.nickname ?: peerHex.take(8)
        when (payload.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = PrivateMessage.decode(payload.body) ?: return
                listener.onPrivateMessage(peerHex, nickname, pm.messageId, pm.content, scheduler.now())
                sendNoise(peerHex, NoisePayload(NoisePayloadType.DELIVERED, pm.messageId.toByteArray(Charsets.UTF_8)).encode())
            }
            NoisePayloadType.DELIVERED -> listener.onDelivered(peerHex, String(payload.body, Charsets.UTF_8))
            NoisePayloadType.READ_RECEIPT -> listener.onRead(peerHex, String(payload.body, Charsets.UTF_8))
            else -> Unit // other payload types are not implemented in v0.1
        }
    }

    private fun rekeyOldSessions(now: Long) {
        for ((hex, s) in sessions.entries.toList()) {
            if (s.established && (now - s.establishedAt > 3_600_000 || s.messagesSent > 10_000)) {
                if (myPeerHex < hex) { sessions.remove(hex); startHandshake(hex) }
            }
        }
    }

    // ---------------------------------------------------------------- signing

    private fun sign(p: Packet): Packet? {
        val bytes = PacketCodec.signingBytes(p) ?: return null
        return p.copy(signature = Crypto.ed25519Sign(identity.signing.privateKey, bytes))
    }

    private fun verify(p: Packet, signingKey: ByteArray): Boolean {
        val sig = p.signature ?: return false
        val bytes = PacketCodec.signingBytes(p) ?: return false
        return Crypto.ed25519Verify(signingKey, bytes, sig)
    }

    // ---------------------------------------------------------------- output

    private fun sendDirected(peerHex: String, p: Packet) {
        val direct = peers[peerHex]?.directLinks?.filter { links.containsKey(it) }.orEmpty()
        dedup.markIfNew(Deduplicator.key(p))
        if (direct.isNotEmpty()) sendOn(direct.take(1), p) else broadcast(p, exclude = null)
    }

    private fun broadcast(p: Packet, exclude: String?) {
        val targets = links.keys.filter { it != exclude }
        if (targets.isEmpty()) return
        sendOn(RelayPolicy.fanout(p, targets, random), p)
    }

    private fun sendOn(targets: List<String>, p: Packet) {
        val frame = PacketCodec.encode(p) ?: return
        val limit = targets.mapNotNull { links[it]?.maxWrite }.minOrNull() ?: return
        if (frame.size <= limit || p.type == MessageType.FRAGMENT) {
            targets.forEach { writer.write(it, frame) }
            return
        }
        val chunk = maxOf(Fragmenter.MIN_CHUNK, minOf(Fragmenter.DEFAULT_CHUNK, limit - 50))
        val fragments = Fragmenter.split(p, chunk) ?: return
        val spacing = if (p.isBroadcast) 30L else 25L
        fragments.forEachIndexed { i, f ->
            val bytes = PacketCodec.encode(f) ?: return
            dedup.markIfNew(Deduplicator.key(f))
            if (i == 0) targets.forEach { writer.write(it, bytes) }
            else scheduler.schedule(spacing * i) { targets.filter { links.containsKey(it) }.forEach { writer.write(it, bytes) } }
        }
    }
}
