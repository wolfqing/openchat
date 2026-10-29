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

    private val status = object : BroadcastReceiver() {
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
        val port = TorService.socksPort
        NetRoute.set(true, if (port > 0) port else 0)
        onState(if (port > 0) TorState.ON else TorState.STARTING)
    }

    private fun start() {
        NetRoute.set(true, NetRoute.socksPort)
        if (!bound) {
            LocalBroadcastManager.getInstance(context).registerReceiver(status, IntentFilter(TorService.ACTION_STATUS))
            bound = context.bindService(Intent(context, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        poll()
    }

    fun stop() {
        if (bound) {
            try { context.unbindService(connection) } catch (e: Exception) { }
            LocalBroadcastManager.getInstance(context).unregisterReceiver(status)
            bound = false
        }
        NetRoute.set(wanted, 0)
        onState(if (wanted) TorState.STARTING else TorState.OFF)
    }

    private fun prefs() = context.getSharedPreferences("net", Context.MODE_PRIVATE)

    private companion object { const val KEY = "tor" }
}
