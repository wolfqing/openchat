package org.openchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openchat.android.core.Announcement
import org.openchat.android.core.AuthPeerState
import org.openchat.android.core.MessageType
import org.openchat.android.core.Packet
import org.openchat.android.core.PacketCodec
import org.openchat.android.core.hexToBytes
import org.openchat.android.crypto.Crypto
import java.util.PriorityQueue
import kotlin.random.Random

/** Single-threaded virtual clock shared by every simulated device. */
class VirtualScheduler : Scheduler {
    private class Task(val at: Long, val seq: Long, val run: () -> Unit, var cancelled: Boolean = false)

    private var time = 1_761_700_000_000L
    private var seq = 0L
    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))

    override fun now() = time
    override fun post(task: () -> Unit) { schedule(0, task) }
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val t = Task(time + delayMs, seq++, task)
        queue.add(t)
        return Cancellable { t.cancelled = true }
    }

    fun advance(ms: Long) {
        val end = time + ms
        while (true) {
            val next = queue.peek() ?: break
            if (next.at > end) break
            queue.poll()
            time = next.at
            if (!next.cancelled) next.run()
        }
        time = end
    }
}

class Recorder : MeshListener {
    val publics = ArrayList<String>()
    val privates = ArrayList<Pair<String, String>>()
    val delivered = ArrayList<String>()
    val reads = ArrayList<String>()
    var peers: List<MeshPeer> = emptyList()
    val queued = ArrayList<String>()
    val carried = ArrayList<String>()
    val verified = ArrayList<String>()
    val groupStates = ArrayList<Pair<String, Boolean>>() // sender peer, invite
    val groupMessages = ArrayList<ByteArray>()
    val carriers = ArrayList<Pair<Boolean, ByteArray>>() // directed, payload
    val drops = ArrayList<ByteArray>()
    val vouches = ArrayList<Pair<ByteArray, ByteArray>>() // session key, signing key
    override fun onVouch(peerHex: String, sessionKey: ByteArray, signingKey: ByteArray, batch: ByteArray) { vouches += sessionKey to signingKey }
    override fun onCarrier(senderHex: String, payload: ByteArray, directed: Boolean) { carriers += directed to payload }
    override fun onDrop(envelope: ByteArray, recipientTagHex: String, expiresAt: Long) { drops += envelope }
    override fun onGroupState(peerHex: String, sessionKey: ByteArray, invite: Boolean, payload: ByteArray) { groupStates += peerHex to invite }
    override fun onGroupMessage(payload: ByteArray, timestamp: Long) { groupMessages += payload }
    val files = ArrayList<Pair<String, Boolean>>() // mime, private
    override fun onFile(senderHex: String, nickname: String, file: org.openchat.android.core.FilePacket, isPrivate: Boolean, timestamp: Long) { files += file.mimeType.orEmpty() to isPrivate }
    override fun onVerified(peerHex: String) { verified += peerHex }
    override fun onPeersChanged(peers: List<MeshPeer>) { this.peers = peers }
    override fun onPublicMessage(senderHex: String, nickname: String, text: String, timestamp: Long, replayed: Boolean) { publics += "$nickname:$text" }
    override fun onPrivateMessage(senderHex: String, nickname: String, messageId: String, text: String, timestamp: Long) { privates += messageId to text }
    override fun onDelivered(peerHex: String, messageId: String) { delivered += messageId }
    override fun onRead(peerHex: String, messageId: String) { reads += messageId }
    override fun onQueued(peerHex: String, messageId: String) { queued += messageId }
    override fun onCarried(peerHex: String, messageId: String) { carried += messageId }
}

/** Wires engines together with in-memory links; each write is delivered after a small delay. */
class SimNetwork(val scheduler: VirtualScheduler, private val maxWrite: Int = 182) {
    val engines = LinkedHashMap<String, MeshEngine>()
    val recorders = HashMap<String, Recorder>()
    private val edges = HashMap<String, Pair<String, String>>() // linkId at node -> (remote node, remote linkId)
    var bytesSent = 0L
    /** Radio loss: writes from [first] to [second] are silently dropped while the pair is listed. */
    val lossy = HashSet<Pair<String, String>>()

