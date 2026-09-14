package dev.otto.phone.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a key that never leaves the Android Keystore. Vendor keys and
 *  the serve token are stored as `base64(iv || ciphertext)`; nothing is ever
 *  written in clear, and Python receives the values only in its environment. */
object Crypt {
    private const val ALIAS = "otto.keys"
    private const val STORE = "AndroidKeyStore"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + bytes, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }
}
