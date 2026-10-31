package org.openchat.android.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.openchat.android.mesh.Identity
import org.openchat.android.mesh.MeshState
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A file in app-private storage sealed with an AES-GCM key that lives in the
 * Android Keystore and never leaves it. One key serves every sealed file.
 */
class SealedFile(context: Context, name: String) {
    private val file = File(context.noBackupFilesDir, name)
    private val tmp = File(context.noBackupFilesDir, "$name.tmp")

    /** The file is there but cannot be opened: corrupt, or the Keystore key is gone or not answering. */
    class Unreadable(cause: Throwable) : Exception(cause)

    val exists get() = file.exists()

    /**
     * Seals and writes atomically: the new content goes to a sibling file and is renamed over the
     * old one, so a crash or power loss mid-write leaves the previous version, never half a file.
     * Returns false instead of throwing when the Keystore or the disk refuses; the caller keeps its
     * in-memory state and tries again on the next change.
     */
    fun write(plain: ByteArray): Boolean = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain)
        val iv = cipher.iv
        tmp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + sealed)
        if (!tmp.renameTo(file)) { tmp.delete(); false } else true
    } catch (e: Exception) {
        tmp.delete()
        false
    }

    /** Null when there is no file. Throws [Unreadable] when there is one we cannot open. */
    fun read(): ByteArray? {
        if (!file.exists()) return null
        try {
            val data = file.readBytes()
            if (data.isEmpty()) throw IllegalStateException("empty sealed file")
            val ivLen = data[0].toInt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, ivLen))
            return cipher.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
        } catch (e: Exception) {
            throw Unreadable(e)
        }
    }

    /** Like [read], but an unreadable file counts as absent: for caches that can be rebuilt. */
    fun readOrNull(): ByteArray? = try { read() } catch (e: Unreadable) { null }

    /** Overwrites and deletes the file. */
    fun delete() {
        tmp.delete()
        if (file.exists()) {
            try { file.writeBytes(ByteArray(file.length().toInt())) } catch (e: Exception) { }
            file.delete()
        }
    }

    companion object {
        private const val ALIAS = "openchat-identity"

        /** Panic wipe: destroys the Keystore key, so every sealed file becomes unreadable. */
        fun destroyKey() {
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
            } catch (e: Exception) { }
        }

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }
    }
}

/** Keeps the identity sealed in app-private storage. */
class IdentityStore(context: Context) {
    private val file = SealedFile(context, "identity.bin")

    /** The saved identity exists but cannot be opened. Never silently replaced: that would lose the user's keys. */
    class Locked(cause: Throwable) : Exception(cause)

    /**
     * Returns the saved identity, or a new one when none was ever saved. A file that is present but
     * will not open (Keystore hiccup after a system update, corrupt bytes) raises [Locked] so the
     * caller can report it and try again later, instead of overwriting the keys with fresh ones.
     */
    fun loadOrCreate(): Identity {
        val bytes = try { file.read() } catch (e: SealedFile.Unreadable) { throw Locked(e) }
        if (bytes != null) {
            // The decoded identity holds its own copies; the plaintext buffer is zeroed right away.
            val identity = try { Identity.deserialize(bytes) } finally { bytes.fill(0) }
            return identity ?: throw Locked(IllegalStateException("undecodable identity"))
        }
        return Identity.generate().also { save(it) }
    }

    fun save(identity: Identity): Boolean {
        val bytes = identity.serialize()
        return try { file.write(bytes) } finally { bytes.fill(0) }
    }

    /** Panic wipe: destroys the sealed file and the Keystore key that could open it. */
    fun destroy() {
        file.delete()
        SealedFile.destroyKey()
    }
}

/** Outbox, carried mail and remembered keys (docs/protocol-spec.md §13.7). */
class MeshStateStore(context: Context) {
    private val file = SealedFile(context, "mesh-state.bin")

    fun load(): MeshState? = file.readOrNull()?.let { MeshState.deserialize(it) }
    fun save(state: MeshState) = file.write(state.serialize())
    fun destroy() = file.delete()
}
