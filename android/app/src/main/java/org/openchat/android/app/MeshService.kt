package org.openchat.android.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import org.openchat.android.R
import org.openchat.android.core.FilePacket
import org.openchat.android.core.Mentions
import org.openchat.android.core.NoisePayloadType
import org.openchat.android.core.NostrEmbedded
import org.openchat.android.core.PrivateMessage
import org.openchat.android.core.VerifyQr
import org.openchat.android.core.hexToBytes
import org.openchat.android.core.sha256
import org.openchat.android.core.toHex
import org.openchat.android.mesh.BleTransport
import org.openchat.android.mesh.Cancellable
import org.openchat.android.mesh.Identity
import org.openchat.android.mesh.MeshEngine
import org.openchat.android.mesh.MeshListener
import org.openchat.android.mesh.MeshPeer
import org.openchat.android.mesh.Scheduler
import org.openchat.android.nostr.Geohash
import org.openchat.android.nostr.RelayDirectory
import org.openchat.android.ui.MainActivity

/**
 * Foreground service that keeps the mesh alive while the screen is off.
 * MeshEngine and BLE run on one dedicated thread; Nostr runs on OkHttp's threads.
 */
class MeshService : Service(), ChatController {
    companion object {
        private const val CHANNEL_STATUS = "mesh-status"
        private const val CHANNEL_MESSAGES = "messages"
        private const val NOTIFICATION_ID = 1
        private const val NOTE_PRECISION = 8 // building level, as bitchat

        fun start(context: Context) {
            val i = Intent(context, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var store: IdentityStore
    private lateinit var stateStore: MeshStateStore
    private lateinit var socialStore: SealedFile
    private lateinit var nostr: NostrDirect
    private lateinit var tor: Tor
    private lateinit var nostrSeenStore: SealedFile
    private val nostrSeen = LinkedHashSet<String>() // message IDs already shown from Nostr, kept across restarts
    // Noise static keys by peer ID, readable from the UI thread (the engine's maps are not).
    private val noiseKeys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val saveState = Runnable { stateStore.save(engine.exportState()) }
    private lateinit var engine: MeshEngine
    private lateinit var ble: BleTransport
    private lateinit var geo: GeoChannels
    private var bleStarted = false

    private val scheduler = object : Scheduler {
        override fun post(task: () -> Unit) { handler.post(task) }
        override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
            val token = Any()
            handler.postAtTime(Runnable { task() }, token, SystemClock.uptimeMillis() + delayMs)
            return Cancellable { handler.removeCallbacksAndMessages(token) }
        }
        override fun now() = System.currentTimeMillis()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Notification text follows the in-app language from the next service start.
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startInForeground()
        thread = HandlerThread("mesh").apply { start() }
        handler = Handler(thread.looper)
        store = IdentityStore(this)
        stateStore = MeshStateStore(this)
        val identity = store.loadOrCreate()
        engine = MeshEngine(identity, scheduler, { link, bytes -> ble.write(link, bytes) }, listener)
        stateStore.load()?.let { s -> engine.importState(s); s.known.forEach { (hex, k) -> noiseKeys[hex] = k.noiseKey } }
        // Route relay traffic before any relay socket opens (§19).
        tor = Tor(this) { s -> ChatRepository.updateGeo { it.copy(tor = s) } }
        tor.apply()
        socialStore = SealedFile(this, "social.bin")
        socialStore.read()?.let { Social.deserialize(it) }?.let { Social.load(it) }
        Social.onChanged = { s -> handler.post { socialStore.write(Social.serialize(s)) } }
        engine.isBlocked = { key -> sha256(key).toHex() in Social.state.value.blocked }
        Social.state.value.favorites.values.forEach { f -> f.noiseKeyHex.hexToBytes()?.let { noiseKeys[Identity.peerIdFor(it).toHex()] = it } }
        nostrSeenStore = SealedFile(this, "nostr-seen.bin")
        nostrSeenStore.read()?.let { nostrSeen.addAll(String(it, Charsets.UTF_8).split('\n').filter { id -> id.isNotEmpty() }) }
        nostr = NostrDirect(identity.nostrSeed) { sender, content, createdAt, _ -> handler.post { onNostrDirect(sender, content, createdAt) } }
        nostr.start()
        ble = BleTransport(this, handler) { engine }
        geo = GeoChannels(RelayDirectory.parse(assets.open("relays.csv").bufferedReader().readText()), identity.nostrSeed, { engine.identity.nickname }) { from, text ->
            notifyMention(from, text)
        }
        ChatRepository.controller = this
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        handler.post {
            engine.start()
            startBleIfPossible()
            publishStatus()
            periodic()
        }
        refreshLocation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.post { startBleIfPossible(); publishStatus() }
        return START_STICKY
    }

    override fun onDestroy() {
        ChatRepository.controller = null
        try { unregisterReceiver(btReceiver) } catch (e: Exception) { }
        handler.post {
            engine.stop()
            ble.stop()
            thread.quitSafely()
        }
        geo.close()
        nostr.close()
        tor.stop()
        ChatRepository.updateMesh { it.copy(running = false, links = 0) }
        super.onDestroy()
    }

    private fun startBleIfPossible() {
        if (bleStarted || !ble.isEnabled) return
        try {
            ble.start()
            bleStarted = true
        } catch (e: SecurityException) {
            bleStarted = false
        }
    }

    private fun periodic() {
        publishStatus()
        tor.poll()
        handler.postDelayed({ periodic() }, 5_000)
    }

    private var lastHeartbeat = 0L
    private fun publishStatus() {
        val id = engine.identity
        ChatRepository.updateMesh {
            it.copy(bluetoothOn = ble.isEnabled, running = bleStarted, links = engine.linkCount, myNickname = id.nickname, myPeerHex = id.peerIdHex, fingerprint = id.fingerprint, carriedCount = engine.carriedCount)
        }
        val now = System.currentTimeMillis()
        if (now - lastHeartbeat > 5 * 60_000) { lastHeartbeat = now; geo.heartbeatAll() }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            handler.post {
                if (state == BluetoothAdapter.STATE_ON) startBleIfPossible()
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) { ble.stop(); bleStarted = false }
                publishStatus()
            }
        }
    }

