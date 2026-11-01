package de.gurum.besseresser

import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Verschluesselt den API-Key mit einem AES-GCM-Schluessel aus dem Android-Keystore.
 * Der Schluessel selbst verlaesst das Geraet nie; in den Preferences liegt nur
 * das Chiffrat (IV + Ciphertext, Base64).
 */
object KeyVault {
    private const val KEY_ALIAS = "besseresser_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val buf = ByteBuffer.allocate(2 + iv.size + ciphertext.size)
        buf.putShort(iv.size.toShort())
        buf.put(iv)
        buf.put(ciphertext)
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    /** Gibt null zurueck, wenn das Chiffrat nicht mehr entschluesselt werden kann
     *  (z. B. nach Backup-Wiederherstellung auf einem anderen Geraet). */
    fun decrypt(encoded: String): String? {
        return try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            val buf = ByteBuffer.wrap(data)
            val ivSize = buf.short.toInt()
            val iv = ByteArray(ivSize).also { buf.get(it) }
            val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