    fun node(name: String): MeshEngine {
        val rec = Recorder()
        lateinit var engine: MeshEngine
        engine = MeshEngine(Identity.generate(name), scheduler, LinkWriter { linkId, bytes ->
            bytesSent += bytes.size
            val (remote, remoteLink) = edges["$name/$linkId"] ?: return@LinkWriter
            if (name to remote in lossy) return@LinkWriter
            // Split into MTU-sized writes like a real radio would.
            bytes.toList().chunked(maxWrite).forEachIndexed { i, chunk ->
                scheduler.schedule(5L + i) { engines[remote]?.onBytes(remoteLink, chunk.toByteArray()) }
            }
        }, rec, Random(name.hashCode()))
        engines[name] = engine
        recorders[name] = rec
        return engine
    }

    fun connect(a: String, b: String) {
        val la = "to-$b"; val lb = "to-$a"
        edges["$a/$la"] = b to lb
        edges["$b/$lb"] = a to la
        engines[a]!!.onLinkUp(la, maxWrite)
        engines[b]!!.onLinkUp(lb, maxWrite)
    }

    fun disconnect(a: String, b: String) {
        val la = "to-$b"; val lb = "to-$a"
        edges.remove("$a/$la"); edges.remove("$b/$lb")
        engines[a]!!.onLinkDown(la)
        engines[b]!!.onLinkDown(lb)
    }
}

class MeshEngineTest {
    private fun line(): SimNetwork {
        val net = SimNetwork(VirtualScheduler())
        listOf("alice", "bob", "carol").forEach { net.node(it).start() }
        net.connect("alice", "bob")
        net.connect("bob", "carol")
        net.scheduler.advance(10_000)
        return net
    }

    @Test
    fun announcesReachTwoHops() {
        val net = line()
        val alicePeers = net.recorders["alice"]!!.peers.map { it.nickname }
        assertEquals(listOf("bob", "carol"), alicePeers)
        val carolSeesAlice = net.recorders["carol"]!!.peers.first { it.nickname == "alice" }
        assertTrue("alice is not carol's neighbour", !carolSeesAlice.direct)
        assertTrue(net.recorders["carol"]!!.peers.first { it.nickname == "bob" }.direct)
    }

    @Test
    fun publicMessageIsRelayedOnce() {
        val net = line()
        net.engines["alice"]!!.sendPublic("hello everyone")
        net.scheduler.advance(3_000)
        assertEquals(listOf("alice:hello everyone"), net.recorders["bob"]!!.publics)
        assertEquals(listOf("alice:hello everyone"), net.recorders["carol"]!!.publics)
        assertTrue(net.recorders["alice"]!!.publics.isEmpty())
    }

    @Test
    fun longPublicMessageIsFragmentedAndRelayed() {
        val net = line()
        val text = (1..60).joinToString(" ") { "word$it-${it * 7919 % 1000}" }
        net.engines["alice"]!!.sendPublic(text)
        net.scheduler.advance(5_000)
        assertEquals(listOf("alice:$text"), net.recorders["carol"]!!.publics)
    }

    @Test
    fun privateMessageAcrossRelayWithReceipts() {
        val net = line()
        val carol = net.engines["carol"]!!
        val ids = net.engines["alice"]!!.sendPrivate(carol.myPeerHex, "secret for carol")
        net.scheduler.advance(5_000)
        assertEquals(listOf(ids[0] to "secret for carol"), net.recorders["carol"]!!.privates)
        assertTrue(net.recorders["bob"]!!.privates.isEmpty())
        assertEquals(ids, net.recorders["alice"]!!.delivered)

        carol.sendReadReceipt(net.engines["alice"]!!.myPeerHex, ids[0])
        net.scheduler.advance(2_000)
        assertEquals(ids, net.recorders["alice"]!!.reads)

        // Reply uses the established session.
        carol.sendPrivate(net.engines["alice"]!!.myPeerHex, "got it")
        net.scheduler.advance(2_000)
        assertEquals("got it", net.recorders["alice"]!!.privates.single().second)
    }

    @Test
    fun crossedHandshakesConverge() {
        val net = line()
        val alice = net.engines["alice"]!!
        val carol = net.engines["carol"]!!
        alice.sendPrivate(carol.myPeerHex, "from alice")
        carol.sendPrivate(alice.myPeerHex, "from carol")
        net.scheduler.advance(10_000)
        assertEquals(listOf("from alice"), net.recorders["carol"]!!.privates.map { it.second })
        assertEquals(listOf("from carol"), net.recorders["alice"]!!.privates.map { it.second })
    }

