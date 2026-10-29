package org.openchat.android.mesh

import org.openchat.android.core.Announcement
import org.openchat.android.core.CourierEnvelope
import org.openchat.android.core.Deduplicator
import org.openchat.android.core.FilePacket
import org.openchat.android.core.FragmentAssembler
import org.openchat.android.core.Fragmenter
import org.openchat.android.core.GcsFilter
import org.openchat.android.core.MessageType
import org.openchat.android.core.PacketId
import org.openchat.android.core.AuthPeerState
import org.openchat.android.core.SyncRequest
import org.openchat.android.core.SyncTypes
import org.openchat.android.core.VerifyQr
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
import org.openchat.android.crypto.NoiseX
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
    /** A private message is waiting in the outbox because the peer is out of reach (§13.1). */
    fun onQueued(peerHex: String, messageId: String) {}
    /** A sealed copy of the message was handed to a courier (§13.1). */
    fun onCarried(peerHex: String, messageId: String) {}
    /** Outbox, carried mail or remembered keys changed; the owner should persist [MeshEngine.exportState]. */
    fun onStateChanged() {}
    /** An image or voice note arrived, public or private (§18). Already type-checked. */
    fun onFile(senderHex: String, nickname: String, file: FilePacket, isPrivate: Boolean, timestamp: Long) {}
    /** The peer proved it holds the keys from the verification QR we scanned (§16.2). */
    fun onVerified(peerHex: String) {}
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
        var announce: Packet? = null, // latest verified announce, served to sync requests (§14.4)
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
    // Store-and-forward (§13).
    private val known = HashMap<String, KnownPeer>()
    private val outbox = LinkedHashMap<String, QueuedMessage>()
    private val carried = ArrayList<CarriedEnvelope>()
    private val seenIds = LinkedHashSet<String>()   // private message IDs already delivered to the listener
    private val acked = LinkedHashMap<String, Int>() // message ID -> best receipt seen (1 delivered, 2 read)
    private val pendingAcks = HashMap<String, MutableSet<String>>()
    private val pendingVerify = HashMap<String, ByteArray>() // peer hex -> challenge nonce
    private val peerCaps = HashMap<String, Long>() // from authenticated peer state, per peer (§18.2)
    // History sync (§14).
    private val history = LinkedHashMap<String, Packet>() // packet ID hex -> verified public message
    private var myAnnounce: Packet? = null
    private val pendingSync = HashMap<String, Long>()     // peer hex -> when we last asked it
    private val syncReplies = HashMap<String, ArrayList<Long>>() // peer hex -> times we answered it
    private var lastSyncRound = 0L
    private var lastPrune = 0L
    private var lastAnnounce = 0L
    private var nextAnnounceDue = 0L
    private var running = false

    /** Local block list, by Noise static key: messages from these senders are dropped unanswered. */
    @Volatile var isBlocked: (noiseKey: ByteArray) -> Boolean = { false }

    val myPeerHex get() = identity.peerIdHex
    val carriedCount get() = carried.size
    val outboxCount get() = outbox.size
    val historyCount get() = history.size

    fun exportState() = MeshState(HashMap(known), outbox.values.toList(), carried.toList(), history.values.toList())

    fun importState(s: MeshState) {
        known.putAll(s.known)
        s.outbox.forEach { outbox[it.messageId] = it }
        carried.addAll(s.carried)
        s.history.forEach { storeHistory(it) }
        prune(scheduler.now())
    }

    /** 0 = not queued, 1 = waiting for the peer, 2 = at least one courier holds a copy. */
    fun outboxState(messageId: String): Int = outbox[messageId]?.let { if (it.couriers.isEmpty()) 1 else 2 } ?: 0

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
        known.clear(); outbox.clear(); carried.clear(); seenIds.clear(); acked.clear(); pendingAcks.clear()
        history.clear(); pendingVerify.clear(); peerCaps.clear(); myAnnounce = null; pendingSync.clear(); syncReplies.clear()
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
        if (now - lastPrune > 30_000) { lastPrune = now; prune(now) }
        if (now - lastSyncRound >= SYNC_INTERVAL_MS) { lastSyncRound = now; syncRound() }
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
        storeHistory(p)
        broadcast(p, exclude = null)
    }

    /**
     * Sends a private message; returns the message IDs used (one per 255-byte chunk).
     * Each chunk stays in the outbox until a receipt arrives (§13.1). A peer out of
     * reach gets the message by courier.
     */
    fun sendPrivate(peerHex: String, text: String): List<String> {
        val ids = ArrayList<String>()
        val now = scheduler.now()
        val reachable = peers.containsKey(peerHex)
        for (chunk in splitUtf8(text, PrivateMessage.MAX_CONTENT_BYTES)) {
            val id = UUID.randomUUID().toString().uppercase()
            val body = PrivateMessage(id, chunk).encode() ?: continue
            val plain = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE, body).encode()
            val q = QueuedMessage(id, peerHex, plain, now)
            outbox[id] = q
            while (outbox.size > OUTBOX_CAP) outbox.remove(outbox.keys.first())
            if (reachable) sendNoise(peerHex, plain) else { listener.onQueued(peerHex, id); deposit(q) }
            ids.add(id)
        }
        listener.onStateChanged()
        return ids
    }

    /** Asks a peer to prove it holds its Noise key, after we scanned its QR (§16.2). */
    fun sendVerifyChallenge(peerHex: String) {
        val key = noiseKeyOf(peerHex) ?: return
        val nonce = random.nextBytes(16)
        pendingVerify[peerHex] = nonce
        sendNoise(peerHex, NoisePayload(NoisePayloadType.VERIFY_CHALLENGE, VerifyQr.challenge(key.toHex(), nonce)).encode())
    }

    fun sendReadReceipt(peerHex: String, messageId: String) =
        sendNoise(peerHex, NoisePayload(NoisePayloadType.READ_RECEIPT, messageId.toByteArray(Charsets.UTF_8)).encode())

    fun peersSnapshot(): List<MeshPeer> = peers.map { (hex, s) ->
        MeshPeer(hex, s.nickname, s.noiseKey, s.signingKey, s.lastSeen, s.directLinks.isNotEmpty())
    }.sortedBy { it.nickname.lowercase() }

    /** Heard from recently, directly or through relays: mesh delivery is worth trying. */
    fun isReachable(peerHex: String) = peers.containsKey(peerHex)

    fun hasSession(peerHex: String) = sessions[peerHex]?.established == true

    // ---------------------------------------------------------------- presence

    private fun sendAnnounce(force: Boolean) {
        val now = scheduler.now()
        if (!force && now < nextAnnounceDue) return
        if (now - lastAnnounce < 1000) return
        val neighbors = links.values.mapNotNull { it.peerHex?.hexToBytes() }.distinctBy { it.toHex() }
        val payload = Announcement(identity.nickname, identity.noise.publicKey, identity.signing.publicKey, neighbors, AuthPeerState.CAP_PRIVATE_MEDIA).encode() ?: return
        val p = sign(Packet(type = MessageType.ANNOUNCE, senderId = identity.peerId, timestamp = now, payload = payload)) ?: return
        dedup.markIfNew(Deduplicator.key(p))
        myAnnounce = p
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

    private fun receive(p: Packet, linkId: String?, relayable: Boolean = true) {
        // A sync reply is only taken from a neighbour we asked recently (§14.5).
        if (p.isRsr && !solicited(linkId)) return
        if (p.type == MessageType.FRAGMENT) {
            // Relay fragments independently, and try to reassemble locally.
            if (!dedup.markIfNew(Deduplicator.key(p))) { pendingRelays.remove(Deduplicator.key(p))?.cancel(); return }
            val addressedToMe = p.recipientId?.toHex() == myPeerHex
            if (p.isBroadcast || addressedToMe) assembler.accept(p)?.let { receive(it, linkId, relayable = false) }
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
            MessageType.COURIER_ENVELOPE -> if (forMe) { handleCourier(p, linkId); return }
            MessageType.REQUEST_SYNC -> { if (forMe || p.isBroadcast) handleSyncRequest(p, linkId); return }
            MessageType.FILE_TRANSFER -> if (p.isBroadcast || forMe) { if (!handleFile(p, forMe)) return; if (forMe) return }
        }
        // Reassembled packets were already relayed as fragments.
        if (relayable && linkId != null) maybeRelay(p, linkId)
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
        if ((state.announce?.timestamp ?: 0) <= p.timestamp) state.announce = p
        // An announce at full TTL straight off a link came from that link's device.
        var newlyDirect = false
        if (linkId != null && p.ttl >= Packet.DEFAULT_TTL) {
            val link = links[linkId]
            if (link != null && link.peerHex != hex) {
                link.peerHex = hex
                state.directLinks.add(linkId)
                newlyDirect = true
                publishPeers()
                scheduler.schedule(INITIAL_SYNC_DELAY_MS) { if (links[linkId]?.peerHex == hex) requestSyncFrom(hex, linkId) }
            }
        }
        if (changed) publishPeers()
        flushIfPossible(hex)
        remember(hex, a.noisePublicKey, a.signingPublicKey, a.nickname, now)
        if (existing == null || newlyDirect) { flushOutboxTo(hex); flushAcksTo(hex) }
        val direct = state.directLinks.any { links.containsKey(it) }
        handoverTo(hex, a.noisePublicKey, direct)
        if (direct) { sprayTo(hex, a.noisePublicKey); depositWith(hex, a.noisePublicKey) }
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
        // A synced message may come from a peer that already left; its remembered key still verifies it.
        val peer = peers[hex]
        val signingKey = peer?.signingKey ?: known[hex]?.signingKey ?: return false
        if (!verify(p, signingKey)) return false
        if (!isFresh(p, scheduler.now())) return false
        (peer?.noiseKey ?: known[hex]?.noiseKey)?.let { if (isBlocked(it)) return true } // relayed and synced, never shown
        val text = try { String(p.payload, Charsets.UTF_8) } catch (e: Exception) { return false }
        if (peer != null && !p.isRsr) peer.lastSeen = scheduler.now()
        if (p.isBroadcast) storeHistory(p)
        listener.onPublicMessage(hex, peer?.nickname ?: known[hex]!!.nickname, text, p.timestamp)
        return true
    }

    // ---------------------------------------------------------------- media (§18)

    /** Encrypted private media is sent only to a peer that said, inside the session, it can take it. */
    fun canSendPrivateFile(peerHex: String) =
        hasSession(peerHex) && (peerCaps[peerHex] ?: 0L) and AuthPeerState.CAP_PRIVATE_MEDIA != 0L

    fun sendPrivateFile(peerHex: String, file: FilePacket): Boolean {
        if (!canSendPrivateFile(peerHex)) return false
        val body = file.encode() ?: return false
        sendNoise(peerHex, NoisePayload(NoisePayloadType.PRIVATE_FILE, body).encode())
        return true
    }

    fun sendPublicFile(file: FilePacket): Boolean {
        val body = file.encode() ?: return false
        val p = sign(Packet(type = MessageType.FILE_TRANSFER, senderId = identity.peerId, timestamp = scheduler.now(), payload = body)) ?: return false
        dedup.markIfNew(Deduplicator.key(p))
        broadcast(p, exclude = null)
        return true
    }

    /** A signed fileTransfer: broadcast, or the legacy unencrypted form addressed to us. */
    private fun handleFile(p: Packet, forMe: Boolean): Boolean {
        val hex = p.senderId.toHex()
        val peer = peers[hex]
        val signingKey = peer?.signingKey ?: known[hex]?.signingKey ?: return false
        if (!verify(p, signingKey)) return false
        if (!isFresh(p, scheduler.now())) return false
        if ((peer?.noiseKey ?: known[hex]?.noiseKey)?.let(isBlocked) == true) return true
        val file = FilePacket.decode(p.payload)?.takeIf { it.kind != null } ?: return true
        listener.onFile(hex, peer?.nickname ?: known[hex]!!.nickname, file, isPrivate = forMe, timestamp = p.timestamp)
        return true
    }

    // ---------------------------------------------------------------- history sync (§14)

    private fun isFresh(p: Packet, now: Long) = now - p.timestamp <= HISTORY_MAX_AGE_MS && p.timestamp - now <= FUTURE_SKEW_MS

    private fun storeHistory(p: Packet) {
        if (!isFresh(p, scheduler.now())) return
        val id = PacketId.compute(p).toHex()
        if (history.put(id, p) == null) {
            while (history.size > HISTORY_CAP) history.remove(history.keys.first())
            listener.onStateChanged()
        }
    }

    private fun solicited(linkId: String?): Boolean {
        val hex = links[linkId ?: return false]?.peerHex ?: return false
        val at = pendingSync[hex] ?: return false
        return scheduler.now() - at <= SYNC_REPLY_WINDOW_MS
    }

    private fun syncCandidates(now: Long): List<Packet> {
        val out = ArrayList<Packet>()
        myAnnounce?.let { out.add(it) }
        peers.values.mapNotNullTo(out) { it.announce }
        history.values.filterTo(out) { isFresh(it, now) }
        out.sortByDescending { it.timestamp }
        return out
    }

    private fun syncRound() {
        for (link in links.values.toList()) link.peerHex?.let { requestSyncFrom(it, link.id) }
    }

    /** Asks one neighbour for the announces and public messages we are missing (§14.3). */
    private fun requestSyncFrom(peerHex: String, linkId: String) {
        if (!links.containsKey(linkId)) return
        val now = scheduler.now()
        val candidates = syncCandidates(now)
        val p = GcsFilter.deriveP(SYNC_FPR)
        val take = minOf(candidates.size, GcsFilter.estimateMaxElements(SYNC_FILTER_BYTES, p), HISTORY_CAP)
        val request = if (take == 0) SyncRequest(p, 1, ByteArray(0)) else {
            val included = candidates.take(take)
            val f = GcsFilter.build(included.map { PacketId.compute(it) }, SYNC_FILTER_BYTES, SYNC_FPR)
            val since = if (f.includedCount in 1 until candidates.size) included[f.includedCount - 1].timestamp else null
            SyncRequest(f.p, f.m, f.data, SyncTypes.PUBLIC, since)
        }
        val pkt = sign(Packet(type = MessageType.REQUEST_SYNC, senderId = identity.peerId, recipientId = peerHex.hexToBytes(), timestamp = now, payload = request.encode(), ttl = 0)) ?: return
        pendingSync[peerHex] = now
        dedup.markIfNew(Deduplicator.key(pkt))
        sendOn(listOf(linkId), pkt)
    }

    /** Answers with every stored packet the asker's filter does not cover (§14.4). */
    private fun handleSyncRequest(p: Packet, linkId: String?) {
        if (linkId == null || !links.containsKey(linkId)) return
        val hex = p.senderId.toHex()
        val signingKey = peers[hex]?.signingKey ?: known[hex]?.signingKey ?: return
        if (!verify(p, signingKey)) return
        val now = scheduler.now()
        val recent = syncReplies.getOrPut(hex) { ArrayList() }
        recent.removeAll { now - it > SYNC_REPLY_WINDOW_MS }
        if (recent.size >= SYNC_REPLY_LIMIT) return
        recent.add(now)
        val request = SyncRequest.decode(p.payload) ?: return
        val filter = GcsFilter.decode(request.p, request.m, request.data)
        fun missing(pkt: Packet) = !GcsFilter.contains(filter, GcsFilter.bucket(PacketId.compute(pkt), request.m))
        val replies = ArrayList<Packet>()
        if (request.wants(SyncTypes.ANNOUNCE)) {
            myAnnounce?.let { if (isFresh(it, now) && missing(it)) replies.add(it) }
            for (peer in peers.values) peer.announce?.let { if (isFresh(it, now) && missing(it)) replies.add(it) }
        }
        if (request.wants(SyncTypes.MESSAGE)) {
            for (m in history.values) {
                if (!isFresh(m, now)) continue
                if (request.since != null && m.timestamp < request.since) continue
                if (missing(m)) replies.add(m)
            }
        }
        for (r in replies) sendOn(listOf(linkId), r.copy(ttl = 0, isRsr = true))
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
        // Tell the peer, inside the session, that it may send us encrypted media (§18.2).
        sendNoise(peerHex, NoisePayload(NoisePayloadType.AUTHENTICATED_PEER_STATE, AuthPeerState.encode(AuthPeerState.CAP_PRIVATE_MEDIA, identity.signing.publicKey)).encode())
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
                if (noiseKeyOf(peerHex)?.let(isBlocked) == true) return
                // Resends and courier copies share an ID: deliver once, but always answer so the sender can settle.
                if (markSeen(pm.messageId)) listener.onPrivateMessage(peerHex, nickname, pm.messageId, pm.content, scheduler.now())
                sendNoise(peerHex, NoisePayload(NoisePayloadType.DELIVERED, pm.messageId.toByteArray(Charsets.UTF_8)).encode())
            }
            NoisePayloadType.DELIVERED -> { val id = String(payload.body, Charsets.UTF_8); if (settle(id, 1)) listener.onDelivered(peerHex, id) }
            NoisePayloadType.READ_RECEIPT -> { val id = String(payload.body, Charsets.UTF_8); if (settle(id, 2)) listener.onRead(peerHex, id) }
            NoisePayloadType.AUTHENTICATED_PEER_STATE -> {
                val (caps, key) = AuthPeerState.decode(payload.body) ?: return
                if (peers[peerHex]?.signingKey?.contentEquals(key) == true) peerCaps[peerHex] = caps
            }
            NoisePayloadType.PRIVATE_FILE, NoisePayloadType.PRIVATE_FILE_PRERELEASE -> {
                if (noiseKeyOf(peerHex)?.let(isBlocked) == true) return
                val file = FilePacket.decode(payload.body)?.takeIf { it.kind != null } ?: return
                listener.onFile(peerHex, nickname, file, isPrivate = true, timestamp = scheduler.now())
            }
            NoisePayloadType.VERIFY_CHALLENGE -> {
                val (keyHex, nonce) = VerifyQr.parseChallenge(payload.body) ?: return
                if (!keyHex.equals(identity.noise.publicKey.toHex(), ignoreCase = true)) return
                sendNoise(peerHex, NoisePayload(NoisePayloadType.VERIFY_RESPONSE, VerifyQr.response(keyHex, nonce, identity.signing)).encode())
            }
            NoisePayloadType.VERIFY_RESPONSE -> {
                val nonce = pendingVerify[peerHex] ?: return
                val peer = peers[peerHex] ?: return
                if (VerifyQr.checkResponse(payload.body, peer.noiseKey.toHex(), nonce, peer.signingKey)) {
                    pendingVerify.remove(peerHex)
                    listener.onVerified(peerHex)
                }
            }
            else -> Unit // other payload types are not implemented
        }
    }

    // ---------------------------------------------------------------- store-and-forward (§13)

    private fun remember(hex: String, noiseKey: ByteArray, signingKey: ByteArray?, nickname: String, now: Long) {
        val k = known[hex]
        if (k == null) {
            if (known.size >= KNOWN_CAP) known.entries.minByOrNull { it.value.lastSeen }?.let { known.remove(it.key) }
            known[hex] = KnownPeer(noiseKey, signingKey, nickname, now)
        } else { k.nickname = nickname; k.lastSeen = now }
        listener.onStateChanged()
    }

    fun noiseKeyOf(hex: String): ByteArray? = peers[hex]?.noiseKey ?: known[hex]?.noiseKey

    /** Clears the outbox copy; true when this receipt advances what the listener already heard. */
    private fun settle(messageId: String, level: Int): Boolean {
        if (outbox.remove(messageId) != null) listener.onStateChanged()
        if ((acked[messageId] ?: 0) >= level) return false
        acked[messageId] = level
        while (acked.size > OPENED_CAP) acked.remove(acked.keys.first())
        return true
    }

    private fun markSeen(messageId: String): Boolean {
        if (!seenIds.add(messageId)) return false
        while (seenIds.size > OPENED_CAP) seenIds.remove(seenIds.first())
        return true
    }

    /** The peer is back: resend everything still waiting for it on the session. */
    private fun flushOutboxTo(hex: String) {
        outbox.values.filter { it.peerHex == hex }.forEach { sendNoise(hex, it.plaintext) }
    }

    private fun flushAcksTo(hex: String) {
        pendingAcks.remove(hex)?.forEach { id -> sendNoise(hex, NoisePayload(NoisePayloadType.DELIVERED, id.toByteArray(Charsets.UTF_8)).encode()) }
    }

    private fun seal(q: QueuedMessage, recipientKey: ByteArray, now: Long, copies: Int): ByteArray? {
        val ct = try { NoiseX.seal(identity.noise, recipientKey, COURIER_PROLOGUE, q.plaintext) } catch (e: Exception) { return null }
        val env = CourierEnvelope(CourierEnvelope.recipientTag(recipientKey, CourierEnvelope.epochDay(now)), now + CourierEnvelope.LIFETIME_MS, ct, copies)
        return env.encode()
    }

    private fun courierPacket(payload: ByteArray, toHex: String): Packet? =
        sign(Packet(type = MessageType.COURIER_ENVELOPE, senderId = identity.peerId, recipientId = toHex.hexToBytes(), timestamp = scheduler.now(), payload = payload))

    /** Sends only over the peer's own link, never relayed (§13.4 step 3 on the far side). */
    private fun sendOnDirectLink(hex: String, p: Packet): Boolean {
        val link = peers[hex]?.directLinks?.firstOrNull { links.containsKey(it) } ?: return false
        dedup.markIfNew(Deduplicator.key(p))
        sendOn(listOf(link), p)
        return true
    }

    /** Hands [q] to connected verified peers until [MAX_COURIERS] hold a copy (§13.1). */
    private fun deposit(q: QueuedMessage) {
        val recipientKey = noiseKeyOf(q.peerHex) ?: return
        val now = scheduler.now()
        val candidates = peers.entries.filter { (hex, s) ->
            hex != q.peerHex && s.directLinks.any { links.containsKey(it) } && s.noiseKey.keyHex() !in q.couriers && !s.noiseKey.contentEquals(recipientKey)
        }.take(MAX_COURIERS - q.couriers.size)
        if (candidates.isEmpty()) return
        val payload = seal(q, recipientKey, now, CourierEnvelope.INITIAL_COPIES) ?: return
        for ((hex, s) in candidates) {
            val p = courierPacket(payload, hex) ?: continue
            if (sendOnDirectLink(hex, p)) { q.couriers.add(s.noiseKey.keyHex()); listener.onCarried(q.peerHex, q.messageId) }
        }
    }

    /** A verified peer just connected: give it any outbox mail it is not carrying yet (§13.6 deposit). */
    private fun depositWith(courierHex: String, courierKey: ByteArray) {
        val keyHex = courierKey.keyHex()
        val now = scheduler.now()
        var changed = false
        for (q in outbox.values) {
            if (q.peerHex == courierHex || q.couriers.size >= MAX_COURIERS || keyHex in q.couriers) continue
            if (now - q.createdAt > CourierEnvelope.LIFETIME_MS) continue
            val recipientKey = noiseKeyOf(q.peerHex) ?: continue
            if (recipientKey.contentEquals(courierKey)) continue
            val payload = seal(q, recipientKey, now, CourierEnvelope.INITIAL_COPIES) ?: continue
            val p = courierPacket(payload, courierHex) ?: continue
            if (sendOnDirectLink(courierHex, p)) { q.couriers.add(keyHex); listener.onCarried(q.peerHex, q.messageId); changed = true }
        }
        if (changed) listener.onStateChanged()
    }

    /** An envelope addressed to us: open it, or carry it for someone else (§13.4). */
    private fun handleCourier(p: Packet, linkId: String?) {
        val env = CourierEnvelope.decode(p.payload) ?: return
        val now = scheduler.now()
        if (env.isExpired(now)) return
        if (env.recipientTag.toHex() in CourierEnvelope.candidateTags(identity.noise.publicKey, now)) { open(env); return }
        val link = linkId?.let { links[it] } ?: return
        val senderHex = p.senderId.toHex()
        if (link.peerHex != senderHex) return
        val depositor = peers[senderHex] ?: return
        if (!verify(p, depositor.signingKey)) return
        if (env.expiry > now + CourierEnvelope.LIFETIME_MS + EXPIRY_SLACK_MS) return
        val depositorHex = depositor.noiseKey.keyHex()
        carried.firstOrNull { it.envelope.ciphertext.contentEquals(env.ciphertext) }?.let { c ->
            if (c.sprayedTo.isEmpty()) c.copies = maxOf(c.copies, env.copies)
            return
        }
        if (carried.count { it.depositorKeyHex == depositorHex } >= PER_DEPOSITOR) return
        while (carried.size >= CARRY_CAP) carried.removeAt(0)
        carried.add(CarriedEnvelope(env, depositorHex, now, env.copies))
        listener.onStateChanged()
    }

    private fun open(env: CourierEnvelope) {
        if (env.prekeyId != null) return // envelope v2: sealed to a prekey we do not have
        val opened = try { NoiseX.open(identity.noise, COURIER_PROLOGUE, env.ciphertext) } catch (e: Exception) { return }
        val payload = NoisePayload.decode(opened.payload) ?: return
        if (payload.type != NoisePayloadType.PRIVATE_MESSAGE) return
        val pm = PrivateMessage.decode(payload.body) ?: return
        if (isBlocked(opened.senderStatic)) return
        if (!markSeen(pm.messageId)) return
        val senderHex = Identity.peerIdFor(opened.senderStatic).toHex()
        val now = scheduler.now()
        if (known[senderHex] == null) remember(senderHex, opened.senderStatic, null, senderHex.take(8), now)
        val nickname = peers[senderHex]?.nickname ?: known[senderHex]?.nickname ?: senderHex.take(8)
        listener.onPrivateMessage(senderHex, nickname, pm.messageId, pm.content, now)
        if (peers.containsKey(senderHex)) sendNoise(senderHex, NoisePayload(NoisePayloadType.DELIVERED, pm.messageId.toByteArray(Charsets.UTF_8)).encode())
        else pendingAcks.getOrPut(senderHex) { LinkedHashSet() }.let { if (it.size < 64) it.add(pm.messageId) }
    }

    /** Carried mail for a peer we just heard from (§13.6). */
    private fun handoverTo(hex: String, noiseKey: ByteArray, direct: Boolean) {
        if (carried.isEmpty()) return
        val now = scheduler.now()
        val tags = CourierEnvelope.candidateTags(noiseKey, now)
        val mine = carried.filter { it.envelope.recipientTag.toHex() in tags }
        if (mine.isEmpty()) return
        var changed = false
        for (c in mine) {
            val payload = c.envelope.withCopies(1).encode() ?: continue
            val p = courierPacket(payload, hex) ?: continue
            if (direct) {
                if (sendOnDirectLink(hex, p)) { carried.remove(c); changed = true }
            } else if (now - c.lastRemoteHandover > REMOTE_HANDOVER_COOLDOWN_MS) {
                c.lastRemoteHandover = now
                dedup.markIfNew(Deduplicator.key(p))
                broadcast(p, exclude = null)
                changed = true
            }
        }
        if (changed) listener.onStateChanged()
    }

    /** Spray-and-wait: split copy budgets with another courier (§13.6). */
    private fun sprayTo(hex: String, noiseKey: ByteArray) {
        if (carried.isEmpty()) return
        val keyHex = noiseKey.keyHex()
        val tags = CourierEnvelope.candidateTags(noiseKey, scheduler.now())
        var changed = false
        for (c in carried) {
            if (c.copies <= 1 || c.depositorKeyHex == keyHex || keyHex in c.sprayedTo) continue
            if (c.envelope.recipientTag.toHex() in tags) continue
            val give = c.copies / 2
            val payload = c.envelope.withCopies(give).encode() ?: continue
            val p = courierPacket(payload, hex) ?: continue
            if (sendOnDirectLink(hex, p)) { c.copies -= give; c.sprayedTo.add(keyHex); changed = true }
        }
        if (changed) listener.onStateChanged()
    }

    private fun prune(now: Long) {
        var changed = outbox.values.removeAll { now - it.createdAt > CourierEnvelope.LIFETIME_MS }
        changed = carried.removeAll { it.envelope.isExpired(now) } || changed
        changed = history.values.removeAll { !isFresh(it, now) } || changed
        pendingSync.values.removeAll { now - it > SYNC_REPLY_WINDOW_MS }
        syncReplies.values.removeAll { times -> times.removeAll { now - it > SYNC_REPLY_WINDOW_MS }; times.isEmpty() }
        if (changed) listener.onStateChanged()
    }

    private fun rekeyOldSessions(now: Long) {
        for ((hex, s) in sessions.entries.toList()) {
            if (s.established && (now - s.establishedAt > 3_600_000 || s.messagesSent > 10_000)) {
                if (myPeerHex < hex) { sessions.remove(hex); startHandshake(hex) }
            }
        }
    }

    // ---------------------------------------------------------------- signing

    companion object {
        private val COURIER_PROLOGUE = "bitchat-courier-v1".toByteArray(Charsets.US_ASCII)
        private const val MAX_COURIERS = 3
        private const val OUTBOX_CAP = 200
        private const val KNOWN_CAP = 500
        private const val CARRY_CAP = 40
        private const val PER_DEPOSITOR = 2
        private const val OPENED_CAP = 512
        private const val EXPIRY_SLACK_MS = 60 * 60 * 1000L
        private const val REMOTE_HANDOVER_COOLDOWN_MS = 10 * 60 * 1000L
        // History sync (§14, §11).
        private const val HISTORY_CAP = 1000
        private const val HISTORY_MAX_AGE_MS = 900_000L
        private const val FUTURE_SKEW_MS = 120_000L
        private const val SYNC_INTERVAL_MS = 15_000L
        private const val INITIAL_SYNC_DELAY_MS = 5_000L
        private const val SYNC_REPLY_WINDOW_MS = 30_000L
        private const val SYNC_REPLY_LIMIT = 8
        private const val SYNC_FILTER_BYTES = 400
        private const val SYNC_FPR = 0.01
    }

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
