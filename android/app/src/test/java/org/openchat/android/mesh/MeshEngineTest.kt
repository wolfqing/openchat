package org.openchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
    override fun onPeersChanged(peers: List<MeshPeer>) { this.peers = peers }
    override fun onPublicMessage(senderHex: String, nickname: String, text: String, timestamp: Long) { publics += "$nickname:$text" }
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

    fun node(name: String): MeshEngine {
        val rec = Recorder()
        lateinit var engine: MeshEngine
        engine = MeshEngine(Identity.generate(name), scheduler, LinkWriter { linkId, bytes ->
            bytesSent += bytes.size
            val (remote, remoteLink) = edges["$name/$linkId"] ?: return@LinkWriter
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
}
