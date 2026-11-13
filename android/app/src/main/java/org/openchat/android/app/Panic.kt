package org.openchat.android.app

import android.app.NotificationManager
import android.content.Context
import java.io.File

/**
 * The part of the panic wipe (docs/protocol-spec.md §10) that needs no running mesh service.
 * The service can be gone exactly when a wipe matters: the Bluetooth permission was revoked, the
 * saved identity will not open, or the system stopped it. What is on disk goes regardless.
 */
object Panic {
    /**
     * Deletes everything under [dir]; with [overwrite], file contents are zeroed first.
     * [dir] itself stays. True when nothing is left.
     */
    fun eraseDirectory(dir: File, overwrite: Boolean): Boolean {
        var clean = true
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) eraseDirectory(f, overwrite)
            else if (overwrite) try { f.writeBytes(ByteArray(f.length().coerceAtMost(MAX_OVERWRITE).toInt())) } catch (e: Exception) { }
            if (!f.delete() && f.exists()) clean = false
        }
        return clean
    }

    /**
     * Erases every sealed file, the Keystore key that opens them, the caches and the settings a
     * wipe resets. Does not touch memory or Tor, so the service uses it too. True when nothing
     * was left behind.
     */
    fun wipeStorage(context: Context): Boolean {
        // The whole directory, not a list of names: a sealed file added later is covered without anyone remembering to.
        var clean = eraseDirectory(context.noBackupFilesDir, overwrite = true)
        clean = SealedFile.destroyKey() && clean
        clean = eraseDirectory(context.cacheDir, overwrite = false) && clean
        NotificationPrivacy.reset(context)
        LiveVoicePrefs.wipe(context)
        Bridge.forget(context)
        AppUpdater.reset(context)
        return clean
    }

    /**
     * The whole wipe when there is no service to carry it out: memory, notifications, storage,
     * and Tor's directory, which nothing is using. The next service start makes a new identity.
     * Runs on the caller's thread so the caller can report what actually happened.
     */
    fun wipeWithoutService(context: Context): Boolean {
        ChatRepository.wipe()
        Social.wipe()
        try { context.getSystemService(NotificationManager::class.java).cancelAll() } catch (e: Exception) { }
        var clean = wipeStorage(context)
        clean = eraseDirectory(context.getDir(TOR_DIR, Context.MODE_PRIVATE), overwrite = false) && clean
        ChatRepository.updateMesh { MeshStatus() }
        return clean
    }

    /** Tor's data directory, as tor-android names it. */
    const val TOR_DIR = "TorService"
    private const val MAX_OVERWRITE = 8L * 1024 * 1024
}
