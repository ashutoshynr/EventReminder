package com.eventreminder.app.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption with a key that lives in the Android Keystore (hardware-backed where the phone
 * supports it). The key never leaves the secure hardware/system service, so the stored text is useless
 * if the app's files are copied off the phone.
 */
object SecureStore {
    private const val ALIAS = "event_reminder_mail_key"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Returns Base64(iv + ciphertext), or "" for empty input. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Returns "" if the text is empty or cannot be decrypted (e.g. phone was reset and the key is gone). */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val all = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