    @Test
    fun panicResetChangesPeerIdAndDropsOldSessions() {
        val net = line()
        val alice = net.engines["alice"]!!
        val carol = net.engines["carol"]!!
        alice.sendPrivate(carol.myPeerHex, "before")
        net.scheduler.advance(5_000)
        val oldId = alice.myPeerHex
        alice.resetIdentity(Identity.generate("anon"))
        net.scheduler.advance(20_000)
        assertNotEquals(oldId, alice.myPeerHex)
        assertTrue(alice.peersSnapshot().isNotEmpty())
        assertTrue(carol.peersSnapshot().any { it.peerIdHex == alice.myPeerHex })
        // A fresh session forms with the new identity.
        alice.sendPrivate(carol.myPeerHex, "after")
        net.scheduler.advance(5_000)
        assertEquals(listOf("before", "after"), net.recorders["carol"]!!.privates.map { it.second })
    }

    @Test
    fun peersExpireAfterLinkLoss() {
        val net = line()
        net.engines["bob"]!!.onLinkDown("to-carol")
        net.engines["carol"]!!.onLinkDown("to-bob")
        net.scheduler.advance(70_000)
        assertEquals(listOf("bob"), net.recorders["alice"]!!.peers.map { it.nickname })
    }

    /**
     * Store-and-forward (§13): alice knows carol, loses her, writes to her while only
     * bob is around; bob carries the sealed envelope, later meets carol and hands it
     * over; carol's receipt reaches alice when they meet again.
     */
    @Test
    fun courierCarriesMailToAbsentPeer() {
        val net = SimNetwork(VirtualScheduler())
        listOf("alice", "bob", "carol").forEach { net.node(it).start() }
        val alice = net.engines["alice"]!!; val bob = net.engines["bob"]!!; val carol = net.engines["carol"]!!
        net.connect("alice", "carol")
        net.scheduler.advance(5_000)
        net.disconnect("alice", "carol")
        net.scheduler.advance(70_000) // carol expires from alice's peer table but her key is remembered
        assertTrue(net.recorders["alice"]!!.peers.none { it.nickname == "carol" })

        net.connect("alice", "bob")
        net.scheduler.advance(5_000)
        val ids = alice.sendPrivate(carol.myPeerHex, "see you at the north gate")
        net.scheduler.advance(3_000)
        assertEquals(ids, net.recorders["alice"]!!.queued)
        assertEquals(ids, net.recorders["alice"]!!.carried)
        assertEquals(1, bob.carriedCount)
        assertEquals(2, alice.outboxState(ids[0]))
        assertTrue(net.recorders["bob"]!!.privates.isEmpty())

        net.disconnect("alice", "bob")
        net.scheduler.advance(70_000)
        net.connect("bob", "carol")
        net.scheduler.advance(5_000)
        assertEquals(listOf(ids[0] to "see you at the north gate"), net.recorders["carol"]!!.privates)
        assertEquals(0, bob.carriedCount)
        assertTrue(net.recorders["alice"]!!.delivered.isEmpty())

        // The receipt waits for alice; the outbox copy is kept meanwhile and cleared on receipt.
        assertEquals(1, alice.outboxCount)
        net.disconnect("bob", "carol")
        net.scheduler.advance(70_000)
        net.connect("alice", "carol")
        net.scheduler.advance(8_000)
        assertEquals(ids, net.recorders["alice"]!!.delivered)
        assertEquals(0, alice.outboxCount)
        // A second copy of the same message must not surface twice.
        assertEquals(1, net.recorders["carol"]!!.privates.size)
    }

    @Test
    fun outboxSurvivesExportImport() {
        val net = SimNetwork(VirtualScheduler())
        listOf("alice", "carol").forEach { net.node(it).start() }
        val alice = net.engines["alice"]!!; val carol = net.engines["carol"]!!
        net.connect("alice", "carol"); net.scheduler.advance(5_000)
        net.disconnect("alice", "carol"); net.scheduler.advance(70_000)
        val ids = alice.sendPrivate(carol.myPeerHex, "kept across restarts")
        val bytes = alice.exportState().serialize()
        val restored = MeshState.deserialize(bytes)!!
        assertEquals(1, restored.outbox.size)
        assertEquals(ids[0], restored.outbox[0].messageId)
        assertTrue(restored.known.containsKey(carol.myPeerHex))
    }

