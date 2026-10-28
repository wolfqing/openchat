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

        fun start(context: Context) {
            val i = Intent(context, MeshService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var store: IdentityStore
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

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startInForeground()
        thread = HandlerThread("mesh").apply { start() }
        handler = Handler(thread.looper)
        store = IdentityStore(this)
        val identity = store.loadOrCreate()
        engine = MeshEngine(identity, scheduler, { link, bytes -> ble.write(link, bytes) }, listener)
        ble = BleTransport(this, handler) { engine }
        geo = GeoChannels(RelayDirectory.parse(assets.open("relays.csv").bufferedReader().readText()), identity.nostrSeed) { engine.identity.nickname }
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
        handler.postDelayed({ periodic() }, 5_000)
    }

    private var lastHeartbeat = 0L
    private fun publishStatus() {
        val id = engine.identity
        ChatRepository.updateMesh {
            it.copy(bluetoothOn = ble.isEnabled, running = bleStarted, links = engine.linkCount, myNickname = id.nickname, myPeerHex = id.peerIdHex, fingerprint = id.fingerprint)
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
            ChatRepository.setPeers(peers)
            ChatRepository.updateMesh { it.copy(links = engine.linkCount) }
        }

        override fun onPublicMessage(senderHex: String, nickname: String, text: String, timestamp: Long) {
            ChatRepository.add(ChatMessage("$senderHex-$timestamp", Conversation.Mesh.key, nickname, senderHex, text, timestamp, mine = false))
        }

        override fun onPrivateMessage(senderHex: String, nickname: String, messageId: String, text: String, timestamp: Long) {
            val conv = Conversation.Private(senderHex).key
            ChatRepository.rememberName(senderHex, nickname)
            ChatRepository.add(ChatMessage(messageId, conv, nickname, senderHex, text, timestamp, mine = false))
            if (ChatRepository.openConversation == conv) engine.sendReadReceipt(senderHex, messageId)
            else notifyPrivate(nickname, text)
        }

        override fun onDelivered(peerHex: String, messageId: String) =
            ChatRepository.setState(Conversation.Private(peerHex).key, messageId, DeliveryState.DELIVERED)

        override fun onRead(peerHex: String, messageId: String) =
            ChatRepository.setState(Conversation.Private(peerHex).key, messageId, DeliveryState.READ)
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
            val ids = engine.sendPrivate(peerHex, text)
            val parts = org.openchat.android.core.splitUtf8(text, org.openchat.android.core.PrivateMessage.MAX_CONTENT_BYTES)
            val now = System.currentTimeMillis()
            ids.zip(parts).forEachIndexed { i, (mid, part) ->
                ChatRepository.add(ChatMessage(mid, Conversation.Private(peerHex).key, id.nickname, id.peerIdHex, part, now + i, mine = true, state = DeliveryState.SENT))
            }
        }
    }

    override fun markRead(peerHex: String, messageIds: List<String>) {
        handler.post { messageIds.forEach { engine.sendReadReceipt(peerHex, it) } }
    }

    override fun setNickname(name: String) {
        val clean = name.trim().take(32).ifEmpty { return }
        handler.post {
            engine.updateNickname(clean)
            store.save(engine.identity)
            publishStatus()
        }
    }

    override fun joinGeo(geohash: String, teleported: Boolean) = geo.join(geohash.lowercase(), teleported)
    override fun leaveGeo(geohash: String) = geo.leave(geohash)
    override fun sendGeo(geohash: String, text: String) = geo.send(geohash, text)

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
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancelAll()
        startInForeground()
        handler.post {
            store.destroy()
            val fresh = Identity.generate()
            store.save(fresh)
            engine.resetIdentity(fresh)
            geo.reset(fresh.nostrSeed)
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

    private fun builder(channel: String): Notification.Builder {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat_mesh).setContentIntent(openAppIntent())
    }
}