    // ------------------------------------------------------------------ engine events

    private val listener = object : MeshListener {
        override fun onPeersChanged(peers: List<MeshPeer>) {
            peers.forEach { noiseKeys[it.peerIdHex] = it.noisePublicKey }
            ChatRepository.setPeers(peers)
            ChatRepository.updateMesh { it.copy(links = engine.linkCount) }
        }

        override fun onPublicMessage(senderHex: String, nickname: String, text: String, timestamp: Long) {
            val me = engine.identity
            val ambiguous = ChatRepository.peers.value.any { it.peerIdHex != me.peerIdHex && it.nickname.equals(me.nickname, ignoreCase = true) }
            val mentioned = Mentions.mentionsMe(text, me.nickname, me.peerIdHex.take(4), ambiguous)
            ChatRepository.add(ChatMessage("$senderHex-$timestamp", Conversation.Mesh.key, nickname, senderHex, text, timestamp, mine = false, mentionsMe = mentioned))
            if (mentioned && ChatRepository.openConversation != Conversation.Mesh.key) notifyMention(PeerLabel.mesh(nickname, senderHex).toString(), text)
        }

        override fun onPrivateMessage(senderHex: String, nickname: String, messageId: String, text: String, timestamp: Long) {
            val key = engine.noiseKeyOf(senderHex)?.also { noiseKeys[senderHex] = it }
            val notice = NostrEmbedded.parseFavoriteNotice(text)
            if (notice != null) { key?.let { favoriteNoticeFrom(it, nickname, notice.first, notice.second) }; return }
            deliverPrivate(senderHex, nickname, messageId, text, timestamp)
        }

        override fun onDelivered(peerHex: String, messageId: String) =
            ChatRepository.setState(Conversation.Private(peerHex).key, messageId, DeliveryState.DELIVERED)

        override fun onRead(peerHex: String, messageId: String) =
            ChatRepository.setState(Conversation.Private(peerHex).key, messageId, DeliveryState.READ)

        override fun onQueued(peerHex: String, messageId: String) =
            ChatRepository.setState(Conversation.Private(peerHex).key, messageId, DeliveryState.QUEUED)

        override fun onCarried(peerHex: String, messageId: String) =
            ChatRepository.setState(Conversation.Private(peerHex).key, messageId, DeliveryState.CARRIED)

        override fun onFile(senderHex: String, nickname: String, file: FilePacket, isPrivate: Boolean, timestamp: Long) {
            val kind = file.kind ?: return
            val stored = Media.store(this@MeshService, file.content, FilePacket.extension(file.mimeType))
            val conv = if (isPrivate) Conversation.Private(senderHex).key else Conversation.Mesh.key
            ChatRepository.rememberName(senderHex, nickname)
            ChatRepository.add(ChatMessage("file-${stored.nameWithoutExtension}", conv, nickname, senderHex, "", minOf(timestamp, System.currentTimeMillis()), mine = false,
                attachment = stored.absolutePath, audio = kind == FilePacket.Kind.AUDIO))
            if (isPrivate && ChatRepository.openConversation != conv) {
                notifyPrivate(PeerLabel.mesh(nickname, senderHex).toString(), getString(if (kind == FilePacket.Kind.AUDIO) R.string.media_voice else R.string.media_photo))
            }
        }

        override fun onVerified(peerHex: String) {
            engine.noiseKeyOf(peerHex)?.let { Social.setVerified(sha256(it).toHex(), true) }
        }

        // Coalesce bursts of changes into one write.
        override fun onStateChanged() { handler.removeCallbacks(saveState); handler.postDelayed(saveState, 500) }
    }