    @Test
    fun lateJoinerGetsPublicHistory() {
        val net = SimNetwork(VirtualScheduler())
        listOf("alice", "bob", "carol").forEach { net.node(it).start() }
        net.connect("alice", "bob"); net.scheduler.advance(10_000)
        val alice = net.engines["alice"]!!
        alice.sendPublic("first"); alice.sendPublic("second")
        net.scheduler.advance(20_000)
        assertEquals(listOf("alice:first", "alice:second"), net.recorders["bob"]!!.publics)
        // Carol arrives after the fact, one hop from bob and never in range of alice.
        // A Golomb-coded set has ~1 % false positives, so one round can miss a message;
        // the next round's filter maps differently and picks it up.
        net.connect("bob", "carol"); net.scheduler.advance(40_000)
        assertEquals(listOf("alice:first", "alice:second"), net.recorders["carol"]!!.publics.sorted())
        // Carol now carries the history herself, and alice gets nothing back she already has.
        assertEquals(2, net.engines["carol"]!!.historyCount)
        assertEquals(emptyList<String>(), net.recorders["alice"]!!.publics)
        // Later rounds re-send nothing: the filter covers what carol holds, so 30 s of
        // announces and sync requests on two links stays small.
        val sent = net.bytesSent
        net.scheduler.advance(30_000)
        assertEquals(2, net.recorders["carol"]!!.publics.size)
        assertTrue("sent ${net.bytesSent - sent}", net.bytesSent - sent < 6_000)
    }

    @Test
    fun unsolicitedSyncReplyIsDropped() {
        val net = SimNetwork(VirtualScheduler())
        listOf("alice", "bob").forEach { net.node(it).start() }
        net.connect("alice", "bob"); net.scheduler.advance(10_000)
        val alice = net.engines["alice"]!!; val bob = net.engines["bob"]!!
        val plain = Packet(type = MessageType.MESSAGE, senderId = alice.identity.peerId, timestamp = net.scheduler.now(), payload = "replayed".toByteArray())
        val signed = plain.copy(signature = Crypto.ed25519Sign(alice.identity.signing.privateKey, PacketCodec.signingBytes(plain)!!))
        // A reply-marked copy on a link bob never asked over is ignored; the same packet unmarked is fine.
        bob.onLinkUp("rogue", 182)
        bob.onBytes("rogue", PacketCodec.encode(signed.copy(ttl = 0, isRsr = true))!!)
        net.scheduler.advance(1_000)
        assertEquals(emptyList<String>(), net.recorders["bob"]!!.publics)
        bob.onBytes("rogue", PacketCodec.encode(signed)!!)
        net.scheduler.advance(1_000)
        assertEquals(listOf("alice:replayed"), net.recorders["bob"]!!.publics)
    }

    @Test
    fun historySurvivesExportImport() {
        val net = SimNetwork(VirtualScheduler())
        listOf("alice", "bob").forEach { net.node(it).start() }
        net.connect("alice", "bob"); net.scheduler.advance(10_000)
        net.engines["alice"]!!.sendPublic("remember me")
        net.scheduler.advance(2_000)
        val bob = net.engines["bob"]!!
        val restored = MeshState.deserialize(bob.exportState().serialize())!!
        assertEquals(1, restored.history.size)
        assertEquals("remember me", String(restored.history[0].payload))
        assertTrue(restored.history[0].signature != null)
    }

    @Test
    fun blockedSenderIsHiddenButStillRelayed() {
        val net = line()
        val alice = net.engines["alice"]!!; val bob = net.engines["bob"]!!; val carol = net.engines["carol"]!!
        val aliceKey = alice.identity.noise.publicKey
        bob.isBlocked = { it.contentEquals(aliceKey) }
        alice.sendPublic("hello all")
        alice.sendPrivate(bob.myPeerHex, "hello bob")
        net.scheduler.advance(10_000)
        assertEquals(emptyList<String>(), net.recorders["bob"]!!.publics)
        assertEquals(0, net.recorders["bob"]!!.privates.size)
        assertEquals(0, net.recorders["alice"]!!.delivered.size) // no receipt tells alice she is blocked
        assertEquals(listOf("alice:hello all"), net.recorders["carol"]!!.publics) // bob still relays
        carol.sendPublic("hi")
        net.scheduler.advance(2_000)
        assertEquals(listOf("carol:hi"), net.recorders["bob"]!!.publics)
    }

