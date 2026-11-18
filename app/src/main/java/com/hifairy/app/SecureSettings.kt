package com.hifairy.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSettings(context: Context) {
    private val prefs = context.getSharedPreferences("hifairy_settings", Context.MODE_PRIVATE)
    private val alias = "hifairy_deepseek_key"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun saveApiKey(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        prefs.edit().putString("api_key", Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
    }

    fun apiKey(): String {
        val stored = prefs.getString("api_key", null) ?: return ""
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
        } catch (_: Exception) { "" }
    }

    var model: String
        get() = prefs.getString("model", "deepseek-flash") ?: "deepseek-flash"
        set(value) { prefs.edit().putString("model", value).apply() }

    var effort: String
        get() = prefs.getString("effort", "high") ?: "high"
        set(value) { prefs.edit().putString("effort", value).apply() }

    var displayName: String
        get() = prefs.getString("display_name", "绳匠") ?: "绳匠"
        set(value) { prefs.edit().putString("display_name", value).apply() }

    var avatar: String
        get() = prefs.getString("avatar", "ling") ?: "ling"
        set(value) { prefs.edit().putString("avatar", value).apply() }
}