    private fun deliverPrivate(senderHex: String, nickname: String, messageId: String, text: String, timestamp: Long) {
        val conv = Conversation.Private(senderHex).key
        ChatRepository.rememberName(senderHex, nickname)
        ChatRepository.add(ChatMessage(messageId, conv, nickname, senderHex, text, timestamp, mine = false))
        if (ChatRepository.openConversation == conv) sendRead(senderHex, messageId)
        else notifyPrivate(PeerLabel.mesh(nickname, senderHex).toString(), text)
    }

    // ------------------------------------------------------------------ favorites over Nostr (§15)

    private fun mutualNpubHex(peerHex: String): String? =
        Social.state.value.favoriteOf(peerHex)?.takeIf { it.mine && it.theyFavorited }?.theirNpub?.let { NostrDirect.npubToHex(it) }

    private fun sendRead(peerHex: String, messageId: String) {
        val viaNostr = if (engine.isReachable(peerHex)) null else mutualNpubHex(peerHex)
        if (viaNostr == null) { engine.sendReadReceipt(peerHex, messageId); return }
        NostrEmbedded.receipt(NoisePayloadType.READ_RECEIPT, messageId, engine.identity.peerId, peerHex.hexToBytes()!!, System.currentTimeMillis())
            ?.let { nostr.send(viaNostr, it, paced = true) }
    }

    /** They favorited or unfavorited us; remember their npub so we can reach them over Nostr. */
    private fun favoriteNoticeFrom(noiseKey: ByteArray, nickname: String, on: Boolean, npub: String) {
        if (NostrDirect.npubToHex(npub) == null) return
        val fp = sha256(noiseKey).toHex()
        val peerHex = Identity.peerIdFor(noiseKey).toHex()
        val old = Social.state.value.favorites[fp]
        val f = (old ?: Favorite(noiseKey.toHex(), nickname, mine = false)).copy(nickname = nickname, theirNpub = npub, theyFavorited = on)
        Social.putFavorite(fp, if (!f.mine && !f.theyFavorited) null else f)
        ChatRepository.rememberName(peerHex, nickname)
        ChatRepository.addLocal(Conversation.Private(peerHex).key, getString(if (on) R.string.fav_they_on else R.string.fav_they_off, PeerLabel.mesh(nickname, peerHex).toString()))
    }