    @Test
    fun verifyChallengeIsAnsweredOverNoise() {
        val net = line()
        val alice = net.engines["alice"]!!; val carol = net.engines["carol"]!!
        alice.sendVerifyChallenge(carol.myPeerHex)
        net.scheduler.advance(5_000)
        assertEquals(listOf(carol.myPeerHex), net.recorders["alice"]!!.verified)
    }

    @Test
    fun photosTravelPubliclyAndPrivately() {
        val net = line()
        val alice = net.engines["alice"]!!; val carol = net.engines["carol"]!!
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(3000) { (it * 7).toByte() }
        val photo = org.openchat.android.core.FilePacket("img_x.jpg", "image/jpeg", jpeg)
        assertTrue(alice.sendPublicFile(photo))
        net.scheduler.advance(10_000)
        assertEquals(listOf("image/jpeg" to false), net.recorders["carol"]!!.files)
        // Private media needs a session in which carol said she takes it.
        assertEquals(false, alice.canSendPrivateFile(carol.myPeerHex))
        alice.sendPrivate(carol.myPeerHex, "hi")
        net.scheduler.advance(5_000)
        assertTrue(alice.canSendPrivateFile(carol.myPeerHex))
        assertTrue(alice.sendPrivateFile(carol.myPeerHex, photo))
        net.scheduler.advance(10_000)
        assertEquals(listOf("image/jpeg" to false, "image/jpeg" to true), net.recorders["carol"]!!.files)
        assertEquals(1, net.recorders["bob"]!!.files.size) // the relay saw only the public one
    }

    // ---------------------------------------------------------------- session liveness and abuse (§8.1)

    private fun pair(): SimNetwork {
        val net = SimNetwork(VirtualScheduler())
        listOf("alice", "bob").forEach { net.node(it).start() }
        net.connect("alice", "bob")
        net.scheduler.advance(10_000)
        return net
    }

    /** A rogue link into [to] that carries whatever bytes the test writes. */
    private fun rogueLink(net: SimNetwork, to: String): (Packet) -> Unit {
        net.engines[to]!!.onLinkUp("rogue", 182)
        return { p -> net.engines[to]!!.onBytes("rogue", PacketCodec.encode(p)!!) }
    }

    @Test
    fun handshakeRetriesAfterLostMessage() {
        val net = pair()
        val alice = net.engines["alice"]!!; val bob = net.engines["bob"]!!
        net.lossy += "alice" to "bob" // message 1 never arrives
        val ids = alice.sendPrivate(bob.myPeerHex, "are you there")
        net.scheduler.advance(3_000)
        assertTrue(net.recorders["bob"]!!.privates.isEmpty())
        net.lossy.clear()
        net.scheduler.advance(15_000) // the stalled handshake times out and is started again
        assertEquals(listOf(ids[0] to "are you there"), net.recorders["bob"]!!.privates)
        assertEquals(ids, net.recorders["alice"]!!.delivered)
    }

    @Test
    fun garbageCiphertextDoesNotCutAnEstablishedSession() {
        val net = pair()
        val alice = net.engines["alice"]!!; val bob = net.engines["bob"]!!
        alice.sendPrivate(bob.myPeerHex, "one")
        net.scheduler.advance(3_000)
        assertTrue(bob.hasSession(alice.myPeerHex))
        // Anyone can put alice's peer ID in the sender field of an unsigned Noise packet.
        val inject = rogueLink(net, "bob")
        repeat(10) { i ->
            inject(Packet(type = MessageType.NOISE_ENCRYPTED, senderId = alice.identity.peerId, recipientId = bob.identity.peerId,
                timestamp = net.scheduler.now(), payload = Random(i).nextBytes(48)))
            net.scheduler.advance(500)
        }
        assertTrue("session survives forged ciphertext", bob.hasSession(alice.myPeerHex))
        alice.sendPrivate(bob.myPeerHex, "two")
        net.scheduler.advance(2_000)
        assertEquals(listOf("one", "two"), net.recorders["bob"]!!.privates.map { it.second })
    }

