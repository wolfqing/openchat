package org.openchat.android.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.openchat.android.mesh.Identity
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the identity in app-private storage, sealed with an AES-GCM key that
 * lives in the Android Keystore and never leaves it.
 */
class IdentityStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "identity.bin")
    private val alias = "openchat-identity"

    fun loadOrCreate(): Identity {
        load()?.let { return it }
        return Identity.generate().also { save(it) }
    }

    fun save(identity: Identity) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(identity.serialize())
        val iv = cipher.iv
        file.writeBytes(byteArrayOf(iv.size.toByte()) + iv + sealed)
    }

    private fun load(): Identity? = try {
        if (!file.exists()) null else {
            val data = file.readBytes()
            val ivLen = data[0].toInt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, ivLen))
            Identity.deserialize(cipher.doFinal(data, 1 + ivLen, data.size - 1 - ivLen))
        }
    } catch (e: Exception) {
        null
    }

    /** Panic wipe: destroys the sealed file and the Keystore key that could open it. */
    fun destroy() {
        if (file.exists()) {
            file.writeBytes(ByteArray(file.length().toInt()))
            file.delete()
        }
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        } catch (e: Exception) { }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
