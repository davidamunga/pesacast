package com.pesacast.android.webhook

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.pesacast.android.util.MigratingSecret
import java.security.MessageDigest

/**
 * Persisted webhook configuration. The token and signing secret are stored encrypted
 * ([SecretCipher]); the URL and switch are not secrets.
 *
 * Enabling requires a successful test of the *current* values: [markVerified] stores a digest of
 * url|token|secret, and [canEnable] only returns true while it still matches (ADR-0013).
 */
class WebhookSettings(context: Context, private val cipher: SecretCipher = KeystoreSecretCipher()) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pesacast_webhook", Context.MODE_PRIVATE)

    private fun secret(key: String) = MigratingSecret(
        cipher = cipher,
        get = { prefs.getString(it, null) },
        put = { k, v -> prefs.edit { if (v == null) remove(k) else putString(k, v) } },
        encryptedKey = key,
    )

    private val tokenSecret = secret(KEY_TOKEN)
    private val signingSecret = secret(KEY_SECRET)

    /**
     * True if a saved token or signing secret exists but can no longer be decrypted (Android reset
     * its secure storage). Delivery is held off rather than sent without credentials; saving new
     * values fixes it, and it clears by itself if the key comes back.
     */
    fun credentialsUnreadable(): Boolean = tokenSecret.hasUnreadableValue() || signingSecret.hasUnreadableValue()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false) && !credentialsUnreadable()
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    var url: String
        get() = prefs.getString(KEY_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_URL, value.trim()) }

    var token: String
        get() = tokenSecret.read().orEmpty()
        set(value) = tokenSecret.write(value)

    var secret: String
        get() = signingSecret.read().orEmpty()
        set(value) = signingSecret.write(value)

    /** Current configuration, or null if there is no URL yet. */
    fun config(): WebhookConfig? {
        val u = url
        if (u.isBlank()) return null
        return WebhookConfig(enabled, u, token.ifBlank { null }, secret.ifBlank { null })
    }

    fun markVerified() = prefs.edit { putString(KEY_VERIFIED, digest()) }

    fun canEnable(): Boolean = url.isNotBlank() && prefs.getString(KEY_VERIFIED, null) == digest()

    private fun digest(): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$url|$token|$secret".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token_enc"
        const val KEY_SECRET = "secret_enc"
        const val KEY_VERIFIED = "verified_digest"
    }
}