    @Test
    fun forgedHandshakeDoesNotReplaceAnEstablishedSession() {
        val net = pair()
        val alice = net.engines["alice"]!!; val bob = net.engines["bob"]!!
        alice.sendPrivate(bob.myPeerHex, "one")
        net.scheduler.advance(3_000)
        val inject = rogueLink(net, "bob")
        // A bare 32-byte message 1 claiming to be alice; the impostor cannot finish it without her static key.
        inject(Packet(type = MessageType.NOISE_HANDSHAKE, senderId = alice.identity.peerId, recipientId = bob.identity.peerId,
            timestamp = net.scheduler.now(), payload = Random(7).nextBytes(32)))
        net.scheduler.advance(1_000)
        assertTrue(bob.hasSession(alice.myPeerHex))
        alice.sendPrivate(bob.myPeerHex, "two")
        net.scheduler.advance(2_000)
        assertEquals(listOf("one", "two"), net.recorders["bob"]!!.privates.map { it.second })
        // The dangling impostor attempt is forgotten.
        net.scheduler.advance(15_000)
        assertEquals(1, bob.sessionCount)
    }

    @Test
    fun strangersHalfOpenHandshakesAreBoundedAndExpire() {
        val net = pair()
        val bob = net.engines["bob"]!!
        val inject = rogueLink(net, "bob")
        repeat(200) { i ->
            val stranger = Identity.generate("x$i")
            inject(Packet(type = MessageType.NOISE_HANDSHAKE, senderId = stranger.peerId, recipientId = bob.identity.peerId,
                timestamp = net.scheduler.now(), payload = Random(i).nextBytes(32)))
        }
        assertTrue("capped at a few dozen, was ${bob.sessionCount}", bob.sessionCount <= 64)
        net.scheduler.advance(15_000)
        assertEquals(0, bob.sessionCount)
    }

    @Test
    fun ownBroadcastReachesEveryNeighbour() {
        // A hub with six leaves: a relayed packet may take the fanout subset, our own must not.
        val net = SimNetwork(VirtualScheduler())
        val leaves = listOf("ada", "grace", "edith", "hedy", "mary", "joan")
        (listOf("hub") + leaves).forEach { net.node(it).start() }
        leaves.forEach { net.connect("hub", it) }
        net.scheduler.advance(10_000)
        net.engines["hub"]!!.sendPublic("to everyone")
        net.scheduler.advance(3_000)
        leaves.forEach { assertEquals(it, listOf("hub:to everyone"), net.recorders[it]!!.publics) }
    }

    @Test
    fun announceUnderAnotherSigningKeyIsRejected() {
        val net = pair()
        val alice = net.engines["alice"]!!; val bob = net.engines["bob"]!!
        net.disconnect("alice", "bob")
        net.scheduler.advance(70_000) // alice has left bob's peer table; her keys are remembered
        assertTrue(bob.peersSnapshot().none { it.peerIdHex == alice.myPeerHex })
        val mallory = Identity.generate("alice")
        val payload = Announcement("alice", alice.identity.noise.publicKey, mallory.signing.publicKey, emptyList(), AuthPeerState.CAP_PRIVATE_MEDIA).encode()!!
        val plain = Packet(type = MessageType.ANNOUNCE, senderId = alice.identity.peerId, timestamp = net.scheduler.now(), payload = payload)
        val forged = plain.copy(signature = Crypto.ed25519Sign(mallory.signing.privateKey, PacketCodec.signingBytes(plain)!!))
        rogueLink(net, "bob")(forged)
        net.scheduler.advance(1_000)
        assertTrue("alice's Noise key under mallory's signing key is not alice", bob.peersSnapshot().none { it.peerIdHex == alice.myPeerHex })
        // The real alice is still welcome.
        net.connect("alice", "bob")
        net.scheduler.advance(5_000)
        assertTrue(bob.peersSnapshot().any { it.peerIdHex == alice.myPeerHex && it.signingPublicKey.contentEquals(alice.identity.signing.publicKey) })
    }

