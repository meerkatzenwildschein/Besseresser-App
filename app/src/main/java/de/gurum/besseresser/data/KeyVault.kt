package de.gurum.besseresser.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Verschlüsselt den API-Key mit einem AES-GCM-Schlüssel aus dem Android-Keystore.
 * Der Schlüssel selbst verlässt das Gerät nie; in den Preferences liegt nur
 * das Chiffrat (IV + Ciphertext, Base64).
 */
object KeyVault {
    private const val KEY_ALIAS = "besseresser_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    @Synchronized
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

    fun encrypt(plain: String): Result<String> {
        if (plain.isBlank()) return Result.success("")
        return runCatching {
            encryptWithKey(plain, getOrCreateKey())
        }
    }

    /** Gibt Result mit Klartext zurück, oder Failure wenn das Chiffrat nicht mehr entschlüsselt werden kann
     *  (z. B. nach Backup-Wiederherstellung auf einem anderen Gerät oder bei ungültigen Chiffraten). */
    fun decrypt(encoded: String): Result<String> {
        if (encoded.isBlank()) return Result.success("")
        return runCatching {
            decryptWithKey(encoded, getOrCreateKey())
                ?: throw IllegalStateException("Entschlüsselung fehlgeschlagen (ungültiges Chiffrat).")
        }
    }

    internal fun encryptWithKey(plain: String, secretKey: SecretKey): String {
        if (plain.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val buf = ByteBuffer.allocate(2 + iv.size + ciphertext.size)
        buf.putShort(iv.size.toShort())
        buf.put(iv)
        buf.put(ciphertext)
        return Base64.getEncoder().encodeToString(buf.array())
    }

    internal fun decryptWithKey(encoded: String, secretKey: SecretKey): String? {
        if (encoded.isBlank()) return ""
        return try {
            val data = Base64.getDecoder().decode(encoded.trim())
            if (data.size < 2) return null
            val buf = ByteBuffer.wrap(data)
            val ivSize = buf.short.toInt()
            if (ivSize <= 0 || ivSize > buf.remaining()) return null
            val iv = ByteArray(ivSize).also { buf.get(it) }
            val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
