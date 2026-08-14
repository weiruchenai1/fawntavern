package me.rerere.fawntavern.data.security

import androidx.core.content.edit
import me.rerere.fawntavern.data.commitChanges

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts sensitive preference values with a non-exportable Android Keystore key. */
object SecurePreferences {
    private const val KEY_ALIAS = "fawntavern_sensitive_preferences_v1"
    private const val PREFIX = "enc:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun getString(
        context: Context,
        prefs: SharedPreferences,
        key: String,
        defaultValue: String? = null,
    ): String? {
        val stored = prefs.getString(key, null) ?: return defaultValue
        if (!stored.startsWith(PREFIX)) {
            putString(context, prefs, key, stored)
            return stored
        }
        return decrypt(context, stored) ?: defaultValue
    }

    fun putString(context: Context, prefs: SharedPreferences, key: String, value: String?) {
        prefs.edit {
            if (value == null) remove(key)
            else putString(key, encrypt(context, value))
        }
    }

    fun putStringSync(context: Context, prefs: SharedPreferences, key: String, value: String?) {
        check(prefs.commitChanges {
            if (value == null) remove(key)
            else putString(key, encrypt(context, value))
        }) { "Unable to persist secure preferences" }
    }

    private fun encrypt(context: Context, value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(context))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$PREFIX$iv:$ciphertext"
    }

    private fun decrypt(context: Context, value: String): String? = runCatching {
        val parts = value.removePrefix(PREFIX).split(':', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(context),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(@Suppress("UNUSED_PARAMETER") context: Context): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