    @Test
    fun groupStateGoesOverNoiseAndMessagesFloodAndSync() {
        val net = line()
        val alice = net.engines["alice"]!!; val carol = net.engines["carol"]!!
        alice.sendGroupState(carol.myPeerHex, invite = true, payload = byteArrayOf(1, 2, 3))
        net.scheduler.advance(5_000)
        assertEquals(listOf(alice.myPeerHex to true), net.recorders["carol"]!!.groupStates)
        assertEquals(0, net.recorders["bob"]!!.groupStates.size)
        alice.broadcastGroupMessage(byteArrayOf(7, 7, 7))
        net.scheduler.advance(5_000)
        assertEquals(1, net.recorders["carol"]!!.groupMessages.size) // relayed through bob
        assertEquals(1, net.recorders["bob"]!!.groupMessages.size)   // bob sees it but cannot open it
        // Dave turns up later next to carol and gets the sealed message by history sync.
        net.node("dave").start()
        net.connect("carol", "dave"); net.scheduler.advance(40_000)
        assertEquals(1, net.recorders["dave"]!!.groupMessages.size)
    }

    @Test
    fun aSyncCopyDoesNotCancelTheRelay() {
        // Same timing as the group test: alice's broadcast lands while bob's sync request to her is
        // in flight, so bob gets her sync reply while his relay to carol is still waiting.
        val net = line()
        net.scheduler.advance(5_000)
        net.engines["alice"]!!.sendPublic("first light")
        net.scheduler.advance(5_000)
        assertEquals(listOf("alice:first light"), net.recorders["carol"]!!.publics)
    }

    @Test
    fun boardPostsFloodSyncAndComeDown() {
        val net = line()
        val alice = net.engines["alice"]!!; val carol = net.engines["carol"]!!
        assertTrue(alice.postToBoard("Water at the north gate until 18:00", urgent = true, days = 1))
        net.scheduler.advance(5_000)
        assertEquals(listOf("Water at the north gate until 18:00"), carol.boardPosts().map { it.content })
        // Someone who arrives later still reads it, past the 15-minute window of chat history.
        net.scheduler.advance(30 * 60_000L)
        net.node("dave").start()
        net.connect("carol", "dave"); net.scheduler.advance(40_000)
        val dave = net.engines["dave"]!!
        assertEquals(1, dave.boardPosts().size)
        // Taking it down spreads the same way, and survives a restart of the board.
        assertTrue(alice.deleteBoardPost(alice.boardPosts()[0].idHex))
        net.scheduler.advance(40_000)
        assertEquals(0, dave.boardPosts().size)
        val restored = MeshState.deserialize(dave.exportState().serialize())!!
        assertEquals(1, restored.board.size) // the tombstone, kept until the post would have expired
    }

    @Test
    fun bridgeCellIsAdvertisedAndCarriersTravel() {
        val net = line()
        val alice = net.engines["alice"]!!; val bob = net.engines["bob"]!!; val carol = net.engines["carol"]!!
        bob.setBridgeCell("u4pruy")
        net.scheduler.advance(3_000)
        assertEquals(listOf(bob.myPeerHex to "u4pruy"), alice.bridgePeers())
        // A deposit goes to the bridge phone alone; a downlink reaches everyone.
        alice.sendCarrier(byteArrayOf(1), bob.myPeerHex)
        bob.sendCarrier(byteArrayOf(2), null)
        net.scheduler.advance(3_000)
        assertEquals(listOf(true), net.recorders["bob"]!!.carriers.map { it.first })
        assertEquals(listOf(false), net.recorders["carol"]!!.carriers.map { it.first })
        assertEquals(listOf(false), net.recorders["alice"]!!.carriers.map { it.first })
        bob.setBridgeCell(null)
        net.scheduler.advance(3_000)
        assertEquals(emptyList<Pair<String, String?>>(), alice.bridgePeers())
    }

    @Test
    fun mailForAnAbsentPeerCanBeDroppedAndOpenedFromARelay() {
        val net = SimNetwork(VirtualScheduler())
        listOf("alice", "carol").forEach { net.node(it).start() }
        val alice = net.engines["alice"]!!; val carol = net.engines["carol"]!!
        net.connect("alice", "carol"); net.scheduler.advance(5_000)
        net.disconnect("alice", "carol"); net.scheduler.advance(70_000)
        alice.sendPrivate(carol.myPeerHex, "left at the relay")
        val drop = net.recorders["alice"]!!.drops.single()
        assertTrue(carol.dropTags().isNotEmpty())
        carol.receiveDrop(drop)
        assertEquals(listOf("left at the relay"), net.recorders["carol"]!!.privates.map { it.second })
        carol.receiveDrop(drop) // a second relay's copy is not shown twice
        assertEquals(1, net.recorders["carol"]!!.privates.size)
    }

