package de.hoefer.nookbot.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Einstellungen. Die beiden Schlüssel (Chat-Token für den Adapter, App-Schlüssel von home-mcp) liegen
 * AES-GCM-verschlüsselt mit einem Schlüssel aus dem Android Keystore – nie im Klartext auf dem Gerät.
 */
class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("nookbot", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("serverUrl", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        set(v) = prefs.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()

    var mcpUrl: String
        get() = prefs.getString("mcpUrl", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MCP
        set(v) = prefs.edit().putString("mcpUrl", v.trim().trimEnd('/')).apply()

    /** "system", "hell" oder "dunkel" */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(v) = prefs.edit().putString("theme", v).apply()

    /** Schriftgröße (1.0 = normal) */
    var textScale: Float
        get() = prefs.getFloat("textScale", 1.1f)
        set(v) = prefs.edit().putFloat("textScale", v).apply()

    /** Im Hintergrund zuhören (Benachrichtigungen) */
    var listen: Boolean
        get() = prefs.getBoolean("listen", true)
        set(v) = prefs.edit().putBoolean("listen", v).apply()

    var chatToken: String
        get() = secret("chatToken")
        set(v) = putSecret("chatToken", clean(v))

    var appKey: String
        get() = secret("appKey")
        set(v) = putSecret("appKey", clean(v))

    val configured: Boolean get() = chatToken.isNotBlank()

    private fun secret(k: String): String =
        prefs.getString(k, null)?.let { runCatching { decrypt(it) }.getOrDefault("") } ?: ""

    private fun putSecret(k: String, v: String) {
        prefs.edit().apply { if (v.isBlank()) remove(k) else putString(k, encrypt(v)) }.apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    companion object {
        val DEFAULT_SERVER: String = de.hoefer.nookbot.BuildConfig.SERVER
        val DEFAULT_MCP: String = de.hoefer.nookbot.BuildConfig.MCP
        private const val KEY_ALIAS = "nookbot_secrets"

        /** Aus Passwort-Managern rutschen gern Leerzeichen und Umbrüche mit – Schlüssel bestehen nur aus diesen Zeichen. */
        fun clean(raw: String): String =
            raw.trim().removePrefix("Bearer ").filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
    }
}