    private fun onNostrDirect(senderPubkey: String, content: String, createdAt: Long) {
        val d = NostrEmbedded.decode(content) ?: return
        val social = Social.state.value
        val fav = social.favorites.values.firstOrNull { it.theirNpub?.let { n -> NostrDirect.npubToHex(n) } == senderPubkey }
        val noiseKey = fav?.noiseKeyHex?.hexToBytes()
            ?: noiseKeys[d.senderId.toHex()]?.takeIf { Identity.peerIdFor(it).contentEquals(d.senderId) } ?: return
        if (sha256(noiseKey).toHex() in social.blocked) return
        val peerHex = Identity.peerIdFor(noiseKey).toHex()
        val nickname = fav?.nickname ?: ChatRepository.names.value[peerHex] ?: peerHex.take(8)
        when (d.payload.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = PrivateMessage.decode(d.payload.body) ?: return
                val notice = NostrEmbedded.parseFavoriteNotice(pm.content)
                if (notice != null) {
                    // The notice must come from the npub it announces.
                    if (NostrDirect.npubToHex(notice.second) == senderPubkey) favoriteNoticeFrom(noiseKey, nickname, notice.first, notice.second)
                    return
                }
                if (fav == null) return // only favorites may write over Nostr
                NostrEmbedded.receipt(NoisePayloadType.DELIVERED, pm.messageId, engine.identity.peerId, d.senderId, System.currentTimeMillis())
                    ?.let { nostr.send(senderPubkey, it, paced = true) }
                if (!nostrSeen.add(pm.messageId)) return
                while (nostrSeen.size > 1000) nostrSeen.remove(nostrSeen.first())
                nostrSeenStore.write(nostrSeen.joinToString("\n").toByteArray(Charsets.UTF_8))
                deliverPrivate(peerHex, nickname, pm.messageId, pm.content, minOf(createdAt * 1000, System.currentTimeMillis()))
            }
            NoisePayloadType.DELIVERED -> ChatRepository.setState(Conversation.Private(peerHex).key, String(d.payload.body, Charsets.UTF_8), DeliveryState.DELIVERED)
            NoisePayloadType.READ_RECEIPT -> ChatRepository.setState(Conversation.Private(peerHex).key, String(d.payload.body, Charsets.UTF_8), DeliveryState.READ)
        }
    }

    // ------------------------------------------------------------------ ChatController

    override fun sendPublic(text: String) {
        val id = engine.identity
        val now = System.currentTimeMillis()
        ChatRepository.add(ChatMessage("${id.peerIdHex}-$now", Conversation.Mesh.key, id.nickname, id.peerIdHex, text, now, mine = true))
        handler.post { engine.sendPublic(text) }
    }

    override fun sendPrivate(peerHex: String, text: String) {
        handler.post {
            val id = engine.identity
            val npubHex = if (engine.isReachable(peerHex) || !nostr.connected) null else mutualNpubHex(peerHex)
            if (npubHex != null) {
                // Apart, but mutual favorites: over the internet (§15).
                val now = System.currentTimeMillis()
                org.openchat.android.core.splitUtf8(text, org.openchat.android.core.PrivateMessage.MAX_CONTENT_BYTES).forEachIndexed { i, part ->
                    val mid = java.util.UUID.randomUUID().toString().uppercase()
                    NostrEmbedded.privateMessage(mid, part, id.peerId, peerHex.hexToBytes()!!, now)?.let { nostr.send(npubHex, it) }
                    ChatRepository.add(ChatMessage(mid, Conversation.Private(peerHex).key, id.nickname, id.peerIdHex, part, now + i, mine = true, state = DeliveryState.SENT))
                }
                return@post
            }
            val ids = engine.sendPrivate(peerHex, text)
            val parts = org.openchat.android.core.splitUtf8(text, org.openchat.android.core.PrivateMessage.MAX_CONTENT_BYTES)
            val now = System.currentTimeMillis()
            ids.zip(parts).forEachIndexed { i, (mid, part) ->
                val state = when (engine.outboxState(mid)) { 2 -> DeliveryState.CARRIED; 1 -> DeliveryState.QUEUED; else -> DeliveryState.SENT }
                ChatRepository.add(ChatMessage(mid, Conversation.Private(peerHex).key, id.nickname, id.peerIdHex, part, now + i, mine = true, state = state))
            }
        }
    }

    override fun markRead(peerHex: String, messageIds: List<String>) {
        handler.post { messageIds.forEach { sendRead(peerHex, it) } }
    }

    override fun setNickname(name: String) {
        val clean = name.trim().take(32).ifEmpty { return }
        handler.post {
            engine.updateNickname(clean)
            store.save(engine.identity)
            publishStatus()
        }
    }

    override fun noiseKey(peerHex: String): ByteArray? = noiseKeys[peerHex]

    override fun myVerifyUrl(): String {
        val id = engine.identity
        return VerifyQr.build(id.noise.publicKey, id.signing, id.nickname, null, System.currentTimeMillis() / 1000).toUrl()
    }

    override fun setFavorite(peerHex: String, nickname: String, on: Boolean) {
        val key = noiseKeys[peerHex] ?: return
        val fp = sha256(key).toHex()
        val old = Social.state.value.favorites[fp]
        Social.putFavorite(fp, if (on) (old ?: Favorite(key.toHex(), nickname)).copy(mine = true) else old?.takeIf { it.theyFavorited }?.copy(mine = false))
        // Tell them, with our npub, so they can reach us over Nostr once it is mutual.
        handler.post {
            val notice = NostrEmbedded.favoriteNotice(on, nostr.npub)
            val theirs = old?.theirNpub?.let { NostrDirect.npubToHex(it) }
            if (engine.isReachable(peerHex) || theirs == null) engine.sendPrivate(peerHex, notice)
            else NostrEmbedded.privateMessage(java.util.UUID.randomUUID().toString().uppercase(), notice, engine.identity.peerId, peerHex.hexToBytes()!!, System.currentTimeMillis())
                ?.let { nostr.send(theirs, it) }
        }
    }

    override fun myNpub(): String = nostr.npub

    override fun verifyScanned(url: String): String? {
        val qr = VerifyQr.verify(url.trim(), System.currentTimeMillis() / 1000) ?: return null
        val key = qr.noiseKeyHex.hexToBytes() ?: return null
        if (key.contentEquals(engine.identity.noise.publicKey)) return null
        Social.setVerified(sha256(key).toHex(), true)
        val peerHex = org.openchat.android.mesh.Identity.peerIdFor(key).toHex()
        noiseKeys.putIfAbsent(peerHex, key)
        ChatRepository.rememberName(peerHex, qr.nickname)
        // If they are around, make them prove they hold the key the QR names.
        handler.post { engine.sendVerifyChallenge(peerHex) }
        return qr.nickname
    }

    override fun joinGeo(geohash: String, teleported: Boolean) = geo.join(geohash.lowercase(), teleported)
    override fun leaveGeo(geohash: String) = geo.leave(geohash)
    override fun sendGeo(geohash: String, text: String) = geo.send(geohash, text)

    override fun dropNote(text: String): Boolean {
        val cell = ChatRepository.geo.value.locationGeohash?.take(NOTE_PRECISION) ?: return false
        geo.dropNote(cell, text)
        return true
    }

    override fun sendMedia(conversation: String, bytes: ByteArray, mime: String): Int {
        if (bytes.size > Media.MAX_SEND_BYTES) return R.string.media_too_big
        if (conversation.startsWith("geo:")) return R.string.media_geo_unsupported
        val file = FilePacket((if (mime.startsWith("audio")) "voice_" else "img_") + java.util.UUID.randomUUID() + "." + FilePacket.extension(mime), mime, bytes)
        val kind = file.kind ?: return R.string.media_too_big
        val peerHex = conversation.removePrefix("dm:").takeIf { conversation.startsWith("dm:") }
        if (peerHex != null && !java.util.concurrent.FutureTask { engine.canSendPrivateFile(peerHex) }.also { handler.post(it) }.get()) {
            return R.string.media_private_unsupported
        }
        val stored = Media.store(this, bytes, FilePacket.extension(mime))
        val id = engine.identity
        val now = System.currentTimeMillis()
        ChatRepository.add(ChatMessage("file-${stored.nameWithoutExtension}", conversation, id.nickname, id.peerIdHex, "", now, mine = true,
            attachment = stored.absolutePath, audio = kind == FilePacket.Kind.AUDIO))
        handler.post { if (peerHex != null) engine.sendPrivateFile(peerHex, file) else engine.sendPublicFile(file) }
        return 0
    }

    override fun setTor(on: Boolean) = tor.setWanted(on)

    override fun watchNotes(cell: String?) = geo.watchNotes(cell?.take(NOTE_PRECISION))

    @SuppressLint("MissingPermission")
    override fun refreshLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fun use(loc: Location?) {
            loc ?: return
            ChatRepository.updateGeo { it.copy(locationGeohash = Geohash.encode(loc.latitude, loc.longitude, 8)) }
        }
        try {
            val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
                .maxByOrNull { it.time }
            use(last)
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { lm.isProviderEnabled(it) } ?: return
            if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, mainExecutor) { use(it) }
            else @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, { use(it) }, mainLooper)
        } catch (e: SecurityException) { }
    }

    /**
     * Triple-tap panic wipe (docs/protocol-spec.md §10): new keys and peer ID,
     * no timelines, no channels, no caches. No confirmation by design.
     */
    override fun panic() {
        ChatRepository.wipe()
        Social.wipe()
        noiseKeys.clear()
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        startInForeground()
        handler.post {
            handler.removeCallbacks(saveState)
            stateStore.destroy()
            socialStore.delete()
            store.destroy()
            val fresh = Identity.generate()
            store.save(fresh)
            engine.resetIdentity(fresh)
            geo.reset(fresh.nostrSeed)
            nostr.reset(fresh.nostrSeed)
            nostrSeen.clear(); nostrSeenStore.delete()
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            publishStatus()
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun startInForeground() {
        val n = builder(CHANNEL_STATUS)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_running))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION_ID, n)
    }

    private var notifySeq = 100
    private fun notifyPrivate(from: String, text: String) {
        val n = builder(CHANNEL_MESSAGES)
            .setContentTitle(from)
            .setContentText(text.take(120))
            .setAutoCancel(true)
            .build()
        try { getSystemService(NotificationManager::class.java).notify(notifySeq++, n) } catch (e: SecurityException) { }
    }

    private fun notifyMention(from: String, text: String) = notifyPrivate(getString(R.string.mention_notify, from), text)

    private fun builder(channel: String): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat_mesh).setContentIntent(openAppIntent())
    }
}