    @Test
    fun pingsComeBackWithHopsAndAreBudgetedPerLink() {
        val net = line()
        val alice = net.engines["alice"]!!; val bob = net.engines["bob"]!!; val carol = net.engines["carol"]!!
        val results = ArrayList<PingResult?>()
        alice.ping(carol.myPeerHex) { results += it }
        alice.ping(bob.myPeerHex) { results += it }
        net.scheduler.advance(2_000)
        assertEquals(listOf(1, 2), results.map { it!!.hops!! }.sorted())
        // Nobody answers for a peer that is not there.
        alice.ping("0011223344556677") { results += it }
        net.scheduler.advance(11_000)
        assertEquals(null, results.last())
        // Bob answers at most five pings a link per ten seconds.
        results.clear()
        repeat(7) { alice.ping(bob.myPeerHex) { results += it } }
        net.scheduler.advance(11_000)
        assertEquals(5, results.count { it != null })
    }

    @Test
    fun pathIsEstimatedFromAnnouncedNeighbours() {
        val net = pair()
        val alice = net.engines["alice"]!!; val bob = net.engines["bob"]!!
        val x = Identity.generate("x"); val y = Identity.generate("y")
        val inject = rogueLink(net, "alice")
        fun announce(id: Identity, neighbours: List<ByteArray>) {
            val payload = Announcement(id.nickname, id.noise.publicKey, id.signing.publicKey, neighbours).encode()!!
            val plain = Packet(type = MessageType.ANNOUNCE, senderId = id.peerId, timestamp = net.scheduler.now(), payload = payload, ttl = 5)
            inject(plain.copy(signature = Crypto.ed25519Sign(id.signing.privateKey, PacketCodec.signingBytes(plain)!!)))
        }
        announce(x, listOf(bob.identity.peerId)) // bob claims nothing; x's claim is enough
        announce(y, listOf(x.peerId))
        net.scheduler.advance(1_000)
        assertEquals(emptyList<String>(), alice.estimatePath(bob.myPeerHex))
        assertEquals(listOf(bob.myPeerHex, x.peerIdHex), alice.estimatePath(y.peerIdHex))
        assertEquals(null, alice.estimatePath("0011223344556677"))
    }

    @Test
    fun gatewayBitIsAdvertisedAndWithdrawn() {
        val net = line()
        val alice = net.engines["alice"]!!; val carol = net.engines["carol"]!!
        carol.setGateway(true)
        net.scheduler.advance(3_000)
        assertEquals(listOf(carol.myPeerHex), alice.gatewayPeers())
        assertEquals(emptyList<Pair<String, String?>>(), alice.bridgePeers()) // a gateway is not a bridge
        carol.setGateway(false)
        net.scheduler.advance(3_000)
        assertEquals(emptyList<String>(), alice.gatewayPeers())
    }

    @Test
    fun vouchesTravelInsideTheSessionAndNameTheSessionPeer() {
        val net = pair()
        val alice = net.engines["alice"]!!; val bob = net.engines["bob"]!!
        alice.sendPrivate(bob.myPeerHex, "hi") // brings the session up
        net.scheduler.advance(5_000)
        val v = org.openchat.android.core.Vouch.create(ByteArray(32) { 7 }, ByteArray(32) { 8 }, net.scheduler.now(), alice.identity.signing)!!
        alice.sendVouches(bob.myPeerHex, org.openchat.android.core.Vouch.encodeList(listOf(v))!!)
        net.scheduler.advance(2_000)
        val (session, signing) = net.recorders["bob"]!!.vouches.single()
        assertTrue(session.contentEquals(alice.identity.noise.publicKey))
        assertTrue(signing.contentEquals(alice.identity.signing.publicKey))
        // Vouching is part of what a phone announces it can do.
        assertTrue(bob.capabilitiesOf(alice.myPeerHex) and MeshEngine.CAP_VOUCH != 0L)
    }
}
