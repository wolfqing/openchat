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

    fun write(plain: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain)
        val iv = cipher.iv
        file.writeBytes(byteArrayOf(iv.size.toByte()) + iv + sealed)
    }

    fun read(): ByteArray? = try {
        if (!file.exists()) null else {
            val data = file.readBytes()
            val ivLen = data[0].toInt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, ivLen))
            cipher.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
        }
    } catch (e: Exception) {
        null
    }

    /** Overwrites and deletes the file. */
    fun delete() {
        if (file.exists()) {
            file.writeBytes(ByteArray(file.length().toInt()))
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

    fun loadOrCreate(): Identity {
        file.read()?.let { Identity.deserialize(it) }?.let { return it }
        return Identity.generate().also { save(it) }
    }

    fun save(identity: Identity) = file.write(identity.serialize())

    /** Panic wipe: destroys the sealed file and the Keystore key that could open it. */
    fun destroy() {
        file.delete()
        SealedFile.destroyKey()
    }
}

/** Outbox, carried mail and remembered keys (docs/protocol-spec.md §13.7). */
class MeshStateStore(context: Context) {
    private val file = SealedFile(context, "mesh-state.bin")

    fun load(): MeshState? = file.read()?.let { MeshState.deserialize(it) }
    fun save(state: MeshState) = file.write(state.serialize())
    fun destroy() = file.delete()
}
