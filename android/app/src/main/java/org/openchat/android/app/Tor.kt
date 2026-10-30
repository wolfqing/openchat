package org.openchat.android.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.openchat.android.nostr.NetRoute
import org.torproject.jni.TorService
import org.torproject.jni.TorStatus

enum class TorState { OFF, STARTING, ON }

/**
 * Runs Tor in-process (Guardian Project's tor-android) while the user wants
 * relay traffic routed through it, which is the default (§19).
 */
class Tor(private val context: Context, private val onState: (TorState) -> Unit) {
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = poll()
        override fun onServiceDisconnected(name: ComponentName?) { NetRoute.set(true, 0); onState(TorState.STARTING) }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = poll()
    }

    val wanted get() = prefs().getBoolean(KEY, true)

    fun apply() {
        if (wanted) start() else stop()
    }

    fun setWanted(on: Boolean) {
        prefs().edit().putBoolean(KEY, on).apply()
        apply()
    }

    /** Called on status broadcasts and periodically: Tor is ready once its SOCKS port is known. */
    fun poll() {
        if (!wanted) return
        // The static port outlives a stop; only a status of ON says it is live right now.
        val port = TorService.socksPort.takeIf { it > 0 && TorStatus.isOn } ?: 0
        NetRoute.set(true, port)
        onState(if (port > 0) TorState.ON else TorState.STARTING)
    }

    /**
     * Panic wipe: stop Tor, delete its directory (guards, state, cached descriptors) and start it
     * again after [delayMs]. Relay sockets stay closed in between (fail-closed), so the new identity's
     * first contact with a relay is over circuits that share nothing with the old one.
     */
    fun wipeAndRestart(main: android.os.Handler, delayMs: Long) {
        main.post {
            val wasWanted = wanted
            stop()
            main.postDelayed({
                try { context.getDir("TorService", Context.MODE_PRIVATE).deleteRecursively() } catch (e: Exception) { }
                if (wasWanted && wanted) start()
            }, delayMs)
        }
    }

    private fun start() {
        NetRoute.set(true, NetRoute.socksPort)
        if (!bound) {
            LocalBroadcastManager.getInstance(context).registerReceiver(statusReceiver, IntentFilter(TorService.ACTION_STATUS))
            bound = context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        poll()
    }

    fun stop() {
        if (bound) {
            try { context.unbindService(connection) } catch (e: Exception) { }
            LocalBroadcastManager.getInstance(context).unregisterReceiver(statusReceiver)
            bound = false
        }
        NetRoute.set(wanted, 0)
        onState(if (wanted) TorState.STARTING else TorState.OFF)
    }

    private fun prefs() = context.getSharedPreferences("net", Context.MODE_PRIVATE)

    private companion object { const val KEY = "tor" }
}
